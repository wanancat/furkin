package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 「灵巧身法」的规格 —— 从技能 JSON 的 passive {@code params.dodge} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "hurt",
 *     "dodge": { "chancePerLevel": 0.08 }   // 必填，每级闪避概率（0~1）
 *   }
 * }
 * }</pre>
 *
 * <p>取块走 {@link SkillParams#block}（trigger 必须是 {@code hurt} —— 闪避挂在
 * {@code LivingAttackEvent}，那是 {@code hurt()} 的入口处）。</p>
 *
 * @param chancePerLevel 每级闪避概率；实际概率 = {@code chancePerLevel × 等级}，再按 1.0 截顶
 */
public record DodgeSpec(double chancePerLevel) {

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "dodge";

    /** 期望的触发位点 —— 闪避是受害侧被动。 */
    private static final String TRIGGER = "hurt";

    /**
     * 每级概率上限。
     *
     * <p>防呆：Lv.3 的 0.08 离 1.0 很远，但没有上限的数据可以让一次笔误
     * （0.8 写成 8）变成 100% 无敌。超过即视为写错、整个技能禁用。</p>
     */
    private static final double MAX_CHANCE_PER_LEVEL = 1.0;

    /**
     * 取某技能的闪避规格。
     *
     * @return 该技能声明的闪避规格；不是该技能 / 配置非法时为空
     */
    public static Optional<DodgeSpec> of(ResourceLocation skillId) {
        return SkillParams.block(skillId, BLOCK_KEY, TRIGGER)
                .flatMap(block -> parse(skillId, block));
    }

    private static Optional<DodgeSpec> parse(ResourceLocation skillId, JsonObject block) {
        if (!block.has("chancePerLevel")) {
            FurkinMod.LOGGER.warn("Furkin dodge: skill {} needs a 'chancePerLevel' value, dodge disabled", skillId);
            return Optional.empty();
        }
        double chance = block.get("chancePerLevel").getAsDouble();
        if (!(chance > 0.0) || chance > MAX_CHANCE_PER_LEVEL) {
            FurkinMod.LOGGER.warn(
                    "Furkin dodge: skill {} chancePerLevel = {} is out of range (0, {}], dodge disabled",
                    skillId, chance, MAX_CHANCE_PER_LEVEL);
            return Optional.empty();
        }
        return Optional.of(new DodgeSpec(chance));
    }
}
