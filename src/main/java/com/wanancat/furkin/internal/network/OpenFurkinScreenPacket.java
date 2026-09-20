package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinPanelScreen;
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

    private final UUID companionId;
    private final String name;
    private final int skillPoints;
    private final List<SkillView> skills;

    public OpenFurkinScreenPacket(UUID companionId, String name, int skillPoints, List<SkillView> skills) {
        this.companionId = companionId;
        this.name = name;
        this.skillPoints = skillPoints;
        this.skills = skills;
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
        return new OpenFurkinScreenPacket(id, name.isEmpty() ? null : name, skillPoints, skills);
    }

    public static void handle(OpenFurkinScreenPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinPanelScreen.onSkillData(packet)));
        ctx.setPacketHandled(true);
    }
}
