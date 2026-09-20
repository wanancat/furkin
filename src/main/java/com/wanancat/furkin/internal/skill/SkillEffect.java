package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 技能效果接口 —— 效果类型的 Java 逻辑实现（设计稿 §3.2）。
 *
 * <p>每种效果类型（{@code attribute} / {@code ability} / {@code passive} /
 * {@code interaction}）实现本接口，负责在宠物升级该技能时「应用」效果、
 * 在洗点/降级时「移除」效果。效果的数值来自技能 JSON 的 {@code params}。</p>
 *
 * <p><b>等级语义</b>：{@code level} 是当前已投等级（1 起）。效果实现据此
 * 按等级线性 / 阶梯计算数值（如每级 +0.5 攻击）。</p>
 *
 * <p><b>skillId</b>：所属技能标识，供效果实现派生确定性的 modifier UUID
 * （不同技能加同一属性时应是独立、可叠加的修正，而非互相覆盖）。</p>
 */
public interface SkillEffect {

    /**
     * 应用效果到目标宠物（某技能升至某级时调用）。
     *
     * @param target  目标宠物实体
     * @param skillId 所属技能标识
     * @param level   当前已投等级（1 起）
     * @param params  该效果在 JSON 里的参数
     */
    void apply(LivingEntity target, ResourceLocation skillId, int level, JsonObject params);

    /**
     * 移除效果（洗点 / 降级 / 解绑时调用）。
     *
     * @param target  目标宠物实体
     * @param skillId 所属技能标识
     * @param params  该效果在 JSON 里的参数（与 apply 时一致）
     */
    void remove(LivingEntity target, ResourceLocation skillId, JsonObject params);
}
