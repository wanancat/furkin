package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.equipment.EquipmentSlots;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.skill.SkillEffectApplier;
import com.wanancat.furkin.internal.skill.SkillPassiveDispatcher;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Function;

/**
 * 伴侣管理 —— 收回 / 召唤 的核心逻辑（设计稿 §3.1「拥有与活跃」）。
 *
 * <p>两个正交维度（设计稿 §2.2）：生命状态（存活 / 已死）× 是否召唤（已召唤 / 已收回）。
 * 本类只操作「是否召唤」维度；生命状态归 {@code revive} 包（M4）。</p>
 *
 * <p><b>数据归属三层切换</b>：收回时实体不在场 → 快照写录（绒亲录为真相）；
 * 召唤时实体重建 → 从录读回写能力（能力为真相）。沿用同一身份 UUID，与复活同路径。</p>
 */
public final class FurkinCompanionManager {

    private FurkinCompanionManager() {
    }

    /**
     * 「召唤或传送」的结果（2026-09-22 定，替代原先的 boolean）。
     *
     * <p>为什么要有它：召唤入口有两处 —— 绒亲录点条目（{@code RequestSummonPacket}）
     * 与命令 {@code /furkin summon}。两者必须走<b>同一套校验与分流</b>（否则会出现
     * 「录里能把在场宠物拉过来、命令却报错」），且失败时必须能说清<b>具体哪一条</b>
     * 没通过，而不是含糊地并列五种可能。</p>
     */
    public enum SummonResult {
        /** 未召唤 → 成功重建实体并落地。 */
        SUMMONED,
        /** 已召唤 → 成功传送到主人身边。 */
        TELEPORTED,
        /** 档案里没有这个 id。 */
        NOT_FOUND,
        /** 不是本人的绒亲。 */
        NOT_OWNER,
        /** 已亡（FALLEN）—— 应走复活流程。 */
        NOT_ALIVE,
        /** 活跃上限已满（仅召唤分支会命中，传送分支不占新名额）。 */
        ACTIVE_LIMIT,
        /** 重建实体失败（物种缺失 / 能力不可用等内部错误）。 */
        REBUILD_FAILED;

        /** 是否算成功（两个成功态）。 */
        public boolean ok() {
            return this == SUMMONED || this == TELEPORTED;
        }
    }

    /**
     * 召唤或传送（两个入口共用的唯一分流点）。
     *
     * <p>语义（对齐绒亲录，2026-09-22 定）：<b>未召唤</b> → {@link #summon} 重建实体；
     * <b>已召唤</b> → {@link #teleportToOwner} 传送到主人身边，<b>不报错</b>。
     * 这正是绒亲录点条目的行为，命令侧原先缺这段分流而以「召唤失败」告终。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 具体结果（成功含两种分流态）
     */
    public static SummonResult summonOrTeleport(ServerPlayer player, UUID companionId) {
        ServerLevel serverLevel = player.getLevel();

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return SummonResult.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return SummonResult.NOT_OWNER;
        }
        if (!entry.isAlive()) {
            return SummonResult.NOT_ALIVE;
        }

        // 分流：已召唤 → 传送（不占新名额、不校验上限）；未召唤 → 召唤。
        if (entry.isSummoned()) {
            boolean teleported = teleportToOwner(player, companionId);
            // 传送失败通常是「档案标了在场但实体其实丢了」——teleportToOwner 内部已自愈
            // （把 summoned 改回 false），此处如实报失败即可。
            return teleported ? SummonResult.TELEPORTED : SummonResult.REBUILD_FAILED;
        }

