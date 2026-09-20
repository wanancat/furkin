package com.wanancat.furkin.internal.network;

import com.wanancat.furkin.internal.client.FurkinScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：打开绒亲界面（技能面板）。
 *
 * <p>携带该只绒亲的身份、显示名、当前技能点，以及「该物种可见技能」的快照
 * （id / 名称 key / 描述 key / maxLevel / cost / 当前等级）。客户端不依赖服务端技能树，
 * 开屏即渲染。</p>
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

        public SkillView(String id, String nameKey, String descriptionKey,
                         int maxLevel, int cost, int currentLevel) {
            this.id = id;
            this.nameKey = nameKey;
            this.descriptionKey = descriptionKey;
            this.maxLevel = maxLevel;
            this.cost = cost;
            this.currentLevel = currentLevel;
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

        /** 是否无限技能（maxLevel == -1）。 */
        public boolean isInfinite() {
            return maxLevel == -1;
        }

        /** 是否已满级。 */
        public boolean isMaxed() {
            return !isInfinite() && currentLevel >= maxLevel;
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
        }
    }

    public static OpenFurkinScreenPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        int skillPoints = buf.readVarInt();
        int size = buf.readVarInt();
        List<SkillView> skills = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            skills.add(new SkillView(
                    buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new OpenFurkinScreenPacket(id, name.isEmpty() ? null : name, skillPoints, skills);
    }

    public static void handle(OpenFurkinScreenPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FurkinScreen.open(packet)));
        ctx.setPacketHandled(true);
    }
}
