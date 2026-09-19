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

    // ===== 复活 =====

    /** 复活冷却时长（秒）。具体数值 M5 平衡时定，占位 600。 */
    public static final ForgeConfigSpec.IntValue REVIVE_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown in seconds between revives.", "Placeholder until M5 balance.")
            .defineInRange("reviveCooldownSeconds", 600, 0, Integer.MAX_VALUE);

    /** 重获魂石冷却时长（秒）。设计稿要求显著高于常规复活。 */
    public static final ForgeConfigSpec.IntValue SOULSTONE_REACQUIRE_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown in seconds for re-acquiring a lost soulstone (significantly higher than revive).", "Placeholder until M5 balance.")
            .defineInRange("soulstoneReacquireCooldownSeconds", 3600, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
