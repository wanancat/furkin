package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.registry.ModMobEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 被动技能事件分发器 —— 把「事件驱动」的被动技能挂到游戏事件上（设计稿 §1）。
 *
 * <p><b>为什么需要它</b>：{@link SkillEffect} 接口是「一次性挂载」模型
 * （{@code apply}/{@code remove}），只适合属性修正这类静态效果；被动技能
 * （流血、闪避、凭空产出、拾荒、低血进食等）需要「受击时 / 每 tick」触发，
 * 故新增本分发器，在既有事件位点（{@code CommonEvents}）插入查询调用。</p>
 *
 * <p><b>不持久化激活态</b>：事件触发时即时读 {@code getSkillLevels()}，
 * 天然满足洗点移除 / 召唤重挂 / 解绑失效，无需额外状态清理。</p>
 *
 * <p><b>路由方式</b>：按 {@code skillId} 显式 {@code if} 路由到具体被动逻辑，
 * 而非泛化的「event 字符串反射」——类型安全、可读性好、编译器可查。</p>
 */
public final class SkillPassiveDispatcher {

    /** 流血撕咬。 */
    private static final ResourceLocation BLEEDING_BITE =
            new ResourceLocation(FurkinMod.MODID, "bleeding_bite");

    private SkillPassiveDispatcher() {
    }

    /**
     * 攻击侧被动：绒亲（attacker）攻击目标（target）时触发。
     *
     * <p>在 {@code CommonEvents#onLivingHurt} 里调用。批 1 实现流血撕咬；
     * 后续批次在此扩展其它攻击类被动（如闪避放受害侧，见 {@link #onCompanionHurt}）。</p>
     *
     * @param attacker 攻击者（可能不是绒亲，方法内部自检）
     * @param target   被攻击目标
     */
    public static void onCompanionAttack(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) {
            return;
        }
        FurkinData data = attacker.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return;
        }

        // 流血撕咬：攻击施加流血，等级越高流血越久。
        int bleedLevel = data.getSkillLevels().getOrDefault(BLEEDING_BITE, 0);
        if (bleedLevel > 0 && target.isAlive()) {
            applyBleeding(target, bleedLevel);
        }
    }

    /**
     * 受害侧被动：绒亲（victim）受击时触发（闪避等，后续批次实现）。
     *
     * @param victim 受击的绒亲（方法内部自检）
     * @param source 伤害来源
     */
    public static void onCompanionHurt(LivingEntity victim) {
        // 批 1 占位：闪避（nimble_grace）在后续批次实现。
    }

    /**
     * 周期被动：服务端 tick 时触发（凭空产出 / 拾荒 / 低血进食等，后续批次实现）。
     */
    public static void onServerTick() {
        // 批 1 占位：周期被动在后续批次实现。
    }

    /** 给目标施加流血：持续固定 4 秒，每秒伤害随技能等级递增（Lv.1/2/3 → 1/2/3 点）。 */
    private static void applyBleeding(LivingEntity target, int level) {
        // 亡灵免疫：与「中毒」对亡灵无效的原版口径一致，亡灵根本不挂流血（无粒子、无图标、无掉血）。
        if (target.isInvertedHealAndHarm()) {
            return;
        }
        int durationTicks = 4 * 20;
        int amplifier = level - 1;
        target.addEffect(new MobEffectInstance(ModMobEffects.BLEEDING.get(), durationTicks, amplifier));
    }
}
