package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.item.FurkinRecordItem;
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
            // 录内召唤/传送后不关屏，必须复用既有刷新入口回发最新列表；
            // 否则服务端状态虽已改变，当前界面仍显示旧状态。
            FurkinRecordItem.refreshRecordList(player);
            return;
        }

        // 失败按具体原因分档（2026-09-22 定）：原先只分「传送失败 / 召唤失败」两档，
        // 文案又是并列五选一的笼统句，玩家看不出到底卡在哪一条。
        // 现与命令侧 /furkin summon 的 switch 同分档（同一套 SummonResult 枚举），
        // 只是把命令侧的英文回执换成本地化 key（此路径由界面按钮触发，须走 lang）。
        // ACTIVE_LIMIT 带 %s（本世界上限），故传参。
        net.minecraft.network.chat.Component msg = switch (result) {
            case NOT_FOUND -> net.minecraft.network.chat.Component.translatable("furkin.msg.summon_not_found");
            case NOT_OWNER -> net.minecraft.network.chat.Component.translatable("furkin.msg.not_owner");
            case NOT_ALIVE -> net.minecraft.network.chat.Component.translatable("furkin.msg.summon_not_alive");
            case ACTIVE_LIMIT -> net.minecraft.network.chat.Component.translatable(
                    "furkin.msg.active_limit",
                    com.wanancat.furkin.internal.config.FurkinServerConfig.ACTIVE_LIMIT.get());
            default -> net.minecraft.network.chat.Component.translatable("furkin.msg.summon_failed");
        };
        player.displayClientMessage(msg, false);
    }
}
