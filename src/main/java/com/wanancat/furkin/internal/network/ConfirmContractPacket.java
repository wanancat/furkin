package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：玩家在命名框里确认后，携带「实体 ID + 名字」请求落契约。
 *
 * <p>契约流程第二步：客户端 {@code ContractNameScreen} 确认后发本包。服务端不信任
 * 包内实体 ID，而是先取出该玩家的待确认会话，再按当前服务端状态重新校验目标与主手。</p>
 *
 * <p>{@code name} 为空串表示玩家留空 → 服务端回退到物种名。</p>
 */
public final class ConfirmContractPacket {

    /** 目标实体 ID。 */
    private final int entityId;

    /** 玩家输入的名字（可为空串 = 留空，服务端回退物种名）。 */
    private final String name;

    public ConfirmContractPacket(int entityId, String name) {
        this.entityId = entityId;
        this.name = name;
    }

    public static void encode(ConfirmContractPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        buf.writeUtf(packet.name);
    }

    public static ConfirmContractPacket decode(FriendlyByteBuf buf) {
        return new ConfirmContractPacket(buf.readVarInt(), buf.readUtf());
    }

    public static void handle(ConfirmContractPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        ctx.enqueueWork(() -> FurkinContractHandler.confirmContract(player, packet.entityId, packet.name));
        ctx.setPacketHandled(true);
    }
}
