package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinClientPacketHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
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

    /** 目标实体 ID。 */
    public int getEntityId() {
        return entityId;
    }

    /** FurkinData 的 NBT 序列化；null 表示收回并清除本地绒亲身份。 */
    public CompoundTag getData() {
        return data;
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
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinClientPacketHandler.handleSyncFurkinData(packet)));
        ctx.setPacketHandled(true);
    }
}
