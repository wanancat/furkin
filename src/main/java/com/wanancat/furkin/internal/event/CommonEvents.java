package com.wanancat.furkin.internal.event;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.command.FurkinCommand;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import com.wanancat.furkin.internal.contract.FurkinUnbindCleanup;
import com.wanancat.furkin.internal.equipment.EquipmentSlots;
import com.wanancat.furkin.internal.growth.CombatParticipationTracker;
import com.wanancat.furkin.internal.growth.FurkinFeeding;
import com.wanancat.furkin.internal.growth.FurkinGrowth;
import com.wanancat.furkin.internal.item.FurkinContractItem;
import com.wanancat.furkin.internal.item.FurkinSoulstoneItem;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.record.FurkinRevocationData;
import com.wanancat.furkin.internal.registry.ModItems;
import com.wanancat.furkin.internal.skill.SkillPassiveDispatcher;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import com.wanancat.furkin.internal.skill.SkillRuntimeCalibrator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;

/**
 * 通用事件处理器（逻辑端共享）。
 *
 * <p>M1：拦截右键实体，触发契约 / 收回动作；注册命令。</p>
 */
@Mod.EventBusSubscriber(modid = "furkin", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {

    private CommonEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        FurkinCommand.register(event.getDispatcher(), event.getBuildContext());
    }

    /**
     * 服务端实体入世时处理注销墓碑，或重建绒亲战斗 AI。
     *
     * <p>区块读盘路径下 capability NBT 已在事件触发前完成反序列化；新建实体路径下
     * attachment 也已存在。墓碑命中时先执行共享清理，成功后才移除墓碑并发送清空后的
     * 能力同步；失败保留墓碑，等下一次入世重试。未命中墓碑的已契约
     * {@link TamableAnimal} 保持原有 AI 重建逻辑。</p>
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return;
        }

        if (tryHandleRevocation(living, serverLevel, data)) {
            return;
        }

        // M-04：实体重新入世时，旧属性 modifier 可能来自已删除或已改写的技能定义；
        // 先按运行时技能树统一清理并重建，再应用战斗 AI。
        if (data.isCompanion()) {
            SkillRuntimeCalibrator.rebuild(living, data);
        }

        if (!(entity instanceof TamableAnimal tamable) || !data.isCompanion()) {
            return;
        }

        // WP-02B：已召唤实体重新入世时刷新定向定位信息。
        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = data.getCompanionId() == null
                ? null : archive.getEntry(data.getCompanionId());
        if (entry != null && entry.isSummoned()
                && (!entity.getUUID().equals(entry.getEntityUuid())
                || !serverLevel.dimension().equals(entry.getEntityDimension()))) {
            entry.setEntityLocation(entity);
            archive.putEntry(entry);
        }

        if (!data.getCombatMode().applyTo(tamable)) {
            FurkinMod.LOGGER.warn("Furkin AI restore rejected on entity join: entity={} id={}",
                    tamable.getUUID(), data.getCompanionId());
        }
    }

    /**
     * 在实体入世时命中服务器级注销墓碑并执行延迟清理。
     *
     * @return 是否命中墓碑；命中时无论清理成功或失败都返回 {@code true}，避免继续按
     * 已注销档案重应用 AI。
     */
    private static boolean tryHandleRevocation(LivingEntity living, ServerLevel serverLevel,
                                               FurkinData data) {
        UUID companionId = data.getCompanionId();
        if (companionId == null) {
            return false;
        }

        FurkinRevocationData revocations = FurkinRevocationData.get(serverLevel.getServer());
        if (!revocations.contains(companionId)) {
            return false;
        }

        FurkinUnbindCleanup.Result cleanup = FurkinUnbindCleanup.cleanup(
                living, companionId, FurkinUnbindCleanup.Trigger.REVOCATION);
        if (!cleanup.success()) {
            FurkinMod.LOGGER.warn(
                    "Furkin revocation cleanup deferred: id={}, entity={}, stage={}",
                    companionId, living.getUUID(), cleanup.failedStage());
            return true;
        }

        if (!revocations.remove(companionId)) {
            FurkinMod.LOGGER.warn(
                    "Furkin revocation cleanup completed but tombstone was already absent: id={}, entity={}",
                    companionId, living.getUUID());
        }

        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> living),
                new SyncFurkinDataPacket(living.getId(), data.syncNBT()));
        return true;
    }

    /**
     * 玩家开始追踪某实体（实体进视野）时，若该实体是绒亲，同步其能力数据给该玩家。
     * 覆盖「玩家登录 / 进视野 / 新实体生成」所有客户端能力数据缺失场景。
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return;
        }
        FurkinNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> (ServerPlayer) event.getEntity()),
                new SyncFurkinDataPacket(target.getId(), data.syncNBT()));
    }

    /** 玩家登出时清除待确认契约会话，避免无效记录残留。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        FurkinContractHandler.clearPendingContract(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        // 只在服务端处理（逻辑端）。单机也是逻辑端，走同一路径。
        if (event.getSide().isClient()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Entity interactionTarget = event.getTarget();
        if (interactionTarget instanceof PartEntity<?> part
                && part.getParent() instanceof LivingEntity parent) {
            // 多部件生物（如 Twilight Forest 的 Hydra）主实体不可拾取，右键命中的是
            // PartEntity；其 interact 会转发给父实体，这里同步把契约目标解析到父实体。
            interactionTarget = parent;
        }
        if (!(interactionTarget instanceof LivingEntity target)) {
            return;
        }

        // ===== 进食通道（§3.2）：手持可食用食物 + 目标是本人契约且在场的绒亲 → 接管喂食 =====
        FurkinData targetData = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        boolean isOwnCompanion = targetData != null
                && targetData.isCompanion()
                && player.getUUID().equals(targetData.getOwnerUuid());

        if (isOwnCompanion && !(player.getMainHandItem().getItem() instanceof FurkinContractItem)) {
            ItemStack held = player.getMainHandItem();
            if (FurkinFeeding.isEdibleFood(held, target)) {
                // 已确认（R11 实测）：EntityInteract 时序在 mobInteract 之前，
                // setCanceled(true) 能干净取消原版喂食（求偶/吃鱼均被压住）。
                boolean fed = FurkinFeeding.feed(player, target, held);
                if (fed) {
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                }
                return;
            }
        }
        // ===== 进食通道结束 =====

        // 潜行 + 右键 + 手持非契约 → 打开绒亲面板（本人契约绒亲，技能 / 行囊 / 装备三页签）。
        // （手持契约时的潜行右键是「收回」，见下；这里只处理非契约物品。）
        if (player.isShiftKeyDown()
                && !(player.getMainHandItem().getItem() instanceof FurkinContractItem)
                && isOwnCompanion) {
            if (FurkinRecordActionHandler.openPanel(player, target)) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
            return;
        }

        // 手持绒亲契约才触发契约 / 收回。
        if (!(player.getMainHandItem().getItem() instanceof FurkinContractItem)) {
            return;
        }

        // 分支①：潜行 + 右键 → 收回（目标已是自己的绒亲）。
        if (player.isShiftKeyDown()) {
            boolean dismissed = FurkinCompanionManager.dismiss(player, target);
            if (dismissed) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
            return;
        }

        // 分支②：普通右键 → 契约。生命值过高也取消原版右键，避免目标切换坐姿等副作用。
        FurkinContractHandler.ContractCheckResult result =
                FurkinContractHandler.tryContract(player, target, player.getMainHandItem());
        if (result == FurkinContractHandler.ContractCheckResult.PASSED
                || result == FurkinContractHandler.ContractCheckResult.HEALTH_TOO_HIGH) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    // ===== 战斗经验（§3.2 口径 A）：登记伤害 → 死亡结算 =====

    /**
     * 攻击（最早段）：绒亲受害侧被动的最早位点。
     *
     * <p><b>为什么必须有这一层</b>：取消 {@code LivingHurtEvent} 只作废伤害量，而受击的
     * 变红闪帧与音效在 {@code hurt()} 更早的段落里已经播出去了（实机表现：明明闪避了，
     * 宠物仍全身变红 + 惨叫）。{@code LivingAttackEvent} 在 {@code hurt()} 入口触发，
     * 取消后整个受击流程直接返回，才叫「干净地闪掉」。故闪避走这一层；
     * 免死留在 {@link #onLivingHurt}（它需要改写伤害量，只能走晚段）。</p>
     */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        // 受害侧早段被动（灵巧身法闪避）：闪避成立则整个受击作废（无伤害 / 无音效 / 无闪帧）。
        SkillPassiveDispatcher.onCompanionAttacked(event.getEntity(), event);
    }

    /**
     * 目标受伤：先跑受害侧被动（九命猫免死），再登记「攻击者」进该目标的参与者表并累计伤害。
     * 攻击者可能是绒亲本人、玩家、或其它来源；是否发经验在死亡结算时再判定。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        // 只在服务端处理。
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        if (source == null) {
            return;
        }

        // 受害侧被动（九命猫免死）——必须早于下面的「有实体攻击者」守卫：
        // 摔落 / 虚空这类伤害没有实体来源，若先过守卫就永远进不来，九命猫护不住摔死。
        // （灵巧身法闪避不在这里 —— 它在更早的 onLivingAttack，取消后整个受击流程直接返回。）
        SkillPassiveDispatcher.onCompanionHurt(target, event);
        if (event.isCanceled()) {
            // 兜底：本事件被其它来源取消时，同样不登记伤害、不触发攻击侧被动。
            return;
        }

        // 攻击者可能不是 LivingEntity（如箭矢、环境），但只登记「直接实体」即可：
        // 绒亲近战攻击时 getEntity() 返回绒亲本身。
        Entity sourceEntity = source.getEntity();
        if (!(sourceEntity instanceof LivingEntity attacker)) {
            return;
        }
        CombatParticipationTracker.recordDamage(target, attacker, event.getAmount());

        // 攻击侧被动（流血撕咬等）。
        SkillPassiveDispatcher.onCompanionAttack(attacker, target);
    }

    /**
     * 目标死亡：绒亲先落「已亡」态，再结算战斗经验。口径 A —— 每个参与过伤害的绒亲各拿
     * 目标原版经验 × {@code COMBAT_XP_MULTIPLIER}。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // 绒亲自身死亡 → 档案落「已亡 + 已收回」（必须先于下面的参与者早退，否则空参与者时会被跳过）。
        markFallenIfCompanion(target, serverLevel);

        Map<UUID, Float> participants = CombatParticipationTracker.takeAndClear(target);
        if (participants.isEmpty()) {
            return;
        }

        // 原版经验值（玩家击杀该生物可得的经验）。
        int baseXp = target.getExperienceReward();
        if (baseXp <= 0) {
            return;
        }
        int xpPerCompanion = (int) Math.round(baseXp * FurkinServerConfig.COMBAT_XP_MULTIPLIER.get());
        if (xpPerCompanion <= 0) {
            return;
        }

        // 对每个参与者：若是某玩家本人的在场绒亲，给该绒亲发经验。
        for (UUID attackerId : participants.keySet()) {
            Entity entity = serverLevel.getEntity(attackerId);
            if (!(entity instanceof LivingEntity attacker)) {
                continue;
            }
            if (!attacker.isAlive()) {
                continue;
            }
            var data = attacker.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
            if (data == null || !data.isCompanion()) {
                continue;
            }
            // 找到主人（可能离线 → 跳过该绒亲，主人不在时战斗经验静默丢弃，可接受）。
            ServerPlayer owner = serverLevel.getServer().getPlayerList()
                    .getPlayer(data.getOwnerUuid());
            if (owner == null) {
                continue;
            }
            FurkinGrowth.addXp(attacker, xpPerCompanion);
        }
    }

    /**
     * 绒亲死亡 → 档案落「已亡 + 已收回」。
     *
     * <p><b>为什么必须有这一步</b>：绒亲实体死亡后会被世界移除，但档案条目的
     * {@code alive} / {@code summoned} 不会自动变化 —— 不落态就会出现「录里显示在场、
     * 点收回却报未在场」的错位。死亡快照（等级 / 经验 / 技能 / 外观 / <b>装备</b>）一并写录，
     * 供 M4 复活（FALLEN → COMPANION）按同一身份 UUID 重建。
     * 装备另外还与「关闭掉落」成对（见方法内注释）—— 那一步的时机可靠性由
     * {@code LivingDeathEvent} 早于掉落的取证保证。</p>
     *
     * <p>复活流程归 M4，本方法只落死亡态，不做任何玩家提示。</p>
     */
    private static void markFallenIfCompanion(LivingEntity target, ServerLevel serverLevel) {
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return;
        }
        UUID companionId = data.getCompanionId();
        if (companionId == null) {
            return;
        }
        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return;
        }

        // 关掉落（M3.2）—— 必须在下面写快照**之前**：让快照里的 ArmorDropChances 也记成 0，
        // 与实体真实状态一致（否则 M4 复活按快照重建时，掉落概率又回到默认的 0.085）。
        // 生效时机有取证保证（2026-09-22 javap）：LivingDeathEvent 在 LivingEntity#die 的
        // offset 0 触发，掉落（dropAllDeathLoot）在同一方法的 offset 162 —— 事件里改掉的
        // 掉落概率，来得及拦住同一次死亡的掉落物。
        EquipmentSlots.sealDrops(target);

        // D6：死亡即掉落 —— 与收回同口径，必须先倒空行囊再存快照。
        // 快照走 saveWithoutId，会带上 ForgeCaps（行囊 NBT 在其中）；顺序颠倒的话，
        // M4 复活按同一身份 UUID 重建实体、load 快照时会把行囊原样回灌。
        PouchDrop.dropAll(target, data.getPouch());

        // 死亡快照：与收回同口径，保证等级 / 经验 / 技能不因死亡丢失。
        entry.setLevel(data.getLevel());
        entry.setXp(data.getXp());
        entry.setSkillPoints(data.getSkillPoints());
        entry.setSkillSnapshot(data.syncNBT().getCompound("skill_levels"));
        entry.setSkillInvestments(data.getSkillInvestments());
        entry.setSkillInvestmentsKnown(data.hasKnownSkillInvestments());
        // 一次 saveWithoutId 供两个用途：整包外观快照 + 从中摘出的装备快照（M3.2）。
        CompoundTag snapshot = target.saveWithoutId(new CompoundTag());
        entry.setEntitySnapshot(snapshot);
        entry.setEquipmentSnapshot(EquipmentSlots.extractFrom(snapshot));
        if (entry.getSpecies() == null) {
            entry.setSpecies(target.getType());
        }
        entry.setAlive(false);
        entry.setSummoned(false); // 实体随死亡被移除，不再是「在场」。
        entry.clearEntityLocation(); // WP-02B：死亡后清空失效实体定位。
        archive.putEntry(entry);

        // M4.1 死亡掉魂石：在死亡位置生成一枚绑定该宠物身份的魂石（纯钥匙）。
        // 魂石物品 fireResistant，熔岩烧不掉（虚空掉落 M5 再议，当前不特殊处理）。
        dropSoulstone(serverLevel, target, companionId);

        FurkinMod.LOGGER.info("Furkin fallen: id={} species={}", companionId, target.getType());
    }

    /** 在死亡位置掉一枚绑定该宠物身份的魂石。 */
    private static void dropSoulstone(ServerLevel serverLevel, LivingEntity target, UUID companionId) {
        ItemStack stone = new ItemStack(ModItems.FURKIN_SOULSTONE.get());
        FurkinSoulstoneItem.bindCompanion(stone, companionId);
        ItemEntity itemEntity = new ItemEntity(
                serverLevel,
                target.getX(), target.getY(), target.getZ(),
                stone);
        // 魂石可交易可追踪：不设 pickupDelay（立即可捡）；默认无速度（就地落下）。
        serverLevel.addFreshEntity(itemEntity);
        FurkinMod.LOGGER.info("Furkin soulstone dropped: id={} at ({}, {}, {})",
                companionId, target.getX(), target.getY(), target.getZ());
    }

    /** 服务端 tick 兜底：清理脱离战斗超时的追踪记录，防止残留；并驱动周期被动。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CombatParticipationTracker.tickCleanup();

        // 周期被动（守夜者夜视 / 群猎战术叠层等）——分发器内部按 20 tick 节流。
        SkillPassiveDispatcher.onServerTick(event.getServer());
        SkillRuntimeCalibrator.onServerTick(event.getServer());
    }

    /**
     * 技能树数据驱动 JSON 的资源重载监听（进服 / 数据包重载时加载）。
     * 注意：AddReloadListenerEvent 是 FORGE 游戏事件总线事件，不能挂 ModBus。
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(SkillRegistry.reloadListener());
    }
}
