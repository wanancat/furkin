package com.wanancat.furkin.internal.event;

import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import com.wanancat.furkin.internal.item.FurkinContractItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 通用事件处理器（逻辑端共享）。
 *
 * <p>M1：拦截右键实体，触发契约动作。</p>
 */
@Mod.EventBusSubscriber(modid = "furkin", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {

    private CommonEvents() {
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

        // 手持绒亲契约才触发。
        if (!(player.getMainHandItem().getItem() instanceof FurkinContractItem)) {
            return;
        }

        boolean contracted = FurkinContractHandler.tryContract(player, target, player.getMainHandItem());
        if (contracted) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }
}
