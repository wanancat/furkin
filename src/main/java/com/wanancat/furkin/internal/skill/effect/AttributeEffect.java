package com.wanancat.furkin.internal.skill.effect;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.skill.SkillEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
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
 */
public final class AttributeEffect implements SkillEffect {

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
                "furkin.skill.attribute",
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
