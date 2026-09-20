package com.wanancat.furkin.internal.event;

import com.wanancat.furkin.internal.command.FurkinCommand;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import com.wanancat.furkin.internal.growth.CombatParticipationTracker;
import com.wanancat.furkin.internal.growth.FurkinFeeding;
import com.wanancat.furkin.internal.growth.FurkinGrowth;
import com.wanancat.furkin.internal.item.FurkinContractItem;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.skill.SkillPassiveDispatcher;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
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
        FurkinCommand.register(event.getDispatcher());
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
                new SyncFurkinDataPacket(target.getId(), data.serializeNBT()));
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

        if (!(event.getTarget() instanceof LivingEntity target)) {
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

        // 潜行 + 右键 + 手持非契约 → 打开绒亲界面（本人契约绒亲，看技能面板）。
        // （手持契约时的潜行右键是「收回」，见下；这里只处理非契约物品。）
        if (player.isShiftKeyDown()
                && !(player.getMainHandItem().getItem() instanceof FurkinContractItem)
                && isOwnCompanion) {
            if (FurkinRecordActionHandler.openFurkinScreen(player, target)) {
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

        // 分支②：普通右键 → 契约。
        boolean contracted = FurkinContractHandler.tryContract(player, target, player.getMainHandItem());
        if (contracted) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    // ===== 战斗经验（§3.2 口径 A）：登记伤害 → 死亡结算 =====

    /**
     * 目标受伤：登记「攻击者」进该目标的参与者表，累计伤害。
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
     * 目标死亡：结算战斗经验。口径 A —— 每个参与过伤害的绒亲各拿
     * 目标原版经验 × {@code COMBAT_XP_MULTIPLIER}。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }

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

    /** 服务端 tick 兜底：清理脱离战斗超时的追踪记录，防止残留。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CombatParticipationTracker.tickCleanup();
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
