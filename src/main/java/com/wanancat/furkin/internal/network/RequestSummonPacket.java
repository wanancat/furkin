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

    /** 服务端应用：按状态分流执行召唤 / 传送，并按结果回反馈。 */
    private static void applyServer(ServerPlayer player, UUID companionId) {
        // 先看档案状态：已召唤 → 传送；未召唤 → 召唤。
        boolean summoned = false;
        var archive = com.wanancat.furkin.internal.record.FurkinArchiveData.get(player.serverLevel());
        var entry = archive.getEntry(companionId);
        if (entry != null) {
            summoned = entry.isSummoned();
        }

        boolean ok = summoned
                ? FurkinCompanionManager.teleportToOwner(player, companionId)
                : FurkinCompanionManager.summon(player, companionId);
        if (ok) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            summoned ? "furkin.msg.teleported" : "furkin.msg.summoned"), true);
        } else {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            summoned ? "furkin.msg.teleport_failed" : "furkin.msg.summon_failed"), false);
        }
    }
}
