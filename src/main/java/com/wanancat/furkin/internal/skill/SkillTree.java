package com.wanancat.furkin.internal.skill;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 技能树 —— 所有技能定义的只读视图 + 前置校验（设计稿 §3.2）。
 *
 * <p>由 {@link SkillLoader} 在资源加载阶段填充，之后只读。提供：按 id 查询、
 * 列全部、以及「某技能的前置是否已满足」校验。</p>
 */
public final class SkillTree {

    private final Map<ResourceLocation, Skill> byId = new LinkedHashMap<>();

    /** 注册一个技能定义（加载阶段调用）。 */
    public void register(Skill skill) {
        byId.put(skill.getId(), skill);
    }

    public Optional<Skill> get(ResourceLocation id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Skill> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** 判断某技能的前置是否已满足（前置列表里每一项都已达到要求的等级）。 */
    public boolean prerequisitesMet(ResourceLocation skillId, Map<ResourceLocation, Integer> skillLevels) {
        Skill skill = byId.get(skillId);
        if (skill == null) {
            return false;
        }
        for (ResourceLocation req : skill.getRequires()) {
            Integer level = skillLevels.get(req);
            // 前置技能至少投过 1 级即视为满足（暂不要求前置满级）。
            if (level == null || level < 1) {
                return false;
            }
        }
        return true;
    }

    /** 空树（加载失败时的兜底）。 */
    public static SkillTree empty() {
        return new SkillTree();
    }
}
