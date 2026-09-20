package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 技能效果应用器 —— 统一驱动「按 skillLevels 把效果挂到宠物身上」（设计稿 §3.2）。
 *
 * <p>职责单一：给定宠物 + 当前技能等级映射，遍历每个技能，把它的所有效果
 * {@code apply} 到实体（或 {@code remove}）。技能升级、洗点、召唤重建后都走这里，
 * 保证「属性修正」这一运行时表现与 {@code skillLevels} 始终一致。</p>
 */
public final class SkillEffectApplier {

    private SkillEffectApplier() {
    }

    /**
     * 把某只宠物的全部技能效果应用到实体（按当前 skillLevels）。
     * 用于召唤重建、档案回灌后重算属性。
     */
    public static void applyAll(LivingEntity target, SkillTree tree,
                                java.util.Map<ResourceLocation, Integer> skillLevels) {
        for (java.util.Map.Entry<ResourceLocation, Integer> entry : skillLevels.entrySet()) {
            ResourceLocation skillId = entry.getKey();
            int level = entry.getValue();
            applySkill(target, tree, skillId, level);
        }
    }

    /** 应用单个技能的全部效果（技能升至某级）。 */
    public static void applySkill(LivingEntity target, SkillTree tree, ResourceLocation skillId, int level) {
        Skill skill = tree.get(skillId).orElse(null);
        if (skill == null || level <= 0) {
            return;
        }
        for (Skill.SkillEffectSpec spec : skill.getEffects()) {
            SkillEffect effect = SkillEffects.byType(spec.getType());
            if (effect == null) {
                FurkinMod.LOGGER.warn("Unknown skill effect type {} for skill {}", spec.getType(), skillId);
                continue;
            }
            effect.apply(target, skillId, level, spec.getParams());
        }
    }

    /** 移除单个技能的全部效果（洗点 / 降级到 0）。 */
    public static void removeSkill(LivingEntity target, SkillTree tree, ResourceLocation skillId) {
        Skill skill = tree.get(skillId).orElse(null);
        if (skill == null) {
            return;
        }
        for (Skill.SkillEffectSpec spec : skill.getEffects()) {
            SkillEffect effect = SkillEffects.byType(spec.getType());
            if (effect == null) {
                continue;
            }
            effect.remove(target, skillId, spec.getParams());
        }
    }

    /** 移除某只宠物的全部技能效果（解绑 / 洗点清空）。 */
    public static void removeAll(LivingEntity target, SkillTree tree,
                                 java.util.Map<ResourceLocation, Integer> skillLevels) {
        for (ResourceLocation skillId : skillLevels.keySet()) {
            removeSkill(target, tree, skillId);
        }
    }
}
