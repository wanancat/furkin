package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * 「流血撕咬」的规格 —— 从技能 JSON 的 passive {@code params.bleeding_bite} 解析出的配置。
 *
 * <p><b>JSON 形制</b>：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "attack",
 *     "bleeding_bite": {
 *       "durationTicks": 80,              // 必填，每次施加的流血时长（tick）。80 = 4s
 *       "damagePerSecond": [1.0, 2.0, 3.0] // 必填，每级每秒伤害，下标 0 = Lv.1
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>本类为什么和别的规格类不一样</b>：其余规格只被 {@code SkillPassiveDispatcher}
 * 读取（施加侧一处），本类<b>被两个地方读</b> —— 施加侧读 {@code durationTicks}，
 * 结算侧 {@link com.wanancat.furkin.internal.effect.BleedingEffect} 读
 * {@code damagePerSecond}。原因是流血伤害写在 {@code MobEffect#applyEffectTick} 里，
 * 而那个方法的入参只有 {@code (LivingEntity, int amplifier)}，<b>拿不到技能 JSON</b>。</p>
 *
 * <p><b>为什么不需要把值编码进 amplifier / duration 强行穿过 addEffect</b>：
 * 流血的数值是<b>全局唯一一份</b>（只有本技能会挂 {@code furkin:bleeding}），
 * 不像 {@code MobEffectInstance} 那样需要「每个实例各带一份客制参数」。
 * 因此 effect 侧直接反查同一份规格即可 —— 走的是与 harvest / feeder 完全相同的
 * {@link SkillParams} 取块 + 缓存通道，{@code /reload} 后自然失效重读。</p>
 *
 * <p><b>热重载语义是实时的</b>：{@link com.wanancat.furkin.internal.effect.BleedingEffect} 每个结算 tick
 * 都重新查询本规格，因此 {@code /reload} 后已有流血立即使用新 DPS，但不会改写已经施加的
 * {@code MobEffectInstance} 持续时间；技能定义被删除或参数失效时查询为空，已有流血不再造成伤害并自然到期。</p>
 *
 * <p><b>等级索引口径</b>：{@code damageForLevel} 收的是「技能等级」（1-based），
 * 而 effect 侧只有 {@code amplifier}（= 等级 − 1），故调用处传
 * {@code amplifier + 1}。等级超出阵列长度时取最后一项（同
 * {@link HarvestSpec#intervalForLevel} / {@link PackTacticsSpec#bonusForLevel}）。</p>
 *
 * @param durationTicks    每次施加的流血时长（tick）
 * @param damagePerSecond  每级对应的每秒伤害，下标 0 = Lv.1
 */
public record BleedingSpec(int durationTicks, double[] damagePerSecond) {

    /**
     * 流血撕咬的技能 id —— <b>施加侧与结算侧共用的唯一入口</b>。
     *
     * <p>放在本类而不是 {@code SkillPassiveDispatcher} 里：流血是全项目唯一一个
     * 「effect 侧也要知道是哪个技能」的被动，把 id 定义在它的规格类里，
     * 「谁定义了这份数值」与「谁在用它」就在一处，不必让 effect 层反过来依赖 dispatcher。</p>
     */
    public static final ResourceLocation SKILL_ID =
            new ResourceLocation(FurkinMod.MODID, "bleeding_bite");

    /** 配置块名（{@code params} 下的键）。 */
    private static final String BLOCK_KEY = "bleeding_bite";

    /** 期望的触发位点 —— 流血挂在攻击侧（{@code LivingHurtEvent}）。 */
    private static final String TRIGGER = "attack";

    /**
     * 单次流血时长上限（tick）。60 秒。
     *
     * <p>与 {@link NightWatchSpec#MAX_RADIUS} 同理的防呆：80 写成 8000 会让「咬一口
     * 流一小时血」，语义完全走样且不会报错。超过即视为写错、整个技能禁用。</p>
     */
    private static final int MAX_DURATION_TICKS = 1200;

    /**
     * 每秒伤害上限（点）。10 颗心。
     *
     * <p>同类防呆：这个数量级已经高于任何原版生物的满血，多写一位（3.0 → 30.0）
     * 就是「咬一口必死」。</p>
     */
    private static final double MAX_DAMAGE_PER_SECOND = 20.0;

    /** 防御性拷贝：record 的数组字段是引用，不拷贝就能被调用方改掉已解析的规格。 */
    public BleedingSpec {
        damagePerSecond = damagePerSecond.clone();
    }

    /**
     * 该等级对应的每秒伤害。
     *
     * <p>等级超出阵列长度时取最后一项（而非报错）：作者可以只写 3 项供 3 级技能用，
     * 将来技能扩到 4 级也不会突然失效。</p>
     *
     * @param level 技能等级（≥ 1）
     * @return 每秒伤害（点）
     */
    public float damageForLevel(int level) {
        int index = Mth.clamp(level, 1, damagePerSecond.length) - 1;
        return (float) damagePerSecond[index];
    }

    /**
     * 取流血撕咬的规格 —— 施加侧与结算侧的统一入口。
     *
     * @return 该规格；技能不存在 / 未声明该块 / {@code trigger} 不是 attack / 字段非法 ⇒ 空
     */
    public static Optional<BleedingSpec> of() {
        return SkillParams.block(SKILL_ID, BLOCK_KEY, TRIGGER).flatMap(BleedingSpec::parse);
    }

    private static Optional<BleedingSpec> parse(JsonObject block) {
        if (!block.has("durationTicks")) {
            FurkinMod.LOGGER.warn("Furkin bleeding_bite: skill {} needs a 'durationTicks' value, skill disabled",
                    SKILL_ID);
            return Optional.empty();
        }
        int duration = block.get("durationTicks").getAsInt();
        if (duration <= 0 || duration > MAX_DURATION_TICKS) {
            FurkinMod.LOGGER.warn(
                    "Furkin bleeding_bite: skill {} durationTicks = {} is out of range [1, {}], skill disabled",
                    SKILL_ID, duration, MAX_DURATION_TICKS);
            return Optional.empty();
        }
        if (!block.has("damagePerSecond")) {
            FurkinMod.LOGGER.warn("Furkin bleeding_bite: skill {} needs a 'damagePerSecond' array, skill disabled",
                    SKILL_ID);
            return Optional.empty();
        }
        JsonElement rawDamage = block.get("damagePerSecond");
        if (!rawDamage.isJsonArray() || rawDamage.getAsJsonArray().isEmpty()) {
            FurkinMod.LOGGER.warn(
                    "Furkin bleeding_bite: skill {} 'damagePerSecond' must be a non-empty array, skill disabled",
                    SKILL_ID);
            return Optional.empty();
        }
        JsonArray array = rawDamage.getAsJsonArray();
        double[] damage = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            damage[i] = array.get(i).getAsDouble();
            if (!(damage[i] > 0.0) || damage[i] > MAX_DAMAGE_PER_SECOND) {
                FurkinMod.LOGGER.warn(
                        "Furkin bleeding_bite: skill {} damagePerSecond[{}] = {} is out of range (0, {}],"
                                + " skill disabled",
                        SKILL_ID, i, damage[i], MAX_DAMAGE_PER_SECOND);
                return Optional.empty();
            }
        }
        return Optional.of(new BleedingSpec(duration, damage));
    }
}
