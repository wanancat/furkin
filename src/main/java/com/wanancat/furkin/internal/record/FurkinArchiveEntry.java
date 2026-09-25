package com.wanancat.furkin.internal.record;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.contract.FurkinCombatMode;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 绒亲录单条档案（设计稿 §2.2）。
 *
 * <p>字段：</p>
 * <pre>
 * 身份 UUID
 * ownerUuid
 * 物种（EntityType，召唤 / 复活重建实体用）
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

    /** 最近一次确认的在世实体 UUID；未召唤或旧档缺失时为空。 */
    private UUID entityUuid;

    /** 最近一次确认的实体所在维度；未召唤或旧档缺失时为空。 */
    private ResourceKey<Level> entityDimension;

    /** 主人 UUID。 */
    private UUID ownerUuid;

    /** 物种（EntityType）。召唤 / 复活重建实体时用。 */
    private EntityType<?> species;

    /** 生命状态：true=存活，false=已死亡待复活。 */
    private boolean alive;

    /** 是否召唤：true=已召唤（实体在场），false=已收回（实体不在场）。 */
    private boolean summoned;

    /** 等级快照。 */
    private int level;

    /** 经验快照（M2 起：升级溢出后的剩余经验，收回 / 召唤时与能力对象互转）。 */
    private int xp;

    /** 可用技能点快照（M2 起：升级得点，收回 / 召唤时与能力对象互转）。 */
    private int skillPoints;

    /** 技能快照（NBT 形态，M2 起填充具体技能）。 */
    private CompoundTag skillSnapshot;

    /** 装备快照（原版 ArmorItems 格式，M3 起填充）。 */
    private CompoundTag equipmentSnapshot;

    /** 实体外观快照（原版实体 saveWithoutId 的 NBT：品种 / 毛色 / 坐定 / 跟随等，召唤 / 复活重建时回灌）。 */
    private CompoundTag entitySnapshot;

    /** 宠物名字（契约时命名 / 命名牌改名，可空；为空时列表显示物种名）。 */
    private Component name;

    /** 战斗模式（四档，收回 / 召唤时与能力对象互转，保证跨召唤记忆）。 */
    private FurkinCombatMode combatMode;

    /** 上次「重获魂石」的世界游戏时刻（game time，M4.3 起）。0 = 从未重获（无冷却）。 */
    private long soulstoneReacquireAt;

    public FurkinArchiveEntry(UUID companionId) {
        this.companionId = companionId;
        this.entityUuid = null;
        this.entityDimension = null;
        this.species = null;
        this.alive = true;
        this.summoned = false;
        this.level = 1;
        this.xp = 0;
        this.skillPoints = 0;
        this.skillSnapshot = new CompoundTag();
        this.equipmentSnapshot = new CompoundTag();
        this.entitySnapshot = new CompoundTag();
        this.name = null;
        this.combatMode = FurkinCombatMode.FOLLOW;
        this.soulstoneReacquireAt = 0L;
    }

    public UUID getCompanionId() {
        return companionId;
    }

    @Nullable
    public UUID getEntityUuid() {
        return entityUuid;
    }

    @Nullable
    public ResourceKey<Level> getEntityDimension() {
        return entityDimension;
    }

    /** 记录实体身份与当前维度；只在实体确实在场时调用。 */
    public void setEntityLocation(Entity entity) {
        this.entityUuid = entity.getUUID();
        this.entityDimension = entity.getLevel().dimension();
    }

    /** 实体离场或被移除后清空位置，避免后续误用失效 UUID。 */
    public void clearEntityLocation() {
        this.entityUuid = null;
        this.entityDimension = null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public EntityType<?> getSpecies() {
        return species;
    }

    public void setSpecies(EntityType<?> species) {
        this.species = species;
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

    public CompoundTag getEntitySnapshot() {
        return entitySnapshot;
    }

    public void setEntitySnapshot(CompoundTag entitySnapshot) {
        this.entitySnapshot = entitySnapshot;
    }

    public Component getName() {
        return name;
    }

    public void setName(Component name) {
        this.name = name;
    }

    public FurkinCombatMode getCombatMode() {
        return combatMode;
    }

    public void setCombatMode(FurkinCombatMode combatMode) {
        this.combatMode = combatMode == null ? FurkinCombatMode.FOLLOW : combatMode;
    }

    public long getSoulstoneReacquireAt() {
        return soulstoneReacquireAt;
    }

    public void setSoulstoneReacquireAt(long soulstoneReacquireAt) {
        this.soulstoneReacquireAt = soulstoneReacquireAt;
    }

    /** 序列化为 NBT。 */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("companion_id", companionId);
        if (entityUuid != null) {
            tag.putUUID("entity_uuid", entityUuid);
        }
        if (entityDimension != null) {
            tag.putString("entity_dimension", entityDimension.location().toString());
        }
        if (ownerUuid != null) {
            tag.putUUID("owner_uuid", ownerUuid);
        }
        if (species != null) {
            tag.putString("species", ForgeRegistries.ENTITY_TYPES.getKey(species).toString());
        }
        tag.putBoolean("alive", alive);
        tag.putBoolean("summoned", summoned);
        tag.putInt("level", level);
        tag.putInt("xp", xp);
        tag.putInt("skill_points", skillPoints);
        tag.put("skill_snapshot", skillSnapshot);
        tag.put("equipment_snapshot", equipmentSnapshot);
        tag.put("entity_snapshot", entitySnapshot);
        if (name != null) {
            tag.putString("name", Component.Serializer.toJson(name));
        }
        tag.putString("combat_mode", combatMode.name());
        tag.putLong("soulstone_reacquire_at", soulstoneReacquireAt);
        return tag;
    }

    /** 从 NBT 反序列化。 */
    public static FurkinArchiveEntry deserializeNBT(CompoundTag tag) {
        UUID id = tag.getUUID("companion_id");
        FurkinArchiveEntry entry = new FurkinArchiveEntry(id);
        entry.entityUuid = tag.hasUUID("entity_uuid") ? tag.getUUID("entity_uuid") : null;
        if (tag.contains("entity_dimension", Tag.TAG_STRING)) {
            String rawDimension = tag.getString("entity_dimension");
            ResourceLocation dimensionId = ResourceLocation.tryParse(rawDimension);
            if (dimensionId == null) {
                FurkinMod.LOGGER.warn("Ignoring invalid entity_dimension '{}' for companion {}",
                        rawDimension, id);
            } else {
                entry.entityDimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimensionId);
            }
        }
        entry.ownerUuid = tag.hasUUID("owner_uuid") ? tag.getUUID("owner_uuid") : null;
        if (tag.contains("species")) {
            ResourceLocation speciesId = new ResourceLocation(tag.getString("species"));
            entry.species = ForgeRegistries.ENTITY_TYPES.getValue(speciesId);
        }
        entry.alive = tag.getBoolean("alive");
        entry.summoned = tag.getBoolean("summoned");
        entry.level = tag.getInt("level");
        // 兼容旧档：xp / skill_points 是 M2 新增字段，旧档无则默认 0。
        entry.xp = tag.contains("xp") ? tag.getInt("xp") : 0;
        entry.skillPoints = tag.contains("skill_points") ? tag.getInt("skill_points") : 0;
        entry.skillSnapshot = tag.getCompound("skill_snapshot");
        entry.equipmentSnapshot = tag.getCompound("equipment_snapshot");
        entry.entitySnapshot = tag.getCompound("entity_snapshot");
        if (tag.contains("name")) {
            entry.name = Component.Serializer.fromJson(tag.getString("name"));
        }
        // 兼容旧档：combat_mode 是 M2 战斗模式新增字段，旧档无则默认 FOLLOW。
        entry.combatMode = tag.contains("combat_mode")
                ? FurkinCombatMode.valueOf(tag.getString("combat_mode"))
                : FurkinCombatMode.FOLLOW;
        // 兼容旧档：soulstone_reacquire_at 是 M4.3 新增字段，旧档无则默认 0（无冷却）。
        entry.soulstoneReacquireAt = tag.contains("soulstone_reacquire_at")
                ? tag.getLong("soulstone_reacquire_at")
                : 0L;
        return entry;
    }
}
