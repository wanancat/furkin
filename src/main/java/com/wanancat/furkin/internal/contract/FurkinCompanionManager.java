package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.skill.SkillEffectApplier;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

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

        if (!(target.level() instanceof ServerLevel serverLevel)) {
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
        entry.setSkillSnapshot(data.serializeNBT().getCompound("skill_levels"));
        // 物种补写：旧档（加 species 字段前契约的）在此自愈——实体在场时物种必然可得。
        if (entry.getSpecies() == null) {
            entry.setSpecies(target.getType());
        }
        // D6：收回即掉落 —— 必须**先倒空行囊，再存快照**。
        // 快照走 saveWithoutId，会带上 ForgeCaps（行囊 NBT 在其中）；顺序颠倒的话，
        // 下面 summon 里的 living.load(snapshot) 会把行囊原样回灌，物品「诈尸」回来。
        PouchDrop.dropAll(target, data.getPouch());

        // 实体外观快照：品种 / 毛色 / 坐定 / 跟随等，收回时整包存下。
        entry.setEntitySnapshot(target.saveWithoutId(new CompoundTag()));
        // 装备快照：原版实体装备槽 → 录（M3 前为空占位，字段保留）。
        entry.setAlive(true);
        entry.setSummoned(false); // 收回：实体不在场。
        archive.putEntry(entry);

        // 移除实体（discard 不触发死亡掉落 / 不广播死亡）。
        target.discard();

        FurkinMod.LOGGER.info("Furkin dismissed: id={} by {}", companionId, player.getName().getString());
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
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

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

        // 重建实体：从档案读物种。
        EntityType<?> species = entry.getSpecies();
        if (species == null) {
            FurkinMod.LOGGER.warn("Furkin summon failed: no species recorded for id={}", companionId);
            return false;
        }

        // 按物种创建实体（沿用同一身份 UUID 在能力层体现，实体 UUID 由世界重新分配）。
        Entity created = species.create(serverLevel);
        if (!(created instanceof LivingEntity living)) {
            FurkinMod.LOGGER.warn("Furkin summon failed: species {} produced non-living entity",
                    species);
            return false;
        }

        // 回灌实体外观快照：品种 / 毛色 / 坐定 / 跟随等（有快照才回灌，兼容旧档）。
        CompoundTag snapshot = entry.getEntitySnapshot();
        if (snapshot != null && !snapshot.isEmpty()) {
            // 回灌前先清掉新建实体自带的 UUID 与默认外观，避免冲突（快照里含原实体 UUID）。
            living.load(snapshot);
        }

        // 写回能力对象（运行时真相）。
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            FurkinMod.LOGGER.warn("Furkin summon failed: capability unavailable for species {}", species);
            return false;
        }
        data.setCompanionId(companionId);
        data.setOwnerUuid(entry.getOwnerUuid());
        data.setLevel(entry.getLevel());
        data.setXp(entry.getXp()); // M2：经验从档案恢复（旧档 xp 默认 0）。
        data.setSkillPoints(entry.getSkillPoints()); // M2：技能点从档案恢复。
        data.setCombatMode(entry.getCombatMode()); // M2：战斗模式从档案恢复（跨召唤记忆）。
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

        // 对 TamableAnimal 的额外动作：置 TAME（与契约同路径）。
        if (living instanceof TamableAnimal tamable) {
            tamable.setTame(true);
            tamable.setOwnerUUID(entry.getOwnerUuid());
            // 清坐定 + 坐姿：坐定意图（orderedToSit）与坐姿渲染 flag（sittingPose）是两个状态，
            // 快照回灌只恢复意图，姿势 flag 若不清会「坐着滑行」。
            tamable.setOrderedToSit(false);
            tamable.setInSittingPose(false);
            // 应用战斗模式（从档案恢复，跨召唤记忆）。
            data.getCombatMode().applyTo(tamable);
        }

        // 名字回灌：快照里的 CustomName 是改名前的旧值，需按档案 name 覆盖
        // （未召唤时改名只更新了档案字段，没更新快照，故召唤后强制覆盖一次）。
        if (entry.getName() != null) {
            living.setCustomName(entry.getName());
            living.setCustomNameVisible(true);
        } else {
            living.setCustomName(null);
            living.setCustomNameVisible(false);
        }

        // 设置召唤位置：玩家附近。
        living.moveTo(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());

        // 加入世界。
        serverLevel.addFreshEntity(living);

        // 行囊容量是「travel_pouch 等级的派生值」，必须与刚回灌的技能等级同步重算：
        // 不重算的话，升级过的绒亲收回再召唤后行囊会变回 0 格。
        // 这里用「可缩」版本而非只扩的 refreshPouchSize：若每级格数被 config 调小，
        // 或旧档快照带回了超格物品，多出来的部分在此掉落。放在加入世界之后，
        // 是为了让落点取到实体的真实位置（前面 moveTo 尚未生效于世界坐标）。
        PouchDrop.dropStacks(living, data.resizePouchToLevel());

        // 置 summoned=true。
        entry.setSummoned(true);
        FurkinArchiveData.get(serverLevel).putEntry(entry);

        // 同步能力数据到客户端。
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> living),
                new SyncFurkinDataPacket(living.getId(), data.serializeNBT()));

        FurkinMod.LOGGER.info("Furkin summoned: id={} species={} by {}",
                companionId, species, player.getName().getString());
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
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

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

        // 找到在场实体：遍历世界按 companionId 匹配能力对象。
        LivingEntity target = findLivingByCompanionId(serverLevel, companionId);
        if (target == null) {
            // 档案标记已召唤但实体不在场（数据不一致）→ 自愈：改回未召唤。
            entry.setSummoned(false);
            archive.putEntry(entry);
            FurkinMod.LOGGER.warn("Furkin teleport: entity missing for id={}, marked dismissed",
                    companionId);
            return false;
        }

        // 传送：绕到玩家朝向正前方一格（避免与玩家重叠）。
        double dx = -Math.sin(Math.toRadians(player.getYRot())) * 1.5;
        double dz = Math.cos(Math.toRadians(player.getYRot())) * 1.5;
        target.teleportTo(player.getX() + dx, player.getY(), player.getZ() + dz);

        // 唤醒跟随：清坐定 + 坐姿，保证传送后立即跟随且不残留坐姿。
        if (target instanceof TamableAnimal tamable) {
            tamable.setOrderedToSit(false);
            tamable.setInSittingPose(false);
        }

        FurkinMod.LOGGER.info("Furkin teleported: id={} to {}",
                companionId, player.getName().getString());
        return true;
    }

    /** 在世界里按 companionId 查找在场绒亲实体。 */
    private static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living) {
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data != null && companionId.equals(data.getCompanionId())) {
                    return living;
                }
            }
        }
        return null;
    }

    /**
     * 统计某主人的当前已召唤（实体在场）绒亲数量。
     */
    private static int countSummoned(ServerLevel level, UUID ownerUuid) {
        int count = 0;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living) {
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data != null && data.isCompanion()
                        && ownerUuid.equals(data.getOwnerUuid())) {
                    count++;
                }
            }
        }
        return count;
    }
}
