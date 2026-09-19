package com.wanancat.furkin.internal.record;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 绒亲录单条档案（设计稿 §2.2）。
 *
 * <p>字段：</p>
 * <pre>
 * 身份 UUID
 * ownerUuid
 * 生命状态（存活 / 已死亡待复活）  —— 两个正交维度之一
 * 是否召唤（已召唤 / 已收回）      —— 两个正交维度之二
 * 等级 / 技能快照
 * 装备快照（原版 ArmorItems 格式，M3 起填充）
 * </pre>
 *
 * <p><b>⚠️ 生命状态与是否召唤是两个正交维度，不是单一枚举</b>
 * （一只宠物可「存活但已收回」）。</p>
 */
public final class FurkinArchiveEntry {

    /** 身份 UUID（主键）。 */
    private final UUID companionId;

    /** 主人 UUID。 */
    private UUID ownerUuid;

    /** 生命状态：true=存活，false=已死亡待复活。 */
    private boolean alive;

    /** 是否召唤：true=已召唤（实体在场），false=已收回（实体不在场）。 */
    private boolean summoned;

    /** 等级快照。 */
    private int level;

    /** 技能快照（NBT 形态，M2 起填充具体技能）。 */
    private CompoundTag skillSnapshot;

    /** 装备快照（原版 ArmorItems 格式，M3 起填充）。 */
    private CompoundTag equipmentSnapshot;

    public FurkinArchiveEntry(UUID companionId) {
        this.companionId = companionId;
        this.alive = true;
        this.summoned = false;
        this.level = 1;
        this.skillSnapshot = new CompoundTag();
        this.equipmentSnapshot = new CompoundTag();
    }

    public UUID getCompanionId() {
        return companionId;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isSummoned() {
        return summoned;
    }

    public void setSummoned(boolean summoned) {
        this.summoned = summoned;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public CompoundTag getSkillSnapshot() {
        return skillSnapshot;
    }

    public void setSkillSnapshot(CompoundTag skillSnapshot) {
        this.skillSnapshot = skillSnapshot;
    }

    public CompoundTag getEquipmentSnapshot() {
        return equipmentSnapshot;
    }

    public void setEquipmentSnapshot(CompoundTag equipmentSnapshot) {
        this.equipmentSnapshot = equipmentSnapshot;
    }

    /** 序列化为 NBT。 */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("companion_id", companionId);
        if (ownerUuid != null) {
            tag.putUUID("owner_uuid", ownerUuid);
        }
        tag.putBoolean("alive", alive);
        tag.putBoolean("summoned", summoned);
        tag.putInt("level", level);
        tag.put("skill_snapshot", skillSnapshot);
        tag.put("equipment_snapshot", equipmentSnapshot);
        return tag;
    }

    /** 从 NBT 反序列化。 */
    public static FurkinArchiveEntry deserializeNBT(CompoundTag tag) {
        UUID id = tag.getUUID("companion_id");
        FurkinArchiveEntry entry = new FurkinArchiveEntry(id);
        entry.ownerUuid = tag.hasUUID("owner_uuid") ? tag.getUUID("owner_uuid") : null;
        entry.alive = tag.getBoolean("alive");
        entry.summoned = tag.getBoolean("summoned");
        entry.level = tag.getInt("level");
        entry.skillSnapshot = tag.getCompound("skill_snapshot");
        entry.equipmentSnapshot = tag.getCompound("equipment_snapshot");
        return entry;
    }
}
