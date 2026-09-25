package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：玩家在命名框里确认后，回传「实体 ID + 名字」请求落契约。
 *
 * <p>契约流程第二步：客户端 {@code ContractNameScreen} 确认后发本包。取消则不发本包，
 * 直接放弃契约。</p>
 *
 * <p><b>本包不是权威入口</b>（WP-01）：服务端不直接按包里的实体 ID 落契约，而是把
 * 内容交给 {@link FurkinContractHandler#confirmContract(ServerPlayer, int, String)}，
 * 由它先消费服务端待确认会话、再按当前服务端状态重跑全部边界。没有会话的确认包
 * （伪造）被静默丢弃。</p>
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
