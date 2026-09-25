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

    /** 与 goal 删除的身份语义保持一致，避免第三方 goal 被按类型误删。 */
    private final Set<Goal> ownedGoals = Collections.newSetFromMap(new IdentityHashMap<>());

    private FurkinCombatAiState() {
    }

    public static FurkinCombatAiState create() {
        return new FurkinCombatAiState();
    }

    public boolean isOriginalCaptured() {
        return originalCaptured;
    }

    public void markOriginalCaptured() {
        this.originalCaptured = true;
    }

    public boolean isLegacyPolluted() {
        return legacyPolluted;
    }

    public void markLegacyPolluted() {
        this.legacyPolluted = true;
    }

    public List<WrappedGoal> getSavedGoalSelectorGoals() {
        return savedGoalSelectorGoals;
    }

    public List<WrappedGoal> getSavedTargetSelectorGoals() {
        return savedTargetSelectorGoals;
    }

    public Set<Goal> getOwnedGoals() {
        return ownedGoals;
    }

    public void clearOwnedGoals() {
        ownedGoals.clear();
    }

    public void clear() {
        originalCaptured = false;
        legacyPolluted = false;
        savedGoalSelectorGoals.clear();
        savedTargetSelectorGoals.clear();
        ownedGoals.clear();
    }
}
