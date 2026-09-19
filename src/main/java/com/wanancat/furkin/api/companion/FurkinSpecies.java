package com.wanancat.furkin.api.companion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Objects;

/**
 * 物种标识 —— 开放集合的载体（设计稿 §4）。
 *
 * <p>一个 {@link FurkinSpecies} 绑定一个 {@link EntityType}，声明该实体类型
 * 可被契约成为伴侣。物种分支（战斗 / 侦察）等属性在技能定义里声明，
 * 此处只承载「什么实体类型是可契约物种」这一最小事实。</p>
 *
 * <p>注册粒度按 {@link EntityType}（设计稿 §4 关键约束），非「生物族 / tag」。</p>
 */
public final class FurkinSpecies {

    /** 物种标识（命名空间 + 名称）。 */
    private final ResourceLocation id;

    /** 对应的实体类型。 */
    private final EntityType<?> entityType;

    /** 物种显示名本地化 key（如 {@code furkin.species.cat}）。 */
    private final String nameKey;

    public FurkinSpecies(ResourceLocation id, EntityType<?> entityType, String nameKey) {
        this.id = Objects.requireNonNull(id, "id");
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.nameKey = Objects.requireNonNull(nameKey, "nameKey");
    }

    public ResourceLocation getId() {
        return id;
    }

    public EntityType<?> getEntityType() {
        return entityType;
    }

    public String getNameKey() {
        return nameKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FurkinSpecies that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "FurkinSpecies{" + id + " -> " + entityType + '}';
    }
}
