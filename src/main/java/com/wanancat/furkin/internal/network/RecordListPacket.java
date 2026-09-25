package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinClientPacketHandler;
import com.wanancat.furkin.internal.contract.FurkinCombatMode;
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
 * 服务端 → 客户端：下发绒亲列表。
 *
 * <p><b>两种用途，靠 {@link #openScreen} 区分</b>：
 * <ul>
 *   <li><b>开屏</b>（右键绒亲录物品）—— 客户端收到后经 {@link DistExecutor} 调
 *       {@code FurkinRecordScreen#open(List)} 打开界面。</li>
 *   <li><b>刷新</b>（录内按钮点完，服务端回发以就地更新）—— 不重开屏，交给已在的录界面
 *       自己换数据。</li>
 * </ul>
 * ⚠️ 2026-09-22 她报「页签点战斗模式→跳转到绒亲录了」：早先本包<b>只会开屏</b>，
 * 而技能面板切档也走 {@code RecordActionPacket} ⇒ 服务端无条件回发本包 ⇒ 面板点一下
 * 就被弹进录界面。⇒ <b>「推列表」不等于「开屏」</b>，两件事必须分开表达。</p>
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
        /** 战斗模式（M5 新增：录内按钮要显示当前档位，而档位不在属性表里）。 */
        private final FurkinCombatMode combatMode;
        /** 属性表（服务端算好下发，脱实体可算，见 {@link RecordAttributes}）。 */
        private final List<RecordAttributes.Line> attributes;

        public Entry(UUID companionId, String speciesName, int level, int xp, int skillPoints,
                     String name, boolean summoned, boolean alive,
                     FurkinCombatMode combatMode,
                     List<RecordAttributes.Line> attributes) {
            this.companionId = companionId;
            this.speciesName = speciesName;
            this.level = level;
            this.xp = xp;
            this.skillPoints = skillPoints;
            this.name = name;
            this.summoned = summoned;
            this.alive = alive;
            this.combatMode = combatMode == null ? FurkinCombatMode.FOLLOW : combatMode;
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

        /** 当前战斗模式。 */
        public FurkinCombatMode getCombatMode() {
            return combatMode;
        }

        /** 属性表（服务端算好下发）。 */
        public List<RecordAttributes.Line> getAttributes() {
            return attributes;
        }
    }

    private final List<Entry> entries;

    /**
     * 客户端收到后是否<b>打开绒亲录界面</b>。
     *
     * <p>{@code true} = 右键物品开屏；{@code false} = 仅供已在的录界面刷新数据
     * （服务端回发时用，避免把别的界面弹掉）。</p>
     */
    private final boolean openScreen;

    /** 开屏用途（右键物品）。 */
    public RecordListPacket(List<Entry> entries) {
        this(entries, true);
    }

    public RecordListPacket(List<Entry> entries, boolean openScreen) {
        this.entries = entries;
        this.openScreen = openScreen;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    /** 客户端收到后是否打开录界面。 */
    public boolean isOpenScreen() {
        return openScreen;
    }

    public static void encode(RecordListPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.openScreen);
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
            // 战斗模式（枚举序数，1 字节）。
            buf.writeEnum(e.combatMode);
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
        boolean openScreen = buf.readBoolean();
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
            FurkinCombatMode combatMode = buf.readEnum(FurkinCombatMode.class);
            int attrSize = buf.readVarInt();
            List<RecordAttributes.Line> attributes = new ArrayList<>(attrSize);
            for (int j = 0; j < attrSize; j++) {
                attributes.add(new RecordAttributes.Line(
                        buf.readUtf(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble()));
            }
            list.add(new Entry(id, species, level, xp, skillPoints,
                    name.isEmpty() ? null : name, summoned, alive, combatMode, attributes));
        }
        return new RecordListPacket(list, openScreen);
    }

    public static void handle(RecordListPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        // ⚠️ 两种用途分开（2026-09-22 她报「页签点战斗模式→跳转到绒亲录了」）：
        //   openScreen = true  → 右键物品开屏，setScreen 换出新录界面；
        //   openScreen = false → 刷新用途，**只喂给已在的录界面**，当前屏不是录则什么都不做
        //                        （否则她在技能面板点档位会被弹进录界面）。
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinClientPacketHandler.handleRecordList(packet)));
        ctx.setPacketHandled(true);
    }
}
