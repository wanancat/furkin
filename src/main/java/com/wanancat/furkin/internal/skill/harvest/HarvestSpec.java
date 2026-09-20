package com.wanancat.furkin.internal.skill.harvest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.skill.Skill;
import com.wanancat.furkin.internal.skill.SkillRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「凭空产出」的规格 —— 从技能 JSON 的 passive params 里解析出的<b>通用配置</b>。
 *
 * <p>本类是「定时往行囊里塞东西」这套机制的契约层：<b>机制在代码里，数值在数据里</b>。
 * 因此新增一条产出技能（藏骨 / 捕鱼，或第三方自己的）<b>不需要改任何 Java</b> ——
 * 写一份 JSON 声明间隔、物品池与数量即可，两条技能只是同一段代码的两份配置。</p>
 *
 * <p><b>JSON 形制</b>（{@code data/furkin/skills/<id>.json} 的 effects 里）：</p>
 * <pre>{@code
 * {
 *   "type": "furkin:passive",
 *   "params": {
 *     "trigger": "tick",
 *     "harvest": {
 *       "interval": [1200, 800, 600],                  // 必填，每级一项（tick）。1200 = 60s
 *       "items": ["minecraft:cod", "minecraft:salmon"], // 必填，候选池，每次随机取一
 *       "count": 1                                     // 选填，默认 1
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>为什么产出配置收在 {@code harvest} 子对象里，而不是与 {@code trigger} 平铺</b>：
 * {@code trigger} 是所有被动共用的「何时查」；{@code harvest} 是「产出什么、多久一次」。
 * 分开之后，将来 {@code tick} 侧再长出别的被动（低血进食、拾荒）可以各带各的子对象，
 * 不会互相争抢 {@code interval} / {@code items} 这类通用键名；「有 harvest 块 ⇒ 是产出技能」
 * 也成了一句可判的判据。</p>
 *
 * <p><b>解析失败一律留痕</b>：数据写错时最坏的表现是「技能能加点、但什么都没发生」——
 * 这类静默失效排查起来极费时间，故每一处失败路径都打 WARN（含技能 id 与具体原因）。</p>
 *
 * @param intervalTicks 每级对应的产出间隔（tick），下标 0 = Lv.1
 * @param pool          候选产出物（非空；每次从中随机取一个）
 * @param count         每次产出数量（≥ 1）
 */
public record HarvestSpec(int[] intervalTicks, List<Item> pool, int count) {

    /** passive 效果类型 id —— 与 {@code SkillEffects.registerBuiltin()} 注册的一致。 */
    private static final ResourceLocation PASSIVE_TYPE =
            new ResourceLocation(FurkinMod.MODID, "passive");

    /** 只认这个触发时机：产出是周期行为。 */
    private static final String TRIGGER_TICK = "tick";

    /** 产出配置块在 params 里的键名。 */
    private static final String HARVEST_KEY = "harvest";

    /**
     * 解析结果缓存（技能 id → 规格）。
     *
     * <p>周期侧每只绒亲每 20 tick 都要问一次「这技能是不是产出技能」，而解析要遍历
     * skill 的 effects 并查物品注册表，逐次重算毫无必要。缓存失效由
     * {@link #invalidateCache()} 在<b>技能树重载</b>时触发 —— 那是 JSON 唯一会变的入口。</p>
     *
     * <p>用 {@link Optional} 装箱而不是只缓存命中的项：让「这个 id 不是产出技能」这个
     * 否定结论同样只算一次。</p>
     */
    private static final Map<ResourceLocation, Optional<HarvestSpec>> CACHE = new ConcurrentHashMap<>();

    /** 数组字段不可变 —— record 的数组组件是引用，不拷贝就能被外部改掉。 */
    public HarvestSpec {
        intervalTicks = intervalTicks.clone();
        pool = List.copyOf(pool);
    }

    /**
     * 取某技能在本等级下的产出间隔（tick）。
     *
     * <p>等级超出配置项数时<b>取最后一项</b>（而非回落到第一项或越界）—— 让作者只写前几级
     * 也有确定行为：{@code maxLevel 3 + interval[1200]} 等价于「每级都是 1200」。</p>
     *
     * @param level 技能当前等级（≥ 1；调用方保证）
     */
    public int intervalForLevel(int level) {
        int index = Mth.clamp(level, 1, intervalTicks.length) - 1;
        return intervalTicks[index];
    }

    /** 从候选池里随机取一个产出物（数量按 {@link #count()}）。 */
    public ItemStack roll(RandomSource random) {
        Item item = pool.get(random.nextInt(pool.size()));
        return new ItemStack(item, count);
    }

    /**
     * 取某技能的产出规格（带缓存）。
     *
     * @return 该技能声明的产出规格；不是产出技能 / 配置非法时为空
     */
    public static Optional<HarvestSpec> of(ResourceLocation skillId) {
        if (skillId == null) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(skillId,
                id -> SkillRegistry.tree().get(id).flatMap(HarvestSpec::parse));
    }

    /** 清空解析缓存（技能树重载时调用 —— 旧树解析出的规格不能再被沿用）。 */
    public static void invalidateCache() {
        CACHE.clear();
    }

    // ===== 解析 =====

    /** 从技能定义里找产出声明。找不到（非产出技能）返回空，<b>不打日志</b> —— 那是常态。 */
    private static Optional<HarvestSpec> parse(Skill skill) {
        for (Skill.SkillEffectSpec effect : skill.getEffects()) {
            if (!PASSIVE_TYPE.equals(effect.getType())) {
                continue;
            }
            JsonObject params = effect.getParams();
            if (params == null || !params.has(HARVEST_KEY)) {
                continue;
            }
            String trigger = params.has("trigger") ? params.get("trigger").getAsString() : "";
            if (!TRIGGER_TICK.equals(trigger)) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} declares '{}' but trigger is '{}'"
                                + " (expected '{}'), ignored",
                        skill.getId(), HARVEST_KEY, trigger, TRIGGER_TICK);
                return Optional.empty();
            }
            return parseHarvest(skill, params.get(HARVEST_KEY));
        }
        return Optional.empty();
    }

    private static Optional<HarvestSpec> parseHarvest(Skill skill, JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {}'s '{}' must be an object",
                    skill.getId(), HARVEST_KEY);
            return Optional.empty();
        }
        JsonObject harvest = raw.getAsJsonObject();
        int[] intervals = parseIntervals(skill, harvest);
        List<Item> pool = parsePool(skill, harvest);
        int count = harvest.has("count") ? harvest.get("count").getAsInt() : 1;
        if (intervals == null || pool.isEmpty() || count < 1) {
            if (pool.isEmpty()) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} has no usable item in 'items',"
                        + " harvest disabled", skill.getId());
            }
            if (count < 1) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} has count = {} (< 1), harvest disabled",
                        skill.getId(), count);
            }
            return Optional.empty();
        }
        return Optional.of(new HarvestSpec(intervals, pool, count));
    }

    /** 解析 {@code interval} 数组：必须存在、非空、每项为正整数。 */
    private static int[] parseIntervals(Skill skill, JsonObject harvest) {
        JsonElement raw = harvest.get("interval");
        if (raw == null || !raw.isJsonArray()) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {} needs an 'interval' array of ticks",
                    skill.getId());
            return null;
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() == 0) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {}'s 'interval' array is empty",
                    skill.getId());
            return null;
        }
        int[] values = new int[array.size()];
        for (int i = 0; i < array.size(); i++) {
            int ticks = array.get(i).getAsInt();
            if (ticks <= 0) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} interval[{}] = {} is not positive",
                        skill.getId(), i, ticks);
                return null;
            }
            values[i] = ticks;
        }
        return values;
    }

    /**
     * 解析 {@code items} 数组。
     *
     * <p>未知物品 id 只<b>跳过该候选</b>、不整条作废：池里还有别的候选时机制仍能工作，
     * 池空了才由调用方判定为「产出禁用」。这样「池里 3 选 1 其中 1 个写错」不至于
     * 把整个技能打死。</p>
     */
    private static List<Item> parsePool(Skill skill, JsonObject harvest) {
        JsonElement raw = harvest.get("items");
        if (raw == null || !raw.isJsonArray()) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {} needs an 'items' array of item ids",
                    skill.getId());
            return List.of();
        }
        List<Item> pool = new ArrayList<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            String text = element.getAsString();
            ResourceLocation itemId = ResourceLocation.tryParse(text);
            Item item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} has unknown item id '{}', skipped",
                        skill.getId(), text);
                continue;
            }
            pool.add(item);
        }
        return pool;
    }
}
