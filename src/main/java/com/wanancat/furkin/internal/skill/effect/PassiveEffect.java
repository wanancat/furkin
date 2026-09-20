package com.wanancat.furkin.internal.skill.effect;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.skill.SkillEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code passive} 效果 —— 被动（夜视、水中呼吸、掉落物自动拾取等）（设计稿 §3.2 效果类型③）。
 *
 * <p><b>本轮占位</b>：被动效果需要挂钩各种游戏事件 / tick 检查，2.4 只注册类型骨架，
 * 具体被动技能留后续批次实现。当前为空操作。</p>
 */
public final class PassiveEffect implements SkillEffect {

    @Override
    public void apply(LivingEntity target, ResourceLocation skillId, int level, JsonObject params) {
        // 占位：被动具体逻辑待后续实现。
    }

    @Override
    public void remove(LivingEntity target, ResourceLocation skillId, JsonObject params) {
        // 占位。
    }
}
