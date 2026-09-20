package com.wanancat.furkin.internal.skill.effect;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.skill.SkillEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code ability} 效果 —— 主动能力（冲刺、威慑、治疗等）（设计稿 §3.2 效果类型②）。
 *
 * <p><b>本轮占位</b>：主动能力需要触发交互（右键 / 快捷键 / 自动施放），比被动复杂，
 * 2.4 只注册类型骨架，具体能力技能留后续批次实现。当前 {@code apply}/{@code remove}
 * 为空操作，仅保证类型可被 JSON 引用而不报错。</p>
 */
public final class AbilityEffect implements SkillEffect {

    @Override
    public void apply(LivingEntity target, ResourceLocation skillId, int level, JsonObject params) {
        // 占位：主动能力具体逻辑待后续实现。
    }

    @Override
    public void remove(LivingEntity target, ResourceLocation skillId, JsonObject params) {
        // 占位。
    }
}
