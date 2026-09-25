package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinClientPacketHandler;
import com.wanancat.furkin.internal.contract.FurkinRecordActionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：绒亲录动作结果回执。
 *
 * <p>常规解绑返回 {@link FurkinRecordActionHandler.Result#ENTITY_UNRESOLVED} 且
 * {@link #forceUnbindAllowed} 为 {@code true} 时，录界面据此弹出二次确认页。
 * 确认页只发送 {@link RecordActionPacket.Action#FORCE_UNBIND}，服务端仍会重新校验所有权、
 * 档案状态和实体可解析性，不信任客户端是否真的确认过。</p>
 */
public final class RecordActionResultPacket {

    private final UUID companionId;
    private final RecordActionPacket.Action action;
    private final FurkinRecordActionHandler.Result result;
    private final boolean forceUnbindAllowed;

    public RecordActionResultPacket(UUID companionId, RecordActionPacket.Action action,
                                    FurkinRecordActionHandler.Result result,
                                    boolean forceUnbindAllowed) {
        this.companionId = companionId;
        this.action = action;
        this.result = result;
        this.forceUnbindAllowed = forceUnbindAllowed;
    }

    public UUID getCompanionId() {
        return companionId;
    }

    public RecordActionPacket.Action getAction() {
        return action;
    }

    public FurkinRecordActionHandler.Result getResult() {
        return result;
    }

    /** 服务端是否允许客户端进入强制解绑二次确认。 */
    public boolean isForceUnbindAllowed() {
        return forceUnbindAllowed;
    }

    public static void encode(RecordActionResultPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.companionId);
        buf.writeEnum(packet.action);
        buf.writeEnum(packet.result);
        buf.writeBoolean(packet.forceUnbindAllowed);
    }

    public static RecordActionResultPacket decode(FriendlyByteBuf buf) {
        return new RecordActionResultPacket(
                buf.readUUID(),
                buf.readEnum(RecordActionPacket.Action.class),
                buf.readEnum(FurkinRecordActionHandler.Result.class),
                buf.readBoolean());
    }

    public static void handle(RecordActionResultPacket packet,
                              Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinClientPacketHandler.handleRecordActionResult(packet)));
        ctx.setPacketHandled(true);
    }
}
