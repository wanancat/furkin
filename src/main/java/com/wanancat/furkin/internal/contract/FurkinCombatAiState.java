package com.wanancat.furkin.internal.contract;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 单只在场绒亲的运行时战斗 AI 状态。
 *
 * <p>该对象只保存 goal 引用、原 goal 快照和所有权标记，不进入 NBT、档案、
 * 网络同步或公开 API。实体卸载后随能力对象一起释放，重新入世时按当前
 * 原版/第三方 goal 重新建立快照。</p>
 */
public final class FurkinCombatAiState {

    private boolean originalCaptured;
    private boolean legacyPolluted;

    private final List<WrappedGoal> savedGoalSelectorGoals = new ArrayList<>();
    private final List<WrappedGoal> savedTargetSelectorGoals = new ArrayList<>();

    /** 与 {@code GoalSelector#removeGoal(Goal)} 的对象身份语义保持一致。 */
    private final Set<Goal> ownedGoals = Collections.newSetFromMap(new IdentityHashMap<>());

    private FurkinCombatAiState() {
    }

    /** 创建一只实体独立的空 AI 状态。 */
    public static FurkinCombatAiState create() {
        return new FurkinCombatAiState();
    }

    /** 原 goal 快照是否已经建立。 */
    public boolean isOriginalCaptured() {
        return originalCaptured;
    }

    /** 标记原 goal 快照已经建立；同一实体后续不再重复捕获。 */
    public void markOriginalCaptured() {
        this.originalCaptured = true;
    }

    /** 是否属于无法安全恢复原 AI 的旧档实体。 */
    public boolean isLegacyPolluted() {
        return legacyPolluted;
    }

    /** 标记旧档实体：只清理本模组状态，不做猜测性原 AI 恢复。 */
    public void markLegacyPolluted() {
        this.legacyPolluted = true;
    }

    /** 行动目标选择器的原 goal 快照；只保存引用，不复制 goal。 */
    public List<WrappedGoal> getSavedGoalSelectorGoals() {
        return savedGoalSelectorGoals;
    }

    /** 目标选择器的原 goal 快照；只保存引用，不复制 goal。 */
    public List<WrappedGoal> getSavedTargetSelectorGoals() {
        return savedTargetSelectorGoals;
    }

    /** 本模组创建并拥有精确身份的 goal 实例集合。 */
    public Set<Goal> getOwnedGoals() {
        return ownedGoals;
    }

    /** 只清空本模组 goal 身份记录，不影响原 goal 快照。 */
    public void clearOwnedGoals() {
        ownedGoals.clear();
    }

    /** 清空全部运行时 AI 状态，供实体解绑或重新初始化使用。 */
    public void clear() {
        originalCaptured = false;
        legacyPolluted = false;
        savedGoalSelectorGoals.clear();
        savedTargetSelectorGoals.clear();
        ownedGoals.clear();
    }
}