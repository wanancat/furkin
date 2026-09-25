package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinClientPacketHandler;
import com.wanancat.furkin.internal.skill.SkillTree;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：绒亲面板的技能快照。
 *
 * <p>携带该只绒亲的身份、显示名、当前技能点，以及「该物种可见技能」的快照
 * （id / 名称 key / 描述 key / maxLevel / cost / 当前等级 / 未满足的前置）。
 * 客户端不依赖服务端技能树，屏一建就能渲染，也能直接判断某个技能为什么点不动。</p>
 *
 * <p><b>为什么不塞进 Menu</b>：技能列表是变长结构，{@code ContainerData} 是
 * {@code int[]} 装不下；而这条「加点 / 洗点后刷新」的链路已经跑通，不必改动。
 * 面板打开走另一条链路（{@code NetworkHooks.openScreen} + {@code FurkinPouchMenu}），
 * 两条包谁先到不保证 —— 客户端由 {@code FurkinPanelScreen.onSkillData} 统一收口并做缓存兜底。</p>
 */
public final class OpenFurkinScreenPacket {

    /** 单条技能快照。 */
    public static final class SkillView {
        private final String id;
        private final String nameKey;
        private final String descriptionKey;
        private final int maxLevel;
        private final int cost;
        private final int currentLevel;
        /**
         * 升「当前等级 + 1」时尚未满足的前置要求（空 = 前置已满足）。
         *
         * <p>由服务端算出后下发，而不是把 {@code requires} / {@code levelGate} 的原始字段发过来
         * 让客户端自己判 —— 后者等于把前置规则实现两遍，一旦两边口径跑偏，症状是
         * 「面板显示可以点，点下去被服务端拒绝」。</p>
         */
        private final List<SkillTree.Requirement> unmet;

        public SkillView(String id, String nameKey, String descriptionKey,
                         int maxLevel, int cost, int currentLevel,
                         List<SkillTree.Requirement> unmet) {
            this.id = id;
            this.nameKey = nameKey;
            this.descriptionKey = descriptionKey;
            this.maxLevel = maxLevel;
            this.cost = cost;
            this.currentLevel = currentLevel;
            this.unmet = unmet;
        }

        public String getId() {
            return id;
        }

        public String getNameKey() {
            return nameKey;
        }

        public String getDescriptionKey() {
            return descriptionKey;
        }

        public int getMaxLevel() {
            return maxLevel;
        }

        public int getCost() {
            return cost;
        }

        public int getCurrentLevel() {
            return currentLevel;
        }

        /** 尚未满足的前置要求（空 = 前置已满足）。 */
        public List<SkillTree.Requirement> getUnmet() {
            return unmet;
        }

        /** 是否无限技能（maxLevel == -1）。 */
        public boolean isInfinite() {
            return maxLevel == -1;
        }

        /** 是否已满级。 */
        public boolean isMaxed() {
            return !isInfinite() && currentLevel >= maxLevel;
        }

        /** 前置是否已满足（不满足时按钮置灰，悬停显示缺哪一项）。 */
        public boolean isPrereqMet() {
            return unmet.isEmpty();
        }
    }

    /**
     * 一条「属性 → 数值」。
     *
     * <p>attributeId 用注册名字符串（如 {@code minecraft:generic.attack_damage}）而不是
     * {@code Attribute} 对象：包里其余标识（技能 id）同样是字符串，解码端再 {@code ForgeRegistries}
     * 反查即可，两端序列化口径一致。</p>
     */
    public record AttributeAmount(String attributeId, double amount) {
    }

    private final UUID companionId;
    private final String name;
    private final int skillPoints;
    private final List<SkillView> skills;
    /**
     * 技能加成（按属性求和）—— 只含 {@code addition} 运算的
     * {@code furkin:attribute} 效果之和（{@code amount × 已投等级}）。
     *
     * <p><b>为什么必须服务端算</b>（设计稿 §4.1 取证⑦）：原版
     * {@code ClientboundUpdateAttributesPacket} 发 modifier 只发 {@code UUID + amount + operation}、
     * <b>不发名字</b>（收端硬编码 {@code "Unknown synced attribute modifier"}），客户端认不出
     * 哪一条是技能加的；且技能 JSON 走 {@code AddReloadListenerEvent}（仅服务端），
     * 客户端手里根本没有技能数值。两条独立证据同向。</p>
     */
    private final List<AttributeAmount> skillBonuses;
    /**
     * 服务端权威总值 —— 只装<b>非 client-syncable</b> 且要显示的属性（当前仅攻击伤害）。
     *
     * <p>7 条可同步属性的总值由客户端直接读实体（实时、权威）；而攻击伤害属 4 条非同步之一，
     * 其技能 modifier 从不上网，客户端那份只有实体类型的默认基值 ⇒ 必须由服务端下发，
     * 否则面板显示的攻伤永远少了技能加成那一段。</p>
     */
    private final List<AttributeAmount> serverTotals;

