package com.wanancat.furkin.internal.skill.effect;

import com.google.gson.JsonObject;
import com.wanancat.furkin.internal.skill.SkillEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code interaction} 效果 —— 交互增强（指路、寻宝等）（设计稿 §3.2 效果类型④）。
 *
 * <p><b>本轮占位</b>：交互增强需要扩展玩家与宠物的交互入口，2.4 只注册类型骨架，
 * 具体交互技能留后续批次实现。当前为空操作。</p>
 */
public final class InteractionEffect implements SkillEffect {

    @Override
    public void apply(LivingEntity target, ResourceLocation skillId, int level, JsonObject params) {
        // 占位：交互增强具体逻辑待后续实现。
    }

    @Override
    public void remove(LivingEntity target, ResourceLocation skillId, JsonObject params) {
        // 占位。
    }
}
