package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 「拾荒」的规格 —— 从技能 JSON 的 passive {@code params.forager} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "tick",
 *     "forager": { "radius": 8 }   // 必填，拾取半径（格）
 *   }
 * }
 * }</pre>
 *
 * <p>与 {@link com.wanancat.furkin.internal.skill.harvest.HarvestSpec} 的分工：
 * 产出类有两条同构技能、且要查物品注册表，故自带一层解析缓存；本类只读一个数字，
 * 直接复用 {@link SkillParams} 的缓存即可，<b>不另设缓存</b>（缓存层数越少，失效面越小）。</p>
 *
 * @param radius 拾取半径（格）；以绒亲碰撞箱为中心按三轴外扩，故实际是一个
 *               {@code 2·radius} 边长的立方体范围
 */
public record ForagerSpec(double radius) {

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "forager";

    /**
     * 半径上限（格）。
     *
     * <p>纯粹是防呆：半径直接决定每 20 tick 一次的实体查询范围，
     * 配置多写一位（8 → 80）会让查询框大成百倍。超过即视为写错、整个技能禁用，
     * 而不是悄悄截断 —— 后者会让「我以为设了 80」和「实际只有 32」难以区分。</p>
     */
    private static final double MAX_RADIUS = 32.0;

    /**
     * 取某技能的拾荒规格。
     *
     * @return 该技能声明的拾荒规格；不是拾荒技能 / 配置非法时为空
     */
    public static Optional<ForagerSpec> of(ResourceLocation skillId) {
        return SkillParams.tickBlock(skillId, BLOCK_KEY)
                .flatMap(block -> parse(skillId, block));
    }

    private static Optional<ForagerSpec> parse(ResourceLocation skillId, JsonObject block) {
        if (!block.has("radius")) {
            FurkinMod.LOGGER.warn("Furkin forager: skill {} needs a 'radius' value, forager disabled", skillId);
            return Optional.empty();
        }
        double radius = block.get("radius").getAsDouble();
        if (!(radius > 0.0) || radius > MAX_RADIUS) {
            FurkinMod.LOGGER.warn("Furkin forager: skill {} radius = {} is out of range (0, {}], forager disabled",
                    skillId, radius, MAX_RADIUS);
            return Optional.empty();
        }
        return Optional.of(new ForagerSpec(radius));
    }
}
