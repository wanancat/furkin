package com.wanancat.furkin.internal.effect;

import com.wanancat.furkin.internal.skill.BleedingSpec;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 流血效果 —— 持续掉血，可叠加时长（设计稿：自实现 {@code furkin:bleeding}）。
 *
 * <p><b>效果逻辑</b>：拥有此效果的实体每 20 tick（1 秒）扣一次血，伤害量
 * 由效果增幅等级决定；对亡灵（{@link LivingEntity#isInvertedHealAndHarm()}）免疫，
 * 与原版「中毒」同类效果一致。伤害计入 {@code generic} 伤害源、不带攻击者
 * （避免触发伤害反弹 / 战斗经验登记）。</p>
 *
 * <p><b>每秒伤害存在哪</b>：不写在本类，而在技能 JSON 的
 * {@code params.bleeding_bite.damagePerSecond}（按技能等级取项）。
 * 本方法只拿得到 {@code (LivingEntity, int amplifier)}、看不到 JSON，
 * 故按技能 id 反查 {@link BleedingSpec} —— 详见该类注释里「为什么不需要
 * 把值编码进 amplifier / duration」一段。</p>
 */
public final class BleedingEffect extends MobEffect {

    /** 每秒触发周期（tick）。 */
    private static final int TICK_INTERVAL = 20;

    public BleedingEffect() {
        super(MobEffectCategory.HARMFUL, 0xB22222);
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // 端守卫。⚠️ 1.20.1 把 applyEffectTick 从 LivingEntity#tickEffects 挪进了
        // MobEffectInstance#tick，而 tickEffects 的 isClientSide 守卫只包住「过期移除」分支，
        // 管不到 tick 内部 ⇒ 客户端也会走到这里（javap 取证：LivingEntity#tickEffects /
        // MobEffectInstance#tick，2026-09-21）。
        // 客户端那次 hurt 本身无副作用（LivingEntity#hurt 在 isClientSide 时直接 return false，
        // 不减血、不闪红、不发声），但没必要跑 —— 更要紧的是别让客户端去读技能树：
        // 技能树只挂在服务端数据包重载事件上，多人游戏下客户端是一棵空树。
        if (entity.level().isClientSide()) {
            return;
        }
        // 亡灵免疫（与中毒一致）。施加侧另有一道同名判定，那道是为了让亡灵「连效果都不挂」，
        // 这道防的是绕过施加侧的途径（如 /effect give）—— 两层判的不是同一件事。
        if (entity.isInvertedHealAndHarm()) {
            return;
        }
        // 每秒伤害按技能等级取（amplifier = 等级 − 1，故此处补 1）。
        // 取不到规格时不掉血、只留图标：这条路径只在「效果已挂上、数据随后被改坏」时才走到，
        // 施加侧已按「取不到即禁用」处理、不会再有新效果产生；留痕由 BleedingSpec 解析时打一次
        // （否定结果进了 SkillParams 的缓存，不会逐 tick 刷屏）。
        float damage = BleedingSpec.of()
                .map(spec -> spec.damageForLevel(amplifier + 1))
                .orElse(0.0f);
        if (damage <= 0.0f) {
            return;
        }
        entity.hurt(entity.damageSources().generic(), damage);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        // 每 20 tick（1 秒）触发一次掉血。
        return duration % TICK_INTERVAL == 0;
    }
}
