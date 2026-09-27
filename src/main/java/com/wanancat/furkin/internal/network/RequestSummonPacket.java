package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.contract.RemoteSummonOrigin;
import com.wanancat.furkin.internal.contract.RemoteSummonResult;
import com.wanancat.furkin.internal.contract.RemoteSummonService;
import com.wanancat.furkin.internal.item.FurkinRecordItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求召唤某只绒亲（绒亲录列表界面点条目触发）。
 *
 * <p>服务端收到后交给 {@link RemoteSummonService}：已加载实体立即传送，未加载实体走
 * 临时区块加载；异步终态再通过回调发送本地化反馈并刷新绒亲录列表。载荷和协议版本
 * 均保持不变。</p>
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

    /** 服务端应用：交给统一远召 service，并处理即时/异步两阶段反馈。 */
    private static void applyServer(ServerPlayer player, UUID companionId) {
        RemoteSummonResult result = RemoteSummonService.forServer(player.getServer()).request(
                player,
                companionId,
                RemoteSummonOrigin.RECORD,
                (feedbackPlayer, ignoredCompanionId, terminalResult) ->
                        sendRecordFeedback(feedbackPlayer, terminalResult, true));

        if (result == RemoteSummonResult.PENDING) {
            player.displayClientMessage(
                    Component.translatable("furkin.msg.remote_summon_pending"), true);
            return;
        }
        sendRecordFeedback(player, result, false);
    }

    private static void sendRecordFeedback(ServerPlayer player, RemoteSummonResult result,
                                           boolean asynchronous) {
        if (result == RemoteSummonResult.COMPLETED_TELEPORT) {
            player.displayClientMessage(Component.translatable(
                    asynchronous ? "furkin.msg.remote_summon_completed" : "furkin.msg.teleported"), true);
        } else if (result == RemoteSummonResult.COMPLETED_REBUILD) {
            player.displayClientMessage(Component.translatable(
                    asynchronous ? "furkin.msg.remote_summon_completed" : "furkin.msg.summoned"), true);
        } else {
            Component message = switch (result) {
                case NOT_FOUND -> Component.translatable("furkin.msg.summon_not_found");
                case NOT_OWNER -> Component.translatable("furkin.msg.not_owner");
                case NOT_ALIVE -> Component.translatable("furkin.msg.summon_not_alive");
                case ACTIVE_LIMIT -> Component.translatable(
                        "furkin.msg.active_limit", FurkinServerConfig.ACTIVE_LIMIT.get());
                case ENTITY_UNRESOLVED -> Component.translatable(
                        "furkin.msg.remote_summon_unresolved");
                case DIMENSION_MISSING -> Component.translatable(
                        "furkin.msg.remote_summon_dimension_missing");
                case TELEPORT_FAILED -> Component.translatable(
                        "furkin.msg.summon_dimension_change_failed");
                case NO_POSITION -> Component.translatable("furkin.msg.remote_summon_no_position");
                case TIMEOUT -> Component.translatable("furkin.msg.remote_summon_timeout");
                case CHUNK_LOAD_FAILED -> Component.translatable(
                        "furkin.msg.remote_summon_chunk_failed");
                case DUPLICATE_CONFLICT -> Component.translatable(
                        "furkin.msg.remote_summon_duplicate");
                case DISABLED -> Component.translatable("furkin.msg.remote_summon_disabled");
                case ALREADY_PENDING -> Component.translatable(
                        "furkin.msg.remote_summon_already_pending");
                case TOO_MANY_PENDING -> Component.translatable(
                        "furkin.msg.remote_summon_too_many_pending");
                case COOLDOWN -> Component.translatable("furkin.msg.remote_summon_cooldown");
                case CANCELLED -> Component.translatable("furkin.msg.remote_summon_cancelled");
                case INVALID_STATE, REBUILD_FAILED, PENDING -> Component.translatable(
                        "furkin.msg.summon_failed");
                default -> Component.translatable("furkin.msg.summon_failed");
            };
            player.displayClientMessage(message, false);
        }

        // 绒亲录 UI 的本地在途态需要一个确定的结束信号。PENDING 不刷新，保留加载态；
        // 其余立即/异步终态都复用既有列表刷新包，不新增协议字段或协议版本。
        FurkinRecordItem.refreshRecordList(player);
    }
}