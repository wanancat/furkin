package com.wanancat.furkin.internal.event;

import com.wanancat.furkin.internal.command.FurkinCommand;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import com.wanancat.furkin.internal.growth.FurkinFeeding;
import com.wanancat.furkin.internal.item.FurkinContractItem;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

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
}
