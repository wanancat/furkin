package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * WP-09：按档案记录的实体 UUID + 维度定向定位已召唤绒亲。
 *
 * <p>只调用 {@link ServerLevel#getEntity(UUID)} 的索引查询，不遍历实体列表、
 * 不加载区块。记录维度未加载或已失效时，仅对服务器当前已加载维度各查询一次。</p>
 */
public final class FurkinEntityLocator {

    private FurkinEntityLocator() {
    }

    /**
     * 解析档案对应的在场实体。
     *
     * @param server 当前服务器
     * @param entry  已召唤绒亲档案
     * @return 命中且身份一致的实体；无 UUID、维度未加载、索引未命中或身份冲突时返回 null
     */
    @Nullable
    public static LivingEntity locate(MinecraftServer server, FurkinArchiveEntry entry) {
        if (server == null || entry == null || !entry.isSummoned()) {
            return null;
        }
        UUID entityUuid = entry.getEntityUuid();
        if (entityUuid == null) {
            return null;
        }

        ResourceKey<Level> recordedDimension = entry.getEntityDimension();
        if (recordedDimension != null) {
            LivingEntity found = query(server.getLevel(recordedDimension), entityUuid, entry);
            if (found != null) {
                return found;
            }
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (recordedDimension != null && level.dimension().equals(recordedDimension)) {
                continue;
            }
            LivingEntity found = query(level, entityUuid, entry);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static LivingEntity query(@Nullable ServerLevel level, UUID entityUuid,
                                      FurkinArchiveEntry entry) {
        if (level == null) {
            return null;
        }
        Entity entity = level.getEntity(entityUuid);
        if (!(entity instanceof LivingEntity living)) {
            return null;
        }
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return null;
        }
        UUID actualCompanionId = data.getCompanionId();
        // 允许 companionId 为空：上一次清理可能在末段失败，UUID 本身仍能唯一锁定原实体；
        // 统一清理管线会继续完成剩余步骤。
        if (actualCompanionId == null || actualCompanionId.equals(entry.getCompanionId())) {
            return living;
        }
        return null;
    }
}
