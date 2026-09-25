package com.wanancat.furkin.internal.skill.effect;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.skill.Skill;
import com.wanancat.furkin.internal.skill.SkillEffect;
import com.wanancat.furkin.internal.skill.SkillEffects;
import com.wanancat.furkin.internal.skill.SkillTree;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code attribute} 效果 —— 按技能等级给宠物挂属性修正（设计稿 §3.2 效果类型①）。
 *
 * <p>JSON 参数：</p>
 * <pre>
 * { "attribute": "minecraft:generic.attack_damage", "amount": 0.5, "operation": "addition" }
 * </pre>
 *
 * <ul>
 *   <li>{@code attribute} —— 属性注册名（如 {@code minecraft:generic.max_health}）。</li>
 *   <li>{@code amount} —— 每级加成量（数值 = amount × 等级）。</li>
 *   <li>{@code operation} —— {@code addition} / {@code multiply_base} / {@code multiply_total}，默认 addition。</li>
 * </ul>
 *
 * <p><b>固定 UUID 策略</b>：按「技能 id + 属性名」派生 name-based UUID，保证同一技能
 * 同一属性永远同一个 UUID —— 升级时先 {@code removeModifier} 再 {@code addPermanentModifier}
 * 幂等重挂，洗点/降级时按 UUID 精确移除；不同技能加同一属性是独立 modifier，正确叠加。</p>
 *
 * <p><b>固定名称策略</b>：所有技能属性 modifier 都使用 {@link #MODIFIER_NAME}。技能定义删除、
 * 属性目标切换或运算方式修改后，无法只凭新树定位旧 modifier；重载与实体入世重建因此先按这个名称
 * 统一清理，再按当前技能树重挂。UUID 仍按「技能 id + 属性名」派生，保留不同技能的独立叠加语义。</p>
 */
public final class AttributeEffect implements SkillEffect {

    /** 所有技能属性 modifier 的固定名称；重载清理时按此名称识别本模组拥有的 modifier。 */
    public static final String MODIFIER_NAME = "furkin.skill.attribute";

    @Override
    public void apply(LivingEntity target, ResourceLocation skillId, int level, JsonObject params) {
        Attribute attribute = resolveAttribute(params);
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) {
            return;
        }

        double amount = params.has("amount") ? params.get("amount").getAsDouble() : 0.0;
        AttributeModifier.Operation op = parseOperation(params);
        UUID modifierId = modifierUuid(skillId, attribute);

        instance.removeModifier(modifierId);
        instance.addPermanentModifier(new AttributeModifier(
                modifierId,
                MODIFIER_NAME,
                amount * level,
                op
        ));
    }

    @Override
    public void remove(LivingEntity target, ResourceLocation skillId, JsonObject params) {
        Attribute attribute = resolveAttribute(params);
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(modifierUuid(skillId, attribute));
    }

    /**
     * 清理目标身上所有由技能属性效果添加的 modifier。
     *
     * <p>按固定名称而不是按当前技能树清理，因此数据包删除技能、切换属性目标或修改运算方式后，
     * 旧 modifier 也能在下一次重建时被移除；同一属性上由不同技能添加的 modifier 仍各自独立。</p>
     */
    public static void clearAll(LivingEntity target) {
        // 不能只用 getSyncableAttributes()：攻击伤害等属性本身不参与客户端同步，但同样可能挂着技能 modifier。
        // 遍历注册表并用 hasAttribute 过滤，覆盖实体支持的全部已注册属性；不支持者不会被取实例。
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES.getValues()) {
            if (!target.getAttributes().hasAttribute(attribute)) {
                continue;
            }
            AttributeInstance instance = target.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : List.copyOf(instance.getModifiers())) {
                if (MODIFIER_NAME.equals(modifier.getName())) {
                    instance.removeModifier(modifier.getId());
                }
            }
        }
    }

    /**
     * 求「按属性合计的技能加成」—— 遍历技能树，把已投等级 &gt; 0 的技能的
     * {@code furkin:attribute} 效果按 {@code amount × 等级} 逐属性求和。
     *
     * <p><b>为什么这段必须在服务端算</b>（设计稿 §4.1 取证⑦⑧，两条独立证据同向）：
     * ① 原版属性同步包发 modifier 只发 {@code UUID + amount + operation}、<b>不发名字</b>，
     * 客户端认不出哪一条是技能加的；② 技能 JSON 走 {@code AddReloadListenerEvent}（仅服务端），
     * 客户端手里根本没有技能数值。故面板的「技能加成」列只能由服务端算好下发。</p>
     *
     * <p>返回的键是<b>属性注册名</b>（如 {@code minecraft:generic.attack_damage}），
     * 无加成的属性不出现；同一属性被多个技能加时合并求和。</p>
     *
     * <p>非 {@code addition} 的运算<b>不并入合计</b>：它会破坏「总值 = 技能 + 装备 + 其他」
     * 这条可加性算术。出现时打一条 WARN 留痕并跳过 —— 当前所有技能都是 addition。</p>
     */
    public static Map<ResourceLocation, Double> totalAdditionBonuses(
            SkillTree tree, Map<ResourceLocation, Integer> skillLevels) {
        Map<ResourceLocation, Double> sums = new LinkedHashMap<>();
        for (Skill skill : tree.ordered()) {
            int level = skillLevels.getOrDefault(skill.getId(), 0);
            if (level <= 0) {
                continue;
            }
            for (Skill.SkillEffectSpec spec : skill.getEffects()) {
                // 按「已注册实现是不是本类」筛选效果类型，不另抄一份 "furkin:attribute" 字面量
                // （两处字符串一旦漂移，这里会静默漏算）。
                if (!(SkillEffects.byType(spec.getType()) instanceof AttributeEffect)) {
                    continue;
                }
                JsonObject params = spec.getParams();
                Attribute attribute = resolveAttribute(params);
                if (attribute == null) {
                    continue;
                }
                AttributeModifier.Operation op = parseOperation(params);
                if (op != AttributeModifier.Operation.ADDITION) {
                    FurkinMod.LOGGER.warn(
                            "[skill attrs] non-addition attribute effect not counted in skill bonus: skill={} attr={} op={}",
                            skill.getId(), attribute.getDescriptionId(), op);
                    continue;
                }
                double amount = params.has("amount") ? params.get("amount").getAsDouble() : 0.0D;
                sums.merge(ForgeRegistries.ATTRIBUTES.getKey(attribute), amount * level, Double::sum);
            }
        }
        return sums;
    }

    private static Attribute resolveAttribute(JsonObject params) {
        if (!params.has("attribute")) {
            return null;
        }
        String attrName = params.get("attribute").getAsString();
        return ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(attrName));
    }

    private static AttributeModifier.Operation parseOperation(JsonObject params) {
        if (!params.has("operation")) {
            return AttributeModifier.Operation.ADDITION;
        }
        return switch (params.get("operation").getAsString()) {
            case "multiply_base" -> AttributeModifier.Operation.MULTIPLY_BASE;
            case "multiply_total" -> AttributeModifier.Operation.MULTIPLY_TOTAL;
            default -> AttributeModifier.Operation.ADDITION;
        };
    }

    /** 从「技能 id + 属性名」派生确定性 UUID。 */
    private static UUID modifierUuid(ResourceLocation skillId, Attribute attribute) {
        String attrKey = ForgeRegistries.ATTRIBUTES.getKey(attribute).toString();
        return UUID.nameUUIDFromBytes(("furkin.attribute:" + skillId + ":" + attrKey)
                .getBytes(StandardCharsets.UTF_8));
    }
}
