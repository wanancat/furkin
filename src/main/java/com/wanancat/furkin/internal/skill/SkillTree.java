package com.wanancat.furkin.internal.skill;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
     * <p>规则（三种前置，全部由 {@link #unmetRequirements} 实现，本方法只是它的布尔出口）：</p>
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
        // 未知技能（数据包被删 / 客户端伪造 id）一律视为不满足 —— 这条与 unmetRequirements 不同，
        // 后者对未知技能返回空列表（它只用于展示，调用方已遍历 all()，不存在未知项）。
        return skill != null && collectUnmet(skill, skillLevels, targetLevel).isEmpty();
    }

    /**
     * 一条**未满足**的前置要求。
     *
     * <p>归一化后的形态：无论来自 {@code requires} / {@code requiresLevel} / {@code levelGate}，
     * 都表达成「某个技能需要达到某个等级」，因此调用方（面板 tooltip 等）不必知道它属于哪一类。
     * {@code requires} 与「等级门限目标级为 1」这两种情况都会归一为 {@code requiredLevel == 1}，
     * 展示侧按「≥1 即需先学会」处理即可。</p>
     *
     * @param nameKey       前置技能的名称 lang key（不是 id —— 展示侧直接 translatable，无需再查表）
     * @param requiredLevel 该前置至少需要达到的等级
     */
    public record Requirement(String nameKey, int requiredLevel) {
    }

    /**
     * 列出某技能升到目标等级时**尚未满足**的前置要求（空列表 = 全部满足）。
     *
     * <p>与 {@link #prerequisitesMet} 共用同一套判据（都走 {@link #collectUnmet}），
     * 避免「校验用的规则」与「展示用的规则」各写一份而悄悄跑偏 —— 那种偏差表现为
     * 「面板说可以点，点下去被拒」。</p>
     *
     * <p>未知技能 id 返回空列表（本方法只服务展示，调用方从 {@code all()} 遍历而来，
     * 不存在未知项；需要「该技能是否存在」的语义请用 {@link #get}）。</p>
     */
    public List<Requirement> unmetRequirements(ResourceLocation skillId,
                                               Map<ResourceLocation, Integer> skillLevels,
                                               int targetLevel) {
        Skill skill = byId.get(skillId);
        return skill == null ? List.of() : collectUnmet(skill, skillLevels, targetLevel);
    }

    /** 前置判定的唯一实现。三种前置依次收集，**同一前置只保留最严的一条**。 */
    private List<Requirement> collectUnmet(Skill skill, Map<ResourceLocation, Integer> skillLevels, int targetLevel) {
        // 同一个前置可能被多种声明同时指向（`bleeding_bite` / `pack_tactics` 都是
        // `requires: [sharp_fang]` 配 `levelGate: sharp_fang`）。若原样逐条收集，
        // 面板会就**同一件事印两行**（乌狸 2026-09-21 截图反馈：前置区出现两条一样的
        //「需先学会 利齿尖牙」）。
        //
        // 合并规则取「最严」（requiredLevel 取 max）：满足最严的那条，其余必然也满足，
        // 因此合并**不会放宽任何判据** —— prerequisitesMet 的布尔结果与合并前逐一比对等价。
        // 展示侧只该看到「要达成什么」，而不是「被声明了几次」。
        Map<ResourceLocation, Integer> strictest = new LinkedHashMap<>();

        // ① 解锁前置：至少投过 1 级。
        for (ResourceLocation req : skill.getRequires()) {
            mergeRequired(strictest, req, 1);
        }
        // ② 固定等级门槛：前置须达到各自声明的最低等级。
        for (Map.Entry<ResourceLocation, Integer> req : skill.getRequiresLevel().entrySet()) {
            mergeRequired(strictest, req.getKey(), req.getValue());
        }
        // ③ 等级门限：门限前置的当前等级必须 ≥ 目标等级。
        ResourceLocation gate = skill.getLevelGate();
        if (gate != null) {
            mergeRequired(strictest, gate, targetLevel);
        }

        List<Requirement> unmet = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> e : strictest.entrySet()) {
            Integer level = skillLevels.get(e.getKey());
            if (level == null || level < e.getValue()) {
                unmet.add(new Requirement(nameKeyOf(e.getKey()), e.getValue()));
            }
        }
        return unmet;
    }

    /** 记录「某前置至少需 {@code requiredLevel} 级」，同一前置并存时保留更严的那个。 */
    private static void mergeRequired(Map<ResourceLocation, Integer> strictest,
                                      ResourceLocation prerequisite, int requiredLevel) {
        strictest.merge(prerequisite, requiredLevel, Math::max);
    }

    /**
     * 取一个前置技能的显示名 key。
     *
     * <p>前置 id 指向不存在的技能时（数据包写错 id），退回 {@code SkillLoader} 的默认命名约定。
     * 此时图内会直接显示出未翻译的 key —— <b>这正是想要的信号</b>，比静默显示空白更容易发现。</p>
     */
    private String nameKeyOf(ResourceLocation id) {
        Skill skill = byId.get(id);
        return skill != null ? skill.getNameKey() : "furkin.skill." + id.getPath();
    }

    /** 空树（加载失败时的兜底）。 */
    public static SkillTree empty() {
        return new SkillTree();
    }
}
