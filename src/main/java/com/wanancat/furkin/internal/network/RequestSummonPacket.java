package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求召唤某只绒亲（绒亲录列表界面点条目触发）。
 *
 * <p>服务端收到后按档案状态分流：<b>未召唤</b> → {@link FurkinCompanionManager#summon}
 * 重建实体；<b>已召唤（在场）</b> → {@link FurkinCompanionManager#teleportToOwner}
 * 传送到主人身边。均回一条反馈给玩家。</p>
 */
public final class RequestSummonPacket {

    private final UUID companionId;

    public RequestSummonPacket(UUID companionId) {
        this.companionId = companionId;
    }

    public static void encode(RequestSummonPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.companionId);
    }

    public static RequestSummonPacket decode(FriendlyByteBuf buf) {
        return new RequestSummonPacket(buf.readUUID());
    }

    public static void handle(RequestSummonPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                applyServer(player, packet.companionId);
            }
        });
        ctx.setPacketHandled(true);
    }

    /** 服务端应用：走统一分流（召唤 / 传送），并按结果回反馈。 */
    private static void applyServer(ServerPlayer player, UUID companionId) {
        // 分流逻辑与命令 /furkin summon 共用同一个入口（2026-09-22 定）：
        // 未召唤 → 重建实体；已召唤 → 传送到身边。两处规则必须一份代码。
        FurkinCompanionManager.SummonResult result =
                FurkinCompanionManager.summonOrTeleport(player, companionId);

        if (result.ok()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            result == FurkinCompanionManager.SummonResult.TELEPORTED
                                    ? "furkin.msg.teleported"
                                    : "furkin.msg.summoned"), true);
        } else {
            // 失败也分流：传送失败与召唤失败文案不同。
            boolean wasTeleport = result == FurkinCompanionManager.SummonResult.REBUILD_FAILED;
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            wasTeleport ? "furkin.msg.teleport_failed" : "furkin.msg.summon_failed"), false);
        }
    }
}
