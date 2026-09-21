package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 「九命猫」的规格 —— 从技能 JSON 的 passive {@code params.nine_lives} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "hurt",
 *     "nine_lives": { "cooldownTicks": 12000 }   // 必填，免死冷却（tick）。12000 = 10 分钟
 *   }
 * }
 * }</pre>
 *
 * <p>时间单位与产出类（{@code harvest.interval}）、进食类（{@code feeder.cooldown}）
 * 一致用 <b>tick</b>，免得同一份数据里混着两种单位。</p>
 *
 * @param cooldownTicks 触发免死之后的冷却（tick）；调用方把它写进
 *                      {@code FurkinData#getCooldowns()} 的 {@code furkin:nine_lives} 键
 */
public record NineLivesSpec(int cooldownTicks) {

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "nine_lives";

    /** 期望的触发位点 —— 免死要改写伤害量，挂在受害侧晚段（{@code LivingHurtEvent}）。 */
    private static final String TRIGGER = "hurt";

    /**
     * 取某技能的免死规格。
     *
     * @return 该技能声明的免死规格；不是该技能 / 配置非法时为空
     */
    public static Optional<NineLivesSpec> of(ResourceLocation skillId) {
        return SkillParams.block(skillId, BLOCK_KEY, TRIGGER)
                .flatMap(block -> parse(skillId, block));
    }

    private static Optional<NineLivesSpec> parse(ResourceLocation skillId, JsonObject block) {
        if (!block.has("cooldownTicks")) {
            FurkinMod.LOGGER.warn("Furkin nine_lives: skill {} needs a 'cooldownTicks' value, skill disabled", skillId);
            return Optional.empty();
        }
        int cooldown = block.get("cooldownTicks").getAsInt();
        // 允许 0（= 无冷却），与 feeder.cooldown 的口径一致。
        if (cooldown < 0) {
            FurkinMod.LOGGER.warn("Furkin nine_lives: skill {} cooldownTicks = {} is negative, skill disabled",
                    skillId, cooldown);
            return Optional.empty();
        }
        return Optional.of(new NineLivesSpec(cooldown));
    }
}
