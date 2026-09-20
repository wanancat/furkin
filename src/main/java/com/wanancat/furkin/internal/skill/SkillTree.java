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

    /**
     * 判断某技能升到目标等级的前置是否已满足。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>{@code requires} 列表里每一项都至少投过 1 级（解锁前置）。</li>
     *   <li>若声明了 {@code requiresLevel}，则其中每个前置技能的当前等级必须 ≥ 声明的最低等级
     *       （固定等级门槛，如「九命猫需灵巧身法 Lv.3」）。</li>
     *   <li>若声明了 {@code levelGate}，则该前置技能的当前等级必须 ≥ 目标等级
     *       （等级门限：本技能可升到的最高等级 = 门限前置的当前等级）。</li>
     * </ul>
     *
     * @param skillId    要升级的技能
     * @param skillLevels 已投等级快照
     * @param targetLevel 目标等级（升级后想达到的等级）
     */
    public boolean prerequisitesMet(ResourceLocation skillId, Map<ResourceLocation, Integer> skillLevels, int targetLevel) {
        Skill skill = byId.get(skillId);
        if (skill == null) {
            return false;
        }
        for (ResourceLocation req : skill.getRequires()) {
            Integer level = skillLevels.get(req);
            // 前置技能至少投过 1 级即视为满足（解锁前置）。
            if (level == null || level < 1) {
                return false;
            }
        }
        // 固定等级门槛：声明的前置技能必须达到各自的最低等级。
        for (Map.Entry<ResourceLocation, Integer> req : skill.getRequiresLevel().entrySet()) {
            Integer level = skillLevels.get(req.getKey());
            if (level == null || level < req.getValue()) {
                return false;
            }
        }
        // 等级门限：门限前置的当前等级必须 ≥ 目标等级。
        ResourceLocation gate = skill.getLevelGate();
        if (gate != null) {
            Integer gateLevel = skillLevels.get(gate);
            if (gateLevel == null || gateLevel < targetLevel) {
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
