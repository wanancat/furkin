package com.wanancat.furkin.api;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Optional;

/**
 * furkin 公开 API 静态入口（设计稿 §4）。
 *
 * <p>第三方模组通过本类注册物种、查询物种、查询版本。所有方法均为静态，
 * 与 internal 实现解耦。</p>
 *
 * <p>本类处于 {@code api} 包，属「对外识别型」命名（设计稿 §9 判据②）。</p>
 */
public final class FurkinApi {

    private FurkinApi() {
    }

    /**
     * 注册一个可契约物种。
     *
     * @param id         物种标识（命名空间 + 名称，如 {@code yourmod:fox}）
     * @param entityType 对应的实体类型
     * @param nameKey    显示名本地化 key
     * @return 注册后的物种对象
     */
    public static FurkinSpecies registerSpecies(ResourceLocation id, EntityType<?> entityType, String nameKey) {
        return FurkinSpeciesRegistry.register(new FurkinSpecies(id, entityType, nameKey));
    }

    /**
     * 查询某实体类型是否已注册为可契约物种。
     */
    public static boolean isRegistered(EntityType<?> entityType) {
        return FurkinSpeciesRegistry.isRegistered(entityType);
    }

    /**
     * 按物种 ID 查询物种。
     */
    public static Optional<FurkinSpecies> getSpecies(ResourceLocation id) {
        return FurkinSpeciesRegistry.byId(id);
    }
}
