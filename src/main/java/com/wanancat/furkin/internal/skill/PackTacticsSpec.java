package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 「群猎战术」的规格 —— 从技能 JSON 的 passive {@code params.pack_tactics} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "tick",
 *     "pack_tactics": {
 *       "bonusPerStack": [0.10, 0.15, 0.20],  // 必填，每级「每层」的攻击加成（MULTIPLY_BASE）
 *       "maxStacks": 3,                        // 必填，最大叠层数
 *       "radius": 16.0                         // 必填，队友计数半径（格）
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>为什么加成写成按等级阵列而不是「每级 +0.05」</b>：10/15/20 不是等差
 * （第二级 +5%、第三级 +5% 但基数不同），写成阵列才能让数值完全由数据说了算 ——
 * 这也正是本次把数值搬出代码的原因。</p>
 *
 * @param bonusPerStack 每级对应的「每层」攻击加成；等级超过数组长度时取最后一项
 * @param maxStacks     最大叠层数
 * @param radius        队友计数半径（格）
 */
public record PackTacticsSpec(double[] bonusPerStack, int maxStacks, double radius) {

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "pack_tactics";

    /** 期望的触发位点 —— 群猎是周期侧被动。 */
    private static final String TRIGGER = "tick";

    /** 半径上限（格）—— 同 {@link ForagerSpec#MAX_RADIUS} 的防呆理由。 */
    private static final double MAX_RADIUS = 32.0;

    /** 防御性拷贝：record 的数组字段可变，不能让调用方通过引用改到已解析的规格。 */
    public PackTacticsSpec {
        bonusPerStack = bonusPerStack.clone();
    }

    /**
     * 该等级对应的「每层」加成。
     *
     * <p>等级超出阵列长度时取最后一项（而不是报错）：数据作者可以只写 3 项供 3 级技能用，
     * 将来技能扩到 4 级也不会突然失效。</p>
     *
     * @param level 技能等级（≥ 1）
     * @return 每层加成
     */
    public double bonusForLevel(int level) {
        int index = Math.min(Math.max(level, 1), bonusPerStack.length) - 1;
        return bonusPerStack[index];
    }

    /**
     * 取某技能的群猎规格。
     *
     * @return 该技能声明的群猎规格；不是该技能 / 配置非法时为空
     */
    public static Optional<PackTacticsSpec> of(ResourceLocation skillId) {
        return SkillParams.block(skillId, BLOCK_KEY, TRIGGER)
                .flatMap(block -> parse(skillId, block));
    }

    private static Optional<PackTacticsSpec> parse(ResourceLocation skillId, JsonObject block) {
        if (!block.has("bonusPerStack")) {
            FurkinMod.LOGGER.warn("Furkin pack_tactics: skill {} needs a 'bonusPerStack' array, skill disabled",
                    skillId);
            return Optional.empty();
        }
        JsonElement rawBonus = block.get("bonusPerStack");
        if (!rawBonus.isJsonArray() || rawBonus.getAsJsonArray().isEmpty()) {
            FurkinMod.LOGGER.warn("Furkin pack_tactics: skill {} 'bonusPerStack' must be a non-empty array, skill disabled",
                    skillId);
            return Optional.empty();
        }
        JsonArray array = rawBonus.getAsJsonArray();
        double[] bonuses = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            bonuses[i] = array.get(i).getAsDouble();
            if (!(bonuses[i] >= 0.0)) {
                FurkinMod.LOGGER.warn(
                        "Furkin pack_tactics: skill {} bonusPerStack[{}] = {} is negative, skill disabled",
                        skillId, i, bonuses[i]);
                return Optional.empty();
            }
        }
        if (!block.has("maxStacks")) {
            FurkinMod.LOGGER.warn("Furkin pack_tactics: skill {} needs a 'maxStacks' value, skill disabled", skillId);
            return Optional.empty();
        }
        int maxStacks = block.get("maxStacks").getAsInt();
        if (maxStacks < 1) {
            FurkinMod.LOGGER.warn("Furkin pack_tactics: skill {} maxStacks = {} must be at least 1, skill disabled",
                    skillId, maxStacks);
            return Optional.empty();
        }
        if (!block.has("radius")) {
            FurkinMod.LOGGER.warn("Furkin pack_tactics: skill {} needs a 'radius' value, skill disabled", skillId);
            return Optional.empty();
        }
        double radius = block.get("radius").getAsDouble();
        if (!(radius > 0.0) || radius > MAX_RADIUS) {
            FurkinMod.LOGGER.warn(
                    "Furkin pack_tactics: skill {} radius = {} is out of range (0, {}], skill disabled",
                    skillId, radius, MAX_RADIUS);
            return Optional.empty();
        }
        return Optional.of(new PackTacticsSpec(bonuses, maxStacks, radius));
    }
}
