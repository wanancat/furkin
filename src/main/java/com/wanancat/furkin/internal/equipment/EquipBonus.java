package com.wanancat.furkin.internal.equipment;

import com.google.common.collect.Multimap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装备属性合计 —— 把四个装备槽里所有装备的属性修饰符合并成一份清单，供装备页显示。
 *
 * <p><b>为什么能在客户端算</b>（2026-09-22 javap 取证）：{@code ItemStack#getAttributeModifiers}
 * 的入参只有 {@link EquipmentSlot}、<b>不需要实体</b>，返回
 * {@code Multimap<Attribute, AttributeModifier>}。而槽里的 {@code ItemStack} 本来就会被容器的
 * 同步包发到客户端 ⇒ <b>不需要新增数据包</b>。</p>
 *
 * <p><b>文本一律复用原版 key，不自造</b>（铁律：先查官方有没有可直接调用的）：
 * 属性名走 {@code Attribute#getDescriptionId()}（= {@code attribute.name.generic.*}），
 * 数值格式走 {@link ItemStack#ATTRIBUTE_MODIFIER_FORMAT}，整行模板走
 * {@code attribute.modifier.plus|take.<0|1|2>} —— 这套写法是从原版
 * {@code ItemStack#getTooltipLines} 的字节码里抄出来的（offset 737–950）：
 * 正数用 {@code plus} + {@link ChatFormatting#BLUE}，负数用 {@code take} 且<b>数值取反</b>
 * + {@link ChatFormatting#RED}；模板第三段是 {@code toValue()}，0 = 数值 / 1 = 乘基百分比 /
 * 2 = 乘总百分比。中文名由游戏语言包提供，mod 侧不另造一份。</p>
 *
 * <p><b>合计口径与「F3 实际增量」的关系</b>：原版
 * {@code AttributeInstance#calculateValue} 的公式是
 * {@code base += Σ(ADDITION)} → {@code result += base × Σ(MULTIPLY_BASE)} →
 * {@code result *= Π(1 + MULTIPLY_TOTAL)}。装备只给 {@code ADDITION} 时（原版盔甲即为如此），
 * 装备带来的增量就<b>精确等于</b>各件 amount 之和，本类的求和与 F3 逐项相等；
 * 出现 {@code MULTIPLY_*} 时它不是"某个数值"，故按原版的百分比模板单列，不混进求和。</p>
 */
public final class EquipBonus {

    private EquipBonus() {
    }

    /**
     * 摘要行最多显示几条。
     *
     * <p>上限来自<b>几何</b>，不是审美（2026-09-22 实测）：装备页四个装备槽横排占
     * {@code x 8..80}，右侧余量约 87px；按 MC 字体宽度（ASCII ≈ 6px、中文 9px、空格 4px），
     * 原版格式一项（如 {@code +4 护甲}）约 34px ⇒ <b>两项 72px 放得下、三项 110px 溢出</b>。
     * 放不下的项不进摘要，靠悬停明细看全。</p>
     */
    public static final int SUMMARY_MAX = 2;

    /**
     * 摘要取舍顺序（上→下）。
     *
     * <p>取<b>固定优先级</b>而非"按数值大小"：数值每帧都可能因装备变化而变，
     * 按大小排会让同一套装备在不同场合下摘要里的项跳来跳去；固定顺序则永远可预测。
     * 前两项覆盖原版盔甲 100% 的情况（护甲 + 韧性），后几项是给第三方模组装备兜底 ——
     * 免得模组盔甲只给移速时摘要行空着。</p>
     */
    private static final List<Attribute> PRIORITY = List.of(
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.MAX_HEALTH,
            Attributes.KNOCKBACK_RESISTANCE,
            Attributes.MOVEMENT_SPEED,
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.LUCK,
            Attributes.FOLLOW_RANGE,
            Attributes.FLYING_SPEED,
            Attributes.ATTACK_KNOCKBACK);

    /** 一条合计：哪个属性、哪种运算、合计多少。 */
    public record Entry(Attribute attribute, AttributeModifier.Operation operation, double amount) {
    }

    /** 聚合键 —— 同一属性可能同时带数值项与百分比项，必须分开累加。 */
    private record Key(Attribute attribute, AttributeModifier.Operation operation) {
    }

    /**
     * 算出一份合计清单（已排序、已滤零）。
     *
     * <p>遍历范围取 {@code min(容器格数, 四格上限)} —— 容器契约允许实现方报出更大的格数，
     * 而 {@link MobEquipmentContainer#slotFor} 只认四个盔甲槽。</p>
     */
    public static List<Entry> total(Container equipment) {
        Map<Key, Double> sums = new LinkedHashMap<>();
        int slots = Math.min(equipment.getContainerSize(), MobEquipmentContainer.SLOT_COUNT);
        for (int i = 0; i < slots; i++) {
            ItemStack stack = equipment.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            EquipmentSlot slot = MobEquipmentContainer.slotFor(i);
            Multimap<Attribute, AttributeModifier> modifiers = stack.getAttributeModifiers(slot);
            for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
                AttributeModifier modifier = entry.getValue();
                sums.merge(new Key(entry.getKey(), modifier.getOperation()),
                        modifier.getAmount(), Double::sum);
            }
        }

        List<Entry> entries = new ArrayList<>();
        sums.forEach((key, amount) -> {
            // 正负相抵为 0 的项不显示 —— 原版 tooltip 同样是逐条列原始值，但摘要是"合计"，
            // 列一条 `+0 护甲` 只会让人以为算错了。
            if (amount != 0.0D) {
                entries.add(new Entry(key.attribute(), key.operation(), amount));
            }
        });
        entries.sort(Comparator
                .comparingInt((Entry entry) -> priorityOf(entry.attribute()))
                .thenComparingInt(entry -> entry.operation().ordinal())
                .thenComparing(entry -> entry.attribute().getDescriptionId()));
        return entries;
    }

    /** 取一条合计显示用的文本样式（正蓝负红，与原版属性行一致）。 */
    public static ChatFormatting style(Entry entry) {
        return entry.amount() >= 0.0D ? ChatFormatting.BLUE : ChatFormatting.RED;
    }

    /** 按原版模板拼一行（如 {@code +4 护甲}）。 */
    public static MutableComponent format(Entry entry) {
        boolean positive = entry.amount() >= 0.0D;
        String key = "attribute.modifier." + (positive ? "plus" : "take")
                + "." + entry.operation().toValue();
        double shown = positive ? entry.amount() : -entry.amount();
        return Component.translatable(key,
                        ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(shown),
                        Component.translatable(entry.attribute().getDescriptionId()))
                .withStyle(style(entry));
    }

    /** 不在优先级表里的属性排最后，彼此之间按注册名定序（保证顺序稳定）。 */
    private static int priorityOf(Attribute attribute) {
        int index = PRIORITY.indexOf(attribute);
        return index >= 0 ? index : PRIORITY.size();
    }
}