    public OpenFurkinScreenPacket(UUID companionId, String name, int skillPoints, List<SkillView> skills,
                                  List<AttributeAmount> skillBonuses, List<AttributeAmount> serverTotals) {
        this.companionId = companionId;
        this.name = name;
        this.skillPoints = skillPoints;
        this.skills = skills;
        this.skillBonuses = skillBonuses;
        this.serverTotals = serverTotals;
    }

    public UUID getCompanionId() {
        return companionId;
    }

    public String getName() {
        return name;
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public List<SkillView> getSkills() {
        return skills;
    }

    public List<AttributeAmount> getSkillBonuses() {
        return skillBonuses;
    }

    public List<AttributeAmount> getServerTotals() {
        return serverTotals;
    }

    /** 取某属性的技能加成（无则 0）。 */
    public double skillBonusOf(String attributeId) {
        for (AttributeAmount entry : skillBonuses) {
            if (entry.attributeId().equals(attributeId)) {
                return entry.amount();
            }
        }
        return 0.0D;
    }

    /**
     * 取某属性的服务端权威总值；<b>服务端未下发该属性时返回 {@code null}</b>。
     *
     * <p>用 {@code null} 而不是 0 区分「没给」与「给了 0」：调用方要据此决定是否回落到
     * 客户端那份（服务端不给有两种成因 —— 该生物没注册这条属性因而不在
     * {@code AttributeDisplay#serverTotalAttributes} 给出的集合里，或这是自旧包来的旧屏）。</p>
     */
    public Double serverTotalOf(String attributeId) {
        for (AttributeAmount entry : serverTotals) {
            if (entry.attributeId().equals(attributeId)) {
                return entry.amount();
            }
        }
        return null;
    }

    public static void encode(OpenFurkinScreenPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.companionId);
        buf.writeUtf(packet.name == null ? "" : packet.name);
        buf.writeVarInt(packet.skillPoints);
        buf.writeVarInt(packet.skills.size());
        for (SkillView s : packet.skills) {
            buf.writeUtf(s.id);
            buf.writeUtf(s.nameKey);
            buf.writeUtf(s.descriptionKey);
            buf.writeVarInt(s.maxLevel);
            buf.writeVarInt(s.cost);
            buf.writeVarInt(s.currentLevel);
            buf.writeVarInt(s.unmet.size());
            for (SkillTree.Requirement r : s.unmet) {
                buf.writeUtf(r.nameKey());
                buf.writeVarInt(r.requiredLevel());
            }
        }
        // 「属性 → 数值」两张小表（变长，与技能列表同一条通道）。
        buf.writeVarInt(packet.skillBonuses.size());
        for (AttributeAmount entry : packet.skillBonuses) {
            buf.writeUtf(entry.attributeId());
            buf.writeDouble(entry.amount());
        }
        buf.writeVarInt(packet.serverTotals.size());
        for (AttributeAmount entry : packet.serverTotals) {
            buf.writeUtf(entry.attributeId());
            buf.writeDouble(entry.amount());
        }
    }

    public static OpenFurkinScreenPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        int skillPoints = buf.readVarInt();
        int size = buf.readVarInt();
        List<SkillView> skills = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String skillId = buf.readUtf();
            String nameKey = buf.readUtf();
            String descriptionKey = buf.readUtf();
            int maxLevel = buf.readVarInt();
            int cost = buf.readVarInt();
            int currentLevel = buf.readVarInt();
            int unmetSize = buf.readVarInt();
            List<SkillTree.Requirement> unmet = new ArrayList<>(unmetSize);
            for (int j = 0; j < unmetSize; j++) {
                unmet.add(new SkillTree.Requirement(buf.readUtf(), buf.readVarInt()));
            }
            skills.add(new SkillView(skillId, nameKey, descriptionKey,
                    maxLevel, cost, currentLevel, unmet));
        }
        List<AttributeAmount> skillBonuses = readAmounts(buf);
        List<AttributeAmount> serverTotals = readAmounts(buf);
        return new OpenFurkinScreenPacket(id, name.isEmpty() ? null : name, skillPoints, skills,
                skillBonuses, serverTotals);
    }

    /** 读一张「属性 → 数值」表（与 {@code encode} 同序）。 */
    private static List<AttributeAmount> readAmounts(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<AttributeAmount> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new AttributeAmount(buf.readUtf(), buf.readDouble()));
        }
        return list;
    }

    public static void handle(OpenFurkinScreenPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinClientPacketHandler.handleSkillData(packet)));
        ctx.setPacketHandled(true);
    }
}
