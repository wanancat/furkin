package com.wanancat.furkin.internal.record;

import com.wanancat.furkin.internal.attribute.AttributeDisplay;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.equipment.EquipBonus;
import com.wanancat.furkin.internal.equipment.EquipmentSlots;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import com.wanancat.furkin.internal.skill.effect.AttributeEffect;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绒亲录属性区的服务端计算 —— 从<b>档案快照</b>算一只绒亲的属性（脱实体）。
 *
 * <p><b>为什么脱实体可算</b>（2026-09-22 取证，别凭印象改）：绒亲录是「脱实体」的 ——
 * 宠物可能已死亡 / 已收回，没有活体 {@code LivingEntity} 可读。但属性三个分量都有
 * <b>不依赖活体实体的权威来源</b>：</p>
 * <ul>
 *   <li><b>基础值</b>：档案 {@code entitySnapshot} 的 {@code Attributes} 列表里，原版
 *       {@code AttributeInstance#save()} 写的是 {@code Name + Base + Modifiers}
 *       （javap 字节码确证），{@code Base} 就是纯基础值，脱实体可读。</li>
 *   <li><b>技能加成</b>：{@link AttributeEffect#totalAdditionBonuses} 只吃
 *       {@code skillLevels}（从 {@code skillSnapshot} 反解），不碰实体。</li>
 *   <li><b>装备加成</b>：{@link EquipBonus#total} 只吃 {@code ItemStack#getAttributeModifiers}
 *       （入参仅 {@link EquipmentSlot}、不需要实体，javap 取证见 EquipBonus 类注释），
 *       装备从 {@code equipmentSnapshot} 的 {@code ArmorItems} 还原。</li>
 * </ul>
 *
 * <p><b>口径与技能页一致</b>（见 {@code FurkinPanelScreen#skillAttributeLines}）：
 * {@code 总值 = 基础 + 技能 + 装备}，三项可加、恒等成立。基础值这里直接读快照
 * {@code Base}（而非技能页那样用「总 − 技能 − 装备」的残差），因为脱实体时「总」本身
 * 就是要拼出来的量，没有独立权威值可用；两者在加法语义下等价。</p>
 *
 * <p><b>两条路径</b>（2026-09-22 验收后定）：<b>在场宠物</b>走 {@link #computeLive}
 * （全部实时读活体实体 —— 快照只在收回 / 死亡时落，穿脱装备不更新，走快照必显示旧值）；
 * <b>已收回 / 已死亡</b>走 {@link #compute}（快照路径，脱实体）。调用方按
 * {@code entry.isSummoned()} 分流，实体找不到时回退快照路径。</p>
 */
public final class RecordAttributes {

    private RecordAttributes() {
    }

    /**
     * 一条属性行：{@code 名称 总值 (基础 + 技能 + 装备)}。
     *
     * @param attributeId 属性注册名（如 {@code minecraft:generic.attack_damage}）
     * @param total       总值 = 基础 + 技能 + 装备
     * @param base        基础值（快照 Base）
     * @param skill       技能加成（addition 求和）
     * @param equip       装备加成（addition 求和）
     */
    public record Line(String attributeId, double total, double base, double skill, double equip) {
    }

    /** 从档案条目算该绒亲的属性清单（脱实体；空快照 ⇒ 空表）。 */
    public static List<Line> compute(FurkinArchiveEntry entry) {
        if (entry == null) {
            return List.of();
        }

        // ① 技能加成：从 skillSnapshot 反解 skillLevels（键=技能id字符串，值=int等级），
        //    与 FurkinData#deserializeNBT 同口径。
        Map<ResourceLocation, Integer> skillLevels = new LinkedHashMap<>();
        CompoundTag skillTag = entry.getSkillSnapshot();
        if (skillTag != null) {
            for (String key : skillTag.getAllKeys()) {
                skillLevels.put(new ResourceLocation(key), skillTag.getInt(key));
            }
        }
        Map<String, Double> skillBonus = new LinkedHashMap<>();
        AttributeEffect.totalAdditionBonuses(SkillRegistry.tree(), skillLevels)
                .forEach((id, sum) -> skillBonus.put(id.toString(), sum));

        // ② 装备加成：从 equipmentSnapshot 的 ArmorItems 还原 4 个 ItemStack，
        //    用只读容器喂给 EquipBonus.total（addition 求和）。
        Map<String, Double> equipBonus = equipAdditionTotals(entry.getEquipmentSnapshot());
        // ③ 基础值 + 合并：遍历 entitySnapshot 的 Attributes 列表，逐项读 Base，
        //    只收「注册表 ∩ 未屏蔽」的属性（与 AttributeDisplay.isDisplayable 同判据）。
        List<Line> lines = new ArrayList<>();
        for (AttributeAmount base : baseAmounts(entry.getEntitySnapshot())) {
            String id = base.attributeId();
            double skill = skillBonus.getOrDefault(id, 0.0D);
            double equip = equipBonus.getOrDefault(id, 0.0D);
            double total = base.amount() + skill + equip;
            lines.add(new Line(id, total, base.amount(), skill, equip));
        }
        // 技能 / 装备给到了「快照里没有基础值」的属性（第三方属性未入 entitySnapshot）
        // 时也要列出来，否则玩家看不见这些增量。
        // （快照的 Attributes 列表由原版 AttributeMap#save 全量写出，正常情况覆盖全部；
        //   此兜底只为防御性，避免漏项。）
        for (String id : skillBonus.keySet()) {
            if (!hasBase(lines, id)) {
                lines.add(new Line(id, skillBonus.get(id), 0.0D, skillBonus.get(id),
                        equipBonus.getOrDefault(id, 0.0D)));
            }
        }
        for (String id : equipBonus.keySet()) {
            if (!hasBase(lines, id)) {
                lines.add(new Line(id, equipBonus.get(id), 0.0D, 0.0D, equipBonus.get(id)));
            }
        }

        lines.sort(Comparator
                .comparingInt((Line line) -> displayRank(line.attributeId()))
                .thenComparing(Line::attributeId));
        return lines;
    }

    /**
     * 从<b>在场活体</b>算该绒亲的属性清单 —— 绒亲录属性区的「活体路径」。
     *
     * <p><b>为什么要活体路径</b>（2026-09-22 验收发现，别删）：{@code equipmentSnapshot}
     * 只在收回 / 死亡时落（{@code FurkinCompanionManager#dismiss} 与
     * {@code CommonEvents} 死亡钩子），<b>穿脱装备的瞬间快照不更新</b> —— 在场宠物
     * 走快照路径会显示上次收回时的旧值。技能 / 基础值同理（快照是收回时刻的）。
     * 故在场宠物一律走本方法，全部实时读实体；仅当实体找不到时才由调用方回退
     * {@link #compute}（防御，正常不会发生）。</p>
     *
     * <p><b>口径</b>：行清单 = {@link AttributeDisplay#displayableAttributes}（显示集合 ∩
     * 实有 ∩ 未屏蔽，优先序与技能页一致）；每行 {@code base = getBaseValue()}、
     * {@code skill} 从实体 {@code FurkinData#getSkillLevels()} 实时反解、
     * {@code equip} 从活体四格盔甲实时求和、{@code total = getValue()}（活体权威真值，
     * 含技能 / 装备之外的一切 modifier —— 无第三方来源时与三分量之和恒等）。</p>
     */
    public static List<Line> computeLive(LivingEntity living) {
        if (living == null) {
            return List.of();
        }

        // ① 技能加成：实体 capability 的实时技能等级（不是 skillSnapshot）。
        Map<String, Double> skillBonus = new LinkedHashMap<>();
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data != null) {
            AttributeEffect.totalAdditionBonuses(SkillRegistry.tree(), data.getSkillLevels())
                    .forEach((id, sum) -> skillBonus.put(id.toString(), sum));
        }

        // ② 装备加成：活体四格（界面序 头/胸/腿/脚，slotFor 的约定），实时求和。
        Map<String, Double> equipBonus = equipTotals(new SnapshotEquipment(new ItemStack[]{
                living.getItemBySlot(EquipmentSlot.HEAD),
                living.getItemBySlot(EquipmentSlot.CHEST),
                living.getItemBySlot(EquipmentSlot.LEGS),
                living.getItemBySlot(EquipmentSlot.FEET),
        }));

        // ③ 逐行：清单走 displayableAttributes（含 hasAttribute 判定，取值不抛异常）。
        List<Line> lines = new ArrayList<>();
        for (Attribute attribute : AttributeDisplay.displayableAttributes(living)) {
            AttributeInstance instance = living.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            String id = AttributeDisplay.idOf(attribute);
            double base = instance.getBaseValue();
            double skill = skillBonus.getOrDefault(id, 0.0D);
            double equip = equipBonus.getOrDefault(id, 0.0D);
            lines.add(new Line(id, instance.getValue(), base, skill, equip));
        }

        lines.sort(Comparator
                .comparingInt((Line line) -> displayRank(line.attributeId()))
                .thenComparing(Line::attributeId));
        return lines;
    }

    /** 排序秩：原版常见属性按优先表排，其余按 id 字母序收尾（与技能页观感一致）。 */
    private static int displayRank(String attributeId) {
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(attributeId));
        return AttributeDisplay.displayRank(attribute);
    }

    private static boolean hasBase(List<Line> lines, String id) {
        for (Line line : lines) {
            if (line.attributeId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 一条「属性 id → 值」。 */
    private record AttributeAmount(String attributeId, double amount) {
    }

    /**
     * 从实体快照的 {@code Attributes} 列表读各属性基础值（只收未屏蔽属性）。
     *
     * <p>快照里 {@code Attributes} 是 {@link ListTag}，每项 {@code {Name, Base, Modifiers}}，
     * 这里只取 {@code Base}（技能 / 装备分量由上方独立算出，不解析 Modifiers ——
     * 那里面技能与装备的 modifier 混在一起，且各自已有更干净的独立来源）。</p>
     */
    private static List<AttributeAmount> baseAmounts(CompoundTag entitySnapshot) {
        List<AttributeAmount> out = new ArrayList<>();
        if (entitySnapshot == null || entitySnapshot.isEmpty()) {
            return out;
        }
        ListTag attributes = entitySnapshot.getList("Attributes", Tag.TAG_COMPOUND);
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag item = attributes.getCompound(i);
            String name = item.getString("Name");
            if (name.isEmpty()) {
                continue;
            }
            Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(name));
            if (!AttributeDisplay.isDisplayable(attribute)) {
                continue;
            }
            double base = item.contains("Base") ? item.getDouble("Base") : 0.0D;
            out.add(new AttributeAmount(name, base));
        }
        return out;
    }

    /**
     * 从装备快照算「属性 → addition 合计」。
     *
     * <p>装备快照是 {@code {ArmorItems: ListTag}}（存档顺序脚/腿/胸/头），逐格
     * {@code ItemStack.of} 还原后交给 {@link #equipTotals}。</p>
     */
    private static Map<String, Double> equipAdditionTotals(CompoundTag equipmentSnapshot) {
        if (equipmentSnapshot == null || equipmentSnapshot.isEmpty()) {
            return Map.of();
        }
        if (!equipmentSnapshot.contains(EquipmentSlots.ARMOR_ITEMS_KEY, Tag.TAG_LIST)) {
            return Map.of();
        }
        ListTag list = equipmentSnapshot.getList(EquipmentSlots.ARMOR_ITEMS_KEY, Tag.TAG_COMPOUND);
        // ⚠️ 顺序对齐（2026-09-22 修）：ArmorItems 存档序是 脚/腿/胸/头，而
        // EquipBonus.total 内部 slotFor 是界面序 头/胸/腿/脚（0=HEAD）——直接喂会把
        // 鞋子当头盔查 modifiers（查出来恒空，装备加成静默丢失）。必须反序。
        ItemStack[] stacks = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            stacks[i] = ItemStack.of(list.getCompound(list.size() - 1 - i));
        }
        return equipTotals(new SnapshotEquipment(stacks));
    }

    /**
     * 从装备容器算「属性 → addition 合计」（活体 / 快照共用核心）。
     *
     * <p>容器索引约定 = {@code MobEquipmentContainer#slotFor} 的界面序（0=头→3=脚），
     * 调用方必须按此序打包（快照 ArmorItems 是反的，见 {@link #equipAdditionTotals}）。</p>
     */
    private static Map<String, Double> equipTotals(Container equipment) {
        Map<String, Double> out = new LinkedHashMap<>();
        List<EquipBonus.Entry> entries = EquipBonus.total(equipment);
        for (EquipBonus.Entry entry : entries) {
            if (entry.operation() != AttributeModifier.Operation.ADDITION) {
                continue;
            }
            String id = AttributeDisplay.idOf(entry.attribute());
            out.merge(id, entry.amount(), Double::sum);
        }
        return out;
    }

    /** 只读装备容器 —— 仅喂给 {@link EquipBonus#total} 取属性，不涉及任何写路径。 */
    private static final class SnapshotEquipment implements Container {
        private final ItemStack[] stacks;

        SnapshotEquipment(ItemStack[] stacks) {
            this.stacks = stacks;
        }

        @Override
        public int getContainerSize() {
            return stacks.length;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot >= 0 && slot < stacks.length ? stacks[slot] : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return false;
        }

        @Override
        public void clearContent() {
        }
    }
}
