package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinRecordScreen;
import com.wanancat.furkin.internal.record.RecordAttributes;
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

    /** 列表单条（身份 UUID + 物种本地化 key + 等级 + 经验 + 技能点 + 名字 + 生命/召唤状态 + 属性表）。 */
    public static final class Entry {
        private final UUID companionId;
        private final String speciesName;
        private final int level;
        /** 经验（M2 起：升级溢出后的剩余经验）。 */
        private final int xp;
        /** 可用技能点（M2 起：升级得点）。 */
        private final int skillPoints;
        /** 宠物名字（可空；空则界面回退显示物种名）。 */
        private final String name;
        /** 是否已召唤（实体在场）。 */
        private final boolean summoned;
        /** 是否存活（false = 已死亡待复活）。 */
        private final boolean alive;
        /** 属性表（服务端算好下发，脱实体可算，见 {@link RecordAttributes}）。 */
        private final List<RecordAttributes.Line> attributes;

        public Entry(UUID companionId, String speciesName, int level, int xp, int skillPoints,
                     String name, boolean summoned, boolean alive,
                     List<RecordAttributes.Line> attributes) {
            this.companionId = companionId;
            this.speciesName = speciesName;
            this.level = level;
            this.xp = xp;
            this.skillPoints = skillPoints;
            this.name = name;
            this.summoned = summoned;
            this.alive = alive;
            this.attributes = attributes == null ? List.of() : attributes;
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

        public int getXp() {
            return xp;
        }

        public int getSkillPoints() {
            return skillPoints;
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

        /** 属性表（服务端算好下发）。 */
        public List<RecordAttributes.Line> getAttributes() {
            return attributes;
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
            buf.writeVarInt(e.xp);
            buf.writeVarInt(e.skillPoints);
            buf.writeUtf(e.name == null ? "" : e.name);
            buf.writeBoolean(e.summoned);
            buf.writeBoolean(e.alive);
            // 属性表（变长，与 OpenFurkinScreenPacket 的属性表同一条序列化思路）。
            buf.writeVarInt(e.attributes.size());
            for (RecordAttributes.Line line : e.attributes) {
                buf.writeUtf(line.attributeId());
                buf.writeDouble(line.total());
                buf.writeDouble(line.base());
                buf.writeDouble(line.skill());
                buf.writeDouble(line.equip());
            }
        }
    }

    public static RecordListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String species = buf.readUtf();
            int level = buf.readVarInt();
            int xp = buf.readVarInt();
            int skillPoints = buf.readVarInt();
            String name = buf.readUtf();
            boolean summoned = buf.readBoolean();
            boolean alive = buf.readBoolean();
            int attrSize = buf.readVarInt();
            List<RecordAttributes.Line> attributes = new ArrayList<>(attrSize);
            for (int j = 0; j < attrSize; j++) {
                attributes.add(new RecordAttributes.Line(
                        buf.readUtf(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble()));
            }
            list.add(new Entry(id, species, level, xp, skillPoints,
                    name.isEmpty() ? null : name, summoned, alive, attributes));
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
