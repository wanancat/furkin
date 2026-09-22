package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：绒亲录管理动作（收回 / 解绑 / 改名）。
 *
 * <p>服务端收到后按 {@link Action} 分派到 {@link FurkinRecordActionHandler}，
 * 并按结果回一条反馈（成功 action bar，失败失败原因）。</p>
 */
public final class RecordActionPacket {

    /** 管理动作类型。 */
    public enum Action {
        DISMISS,
        UNBIND,
        RENAME,
        REACQUIRE_SOULSTONE
    }

    private final Action action;
    private final UUID companionId;
    /** 仅 RENAME 使用（可空）。 */
    private final String name;

    public RecordActionPacket(Action action, UUID companionId, String name) {
        this.action = action;
        this.companionId = companionId;
        this.name = name;
    }

    public static void encode(RecordActionPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action);
        buf.writeUUID(packet.companionId);
        buf.writeUtf(packet.name == null ? "" : packet.name);
    }

    public static RecordActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        return new RecordActionPacket(action, id, name.isEmpty() ? null : name);
    }

    public static void handle(RecordActionPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                applyServer(player, packet);
            }
        });
        ctx.setPacketHandled(true);
    }

    /** 服务端应用：按动作分派，回反馈。 */
    private static void applyServer(ServerPlayer player, RecordActionPacket packet) {
        String msgKey;
        boolean ok;

        switch (packet.action) {
            case DISMISS -> {
                ok = FurkinRecordActionHandler.dismiss(player, packet.companionId);
                msgKey = ok ? "furkin.msg.dismissed" : "furkin.msg.dismiss_failed";
            }
            case UNBIND -> {
                FurkinRecordActionHandler.Result r =
                        FurkinRecordActionHandler.unbind(player, packet.companionId);
                ok = r == FurkinRecordActionHandler.Result.OK;
                msgKey = ok ? "furkin.msg.unbound" : "furkin.msg.unbind_failed";
            }
            case RENAME -> {
                FurkinRecordActionHandler.Result r =
                        FurkinRecordActionHandler.rename(player, packet.companionId, packet.name);
                ok = r == FurkinRecordActionHandler.Result.OK;
                msgKey = ok ? "furkin.msg.renamed" : "furkin.msg.rename_failed";
            }
            case REACQUIRE_SOULSTONE -> {
                FurkinRecordActionHandler.Result r =
                        FurkinRecordActionHandler.reacquireSoulstone(player, packet.companionId);
                ok = r == FurkinRecordActionHandler.Result.OK;
                msgKey = switch (r) {
                    case OK -> "furkin.msg.soulstone_reacquired";
                    case NOT_FOUND, NOT_OWNER -> "furkin.msg.not_owner";
                    case NOT_DEAD -> "furkin.msg.soulstone_not_dead";
                    case ON_COOLDOWN -> "furkin.msg.soulstone_cooldown";
                    case NO_DIAMOND -> "furkin.msg.soulstone_no_diamond";
                    default -> "furkin.msg.soulstone_reacquire_failed";
                };
            }
            default -> {
                return;
            }
        }

        player.displayClientMessage(
                Component.translatable(msgKey), ok);
    }
}