        // 未召唤分支：活跃上限只在真正新增实体时校验。
        if (countSummoned(serverLevel, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            FurkinMod.LOGGER.info("Furkin summon blocked: active limit reached for {}",
                    player.getName().getString());
            return SummonResult.ACTIVE_LIMIT;
        }

        boolean summoned = rebuildCompanion(player, entry,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), "summoned");
        return summoned ? SummonResult.SUMMONED : SummonResult.REBUILD_FAILED;
    }

    /**
     * 收回一只已召唤的绒亲。
     *
     * <p>流程：校验主人与状态 → 快照写录（等级 / 技能 / 装备）→ 置 {@code summoned=false}
     * → 移除实体。数据完整留在录里，可再召唤。</p>
     *
     * @param player 主人
     * @param target 要收回的绒亲实体
     * @return 是否成功收回
     */
    public static boolean dismiss(ServerPlayer player, LivingEntity target) {
        // 取能力，校验已契约。
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return false;
        }

        // 校验主人身份。
        UUID ownerUuid = data.getOwnerUuid();
        if (ownerUuid == null || !ownerUuid.equals(player.getUUID())) {
            return false;
        }

        UUID companionId = data.getCompanionId();
        if (companionId == null) {
            return false;
        }

        // 已倒下的（FALLEN）不能收回（那是复活流程管辖）。
        if (data.getState() == FurkinState.FALLEN) {
            return false;
        }

        if (!(target.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return false;
        }

        // 快照写录：等级 / 经验 / 技能点 / 技能 / 装备（装备 M3 起填充，这里保证结构就位）。
        entry.setLevel(data.getLevel());
        entry.setXp(data.getXp());
        entry.setSkillPoints(data.getSkillPoints());
        entry.setSkillSnapshot(data.syncNBT().getCompound("skill_levels"));
        // 物种补写：旧档（加 species 字段前契约的）在此自愈——实体在场时物种必然可得。
        if (entry.getSpecies() == null) {
            entry.setSpecies(target.getType());
        }
        // D6：收回即掉落 —— 必须**先倒空行囊，再存快照**。
        // 快照走 saveWithoutId，会带上 ForgeCaps（行囊 NBT 在其中）；顺序颠倒的话，
        // 下面 summon 里的 living.load(snapshot) 会把行囊原样回灌，物品「诈尸」回来。
        PouchDrop.dropAll(target, data.getPouch());

        // 实体外观快照：品种 / 毛色 / 坐定 / 跟随等，收回时整包存下。
        // 这一份整包快照里**本来就带装备**（原版 Mob 存档自带 ArmorItems），但装备另有专用字段，
        // 于是在同一份快照上摘一次即可 —— 不为自己再算一遍 saveWithoutId。
        CompoundTag snapshot = target.saveWithoutId(new CompoundTag());
        entry.setEntitySnapshot(snapshot);
        // 装备快照：原版实体装备槽 → 录（M3.2 起填充；此前是空占位）。
        entry.setEquipmentSnapshot(EquipmentSlots.extractFrom(snapshot));
        entry.setAlive(true);
        entry.setSummoned(false); // 收回：实体不在场。
        entry.clearEntityLocation(); // WP-09：实体已 discard，定位字段不得继续指向失效 UUID。
        archive.putEntry(entry);

        // 移除实体（discard 不触发死亡掉落 / 不广播死亡）。
        target.discard();

        // health 一并记下：与 summon 那行「restored to x」互为独立来源的两个读数，
        // 两者相等才说明「保持收回前血量」真的生效（否则看不出写回是生效了还是碰巧撞上 20）。
        FurkinMod.LOGGER.info("Furkin dismissed: id={} health={} by {}",
                companionId, target.getHealth(), player.getName().getString());
        return true;
    }

    /**
     * 召唤一只已收回（且存活）的绒亲。
     *
     * <p>流程：从录读条目 → 校验存活且未召唤 → 校验活跃上限 → 按物种重建实体
     * （沿用同一身份 UUID）→ 写回能力 → 置 {@code summoned=true}。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 是否成功召唤
     */
    public static boolean summon(ServerPlayer player, UUID companionId) {
        ServerLevel serverLevel = player.getLevel();

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return false;
        }

        // 校验主人。
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return false;
        }

        // 校验生命状态（已死 → 走复活，不在此召唤）。
        if (!entry.isAlive()) {
            return false;
        }

        // 校验是否已召唤。
        if (entry.isSummoned()) {
            return false;
        }

        // 校验活跃上限（当前已召唤数量）。
        if (countSummoned(serverLevel, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            FurkinMod.LOGGER.info("Furkin summon blocked: active limit reached for {}",
                    player.getName().getString());
            return false;
        }

        // 重建实体并落地。召唤的落点 = 玩家所在精确坐标（与旧实现一致）。
        return rebuildCompanion(player, entry,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), "summoned");
    }

    /**
     * 复活一只已亡（且未在场）的绒亲（M4.2）。
     *
     * <p>与 {@link #summon} 的区别：不要求 {@code isAlive()}（恰好相反，要求已亡），
     * 落点是结构中心而非玩家身边。复活本体零代价（魂石即代价，在物品侧扣除）。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @param spawnPos    重建实体落点（结构中心上方）
     * @return 是否成功复活
     */
    public static boolean revive(ServerPlayer player, UUID companionId, BlockPos spawnPos) {
        ServerLevel serverLevel = player.getLevel();

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return false;
        }

        // 校验主人。
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return false;
        }

        // 校验生命状态（必须已死 —— 存活走召唤，不在此复活）。
        if (entry.isAlive()) {
            return false;
        }

        // 校验是否已在场（理论上已亡必然未在场，双保险）。
        if (entry.isSummoned()) {
            return false;
        }

        // 校验活跃上限（复活同样占用一个活跃名额）。
        if (countSummoned(serverLevel, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            FurkinMod.LOGGER.info("Furkin revive blocked: active limit reached for {}",
                    player.getName().getString());
            return false;
        }

        // 重建实体并落地。alive 的翻转在 rebuildCompanion 的落态段统一处理
        // （保证「实体建出来才置 alive」），此处不提前写。落点 = 结构中心上方一格。
        return rebuildCompanion(player, entry,
                spawnPos.getX() + 0.5, spawnPos.getY() + 1.0, spawnPos.getZ() + 0.5,
                0.0F, 0.0F, "revived");
    }

    /**
     * 重建一只绒亲实体并落地 —— {@link #summon} 与 {@link #revive} 共用的重建段。
     *
     * <p>从档案读物种 / 外观 / 装备 / 技能 / 名字 / 血量，按同一身份 UUID 重建实体、
     * 写回能力、重挂技能效果、重算行囊容量、重置周期被动计时，最后置
     * {@code summoned=true} 并同步到客户端。</p>
     *
     * <p><b>前置校验不在此处</b>：调用方（summon / revive）各自做完「主人 / 生命状态 /
     * 在场 / 活跃上限」校验后再进本方法，故本方法只负责「重建 + 落地 + 落态」。</p>
     *
     * @param player    主人
     * @param entry     档案条目（须已通过调用方的前置校验）
     * @param x         落点 X（召唤=玩家坐标，复活=结构中心上方）
     * @param y         落点 Y
     * @param z         落点 Z
     * @param yRot      落点朝向（召唤=玩家朝向，复活=0）
     * @param xRot      落点俯仰（同上）
     * @param actionLog 日志动作词（"summoned" / "revived"）
     * @return 是否成功
     */
    private static boolean rebuildCompanion(ServerPlayer player, FurkinArchiveEntry entry,
                                            double x, double y, double z,
                                            float yRot, float xRot, String actionLog) {
        ServerLevel serverLevel = (ServerLevel) player.getLevel();
        UUID companionId = entry.getCompanionId();

        // 重建实体：从档案读物种。
        EntityType<?> species = entry.getSpecies();
        if (species == null) {
            FurkinMod.LOGGER.warn("Furkin {} failed: no species recorded for id={}", actionLog, companionId);
            return false;
        }

        // 按物种创建实体（沿用同一身份 UUID 在能力层体现，实体 UUID 由世界重新分配）。
        Entity created = species.create(serverLevel);
        if (!(created instanceof LivingEntity living)) {
            FurkinMod.LOGGER.warn("Furkin {} failed: species {} produced non-living entity",
                    actionLog, species);
            return false;
        }

        // 回灌实体外观快照：品种 / 毛色 / 坐定 / 跟随等（有快照才回灌，兼容旧档）。
        CompoundTag snapshot = entry.getEntitySnapshot();
        if (snapshot != null && !snapshot.isEmpty()) {
            // 回灌前先清掉新建实体自带的 UUID 与默认外观，避免冲突（快照里含原实体 UUID）。
            living.load(snapshot);
        }

        // 装备铺回（M3.2）：以档案的装备快照为**权威副本**，不搭上面那次 load 的顺风车。
        // 理由有两条 —— ① 上面整包回灌只在快照非空时才发生，装备不该依赖那个分支；
        // ② 显式写明来源，免得后人看到「装备怎么回来的」要去翻原版存档格式才知道。
        // 快照为空（M3.2 之前收回的旧档）时本方法什么都不做，见 EquipmentSlots#applyTo。
        EquipmentSlots.applyTo(living, entry.getEquipmentSnapshot());
        // 关掉落（M3.2）：实体每次入世都必须显式设一遍 —— 旧档快照里没有 ArmorDropChances 这个键，
        // 原版读档对它是「没有就保持默认」，而新建实体的默认值是 0.085 ⇒ 光靠 load 回灌保不住。
        EquipmentSlots.sealDrops(living);

        // 写回能力对象（运行时真相）。
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            FurkinMod.LOGGER.warn("Furkin {} failed: capability unavailable for species {}", actionLog, species);
            return false;
        }
        data.setCompanionId(companionId);
        data.setOwnerUuid(entry.getOwnerUuid());
        data.setLevel(entry.getLevel());
        data.setXp(entry.getXp()); // M2：经验从档案恢复（旧档 xp 默认 0）。
        data.setSkillPoints(entry.getSkillPoints()); // M2：技能点从档案恢复。
        data.setCombatMode(entry.getCombatMode()); // M2：战斗模式从档案恢复（跨召唤记忆）。
        data.setAiStateVersion(FurkinData.CURRENT_AI_STATE_VERSION);
        data.setState(FurkinState.COMPANION);
        // 技能快照读回（M2 起填充具体技能）。
        if (entry.getSkillSnapshot() != null && !entry.getSkillSnapshot().isEmpty()) {
            // 技能等级反序列化：skill_levels 的 NBT 形态。
            data.getSkillLevels().clear();
            for (String key : entry.getSkillSnapshot().getAllKeys()) {
                data.getSkillLevels().put(
                        new ResourceLocation(key),
                        entry.getSkillSnapshot().getInt(key));
            }
        }

        // 重挂技能效果（M2.4）：实体重建后 attribute modifier 是运行时表现，须按 skillLevels
        // 重新 apply，否则收回再召唤后属性加成丢失。
        SkillEffectApplier.applyAll(living, SkillRegistry.tree(), data.getSkillLevels());

        // 血量读数：取自**快照 NBT**，不能取 `living.getHealth()`。原因（javap 取证，2026-09-22 第六轮）：
        // `load(snapshot)` 内部会走到 `TamableAnimal#readAdditionalSaveData` —— 它先经 super 链读到
        // `Health` 并 `setHealth`，**紧接着**又读 `Tame` 并调 `setTame(true)`，而 `Wolf#setTame(true)`
        // 写死了 `setHealth(20.0f)` ⇒ **load 自己就已经把血冲成 20 了**。此刻 `getHealth()` 早已不是
        // 快照里的真值（第五轮把读数点放在这里，拿到的就是被冲过的 20，日志里 `restored to 20.0` 即此）。
        // 唯一可靠来源是快照 NBT 的 `Health` 键 —— `LivingEntity#addAdditionalSaveData` 写
        // `putFloat("Health", getHealth())`，收回时 `saveWithoutId` 现取，是收回那一刻的真值。
        // 99 = 「任意数值型」，与原版读档同判据；快照缺失（M3.2 前的旧档）时退回运行时读数。
        // 复活场景：死亡快照里的 Health 可能是 0（死在那一刻），读出来回灌会得到一个 0 血实体。
        // 为稳妥，复活时若快照血 <= 0，退回满血（让复活对象以健康状态归来，M4.2 定案口径）。
        float recalledHealth = snapshot != null && snapshot.contains("Health", 99)
                ? snapshot.getFloat("Health")
                : living.getHealth();
        if (recalledHealth <= 0.0F) {
            recalledHealth = living.getMaxHealth();
        }

        // 对 TamableAnimal 的额外动作：置 TAME（与契约同路径）。
        if (living instanceof TamableAnimal tamable) {
            tamable.setTame(true);
            tamable.setOwnerUUID(entry.getOwnerUuid());
            // 清坐定 + 坐姿：坐定意图（orderedToSit）与坐姿渲染 flag（sittingPose）是两个状态，
            // 快照回灌只恢复意图，姿势 flag 若不清会「坐着滑行」。
            tamable.setOrderedToSit(false);
            tamable.setInSittingPose(false);
            // 应用战斗模式（从档案恢复，跨召唤记忆）。失败时实体尚未入世，不落 summoned。
            if (!data.getCombatMode().applyTo(tamable)) {
                FurkinMod.LOGGER.warn("Furkin {} failed: combat AI apply rejected for id={}",
                        actionLog, companionId);
                return false;
            }
        }

        // 血量写回（2026-09-22 乌狸定案：召唤时「保持收回前的血量」）：
        // `Wolf#setTame(true)` 的字节码里写死了 `getAttribute(MAX_HEALTH).setBaseValue(20.0d)`
        // ＋ `setHealth(20.0f)`，会把血量冲掉 —— 这正是「收回再放出就回满到基础生命值上限」的根因。
        // ⚠️ 本方法里它被调用**两次**：① `load(snapshot)` 顺路调的（见上面读数处的取证）；
        // ② 下面这段 `TamableAnimal` 分支显式调的一次。所以写回**必须**排在 ② 之后。
        // 读数值来自快照 NBT（不是 getHealth()），理由见上面。
        // setHealth 内部自带 clamp(0, 上限)，越界会自行收敛（例如快照血高于当前上限时）。
        float forcedByTame = living.getHealth(); // 取证用：setTame 强制写下的值（非驯服类生物即原值）
        living.setHealth(recalledHealth);
        // 一次一行、非 tick 刷屏：给出「被覆盖成几 / 写回成几 / 上限几」三个互相约束的数，
        // 免得只能靠肉眼看面板判断写回有没有生效。不需要时可整段删。
        FurkinMod.LOGGER.info("Furkin {} health: setTame forced {}, restored to {} (max {})",
                actionLog, forcedByTame, living.getHealth(), living.getMaxHealth());

        // 名字回灌：快照里的 CustomName 是改名前的旧值，需按档案 name 覆盖
        // （未召唤时改名只更新了档案字段，没更新快照，故召唤后强制覆盖一次）。
        if (entry.getName() != null) {
            living.setCustomName(entry.getName());
            living.setCustomNameVisible(true);
        } else {
            living.setCustomName(null);
            living.setCustomNameVisible(false);
        }

        // 设置落点：召唤在玩家身边，复活在结构中心上方。
        living.moveTo(x, y, z, yRot, xRot);

        // 加入世界。
        serverLevel.addFreshEntity(living);

        // 行囊容量是「travel_pouch 等级的派生值」，必须与刚回灌的技能等级同步重算：
        // 不重算的话，升级过的绒亲收回再召唤后行囊会变回 0 格。
        // 这里用「可缩」版本而非只扩的 refreshPouchSize：若每级格数被 config 调小，
        // 或旧档快照带回了超格物品，多出来的部分在此掉落。放在加入世界之后，
        // 是为了让落点取到实体的真实位置（前面 moveTo 尚未生效于世界坐标）。
        PouchDrop.dropStacks(living, data.resizePouchToLevel());

        // 周期型被动（凭空产出）的计时从此刻重新起算：收回期间服务器时钟照走，而 D12 要的是
        // 「仅在场才计时」—— 不重置的话，收回久了再召唤会立刻补产一个。同在实体入世之后。
        SkillPassiveDispatcher.resetPeriodicTimers(living, data);

        // 落态：实体已入世 ⇒ summoned=true；复活场景同时把 alive 翻回 true。
        // 放在此处（而非 revive 的前置校验后）是为保证「只有实体真正建出来才落态」，
        // 避免 rebuild 中途失败时留下「alive=true 却无实体」的中间态。
        entry.setAlive(true);
        entry.setSummoned(true);
        entry.setEntityLocation(living); // WP-09：召唤/复活后记录新实体 UUID 与维度。
        FurkinArchiveData.get(serverLevel).putEntry(entry);

        // 同步能力数据到客户端。
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> living),
                new SyncFurkinDataPacket(living.getId(), data.syncNBT()));

        FurkinMod.LOGGER.info("Furkin {}: id={} species={} by {}",
                actionLog, companionId, species, player.getName().getString());
        return true;
    }

    /**
     * 传送一只「已召唤（实体在场）」的绒亲到主人身边，并唤醒跟随。
     *
     * <p>与 {@link #summon} 的区别：不新增活跃数、不重建实体，仅对在场实体做位置挪移。
     * 故<b>不查活跃上限</b>。语义对应绒亲录里「已召唤条目点召唤 = 传送到身边」。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 是否成功传送
     */
    public static boolean teleportToOwner(ServerPlayer player, UUID companionId) {
        ServerLevel serverLevel = player.getLevel();

        // 从档案确认主人与生命状态。
        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return false;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return false;
        }
        if (!entry.isAlive()) {
            return false;
        }
        if (!entry.isSummoned()) {
            // 未召唤（不在场）→ 走 summon，不在此处理。
            return false;
        }

        // 按档案中的 UUID + 维度定位；此前只扫当前维度，跨维度传送会把
        // 主世界宠物误判为“丢失”并错误地改回未召唤。
        LivingEntity target = FurkinEntityLocator.locate(player.getServer(), entry);
        if (target == null) {
            // 档案标记已召唤但实体不在场（数据不一致）→ 自愈：改回未召唤。
            entry.setSummoned(false);
            entry.clearEntityLocation();
            archive.putEntry(entry);
            FurkinMod.LOGGER.warn("Furkin teleport: entity missing for id={}, marked dismissed",
                    companionId);
            return false;
        }

        // 传送：绕到玩家朝向正前方一格（避免与玩家重叠）。
        double dx = -Math.sin(Math.toRadians(player.getYRot())) * 1.5;
        double dz = Math.cos(Math.toRadians(player.getYRot())) * 1.5;
        double targetX = player.getX() + dx;
        double targetY = player.getY();
        double targetZ = player.getZ() + dz;
        LivingEntity relocated = target;
        if (target.getLevel() != serverLevel) {
            // 1.19.2 的 Entity#changeDimension(ServerLevel, ITeleporter) 已核实为公开 API。
            // 用固定落点 teleporter，既完成跨维度实体迁移，也保留能力 NBT。
            Entity changed = target.changeDimension(serverLevel,
                    new FixedTeleporter(targetX, targetY, targetZ,
                            player.getYRot(), player.getXRot()));
            if (!(changed instanceof LivingEntity living)) {
                FurkinMod.LOGGER.warn(
                        "Furkin teleport failed: dimension change returned no living entity for id={}",
                        companionId);
                return false;
            }
            relocated = living;
        } else {
            target.teleportTo(targetX, targetY, targetZ);
        }
        // WP-09：传送成功后刷新一次定位。
        entry.setEntityLocation(relocated);
        archive.putEntry(entry);

        // 唤醒跟随：清坐定 + 坐姿，保证传送后立即跟随且不残留坐姿。
        if (relocated instanceof TamableAnimal tamable) {
            tamable.setOrderedToSit(false);
            tamable.setInSittingPose(false);
        }

        final LivingEntity syncedTarget = relocated;
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> syncedTarget),
                new SyncFurkinDataPacket(syncedTarget.getId(),
                        syncedTarget.getCapability(FurkinCapability.FURKIN_DATA)
                                .orElseGet(FurkinData::new).syncNBT()));

        FurkinMod.LOGGER.info("Furkin teleported: id={} to {}",
                companionId, player.getName().getString());
        return true;
    }

    /** 按服务器级档案的 UUID / 维度定向查找在场绒亲实体。 */
    public static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        FurkinArchiveEntry entry = FurkinArchiveData.get(level).getEntry(companionId);
        return entry == null ? null : FurkinEntityLocator.locate(level.getServer(), entry);
    }

    /**
     * 统计某主人的当前已召唤（实体在场）绒亲数量。
     */
    private static int countSummoned(ServerLevel level, UUID ownerUuid) {
        int count = 0;
        for (FurkinArchiveEntry entry : FurkinArchiveData.get(level).allEntries()) {
            if (entry.isSummoned() && ownerUuid.equals(entry.getOwnerUuid())) {
                count++;
            }
        }
        return count;
    }

    /** 将实体迁到指定维度坐标时使用的固定落点传送器。 */
    private static final class FixedTeleporter implements ITeleporter {

        private final double x;
        private final double y;
        private final double z;
        private final float yRot;
        private final float xRot;

        private FixedTeleporter(double x, double y, double z, float yRot, float xRot) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
        }

        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destination,
                                        Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            return new PortalInfo(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot);
        }
    }
}
