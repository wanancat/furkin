package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 「低血进食」的规格 —— 从技能 JSON 的 passive {@code params.feeder} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "tick",
 *     "feeder": {
 *       "threshold": 0.3,   // 必填，血量低于此比例（0~1）时开吃
 *       "cooldown": 200     // 必填，两次进食之间的最短间隔（tick）。200 = 10s
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>时间单位与产出类保持一致用 <b>tick</b>（{@code harvest.interval} 同理），
 * 免得同一份数据里混着两种单位。</p>
 *
 * @param threshold     开吃门槛（血量占上限的比例）
 * @param cooldownTicks 两次进食之间的最短间隔（tick）
 */
public record FeederSpec(double threshold, int cooldownTicks) {

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "feeder";

    /**
     * 取某技能的低血进食规格。
     *
     * @return 该技能声明的进食规格；不是该技能 / 配置非法时为空
     */
    public static Optional<FeederSpec> of(ResourceLocation skillId) {
        return SkillParams.tickBlock(skillId, BLOCK_KEY)
                .flatMap(block -> parse(skillId, block));
    }

    private static Optional<FeederSpec> parse(ResourceLocation skillId, JsonObject block) {
        if (!block.has("threshold")) {
            FurkinMod.LOGGER.warn("Furkin feeder: skill {} needs a 'threshold' value, feeder disabled", skillId);
            return Optional.empty();
        }
        double threshold = block.get("threshold").getAsDouble();
        // 上限取 1.0（< 100% 血才吃）：等于 1.0 会让绒亲一满血就吃，语义成了「白吃」。
        if (!(threshold > 0.0) || threshold >= 1.0) {
            FurkinMod.LOGGER.warn("Furkin feeder: skill {} threshold = {} is out of range (0, 1), feeder disabled",
                    skillId, threshold);
            return Optional.empty();
        }
        if (!block.has("cooldown")) {
            FurkinMod.LOGGER.warn("Furkin feeder: skill {} needs a 'cooldown' value, feeder disabled", skillId);
            return Optional.empty();
        }
        int cooldown = block.get("cooldown").getAsInt();
        if (cooldown < 0) {
            FurkinMod.LOGGER.warn("Furkin feeder: skill {} cooldown = {} is negative, feeder disabled",
                    skillId, cooldown);
            return Optional.empty();
        }
        return Optional.of(new FeederSpec(threshold, cooldown));
    }
}
