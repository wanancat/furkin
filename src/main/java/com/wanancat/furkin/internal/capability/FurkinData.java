package com.wanancat.furkin.internal.capability;

import com.wanancat.furkin.internal.contract.FurkinState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 绒亲能力对象 —— 实体在场时的运行时数据唯一真相源。
 *
 * <p>只持有原版没有的数据（设计稿 §1 通用判别法）：等级 / 经验 / 技能点 / 技能等级 / 身份。
 * 装备、生命值等原版已有权威数据位的东西，一律不在此重复持有。</p>
 *
 * <p>字段（设计稿 §2.1）：</p>
 * <pre>
 * companionId    UUID    宠物身份（绑定主人与宠物，跨实体唯一档案主键）
 * ownerUuid      UUID    主人
 * level / xp     等级 / 经验（等级无上限）
 * skillPoints    可用技能点
 * skillLevels    Map&lt;ResourceLocation, Integer&gt;   技能 → 已投等级
 * state          FurkinState                        状态机当前值
 * </pre>
 */
public final class FurkinData {

    /** 技能等级映射里「技能 ID」的键类型 —— 用 ResourceLocation 命名空间（设计稿 §4）。 */
    private UUID companionId;
    private UUID ownerUuid;
    private int level;
    private int xp;
    private int skillPoints;
    private final Map<ResourceLocation, Integer> skillLevels;
    private FurkinState state;

    /** 连续进食计数（递减收益防刷，运行时状态，不持久化到档案）。 */
    private int feedCount;
    /** 上次进食时间戳（毫秒），用于递减收益的「停喂恢复」。 */
    private long lastFeedMillis;

    public FurkinData() {
        this.companionId = null;
        this.ownerUuid = null;
        this.level = 1;
        this.xp = 0;
        this.skillPoints = 0;
        this.skillLevels = new HashMap<>();
        this.state = FurkinState.WILD;
        this.feedCount = 0;
        this.lastFeedMillis = 0;
    }

    public UUID getCompanionId() {
        return companionId;
    }

    public void setCompanionId(UUID companionId) {
        this.companionId = companionId;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getXp() {
        return xp;
    }

    public void setXp(int xp) {
        this.xp = xp;
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public void setSkillPoints(int skillPoints) {
        this.skillPoints = skillPoints;
    }

    public Map<ResourceLocation, Integer> getSkillLevels() {
        return skillLevels;
    }

    public FurkinState getState() {
        return state;
    }

    public void setState(FurkinState state) {
        this.state = state;
    }

    /** 连续进食计数（递减收益防刷）。 */
    public int getFeedCount() {
        return feedCount;
    }

    public void setFeedCount(int feedCount) {
        this.feedCount = feedCount;
    }

    /** 上次进食时间戳（毫秒）。 */
    public long getLastFeedMillis() {
        return lastFeedMillis;
    }

    public void setLastFeedMillis(long lastFeedMillis) {
        this.lastFeedMillis = lastFeedMillis;
    }

    /** 是否已契约（COMPANION 或 FALLEN 都算「已进入伴侣体系」）。 */
    public boolean isCompanion() {
        return state == FurkinState.COMPANION || state == FurkinState.FALLEN;
    }

    /**
     * 序列化为 NBT。能力对象只存原版没有的字段。
     */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        if (companionId != null) {
            tag.putUUID("companion_id", companionId);
        }
        if (ownerUuid != null) {
            tag.putUUID("owner_uuid", ownerUuid);
        }
        tag.putInt("level", level);
        tag.putInt("xp", xp);
        tag.putInt("skill_points", skillPoints);
        tag.putString("state", state.name());

        CompoundTag skills = new CompoundTag();
        for (Map.Entry<ResourceLocation, Integer> e : skillLevels.entrySet()) {
            skills.putInt(e.getKey().toString(), e.getValue());
        }
        tag.put("skill_levels", skills);
        return tag;
    }

    /**
     * 从 NBT 反序列化。
     */
    public void deserializeNBT(CompoundTag tag) {
        this.companionId = tag.hasUUID("companion_id") ? tag.getUUID("companion_id") : null;
        this.ownerUuid = tag.hasUUID("owner_uuid") ? tag.getUUID("owner_uuid") : null;
        this.level = tag.getInt("level");
        this.xp = tag.getInt("xp");
        this.skillPoints = tag.getInt("skill_points");
        this.state = FurkinState.valueOf(tag.getString("state"));

        this.skillLevels.clear();
        CompoundTag skills = tag.getCompound("skill_levels");
        for (String key : skills.getAllKeys()) {
            this.skillLevels.put(new ResourceLocation(key), skills.getInt(key));
        }
    }

    /** 数据深拷贝（用于存档快照 / 网络同步）。 */
    public FurkinData copy() {
        FurkinData copy = new FurkinData();
        copy.companionId = this.companionId;
        copy.ownerUuid = this.ownerUuid;
        copy.level = this.level;
        copy.xp = this.xp;
        copy.skillPoints = this.skillPoints;
        copy.skillLevels.putAll(this.skillLevels);
        copy.state = this.state;
        copy.feedCount = this.feedCount;
        copy.lastFeedMillis = this.lastFeedMillis;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FurkinData other)) return false;
        return level == other.level
                && xp == other.xp
                && skillPoints == other.skillPoints
                && Objects.equals(companionId, other.companionId)
                && Objects.equals(ownerUuid, other.ownerUuid)
                && Objects.equals(skillLevels, other.skillLevels)
                && state == other.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(companionId, ownerUuid, level, xp, skillPoints, skillLevels, state);
    }
}
