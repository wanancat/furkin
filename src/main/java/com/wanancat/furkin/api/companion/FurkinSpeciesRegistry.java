package com.wanancat.furkin.api.companion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生物注册表 —— 可契约物种的开放集合（设计稿 §4）。
 *
 * <p>第三方模组通过 {@link com.wanancat.furkin.api.FurkinApi} 暴露的静态方法
 * 注册自己的物种。猫狗是本模组内置注册的前两个物种，与第三方走完全相同的路径。</p>
 *
 * <p>线程安全：注册可能发生在模组加载阶段（单线程），查询发生在运行时（多线程），
 * 用 {@link ConcurrentHashMap} 兜底。</p>
 */
public final class FurkinSpeciesRegistry {

    /** 物种 ID → 物种。 */
    private static final Map<ResourceLocation, FurkinSpecies> BY_ID = new ConcurrentHashMap<>();

    /** EntityType → 物种（查询用）。 */
    private static final Map<EntityType<?>, FurkinSpecies> BY_ENTITY_TYPE = new ConcurrentHashMap<>();

    private FurkinSpeciesRegistry() {
    }

    /**
     * 注册一个物种。重复注册同一物种 ID 会覆盖（幂等）。
     *
     * @return 被注册的物种对象
     */
    public static FurkinSpecies register(FurkinSpecies species) {
        BY_ID.put(species.getId(), species);
        BY_ENTITY_TYPE.put(species.getEntityType(), species);
        return species;
    }

    /**
     * 按物种 ID 查询。
     */
    public static Optional<FurkinSpecies> byId(ResourceLocation id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * 按实体类型查询。
     */
    public static Optional<FurkinSpecies> byEntityType(EntityType<?> entityType) {
        return Optional.ofNullable(BY_ENTITY_TYPE.get(entityType));
    }

    /**
     * 判断某实体类型是否已注册为可契约物种。
     */
    public static boolean isRegistered(EntityType<?> entityType) {
        return BY_ENTITY_TYPE.containsKey(entityType);
    }

    /**
     * 判断某实体是否为已注册物种（等价于其 {@link EntityType} 已注册）。
     */
    public static boolean isRegisteredEntity(net.minecraft.world.entity.Entity entity) {
        return isRegistered(entity.getType());
    }
}
