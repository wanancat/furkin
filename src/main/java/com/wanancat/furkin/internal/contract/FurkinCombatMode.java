package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;

import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OcelotAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Monster;

import java.util.List;

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
 * <p>实现：{@link #applyTo} 首次接管时精确保存并移除冲突原版/第三方 goal，之后每次按
 * 档位重建本模组私有 goal。删除优先按记录实例，私有标记仅作兜底；解绑时按原 goal
 * 实例与优先级恢复。</p>
 *
 * <p><b>TargetGoal 构造时绑定 mob 引用，不能跨实体共用</b>，故每次 apply 时按需
 * {@code new} 一套；重复调用保持幂等。{@code GoalSelector#addGoal} 会重新创建 wrapper，
 * 因此只承诺恢复原 goal 实例和优先级，不承诺 wrapper 身份相同。</p>
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
     * <p>设计稿铁律：只处理两个 selector 中与战斗冲突的 goal，不碰原版 {@code TAME}
     * 行为层或非冲突 goal。幂等（同档位重复调用无副作用）。</p>
     */
    public boolean applyTo(TamableAnimal animal) {
        if (animal.level().isClientSide()) {
            return false;
        }
        FurkinData data = animal.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            FurkinMod.LOGGER.warn("Cannot apply Furkin combat mode: entity {} has no Furkin capability",
                    animal.getUUID());
            return false;
        }
        FurkinCombatAiState aiState = data.getOrCreateCombatAiState();

        // 每次应用先清除上一轮本模组 goal 与当前攻击目标，避免重复叠加。
        clearOwnedGoals(animal, aiState);
        animal.setTarget(null);

        // 首次接管时才建立快照。旧档没有可恢复来源，只记录 legacy 并移除冲突 goal。
        if (!aiState.isOriginalCaptured()) {
            if (data.getAiStateVersion() >= FurkinData.CURRENT_AI_STATE_VERSION) {
                captureAndRemoveOriginalGoals(animal, aiState);
            } else {
                markLegacyAndRemoveConflictingGoals(animal, aiState);
            }
        }

        // 近战行动目标恒挂。
        addOwnedGoal(aiState, animal.goalSelector, 5,
                new FurkinMeleeAttackGoal(animal, 1.0, true));

        // 反击（挨打还手）—— PASSIVE 起挂。
        if (this != FOLLOW) {
            addOwnedGoal(aiState, animal.targetSelector, 3,
                    new FurkinHurtByTargetGoal(animal));
        }

        // 护主 —— PROTECT 起挂。
        if (this == PROTECT || this == AGGRESSIVE) {
            addOwnedGoal(aiState, animal.targetSelector, 1,
                    new FurkinOwnerHurtByTargetGoal(animal));
            addOwnedGoal(aiState, animal.targetSelector, 2,
                    new FurkinOwnerHurtTargetGoal(animal));
        }

        // 主动攻击所有敌对生物 —— 仅 AGGRESSIVE。
        if (this == AGGRESSIVE) {
            addOwnedGoal(aiState, animal.targetSelector, 4,
                    new FurkinNearestAttackableTargetGoal<>(animal, Monster.class, true));
        }
        return true;
    }

    /**
     * 解绑时移除本模组 goal，并在快照有效时恢复原 goal。
     *
     * <p>旧档实体只有 {@code legacyPolluted} 标记，没有可恢复快照，因此只清理
     * Furkin 状态，不做猜测性恢复。</p>
     */
    public static void onUnbind(TamableAnimal animal, FurkinCombatAiState aiState) {
        clearOwnedGoals(animal, aiState);
        animal.setTarget(null);

        if (aiState.isOriginalCaptured() && !aiState.isLegacyPolluted()) {
            restoreOriginalGoals(animal, aiState);
        }

        FurkinData data = animal.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data != null) {
            data.clearCombatAiState();
            data.setAiStateVersion(0);
        } else {
            aiState.clear();
        }
    }

    /** 只移除本模组拥有的 goal；优先精确实例，私有标记做兜底。 */
    private static void clearOwnedGoals(TamableAnimal animal, FurkinCombatAiState aiState) {
        for (Goal goal : List.copyOf(aiState.getOwnedGoals())) {
            animal.goalSelector.removeGoal(goal);
            animal.targetSelector.removeGoal(goal);
        }
        aiState.clearOwnedGoals();

        animal.targetSelector.removeAllGoals(goal -> goal instanceof FurkinOwnedGoal);
        animal.goalSelector.removeAllGoals(goal -> goal instanceof FurkinOwnedGoal);
    }

    /** 保存并将冲突原版/第三方 goal 从两个选择器中移除。 */
    private static void captureAndRemoveOriginalGoals(TamableAnimal animal,
                                                       FurkinCombatAiState aiState) {
        aiState.getSavedGoalSelectorGoals().clear();
        aiState.getSavedTargetSelectorGoals().clear();

        for (WrappedGoal wrapped : List.copyOf(animal.goalSelector.getAvailableGoals())) {
            if (isConflictingGoal(wrapped.getGoal())) {
                aiState.getSavedGoalSelectorGoals().add(wrapped);
                animal.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
        for (WrappedGoal wrapped : List.copyOf(animal.targetSelector.getAvailableGoals())) {
            if (isConflictingGoal(wrapped.getGoal())) {
                aiState.getSavedTargetSelectorGoals().add(wrapped);
                animal.targetSelector.removeGoal(wrapped.getGoal());
            }
        }
        aiState.markOriginalCaptured();
    }

    /** 旧档实体不保存快照，只移除会与 Furkin 模式冲突的 goal。 */
    private static void markLegacyAndRemoveConflictingGoals(TamableAnimal animal,
                                                             FurkinCombatAiState aiState) {
        if (!aiState.isLegacyPolluted()) {
            aiState.markLegacyPolluted();
            FurkinMod.LOGGER.warn("Legacy Furkin AI state for entity {}: original goals cannot be restored",
                    animal.getUUID());
        }
        removeConflictingGoals(animal);
    }

    /** 按设计稿的冲突谓词移除 goal，不保存快照。 */
    private static void removeConflictingGoals(TamableAnimal animal) {
        for (WrappedGoal wrapped : List.copyOf(animal.goalSelector.getAvailableGoals())) {
            if (isConflictingGoal(wrapped.getGoal())) {
                animal.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
        for (WrappedGoal wrapped : List.copyOf(animal.targetSelector.getAvailableGoals())) {
            if (isConflictingGoal(wrapped.getGoal())) {
                animal.targetSelector.removeGoal(wrapped.getGoal());
            }
        }
    }

    /** 恢复捕获时的原 goal 实例与优先级，跳过已经被其他逻辑加回的同一实例。 */
    private static void restoreOriginalGoals(TamableAnimal animal, FurkinCombatAiState aiState) {
        for (WrappedGoal wrapped : aiState.getSavedGoalSelectorGoals()) {
            restoreGoal(animal.goalSelector, wrapped);
        }
        for (WrappedGoal wrapped : aiState.getSavedTargetSelectorGoals()) {
            restoreGoal(animal.targetSelector, wrapped);
        }
    }

    /** 使用公开 GoalSelector API 恢复 goal；wrapper 会由选择器重新创建。 */
    private static void restoreGoal(GoalSelector selector, WrappedGoal saved) {
        boolean alreadyPresent = selector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() == saved.getGoal());
        if (!alreadyPresent) {
            selector.addGoal(saved.getPriority(), saved.getGoal());
        }
    }

    /** 与设计稿 D2 一致的冲突 goal 范围。 */
    private static boolean isConflictingGoal(Goal goal) {
        return goal instanceof TargetGoal
                || goal instanceof MeleeAttackGoal
                || goal instanceof OcelotAttackGoal;
    }

    /** 加入本模组 goal，并记录精确实例所有权。 */
    private static void addOwnedGoal(FurkinCombatAiState aiState, GoalSelector selector,
                                     int priority, Goal goal) {
        selector.addGoal(priority, goal);
        aiState.getOwnedGoals().add(goal);
    }

    /** 本模组创建的 goal 统一使用的私有所有权标记，不向第三方开放。 */
    private interface FurkinOwnedGoal {
    }

    /** 保持原版 {@link MeleeAttackGoal} 的构造与行为语义。 */
    private static final class FurkinMeleeAttackGoal
            extends MeleeAttackGoal implements FurkinOwnedGoal {

        private FurkinMeleeAttackGoal(PathfinderMob mob, double speedModifier,
                                      boolean followingTargetEvenIfNotSeen) {
            super(mob, speedModifier, followingTargetEvenIfNotSeen);
        }
    }

    /** 保持原版 {@link HurtByTargetGoal} 的构造与行为语义。 */
    private static final class FurkinHurtByTargetGoal
            extends HurtByTargetGoal implements FurkinOwnedGoal {

        private FurkinHurtByTargetGoal(PathfinderMob mob, Class<?>... toIgnoreDamage) {
            super(mob, toIgnoreDamage);
        }
    }

    /** 保持原版 {@link OwnerHurtByTargetGoal} 的构造与行为语义。 */
    private static final class FurkinOwnerHurtByTargetGoal
            extends OwnerHurtByTargetGoal implements FurkinOwnedGoal {

        private FurkinOwnerHurtByTargetGoal(TamableAnimal tameAnimal) {
            super(tameAnimal);
        }
    }

    /** 保持原版 {@link OwnerHurtTargetGoal} 的构造与行为语义。 */
    private static final class FurkinOwnerHurtTargetGoal
            extends OwnerHurtTargetGoal implements FurkinOwnedGoal {

        private FurkinOwnerHurtTargetGoal(TamableAnimal tameAnimal) {
            super(tameAnimal);
        }
    }

    /** 保持原版 {@link NearestAttackableTargetGoal} 的构造与行为语义。 */
    private static final class FurkinNearestAttackableTargetGoal<T extends LivingEntity>
            extends NearestAttackableTargetGoal<T> implements FurkinOwnedGoal {

        private FurkinNearestAttackableTargetGoal(Mob mob, Class<T> targetType,
                                                   boolean mustSee) {
            super(mob, targetType, mustSee);
        }
    }

    /** 本模式是否至少「被动反击」级。 */
    public boolean atLeastPassive() {
        return this != FOLLOW;
    }
}
