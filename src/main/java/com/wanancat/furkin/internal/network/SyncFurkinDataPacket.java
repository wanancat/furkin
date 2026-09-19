package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：同步某只绒亲的能力数据。
 *
 * <p>携带实体 ID + {@link FurkinData} 的 NBT 序列化。客户端收到后按实体 ID
 * 定位在场实体，反序列化写回其本地能力，供客户端渲染（头顶图标等）读取。</p>
 */
public final class SyncFurkinDataPacket {

    /** 目标实体 ID。 */
    private final int entityId;

    /** FurkinData 的 NBT 序列化（可空，null 表示「清除绒亲身份」——用于收回）。 */
    private final CompoundTag data;

    public SyncFurkinDataPacket(int entityId, CompoundTag data) {
        this.entityId = entityId;
        this.data = data;
    }

    public static void encode(SyncFurkinDataPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        buf.writeNbt(packet.data);
    }

    public static SyncFurkinDataPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        CompoundTag data = buf.readNbt();
        return new SyncFurkinDataPacket(entityId, data);
    }

    public static void handle(SyncFurkinDataPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> applyClient(packet)));
        ctx.setPacketHandled(true);
    }

    /** 客户端应用：按实体 ID 定位实体，写回本地能力。 */
    private static void applyClient(SyncFurkinDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(packet.entityId);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return;
        }
        if (packet.data != null && !packet.data.isEmpty()) {
            data.deserializeNBT(packet.data);
        } else {
            // 空包 = 收回，清回 WILD。
            data.setState(com.wanancat.furkin.internal.contract.FurkinState.WILD);
        }
    }
}
