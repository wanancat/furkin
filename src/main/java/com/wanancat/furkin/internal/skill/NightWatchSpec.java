package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 「守夜者」的规格 —— 从技能 JSON 的 passive {@code params.night_watch} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "tick",
 *     "night_watch": {
 *       "radius": 16.0,        // 必填，生效半径（格），以绒亲为中心
 *       "durationTicks": 300   // 必填，每次刷新的夜视时长（tick）。300 = 15s
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>夜视刻意用<b>短时长 + 每 20 tick 刷新</b>，而不是长时长：绒亲离开半径、
 * 天亮、主人下线时都只需「停止刷新」即可自然失效，不必去逐条清除效果。</p>
 *
 * @param radius        生效半径（格）
 * @param durationTicks 每次施加的夜视时长（tick）
 */
public record NightWatchSpec(double radius, int durationTicks) {

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "night_watch";

    /** 期望的触发位点 —— 夜视是周期侧被动。 */
    private static final String TRIGGER = "tick";

    /**
     * 半径上限（格）。
     *
     * <p>与 {@link ForagerSpec#MAX_RADIUS} 同理的防呆：半径决定每 20 tick 一次的
     * 距离判定与实体可见范围，多写一位（16 → 160）会让语义完全走样。
     * 超过即视为写错、整个技能禁用，而不是悄悄截断。</p>
     */
    private static final double MAX_RADIUS = 32.0;

    /**
     * 取某技能的守夜规格。
     *
     * @return 该技能声明的守夜规格；不是该技能 / 配置非法时为空
     */
    public static Optional<NightWatchSpec> of(ResourceLocation skillId) {
        return SkillParams.block(skillId, BLOCK_KEY, TRIGGER)
                .flatMap(block -> parse(skillId, block));
    }

    private static Optional<NightWatchSpec> parse(ResourceLocation skillId, JsonObject block) {
        if (!block.has("radius")) {
            FurkinMod.LOGGER.warn("Furkin night_watch: skill {} needs a 'radius' value, skill disabled", skillId);
            return Optional.empty();
        }
        double radius = block.get("radius").getAsDouble();
        if (!(radius > 0.0) || radius > MAX_RADIUS) {
            FurkinMod.LOGGER.warn(
                    "Furkin night_watch: skill {} radius = {} is out of range (0, {}], skill disabled",
                    skillId, radius, MAX_RADIUS);
            return Optional.empty();
        }
        if (!block.has("durationTicks")) {
            FurkinMod.LOGGER.warn("Furkin night_watch: skill {} needs a 'durationTicks' value, skill disabled",
                    skillId);
            return Optional.empty();
        }
        int duration = block.get("durationTicks").getAsInt();
        if (duration <= 0) {
            FurkinMod.LOGGER.warn("Furkin night_watch: skill {} durationTicks = {} must be positive, skill disabled",
                    skillId, duration);
            return Optional.empty();
        }
        return Optional.of(new NightWatchSpec(radius, duration));
    }
}
