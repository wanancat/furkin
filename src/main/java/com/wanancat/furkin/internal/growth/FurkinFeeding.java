package com.wanancat.furkin.internal.growth;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * 进食经验通道 —— 喂食结算（设计稿 §3.2「进食通道」）。
 *
 * <p><b>五条落地规则</b>（全部 2026-09-19 已定）：</p>
 * <ul>
 *   <li>① 判定：{@code ItemStack#getFoodProperties()} 返回非 null 且 {@code nutrition > 0}
 *       即有效食物，不依赖食物 tag（1.20.1 无食物 tag）。</li>
 *   <li>② 折算：{@code 经验 = nutrition × (1 + saturationModifier) × 系数}（系数可配）。</li>
 *   <li>③ 接受范围：任意可食用食物（比原版狼/猫白名单宽），须接管喂食。</li>
 *   <li>④ 原版行为：回血<b>保留</b>（原版公式）；求偶/繁殖<b>取消</b>；消耗 1 个；食物效果<b>不施加</b>。</li>
 *   <li>⑤ 防刷：<b>递减收益</b>——连续进食收益递减，停喂自然恢复（速率/恢复时间可配）。</li>
 * </ul>
 *
 * <p>递减收益状态（连续进食计数 + 上次进食 tick）属<b>运行时状态</b>，存于
 * {@link FurkinData}；收回（dismiss）即重置递减，符合「停喂恢复」的直觉。</p>
 */
public final class FurkinFeeding {

    private FurkinFeeding() {
    }

    /** 食物判定：是否「玩家可食用且回复饱食度」的有效食物。 */
    public static boolean isEdibleFood(ItemStack stack, LivingEntity eater) {
        FoodProperties food = stack.getFoodProperties(eater);
        return food != null && food.getNutrition() > 0;
    }

    /**
     * 计算某食物喂给某绒亲的原始经验值（未叠递减收益）。
     *
     * <p>公式：{@code nutrition × (1 + saturationModifier) × 系数}。</p>
     */
    public static int baseXpFor(ItemStack stack, LivingEntity companion) {
        FoodProperties food = stack.getFoodProperties(companion);
        if (food == null) {
            return 0;
        }
        double raw = food.getNutrition() * (1.0 + food.getSaturationModifier())
                * FurkinServerConfig.FEEDING_XP_MULTIPLIER.get();
        return (int) Math.floor(raw);
    }

    /**
     * 执行喂食：结算递减收益 → 折算经验 → 加经验 → 回血 → 消耗食物。
     *
     * <p>由 {@code CommonEvents.onEntityInteract} 在「已确认取消原版行为」后调用。
     * 本方法不做归属/身份校验（调用方已保证目标是本人契约且在场的绒亲）。</p>
     *
     * @param player    喂食玩家（须为绒亲主人）
     * @param companion 在场绒亲
     * @param held      玩家手持的食物
     * @return 是否成功结算（食物有效且已消耗）
     */
    public static boolean feed(ServerPlayer player, LivingEntity companion, ItemStack held) {
        FurkinData data = companion.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return false;
        }

        FoodProperties food = held.getFoodProperties(companion);
        if (food == null || food.getNutrition() <= 0) {
            return false;
        }

        // ① 折算原始经验。
        int baseXp = baseXpFor(held, companion);

        // ② 递减收益：连续进食收益递减，停喂恢复。
        int effectiveXp = applyDiminishingReturns(data, baseXp);

        // ③ 加经验（FurkinGrowth 负责升级/发点/广播/写档案）。
        FurkinGrowth.addXp(companion, effectiveXp);

        // ④ 回血（沿用原版公式：回复饱食度值）。不施加食物效果、不触发求偶。
        heal(companion, food.getNutrition());

        // 先取食物 id（shrink 后 stack 变 empty，getDescriptionId 会返回 block.minecraft.air）。
        String foodId = held.getItem().getDescriptionId();

        // ⑤ 消耗 1 个（与原版一致）。
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }

        FurkinMod.LOGGER.info("Furkin fed: id={} food={} baseXp={} effectiveXp={}",
                data.getCompanionId(), foodId, baseXp, effectiveXp);

        return true;
    }

    /**
     * 递减收益：连续进食时收益递减，停喂一定时间后恢复。
     *
     * <p>模型：宠物维护一个「进食强度」计数（{@code feedCount}），每次进食 +1；
     * 每过 {@code DIMINISH_RECOVERY_TICKS} 未进食则计数 -1（下限 0）。
     * 实际经验 = 原始经验 × 递减因子，因子随计数线性下降，最低降到 0.1。</p>
     */
    private static int applyDiminishingReturns(FurkinData data, int baseXp) {
        long now = data.getOwnerUuid() == null ? 0 : System.currentTimeMillis();
        // 用 tick 更贴合游戏节奏，但这里用时间戳足够表达「停喂恢复」。
        long lastFed = data.getLastFeedMillis();
        long recoveryMillis = FurkinServerConfig.DIMINISH_RECOVERY_SECONDS.get() * 1000L;
        long elapsed = now - lastFed;

        int count = data.getFeedCount();
        // 停喂期间每过 recovery 时长，计数回落 1 档。
        if (lastFed > 0 && recoveryMillis > 0) {
            int recovered = (int) (elapsed / recoveryMillis);
            count = Math.max(0, count - recovered);
        }
        count++; // 本次进食 +1。
        data.setFeedCount(count);
        data.setLastFeedMillis(now);

        // 递减因子：每档递减 DIMINISH_STEP，最低 0.1。
        double factor = Math.max(0.1, 1.0 - (count - 1) * FurkinServerConfig.DIMINISH_STEP.get());
        return (int) Math.floor(baseXp * factor);
    }

    /** 回血（沿用原版喂食回血：回复饱食度值的一半，至少 1 点，不超过原版上限）。 */
    private static void heal(LivingEntity companion, int nutrition) {
        float healAmount = Math.max(1.0f, nutrition / 2.0f);
        companion.heal(healAmount);
    }
}
