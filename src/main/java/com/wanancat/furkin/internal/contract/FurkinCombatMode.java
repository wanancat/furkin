package com.wanancat.furkin.internal.contract;

import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Monster;

/**
 * 绒亲战斗模式（四档，统一猫狗 —— 设计稿 §3.2 战斗经验配套）。
 *
 * <p>原版只有<b>狗</b>有攻击目标体系（护主 / 反击 / 主动打骷髅），猫没有。
 * 本枚举让所有绒亲（猫 / 狗 / 后续物种）统一一套战斗模式，由玩家切换：</p>
 *
 * <pre>
 * FOLLOW      跟随 —— 只跟随，不参战（默认）
 * PASSIVE     被动 —— 仅挨打时反击
 * PROTECT     保护 —— 被动 + 护主（主人被攻击时反击、攻击主人正在打的目标）
 * AGGRESSIVE  主动 —— 保护 + 主动攻击所有敌对生物
 * </pre>
 *
 * <p>目标挂载（逐级递增）：</p>
 * <pre>
 *                  HurtBy   OwnerHurtBy/OwnerHurtTarget   NearestAttackable(Monster)
 * FOLLOW           ✗        ✗                              ✗
 * PASSIVE          ✓        ✗                              ✗
 * PROTECT          ✓        ✓                              ✗
 * AGGRESSIVE       ✓        ✓                              ✓
 * </pre>
 *
 * <p>实现：{@link #applyTo} 按档位增删目标（Forge 的 {@code GoalSelector.removeAllGoals}
 * 按类型删除可用）。<b>TargetGoal 构造时绑定 mob 引用，不能跨实体共用</b>，故每次
 * apply 时按需 {@code new} 一套；通过「先按类型清、再按档位重挂」保持幂等。</p>
 *
 * <p>行动目标 {@code MeleeAttackGoal} 恒挂（无目标时 canUse 为 false，不影响跟随 / 坐下）。</p>
 */
public enum FurkinCombatMode {

    FOLLOW,
    PASSIVE,
    PROTECT,
    AGGRESSIVE;

    /** 从字符串解析（命令参数用），非法返回 null。 */
    public static FurkinCombatMode parse(String s) {
        if (s == null) {
            return null;
        }
        for (FurkinCombatMode m : values()) {
            if (m.name().equalsIgnoreCase(s)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 把战斗模式应用到一只在场绒亲（增删攻击目标）。
     *
     * <p>前提：目标须是 {@link TamableAnimal}（猫狗都是）。非 TamableAnimal 的物种
     * 暂不适用（M 后续物种接入时再扩展）。</p>
     *
     * <p>设计稿铁律：只操作「选目标」的 targetSelector，不碰原版 {@code TAME} 行为层。
     * 幂等（同档位重复调用无副作用）。</p>
     */
    public void applyTo(TamableAnimal animal) {
        // 先移除本模组可能已挂上的全部攻击目标（按类型删，幂等）。
        removeAllCombatTargets(animal);

        // 近战行动目标恒挂。
        animal.goalSelector.addGoal(5, new MeleeAttackGoal(animal, 1.0, true));

        // 反击（挨打还手）—— PASSIVE 起挂。
        if (this != FOLLOW) {
            animal.targetSelector.addGoal(3, new HurtByTargetGoal(animal));
        }

        // 护主 —— PROTECT 起挂。
        if (this == PROTECT || this == AGGRESSIVE) {
            animal.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(animal));
            animal.targetSelector.addGoal(2, new OwnerHurtTargetGoal(animal));
        }

        // 主动攻击所有敌对生物 —— 仅 AGGRESSIVE。
        if (this == AGGRESSIVE) {
            animal.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(animal, Monster.class, true));
        }
    }

    /**
     * 移除本模组可能挂上的全部攻击目标（含近战行动目标）。
     * 按类型删除（GoalSelector.removeAllGoals），不依赖实例引用。
     */
    private static void removeAllCombatTargets(TamableAnimal animal) {
        animal.targetSelector.removeAllGoals(goal ->
                goal instanceof HurtByTargetGoal
                        || goal instanceof OwnerHurtByTargetGoal
                        || goal instanceof OwnerHurtTargetGoal
                        || goal instanceof NearestAttackableTargetGoal);
        animal.goalSelector.removeAllGoals(goal -> goal instanceof MeleeAttackGoal);
    }

    /** 本模式是否至少「被动反击」级。 */
    public boolean atLeastPassive() {
        return this != FOLLOW;
    }
}
