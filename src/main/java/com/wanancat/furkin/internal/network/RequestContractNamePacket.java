package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：请求玩家为「将要契约的实体」输入名字。
 *
 * <p>契约流程第一步（边界校验全部通过后）：服务端发本包，客户端打开
 * {@code ContractNameScreen} 让玩家命名。玩家确认 / 取消后经
 * {@link ConfirmContractPacket} 回服务端真正落契约。</p>
 *
 * <p>携带目标实体 ID，客户端据此向服务端回传「对哪只实体契约」。</p>
 */
public final class RequestContractNamePacket {

    /** 目标实体 ID（待契约的实体）。 */
    private final int entityId;

    public RequestContractNamePacket(int entityId) {
        this.entityId = entityId;
    }

    public int getEntityId() {
        return entityId;
    }

    public static void encode(RequestContractNamePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
    }

    public static RequestContractNamePacket decode(FriendlyByteBuf buf) {
        return new RequestContractNamePacket(buf.readVarInt());
    }

    public static void handle(RequestContractNamePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinClientPacketHandler.openContractName(packet)));
        ctx.setPacketHandled(true);
    }
}
