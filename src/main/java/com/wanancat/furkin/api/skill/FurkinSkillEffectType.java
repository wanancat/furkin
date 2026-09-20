package com.wanancat.furkin.api.skill;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 技能效果类型 —— 开放注册表的一个条目（设计稿 §4）。
 *
 * <p>一个效果类型绑定一个 {@code ResourceLocation} 标识 + 一段 Java 逻辑
 * （由 {@link com.wanancat.furkin.internal.skill.SkillEffect} 实现）。
 * 内置四类：{@code attribute} / {@code ability} / {@code passive} / {@code interaction}，
 * 第三方可注册自己的效果类型。</p>
 *
 * <p>与物种注册（{@code FurkinSpeciesRegistry}）同构：开放集合，不用硬编码枚举。</p>
 */
public final class FurkinSkillEffectType {

    /** 效果类型标识（命名空间 + 名称，如 {@code furkin:attribute}）。 */
    private final ResourceLocation id;

    public FurkinSkillEffectType(ResourceLocation id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public ResourceLocation getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FurkinSkillEffectType that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "FurkinSkillEffectType{" + id + '}';
    }
}
