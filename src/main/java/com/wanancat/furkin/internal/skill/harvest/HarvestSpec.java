package com.wanancat.furkin.internal.skill.harvest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.skill.SkillParams;
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
 * 「凭空产出」的规格 —— 从技能 JSON 的 passive {@code params.harvest} 解析出的<b>通用配置</b>。
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
 * <p>「块为什么收在子对象里」这件事由 {@link SkillParams} 统一说明 —— 取块、校验
 * {@code trigger}、缓存三件事都在那里，本类只负责解析块内部。</p>
 *
 * <p><b>本类为什么另有缓存</b>：块内容之外的解析（查物品注册表把 id 变成 {@link Item}）
 * 比读几个数字贵，而周期侧会反复问；缓存的失效与 {@link SkillParams} 同步
 * （都由技能树重载触发）。</p>
 *
 * <p><b>解析失败一律留痕</b>：数据写错时最坏的表现是「技能能加点、但什么都没发生」——
 * 这类静默失效排查起来极费时间，故每一处失败路径都打 WARN（含技能 id 与具体原因）。</p>
 *
 * @param intervalTicks 每级对应的产出间隔（tick），下标 0 = Lv.1
 * @param pool          候选产出物（非空；每次从中随机取一个）
 * @param count         每次产出数量（≥ 1）
 */
public record HarvestSpec(int[] intervalTicks, List<Item> pool, int count) {

    /** 产出配置块在 params 里的键名。 */
    private static final String HARVEST_KEY = "harvest";

    /**
     * 解析结果缓存（技能 id → 规格）。
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
                id -> SkillParams.tickBlock(id, HARVEST_KEY)
                        .flatMap(block -> parseHarvest(id, block)));
    }

    /** 清空解析缓存（技能树重载时调用 —— 旧树解析出的规格不能再被沿用）。 */
    public static void invalidateCache() {
        CACHE.clear();
    }

    // ===== 解析 =====

    private static Optional<HarvestSpec> parseHarvest(ResourceLocation skillId, JsonObject harvest) {
        int[] intervals = parseIntervals(skillId, harvest);
        List<Item> pool = parsePool(skillId, harvest);
        int count = harvest.has("count") ? harvest.get("count").getAsInt() : 1;
        if (intervals == null || pool.isEmpty() || count < 1) {
            if (pool.isEmpty()) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} has no usable item in 'items',"
                        + " harvest disabled", skillId);
            }
            if (count < 1) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} has count = {} (< 1), harvest disabled",
                        skillId, count);
            }
            return Optional.empty();
        }
        return Optional.of(new HarvestSpec(intervals, pool, count));
    }

    /** 解析 {@code interval} 数组：必须存在、非空、每项为正整数。 */
    private static int[] parseIntervals(ResourceLocation skillId, JsonObject harvest) {
        JsonElement raw = harvest.get("interval");
        if (raw == null || !raw.isJsonArray()) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {} needs an 'interval' array of ticks", skillId);
            return null;
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() == 0) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {}'s 'interval' array is empty", skillId);
            return null;
        }
        int[] values = new int[array.size()];
        for (int i = 0; i < array.size(); i++) {
            int ticks = array.get(i).getAsInt();
            if (ticks <= 0) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} interval[{}] = {} is not positive",
                        skillId, i, ticks);
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
    private static List<Item> parsePool(ResourceLocation skillId, JsonObject harvest) {
        JsonElement raw = harvest.get("items");
        if (raw == null || !raw.isJsonArray()) {
            FurkinMod.LOGGER.warn("Furkin harvest: skill {} needs an 'items' array of item ids", skillId);
            return List.of();
        }
        List<Item> pool = new ArrayList<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            String text = element.getAsString();
            ResourceLocation itemId = ResourceLocation.tryParse(text);
            Item item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) {
                FurkinMod.LOGGER.warn("Furkin harvest: skill {} has unknown item id '{}', skipped",
                        skillId, text);
                continue;
            }
            pool.add(item);
        }
        return pool;
    }
}
