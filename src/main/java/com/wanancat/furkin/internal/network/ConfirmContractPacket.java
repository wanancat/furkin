package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.contract.FurkinContractHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：玩家在命名框里确认后，携带「实体 ID + 名字」真正落契约。
 *
 * <p>契约流程第二步：客户端 {@code ContractNameScreen} 确认后发本包，服务端
 * 据此执行真正的契约动作（消耗契约 / 写能力 / 建档 / 存名字）。取消则不发本包，
 * 直接放弃契约。</p>
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
        ctx.enqueueWork(() -> {
            Entity entity = player.serverLevel().getEntity(packet.entityId);
            if (entity instanceof net.minecraft.world.entity.LivingEntity target) {
                FurkinContractHandler.executeContract(player, target, player.getMainHandItem(), packet.name);
            }
        });
        ctx.setPacketHandled(true);
    }
}
