package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinRecordScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：下发本人「存活且已收回」的绒亲列表。
 *
 * <p>客户端收到后经 {@link DistExecutor} 调用 {@link FurkinRecordScreen#open(List)}
 * 打开列表界面（{@code FurkinRecordScreen} 标 {@code @OnlyIn(Dist.CLIENT)}，
 * 服务端加载安全）。</p>
 */
public final class RecordListPacket {

    /** 列表单条（身份 UUID + 物种本地化 key + 等级 + 名字 + 生命/召唤状态）。 */
    public static final class Entry {
        private final UUID companionId;
        private final String speciesName;
        private final int level;
        /** 宠物名字（可空；空则界面回退显示物种名）。 */
        private final String name;
        /** 是否已召唤（实体在场）。 */
        private final boolean summoned;
        /** 是否存活（false = 已死亡待复活）。 */
        private final boolean alive;

        public Entry(UUID companionId, String speciesName, int level, String name,
                     boolean summoned, boolean alive) {
            this.companionId = companionId;
            this.speciesName = speciesName;
            this.level = level;
            this.name = name;
            this.summoned = summoned;
            this.alive = alive;
        }

        public UUID getCompanionId() {
            return companionId;
        }

        public String getSpeciesName() {
            return speciesName;
        }

        public int getLevel() {
            return level;
        }

        public String getName() {
            return name;
        }

        /** 是否已被命名（名字非空）。 */
        public boolean hasName() {
            return name != null && !name.isEmpty();
        }

        /** 是否已召唤（实体在场）。 */
        public boolean isSummoned() {
            return summoned;
        }

        /** 是否存活。 */
        public boolean isAlive() {
            return alive;
        }
    }

    private final List<Entry> entries;

    public RecordListPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public static void encode(RecordListPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entries.size());
        for (Entry e : packet.entries) {
            buf.writeUUID(e.companionId);
            buf.writeUtf(e.speciesName);
            buf.writeVarInt(e.level);
            buf.writeUtf(e.name == null ? "" : e.name);
            buf.writeBoolean(e.summoned);
            buf.writeBoolean(e.alive);
        }
    }

    public static RecordListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String species = buf.readUtf();
            int level = buf.readVarInt();
            String name = buf.readUtf();
            boolean summoned = buf.readBoolean();
            boolean alive = buf.readBoolean();
            list.add(new Entry(id, species, level, name.isEmpty() ? null : name, summoned, alive));
        }
        return new RecordListPacket(list);
    }

    public static void handle(RecordListPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinRecordScreen.open(packet.entries)));
        ctx.setPacketHandled(true);
    }
}
