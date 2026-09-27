package com.wanancat.furkin.internal.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * furkin 服务端配置（SERVER 类型 TOML）。
 *
 * <p><b>设计稿 §5 判据二</b>：影响平衡的数值一律 {@code SERVER} 类型（进服自动同步）。
 * 数值放 {@code COMMON} = 白送外挂，故禁用。</p>
 *
 * <p><b>判据一</b>：可配的是「速率」，不可配的是「刻度」。本类只收「速率」项；
 * 成长曲线公式、每级 1 技能点、4 装备槽等「刻度」硬编码不开放。</p>
 *
 * <p>默认值 = 设计意图值（玩家零配置即得设计好的体验）。M1–M4 用占位，M5 平衡时定数。</p>
 */
public class FurkinServerConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ===== 契约 / 拥有 =====

    /** 活跃伴侣上限（小队规模）。设计稿已定为 3（属「刻度」，但以配置形式外露以便整合包调整）。 */
    public static final ForgeConfigSpec.IntValue ACTIVE_LIMIT = BUILDER
            .comment("Maximum number of simultaneously active companions (summoned).", "Design intent: 3.")
            .defineInRange("activeLimit", 3, 1, 64);

    // ===== 契约血量门槛 =====

    /** Enemy 契约目标的当前生命百分比上限。100 表示该分支不限制。 */
    public static final ForgeConfigSpec.DoubleValue CONTRACT_ENEMY_HEALTH_PERCENT = BUILDER
            .comment("Contract health gate for Enemy targets: maximum health percentage.",
                    "Passes when current health <= percent, or current health <= absolute (when absolute > 0).",
                    "Design intent: 30.")
            .defineInRange("contractEnemyHealthPercent", 30.0D, 0.0D, 100.0D);

    /** Enemy 契约目标的绝对生命值上限。0 表示禁用该分支。 */
    public static final ForgeConfigSpec.DoubleValue CONTRACT_ENEMY_HEALTH_ABSOLUTE = BUILDER
            .comment("Contract health gate for Enemy targets: absolute health threshold.",
                    "Set to 0 to disable this branch.",
                    "Design intent: 4.0 (two hearts).")
            .defineInRange("contractEnemyHealthAbsolute", 4.0D, 0.0D, 1024.0D);

    /** NeutralMob 契约目标的当前生命百分比上限。100 表示该分支不限制。 */
    public static final ForgeConfigSpec.DoubleValue CONTRACT_NEUTRAL_HEALTH_PERCENT = BUILDER
            .comment("Contract health gate for NeutralMob targets: maximum health percentage.",
                    "Passes when current health <= percent, or current health <= absolute (when absolute > 0).",
                    "Design intent: 50.")
            .defineInRange("contractNeutralHealthPercent", 50.0D, 0.0D, 100.0D);

    /** NeutralMob 契约目标的绝对生命值上限。0 表示禁用该分支。 */
    public static final ForgeConfigSpec.DoubleValue CONTRACT_NEUTRAL_HEALTH_ABSOLUTE = BUILDER
            .comment("Contract health gate for NeutralMob targets: absolute health threshold.",
                    "Set to 0 to disable this branch.",
                    "Design intent: 8.0 (four hearts; lets a full-health vanilla wolf pass).")
            .defineInRange("contractNeutralHealthAbsolute", 8.0D, 0.0D, 1024.0D);

    /** 其他契约目标的当前生命百分比上限。100 表示该分支不限制。 */
    public static final ForgeConfigSpec.DoubleValue CONTRACT_OTHER_HEALTH_PERCENT = BUILDER
            .comment("Contract health gate for other targets: maximum health percentage.",
                    "100 disables the percentage limit for this category.",
                    "Design intent: 100.")
            .defineInRange("contractOtherHealthPercent", 100.0D, 0.0D, 100.0D);

    /** 其他契约目标的绝对生命值上限。0 表示禁用该分支。 */
    public static final ForgeConfigSpec.DoubleValue CONTRACT_OTHER_HEALTH_ABSOLUTE = BUILDER
            .comment("Contract health gate for other targets: absolute health threshold.",
                    "Set to 0 to disable this branch.",
                    "Design intent: 0 (disabled).")
            .defineInRange("contractOtherHealthAbsolute", 0.0D, 0.0D, 1024.0D);

    // ===== 成长 / 经验 =====

    /** 战斗经验系数（宠物所得经验 = 玩家所得 × 该系数）。设计稿默认 100%。 */
    public static final ForgeConfigSpec.DoubleValue COMBAT_XP_MULTIPLIER = BUILDER
            .comment("Combat experience multiplier for companions.", "Design intent: 1.0 (equal to player).")
            .defineInRange("combatXpMultiplier", 1.0, 0.0, 100.0);

    /** 进食折算系数（经验 = nutrition × (1 + saturationModifier) × 该系数）。设计稿默认 1.0。 */
    public static final ForgeConfigSpec.DoubleValue FEEDING_XP_MULTIPLIER = BUILDER
            .comment("Feeding experience multiplier for companions.", "Design intent: 1.0.")
            .defineInRange("feedingXpMultiplier", 1.0, 0.0, 100.0);

    /** 递减收益：连续进食每多一档，收益递减的幅度（0–1）。默认 0.1（每档 -10%）。 */
    public static final ForgeConfigSpec.DoubleValue DIMINISH_STEP = BUILDER
            .comment("Diminishing returns: how much experience multiplier drops per consecutive feed.", "Design intent: 0.1 (each feed -10%, floor 0.1).")
            .defineInRange("diminishStep", 0.1, 0.0, 1.0);

    /** 递减收益恢复时间（秒）：停喂这么久，进食计数回落 1 档。默认 30。 */
    public static final ForgeConfigSpec.IntValue DIMINISH_RECOVERY_SECONDS = BUILDER
            .comment("Diminishing returns: seconds without feeding to recover one step.", "Design intent: 30.")
            .defineInRange("diminishRecoverySeconds", 30, 1, Integer.MAX_VALUE);

    // ===== 随身行囊（D5 / D7）=====

    /**
     * 随身行囊每级格数（D5：9 → 18 → 27）。0 = 行囊完全关闭。
     *
     * <p><b>上限为什么收到 18</b>：行囊界面用原版 {@code generic_54.png} 绘制，
     * 该纹理最多画 6 行 = 54 格；三级 × 18 = 54 格刚好用满。上限留在 64 会允许配出
     * 「格子存在但界面上够不到」的状态，故把「配置能写出的值」与「界面能显示的范围」对齐。
     * 容器侧另有一层 256 格硬上限，防的是内部异常膨胀，与界面无关。</p>
     */
    public static final ForgeConfigSpec.IntValue POUCH_SLOTS_PER_LEVEL = BUILDER
            .comment("Travel pouch slots granted per travel_pouch level.",
                    "Design intent: 9 (Lv1 = 9, Lv2 = 18, Lv3 = 27).",
                    "Upper bound 18 keeps three levels within the 6 rows (54 slots) the vanilla container texture can draw.",
                    "0 disables the pouch entirely.")
            .defineInRange("pouchSlotsPerLevel", 9, 0, 18);

    // ===== 复活 =====

    /**
     * 重获魂石冷却时长（秒）。
     *
     * <p><b>语义（2026-09-22 乌狸定案，纠正早期误读）</b>：复活仪式本体<b>零代价、零冷却</b>，
     * 唯一代价是消耗魂石。本冷却项作用于「绒亲录里重新获取已亡绒亲魂石」这个<b>兜底动作</b>，
     * 不是复活的冷却 —— 玩家丢失魂石（掉岩浆 / 被捡走）后，去录里重获一枚，受此冷却约束。
     * 具体数值 M5 平衡时定，占位 600。</p>
     */
    public static final ForgeConfigSpec.IntValue REVIVE_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown in seconds for re-acquiring a lost soulstone from the record (NOT for the revive ritual itself, which costs only the soulstone).", "Placeholder until M5 balance.")
            .defineInRange("reviveCooldownSeconds", 600, 0, Integer.MAX_VALUE);

    /**
     * 重获魂石兜底的二次冷却（秒）？—— 已废弃：早期「显著高于常规复活」的语义
     * 建立在「复活本身有冷却」的错误认知上。现复活零冷却，故此项不再需要独立含义。
     * 保留占位以兼容旧 config，M5 平衡时决定是否移除或并入其他用途。
     */
    public static final ForgeConfigSpec.IntValue SOULSTONE_REACQUIRE_COOLDOWN_SECONDS = BUILDER
            .comment("Deprecated placeholder: was 're-acquire cooldown significantly higher than revive', but revive has no cooldown now.", "Kept for config compatibility; revisit at M5 balance.")
            .defineInRange("soulstoneReacquireCooldownSeconds", 3600, 0, Integer.MAX_VALUE);

    // ===== 远距召唤 =====

    /**
     * 远距召唤总开关。
     *
     * <p>关闭后只拒绝「已召唤但实体不在运行时索引」的临时区块加载路径；
     * 已加载实体传送和 {@code summoned=false} 的合法重建不受影响。</p>
     */
    public static final ForgeConfigSpec.BooleanValue REMOTE_SUMMON_ENABLED = BUILDER
            .comment("Enable true remote summon (temporary chunk loading) for summoned companions not in the runtime entity index.",
                    "Disabling only rejects the remote chunk-loading path; loaded-entity teleport and legal rebuild from a recalled/dead record still work.",
                    "Design intent: true.")
            .define("remoteSummonEnabled", true);

    /** 目标位置周围的 ticket 传播半径：0 = 仅中心区块，1 = 3x3，2 = 5x5。 */
    public static final ForgeConfigSpec.IntValue REMOTE_SUMMON_TICKET_RADIUS = BUILDER
            .comment("Ticket radius around the last known companion position.",
                    "0 = center chunk only, 1 = 3x3 chunks, 2 = 5x5 chunks.",
                    "Higher values load more chunks and cost more; design intent: 1.")
            .defineInRange("remoteSummonTicketRadius", 1, 0, 2);

    /** 单次远召等待区块加载的 tick 上限；超时释放临时 ticket。 */
    public static final ForgeConfigSpec.IntValue REMOTE_SUMMON_TIMEOUT_TICKS = BUILDER
            .comment("Maximum ticks a remote summon waits for its target chunks before timing out.",
                    "Timeout releases the temporary ticket; design intent: 600 (30 seconds at 20 TPS).")
            .defineInRange("remoteSummonTimeoutTicks", 600, 20, 600);

    /** 每名玩家同时存在的远召 pending 上限。 */
    public static final ForgeConfigSpec.IntValue REMOTE_SUMMON_MAX_PENDING_PER_PLAYER = BUILDER
            .comment("Maximum concurrent remote summon requests per player.",
                    "Design intent: 1 (one request at a time).")
            .defineInRange("remoteSummonMaxPendingPerPlayer", 1, 1, 4);

    /** 全服同时存在的远召 pending 上限。 */
    public static final ForgeConfigSpec.IntValue REMOTE_SUMMON_MAX_PENDING_GLOBAL = BUILDER
            .comment("Maximum concurrent remote summon requests across the whole server.",
                    "Radius 1 means at most 9 chunk futures per request; keep this bounded.",
                    "Design intent: 4.")
            .defineInRange("remoteSummonMaxPendingGlobal", 4, 1, 64);

    /** 同一玩家 + companion 终态后的重复请求抑制时长；0 = 关闭。 */
    public static final ForgeConfigSpec.IntValue REMOTE_SUMMON_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown ticks before the same player can remotely summon the same companion again after a terminal result.",
                    "0 disables cooldown; design intent: 20 (1 second at 20 TPS).")
            .defineInRange("remoteSummonCooldownTicks", 20, 0, 200);
    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
