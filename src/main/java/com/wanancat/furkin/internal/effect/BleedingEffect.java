package com.wanancat.furkin.internal.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 流血效果 —— 持续掉血，可叠加时长（设计稿：自实现 {@code furkin:bleeding}）。
 *
 * <p><b>效果逻辑</b>：拥有此效果的实体每 20 tick（1 秒）扣一次血，伤害量
 * 由效果增幅等级决定；对亡灵（{@link LivingEntity#isInvertedHealAndHarm()}）免疫，
 * 与原版「中毒」同类效果一致。伤害不计入攻击者（避免触发伤害反弹 / 战斗经验登记）。</p>
 *
 * <p><b>每级伤害</b>：每秒伤害 = 1 + amplifier 点生命值（施加方用 amplifier 表达技能等级）。
 * 具体数值待数值定稿阶段统一调整。</p>
 */
public final class BleedingEffect extends MobEffect {

    /** 每秒触发周期（tick）。 */
    private static final int TICK_INTERVAL = 20;

    public BleedingEffect() {
        super(MobEffectCategory.HARMFUL, 0xB22222);
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // 亡灵免疫（僵尸 / 骷髅等），与中毒一致。
        if (entity.isInvertedHealAndHarm()) {
            return;
        }
        // 每秒伤害 = 1 + amplifier（amplifier 由技能等级决定：Lv.1→0、Lv.2→1、Lv.3→2）。
        float damage = 1.0f + amplifier;
        entity.hurt(entity.damageSources().generic(), damage);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        // 每 20 tick（1 秒）触发一次掉血。
        return duration % TICK_INTERVAL == 0;
    }
}
