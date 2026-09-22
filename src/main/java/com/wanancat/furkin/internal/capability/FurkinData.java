package com.wanancat.furkin.internal.capability;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.contract.FurkinCombatMode;
import com.wanancat.furkin.internal.contract.FurkinState;
import com.wanancat.furkin.internal.inventory.FurkinInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 绒亲能力对象 —— 实体在场时的运行时数据唯一真相源。
 *
 * <p>只持有原版没有的数据（设计稿 §1 通用判别法）：等级 / 经验 / 技能点 / 技能等级 / 身份。
 * 装备、生命值等原版已有权威数据位的东西，一律不在此重复持有。</p>
 *
 * <p>字段（设计稿 §2.1；已随实现补全 —— 2026-09-21）：</p>
 * <pre>
 * companionId    UUID    宠物身份（绑定主人与宠物，跨实体唯一档案主键）
 * ownerUuid      UUID    主人
 * level / xp     等级 / 经验（等级无上限）
 * skillPoints    可用技能点
 * skillLevels    Map&lt;ResourceLocation, Integer&gt;   技能 → 已投等级
 * state          FurkinState                        状态机当前值
 * combatMode     FurkinCombatMode                   战斗模式四档（跟随 / 被动 / 保护 / 主动）
 * feedCount / lastFeedMillis                        进食防刷的递减收益计数与最近喂食时刻
 * cooldowns      Map&lt;ResourceLocation, Long&gt;       技能冷却（技能 id → 冷却结束的 game time，入档持久化）
 * pouch          FurkinInventory                    随身行囊容器（只在场有效，格数随 travel_pouch 等级派生）
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
    /** 战斗模式（四档：跟随 / 被动 / 保护 / 主动）。 */
    private FurkinCombatMode combatMode;

    /** 连续进食计数（递减收益防刷，运行时状态，不持久化到档案）。 */
    private int feedCount;
    /** 上次进食时间戳（毫秒），用于递减收益的「停喂恢复」。 */
    private long lastFeedMillis;

    /** 技能冷却（技能 id → 冷却结束的 world game time）。**入档持久化**，跨会话保留。 */
    private final Map<ResourceLocation, Long> cooldowns = new HashMap<>();

    /** 随身行囊技能 id —— 行囊格数由它的当前等级决定。 */
    private static final ResourceLocation TRAVEL_POUCH =
            new ResourceLocation(FurkinMod.MODID, "travel_pouch");

    /**
     * 随身行囊容器（只在场有效）。
     *
     * <p>格数是<b>派生值</b>（= travel_pouch 等级 × 每级格数），故容器内不另存格数 ——
     * 实体加载与加点时各刷一次（见 {@link #refreshPouchSize()}）。</p>
     */
    private final FurkinInventory pouch = new FurkinInventory(0);

    public FurkinData() {
        this.companionId = null;
        this.ownerUuid = null;
        this.level = 1;
        this.xp = 0;
        this.skillPoints = 0;
        this.skillLevels = new HashMap<>();
        this.state = FurkinState.WILD;
        this.combatMode = FurkinCombatMode.FOLLOW;
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

    /** 战斗模式（四档）。 */
    public FurkinCombatMode getCombatMode() {
        return combatMode;
    }

    public void setCombatMode(FurkinCombatMode combatMode) {
        this.combatMode = combatMode == null ? FurkinCombatMode.FOLLOW : combatMode;
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

    /** 技能冷却表（技能 id → 冷却结束的 world game time）。**入档持久化**，跨会话保留。 */
    public Map<ResourceLocation, Long> getCooldowns() {
        return cooldowns;
    }

    /** 随身行囊容器（格数由 travel_pouch 等级决定，见 {@link #refreshPouchSize()}）。 */
    public FurkinInventory getPouch() {
        return pouch;
    }

    /**
     * 按 travel_pouch 当前等级刷新行囊容量，**只扩不缩**（加点后 / 实体加载时调用）。
     *
     * <p>扩容不产生溢出，可直接做。缩容会把已有物品挤出容器，而那些物品必须由
     * <b>持有实体的服务端调用方</b>去掉落（本类不持有实体，拿不到落点）——
     * 绝不在容器层留一条「静默吞物品」的路径。需要缩容的场合走
     * {@link #resizePouchToLevel()}，它把挤出的物品交还给调用方。</p>
     */
    public void refreshPouchSize() {
        int target = pouchSlotsForLevel();
        if (target > pouch.getContainerSize()) {
            pouch.resize(target);
        }
    }

    /**
     * 把容量对齐到「travel_pouch 等级 × 每级格数」（**可扩可缩**），返回被挤出的物品。
     *
     * <p>与 {@link #refreshPouchSize()} 的分工：本方法允许缩容，所以只能由<b>能处置溢出物</b>的
     * 服务端调用方使用（洗点后归零、召唤时按 config 重算），拿到返回值后交给
     * {@code PouchDrop.dropStacks(...)} 倒在宠物脚下。读档路径（无实体在场）只能用只扩的那个。</p>
     *
     * @return 被挤出的物品；扩容或容量无变化时为空列表
     */
    public List<ItemStack> resizePouchToLevel() {
        return pouch.resize(pouchSlotsForLevel());
    }

    /**
     * 行囊容量 = travel_pouch 等级 × 每级格数（D5 / D7：每级格数进 server config）。
     *
     * <p>本类双端都会实例化，而客户端读 SERVER config 拿到的是<b>本地副本</b>
     * （多人游戏下未必等于服务端的值）。这是可接受的近似：行囊内容的权威副本在服务端，
     * 客户端算出的格数只用于本地推断，最终以服务端下发的 Menu 数据为准。</p>
     */
    private int pouchSlotsForLevel() {
        int level = skillLevels.getOrDefault(TRAVEL_POUCH, 0);
        return Math.max(0, level) * FurkinServerConfig.POUCH_SLOTS_PER_LEVEL.get();
    }

    /** 是否已契约（COMPANION 或 FALLEN 都算「已进入伴侣体系」）。 */
    public boolean isCompanion() {
        return state == FurkinState.COMPANION || state == FurkinState.FALLEN;
    }

    /**
     * 序列化核心字段（不含随身行囊）到 NBT。
     *
     * <p>被 {@link #serializeNBT()}（持久化出口）与 {@link #syncNBT()}（网络同步出口）共用，
     * 二者区别只在于是否附带行囊物品。</p>
     */
    private void writeCore(CompoundTag tag) {
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
        tag.putString("combat_mode", combatMode.name());

        CompoundTag skills = new CompoundTag();
        for (Map.Entry<ResourceLocation, Integer> e : skillLevels.entrySet()) {
            skills.putInt(e.getKey().toString(), e.getValue());
        }
        tag.put("skill_levels", skills);

        // 技能冷却入档：存「冷却结束的绝对 game time」。
        // gameTime 在同一存档内跨会话连续递增，且离线期间不流逝，故直接存绝对值语义正确。
        CompoundTag cooldownsTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, Long> e : cooldowns.entrySet()) {
            cooldownsTag.putLong(e.getKey().toString(), e.getValue());
        }
        tag.put("cooldowns", cooldownsTag);
    }

    /**
     * 序列化为 NBT（持久化出口）。
     *
     * <p>含 {@code pouch} 行囊物品 —— 物品随实体 NBT 走（宠物退游戏 / 区块卸载重载都不丢）。
     * 仅由 {@link FurkinProvider} 在实体存档时调用；网络同步请用 {@link #syncNBT()}。</p>
     */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        writeCore(tag);
        // 随身行囊：物品随实体 NBT 走（宠物退游戏 / 区块卸载重载都不丢）。
        // 注意与「收回 / 死亡」的区别 —— 那两条路径会先清空并掉落物品再存快照（D6），
        // 否则快照里的 ForgeCaps 会把行囊原样回灌。
        tag.put("pouch", pouch.createTag());
        return tag;
    }

    /**
     * 序列化为 NBT（网络同步出口）。
     *
     * <p><b>不含行囊</b>：客户端渲染（头顶 / 面板属性区）只依赖等级 / 经验 / 技能点 / 状态 /
     * 技能快照，行囊物品由 {@code FurkinPouchMenu}（Container 同步）单独下发，
     * 不该经此包背最多 27 格物品的 NBT（浪费带宽 + 序列化开销）。</p>
     */
    public CompoundTag syncNBT() {
        CompoundTag tag = new CompoundTag();
        writeCore(tag);
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
        this.combatMode = tag.contains("combat_mode")
                ? FurkinCombatMode.valueOf(tag.getString("combat_mode"))
                : FurkinCombatMode.FOLLOW; // 旧档缺省 FOLLOW。

        this.skillLevels.clear();
        CompoundTag skills = tag.getCompound("skill_levels");
        for (String key : skills.getAllKeys()) {
            this.skillLevels.put(new ResourceLocation(key), skills.getInt(key));
        }

        // 技能冷却：旧档无此键时保持空表（等价于「无冷却」）。
        this.cooldowns.clear();
        CompoundTag cooldownsTag = tag.getCompound("cooldowns");
        for (String key : cooldownsTag.getAllKeys()) {
            this.cooldowns.put(new ResourceLocation(key), cooldownsTag.getLong(key));
        }

        // 随身行囊：**先按等级定容量，再读物品**。
        // ContainerHelper.loadAllItems 对越界 Slot 是静默跳过（不报错），顺序颠倒会让
        // 高编号格子里的物品被无声吞掉。旧档无此键时读到空 tag，等价于空行囊。
        refreshPouchSize();
        this.pouch.fromTag(tag.getCompound("pouch"));
    }

    /**
     * 数据深拷贝（存档快照用，D6 路径留底契约 / 死亡前的状态）。
     *
     * <p><b>刻意不复制行囊物品</b>：按 D6「行囊只在场」，物品不随快照走。
     * 容器内容另有两条独立通路（实体 NBT 持久化、Menu 增量同步），都不经过本拷贝。</p>
     */
    public FurkinData copy() {
        FurkinData copy = new FurkinData();
        copy.companionId = this.companionId;
        copy.ownerUuid = this.ownerUuid;
        copy.level = this.level;
        copy.xp = this.xp;
        copy.skillPoints = this.skillPoints;
        copy.skillLevels.putAll(this.skillLevels);
        copy.state = this.state;
        copy.combatMode = this.combatMode;
        copy.feedCount = this.feedCount;
        copy.lastFeedMillis = this.lastFeedMillis;
        copy.cooldowns.putAll(this.cooldowns);
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
