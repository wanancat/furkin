package com.wanancat.furkin.internal.equipment;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * 实体装备槽的三件「实体侧」操作：<b>关掉落 / 存档摘取 / 铺回</b>（M3.2 搬运批）。
 *
 * <p>为什么单独成类：{@link MobEquipmentContainer} 是<b>容器视图</b>（给 {@code Slot} 用的那一层），
 * 不该掺进实体生命周期的策略；而 {@code FurkinCompanionManager} 只管「收回 / 召唤」两条搬运链路，
 * 契约侧与死亡侧也要用这里的两个方法。抽成工具类，三个调用点各自引用即可。</p>
 *
 * <h2>为什么「关掉落」必须与「死亡快照」配对</h2>
 *
 * <p>只关掉落而不存快照，装备会<b>随实体直接消失</b>（不掉落、不入档）—— 比不关更糟。
 * 故本类的 {@link #sealDrops} 与 {@link #extractFrom} 在调用点上成对出现（设计稿 §2.3 硬约束）。</p>
 *
 * <h2>⚠️ 两个「顺序」不是一回事，别混用</h2>
 *
 * <table border="1">
 *   <caption>两种顺序</caption>
 *   <tr><th>顺序</th><th>成员</th><th>用在哪</th></tr>
 *   <tr><td><b>界面顺序</b></td><td>头 → 胸 → 腿 → 脚</td>
 *       <td>{@link MobEquipmentContainer} 的容器索引 0–3</td></tr>
 *   <tr><td><b>存档顺序</b>（{@link EquipmentSlot#getIndex()}）</td><td>脚 → 腿 → 胸 → 头</td>
 *       <td>原版 {@code ArmorItems} NBT 列表，本条链路上的一切解析</td></tr>
 * </table>
 *
 * <p>本类处理的是<b>存档顺序</b>，故按 {@code getIndex()} 建表（{@link #ARMOR_BY_INDEX}）。
 * 硬编码 {@code {HEAD, CHEST, LEGS, FEET}} 会在这里踩坑 —— 那会让「头的装备写到脚上」。</p>
 */
public final class EquipmentSlots {

    private EquipmentSlots() {
    }

    /** 原版写装备用的 NBT 键名，与 {@code Mob#addAdditionalSaveData} 逐字一致。 */
    private static final String KEY_ARMOR_ITEMS = "ArmorItems";

    /** {@link #KEY_ARMOR_ITEMS} 的公开别名 —— 供属性计算（RecordAttributes）等只读方复用，避免散落字面量。 */
    public static final String ARMOR_ITEMS_KEY = KEY_ARMOR_ITEMS;

    /** 原版盔甲槽个数（1.20.1 固定 4）。 */
    private static final int ARMOR_COUNT = 4;

    /**
     * 存档顺序（{@code getIndex()} 升序 = 脚/腿/胸/头）的盔甲槽表。
     *
     * <p>不写死成员，而是从枚举里按 {@code getType() == ARMOR} + {@code getIndex()} 现推 ——
     * 将来原版增删盔甲槽时这张表自动跟上，解析下标不会错位。</p>
     */
    private static final EquipmentSlot[] ARMOR_BY_INDEX = buildArmorByIndex();

    private static EquipmentSlot[] buildArmorByIndex() {
        EquipmentSlot[] slots = new EquipmentSlot[ARMOR_COUNT];
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR && slot.getIndex() < ARMOR_COUNT) {
                slots[slot.getIndex()] = slot;
            }
        }
        return slots;
    }

    // ===== ① 关掉落 =====

    /**
     * 把四个盔甲槽的掉落概率归零 —— <b>宠物死亡时装备不掉出世界</b>。
     *
     * <p><b>为什么归零就够了</b>（2026-09-22 javap 取证，{@code Mob#dropCustomDeathLoot} 字节码）：
     * 掉落判定是 {@code guaranteed = chance > 1.0F}，随后
     * {@code if (!(recentlyHit || guaranteed)) skip}，再 {@code if (max(nextFloat() - looting*0.01F, 0F) < chance)}。
     * {@code chance == 0} 时<b>两条入口都进不去</b>（{@code recentlyHit} 为真也要过
     * {@code nextFloat() < 0}，恒假）⇒ 装备必定保留。</p>
     *
     * <p><b>为什么不能省</b>（同上取证）：{@code Mob#addAdditionalSaveData} 会把
     * {@code ArmorDropChances} 一起写进存档，而 {@code Mob#readAdditionalSaveData} 读它时带
     * {@code contains("ArmorDropChances", 9)} 守卫 —— <b>旧档没有这个键，读回来仍是默认的 0.085</b>。
     * 所以「新建实体 → load 快照」这条路只有在快照是本批之后存的才带零值，
     * 每次实体入世都必须显式再设一遍。</p>
     *
     * <p>只动盔甲槽：手部（主/副手）不属于「绒亲装备」这条功能线，保持原版掉落行为。</p>
     *
     * @param living 目标实体；非 {@link Mob}（如玩家、盔甲架）时静默跳过
     */
    public static void sealDrops(LivingEntity living) {
        if (!(living instanceof Mob mob)) {
            return;
        }
        for (EquipmentSlot slot : ARMOR_BY_INDEX) {
            mob.setDropChance(slot, 0.0F);
        }
    }

    /**
     * 把四个盔甲槽掉落到实体脚下并清空；空槽不生成物品实体。
     *
     * <p>1.19.2 没有原版 {@code MobEquipmentContainer}，但本分支已有同名的自维护只读视图；这里
     * 复用它做官方 {@link Containers#dropContents} 的容器参数，掉落后显式清空视图，避免
     * “掉落成功但槽位仍在”的半完成状态。1.19.2 的 {@code Containers.dropContents} 不保证
     * 清空容器，不能依赖它完成第二步。</p>
     *
     * @param living 目标实体；客户端侧、非 {@link Mob} 或空装备时静默返回 false
     * @return 是否至少有一个非空盔甲槽被处理
     */
    public static boolean dropAndClear(LivingEntity living) {
        if (!(living.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        MobEquipmentContainer equipment = new MobEquipmentContainer(living);
        if (equipment.isEmpty()) {
            return false;
        }
        Containers.dropContents(level, living, equipment);
        equipment.clearContent();
        return true;
    }

    /**
     * 把四个盔甲槽的掉落概率恢复为原版默认值。
     *
     * <p>解绑后实体回到普通动物语义，不能继续继承绒亲死亡不掉落规则。1.19.2 没有公开的
     * 原值 getter，按冻结口径 D4 写回 {@link Mob#DEFAULT_EQUIPMENT_DROP_CHANCE}；第三方自定义
     * 原始掉率无法精确还原，该边界已在 WP-02B 文档保留。</p>
     *
     * @param living 目标实体；非 {@link Mob} 时静默跳过
     */
    public static void restoreDefaultDropChances(LivingEntity living) {
        if (!(living instanceof Mob mob)) {
            return;
        }
        for (EquipmentSlot slot : ARMOR_BY_INDEX) {
            mob.setDropChance(slot, Mob.DEFAULT_EQUIPMENT_DROP_CHANCE);
        }
    }

    /**
     * 把档案里的四件盔甲掉落到发起解绑的玩家脚下。
     *
     * <p>用于目标实体已收回或已死亡、无法在实体脚下掉落的解绑路径。NBT 仍复用原版
     * {@code ArmorItems} 格式；仅当至少一个槽非空时才创建容器并调用官方掉落流程。</p>
     *
     * @param player       发起解绑的玩家
     * @param equipmentTag 档案的 {@code equipmentSnapshot}
     * @return 是否至少掉落了一个非空槽
     */
    public static boolean dropArchivedEquipment(ServerPlayer player, CompoundTag equipmentTag) {
        if (player == null || equipmentTag == null
                || !equipmentTag.contains(KEY_ARMOR_ITEMS, Tag.TAG_LIST)) {
            return false;
        }
        ServerLevel level = player.getLevel();
        ListTag list = equipmentTag.getList(KEY_ARMOR_ITEMS, Tag.TAG_COMPOUND);
        int count = Math.min(ARMOR_BY_INDEX.length, list.size());
        SimpleContainer equipment = new SimpleContainer(count);
        boolean hasItems = false;
        for (int i = 0; i < count; i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) {
                equipment.setItem(i, stack);
                hasItems = true;
            }
        }
        if (!hasItems) {
            return false;
        }
        Containers.dropContents(level, player, equipment);
        equipment.clearContent();
        return true;
    }

    // ===== ② 存档摘取 =====

    /**
     * 从实体整包快照里摘出装备内容，作为档案里的 {@code equipmentSnapshot}。
     *
     * <p><b>为什么要摘而不是自己序列化</b>：原版 {@code Mob#addAdditionalSaveData} 本来就会把
     * 装备写成 {@code ArmorItems} 列表（哪怕是空槽也逐格写一个空 tag），格式由它定义。
     * 自己再写一份序列化 = 多一处会与原版脱节的格式假设；摘键则是零假设 ——
     * 将来原版改格式、加槽位，这里跟着变。</p>
     *
     * <p>入参用「已经算好的整包快照」而不是实体本身，是因为两个调用点
     * （{@code dismiss} / 死亡侧）为了 {@code entitySnapshot} 都已经调过一次
     * {@code saveWithoutId}，没必要再算第二遍。</p>
     *
     * @param entitySnapshot 实体整包快照（{@code LivingEntity#saveWithoutId} 的结果）
     * @return 只含 {@code ArmorItems} 的标签；快照为 null / 空时返回空标签
     */
    public static CompoundTag extractFrom(CompoundTag entitySnapshot) {
        CompoundTag out = new CompoundTag();
        if (entitySnapshot == null || entitySnapshot.isEmpty()) {
            return out;
        }
        Tag armor = entitySnapshot.get(KEY_ARMOR_ITEMS);
        if (armor != null) {
            out.put(KEY_ARMOR_ITEMS, armor.copy());
        }
        return out;
    }

    // ===== ③ 铺回槽位 =====

    /**
     * 把装备快照铺回实体装备槽。
     *
     * <p><b>空快照守卫是必须的，不是保险</b>：M3.2 之前收回的宠物，档案里的
     * {@code equipmentSnapshot} 是构造时的空标签，而它的 {@code entitySnapshot} 里可能<b>已经带了装备</b>
     * （原版 {@code Mob} 存档自带 ArmorItems —— 也就是说「收回→召唤」往返其实早就通了）。
     * 若这里不加守卫地清空四槽，就会把 {@code load(snapshot)} 刚恢复的装备抹掉。</p>
     *
     * <p>所以判据是：<b>有装备快照才以它为准</b>（它是这条功能线的权威副本）；
     * 没有就什么都不做，留给实体自身已恢复的内容。</p>
     *
     * @param living   目标实体
     * @param equipmentTag 档案里的 {@code equipmentSnapshot}
     */
    public static void applyTo(LivingEntity living, CompoundTag equipmentTag) {
        if (living == null || equipmentTag == null || equipmentTag.isEmpty()) {
            // 旧档（本批之前存的）没有装备快照 → 不动槽位，保留实体已恢复的内容。
            return;
        }
        if (!equipmentTag.contains(KEY_ARMOR_ITEMS, Tag.TAG_LIST)) {
            return;
        }
        ListTag list = equipmentTag.getList(KEY_ARMOR_ITEMS, Tag.TAG_COMPOUND);
        int count = Math.min(ARMOR_BY_INDEX.length, list.size());
        for (int i = 0; i < count; i++) {
            // 空槽在原版列表里是空 CompoundTag，ItemStack.of 会还原成 EMPTY —— 正好是「清空该槽」。
            living.setItemSlot(ARMOR_BY_INDEX[i], ItemStack.of(list.getCompound(i)));
        }
    }
}
