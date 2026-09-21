package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被动技能 {@code params} 的取块入口 —— 带缓存。
 *
 * <p><b>它在链路里的位置</b>：JSON 的技能效果是一段 {@code params} 对象，
 * 各类被动各取其中一个<b>子对象</b>作为自己的配置块（产出取 {@code harvest}、
 * 拾荒取 {@code forager}、进食取 {@code feeder}，闪避 / 免死 / 夜视 / 群猎同理）。
 * 本类只负责一件事：<b>按技能 id + 块名把那个子对象取出来</b>，块的语义交给各自的规格类解析
 * （{@link com.wanancat.furkin.internal.skill.harvest.HarvestSpec} / {@link ForagerSpec} /
 * {@link FeederSpec} / {@link DodgeSpec} / {@link NineLivesSpec} /
 * {@link NightWatchSpec} / {@link PackTacticsSpec}）。</p>
 *
 * <p><b>为什么块要收在子对象里而不是与 {@code trigger} 平铺</b>：
 * {@code trigger} 是所有被动共用的「何时查」，块是「这个技能做什么、参数多少」。
 * 分开之后多条被动可以各带各的子对象，不抢 {@code interval} / {@code radius}
 * 这类通用键名；且「有某个块 ⇒ 是某类技能」成为一句可判的判据
 * （数据驱动路由的依据，见 {@code SkillPassiveDispatcher} 的路由两分）。</p>
 *
 * <p><b>trigger 是校验项，不是过滤项</b>：调用方须声明自己期望的 trigger
 * （周期侧 {@code tick}、攻击侧 {@code attack}、受害侧 {@code hurt}）。
 * 数据里 trigger 写错时，技能会在错误的位点被查询 —— 表现为「技能加了点、什么都不发生」。
 * 这类静默失效极难排查，故此处打 WARN 而不放行。</p>
 *
 * <p><b>缓存</b>：周期侧每只绒亲每 20 tick 都要问一遍「这技能是不是某某技能」，
 * 而解析要遍历 effects 与 params。缓存失效只能由 {@link #invalidateCache()}
 * 在<b>技能树重载</b>时触发 —— 那是 JSON 唯一会变的入口（同 {@code HarvestSpec} 的理由）。</p>
 *
 * <p><b>失败一律留痕</b>：数据写错时最坏的表现是「技能能加点、但什么都没发生」，
 * 这类静默失效极难排查，故每一处失败路径都打 WARN（含技能 id 与具体原因）。
 * 唯一静默的情形是「该技能根本没声明这个块」—— 那是常态（例如属性技能、
 * 或另一类被动），不是错误。</p>
 */
public final class SkillParams {

    /** passive 效果类型 id —— 与 {@code SkillEffects.registerBuiltin()} 注册的一致。 */
    private static final ResourceLocation PASSIVE_TYPE =
            new ResourceLocation(FurkinMod.MODID, "passive");

    private static final String TRIGGER_KEY = "trigger";
    /** 周期侧被动的 trigger 值。 */
    private static final String TRIGGER_TICK = "tick";

    /**
     * 缓存键 = 技能 id + '#' + 块名 + '@' + 期望 trigger。
     *
     * <p>三者都是查询的一部分：同一个技能的同一块名理论上只该有一个 trigger，
     * 但把它纳入键可以杜绝「两个调用方用不同期望查同一块时拿到彼此的结果」。</p>
     */
    private static final Map<String, Optional<JsonObject>> CACHE = new ConcurrentHashMap<>();

    private SkillParams() {
    }

    /**
     * 取某技能声明的 {@code tick} 类配置块 —— 周期侧（{@code onServerTick}）的取块入口。
     *
     * @param skillId  技能 id
     * @param blockKey 块名（{@code params} 下的键，如 {@code harvest} / {@code forager}）
     * @return 该块；技能不存在 / 不是 passive / 未声明该块 / {@code trigger} 不是 tick / 块不是对象 ⇒ 空
     */
    public static Optional<JsonObject> tickBlock(ResourceLocation skillId, String blockKey) {
        return block(skillId, blockKey, TRIGGER_TICK);
    }

    /**
     * 取某技能声明的配置块（带期望 trigger 校验）。
     *
     * @param skillId         技能 id
     * @param blockKey        块名（{@code params} 下的键）
     * @param expectedTrigger 调用方期望的触发位点（{@code tick} / {@code attack} / {@code hurt}）
     * @return 该块；技能不存在 / 不是 passive / 未声明该块 / {@code trigger} 与期望不符 / 块不是对象 ⇒ 空
     */
    public static Optional<JsonObject> block(ResourceLocation skillId, String blockKey, String expectedTrigger) {
        if (skillId == null || blockKey == null || expectedTrigger == null) {
            return Optional.empty();
        }
        String cacheKey = skillId + "#" + blockKey + "@" + expectedTrigger;
        return CACHE.computeIfAbsent(cacheKey, key -> resolve(skillId, blockKey, expectedTrigger));
    }

    /** 清空缓存（技能树重载时调用 —— 旧树解析出的块不能再被沿用）。 */
    public static void invalidateCache() {
        CACHE.clear();
    }

    private static Optional<JsonObject> resolve(ResourceLocation skillId, String blockKey, String expectedTrigger) {
        List<Skill.SkillEffectSpec> effects =
                SkillRegistry.tree().get(skillId).map(Skill::getEffects).orElse(List.of());
        for (Skill.SkillEffectSpec effect : effects) {
            if (!PASSIVE_TYPE.equals(effect.getType())) {
                continue;
            }
            JsonObject params = effect.getParams();
            if (params == null || !params.has(blockKey)) {
                // 没声明这个块 = 不是这类技能，常态，静默。
                continue;
            }
            String trigger = params.has(TRIGGER_KEY) ? params.get(TRIGGER_KEY).getAsString() : "";
            if (!expectedTrigger.equals(trigger)) {
                FurkinMod.LOGGER.warn("Furkin skill: {} declares '{}' but trigger is '{}' (expected '{}'), ignored",
                        skillId, blockKey, trigger, expectedTrigger);
                return Optional.empty();
            }
            JsonElement block = params.get(blockKey);
            if (!block.isJsonObject()) {
                FurkinMod.LOGGER.warn("Furkin skill: {}'s '{}' must be an object, ignored",
                        skillId, blockKey);
                return Optional.empty();
            }
            return Optional.of(block.getAsJsonObject());
        }
        return Optional.empty();
    }
}
