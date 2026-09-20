package com.wanancat.furkin.internal.skill;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 技能定义 —— 单个技能条目的数据模型（设计稿 §3.2）。
 *
 * <p>来源是 {@code data/furkin/skills/<skill_id>.json}，由 {@link SkillLoader} 解析。
 * 字段与设计稿对齐：id / name / description / tier / requires / maxLevel / cost / effects / species。</p>
 *
 * <p><b>maxLevel 语义</b>：{@code 1} = 单级（默认）；{@code -1} = 可无限升级（「蚊子腿」技能，
 * 不计入毕业点求和）；其它正整数 = 多级。求和边界：只累加 {@code maxLevel > 0} 的条目。</p>
 */
public final class Skill {

    /** 技能标识（含命名空间）。 */
    private final ResourceLocation id;

    /** 显示名本地化 key。 */
    private final String nameKey;

    /** 描述本地化 key。 */
    private final String descriptionKey;

    /** 层级（决定树上的位置，越大越靠后）。 */
    private final int tier;

    /** 前置技能标识列表（可空）。 */
    private final List<ResourceLocation> requires;

    /** 等级门限：指向某个前置技能，本技能可升到的最高等级 = 该前置技能的当前等级（可空，表示无此约束）。 */
    private final ResourceLocation levelGate;

    /** 可升级级数：1=单级（默认），-1=无限，正整数=多级。 */
    private final int maxLevel;

    /** 每级消耗技能点（通常 1）。 */
    private final int cost;

    /** 效果列表（类型 + 参数）。 */
    private final List<SkillEffectSpec> effects;

    /** 归属物种标识列表（开放集合，来自物种注册表）；空 = 全物种共用（主干）。 */
    private final List<ResourceLocation> species;

    public Skill(ResourceLocation id,
                 String nameKey,
                 String descriptionKey,
                 int tier,
                 List<ResourceLocation> requires,
                 ResourceLocation levelGate,
                 int maxLevel,
                 int cost,
                 List<SkillEffectSpec> effects,
                 List<ResourceLocation> species) {
        this.id = id;
        this.nameKey = nameKey;
        this.descriptionKey = descriptionKey;
        this.tier = tier;
        this.requires = requires == null ? Collections.emptyList() : requires;
        this.levelGate = levelGate;
        this.maxLevel = maxLevel;
        this.cost = cost;
        this.effects = effects == null ? Collections.emptyList() : effects;
        this.species = species == null ? Collections.emptyList() : species;
    }

    public ResourceLocation getId() {
        return id;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    public int getTier() {
        return tier;
    }

    public List<ResourceLocation> getRequires() {
        return requires;
    }

    /** 等级门限前置技能；{@code null} = 无等级跟随约束。 */
    public ResourceLocation getLevelGate() {
        return levelGate;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    /** 是否无限技能（maxLevel == -1，不计入毕业点求和）。 */
    public boolean isInfinite() {
        return maxLevel == -1;
    }

    public int getCost() {
        return cost;
    }

    public List<SkillEffectSpec> getEffects() {
        return effects;
    }

    /** 归属物种列表；空 = 主干（全物种共用）。 */
    public List<ResourceLocation> getSpecies() {
        return species;
    }

    /** 判断某物种是否可见此技能（空 species = 主干，全可见）。 */
    public boolean availableTo(ResourceLocation speciesId) {
        if (species.isEmpty()) {
            return true;
        }
        return species.contains(speciesId);
    }

    /**
     * 单个效果的定义（JSON 的 effects 数组里的一项）。
     * 类型 + 参数（参数是自由 JSON，由对应效果类型自行解析）。
     */
    public static final class SkillEffectSpec {
        private final ResourceLocation type;
        private final JsonObject params;

        public SkillEffectSpec(ResourceLocation type, JsonObject params) {
            this.type = type;
            this.params = params == null ? new JsonObject() : params;
        }

        public ResourceLocation getType() {
            return type;
        }

        public JsonObject getParams() {
            return params;
        }
    }
}
