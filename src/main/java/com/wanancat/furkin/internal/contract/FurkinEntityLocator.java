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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WP-09：按档案记录的实体 UUID + 维度定向定位已召唤绒亲。
 *
 * <p>普通定位只调用 {@link ServerLevel#getEntity(UUID)} 的索引查询，不遍历实体列表、
 * 不加载区块。记录维度未加载或已失效时，仅对服务器当前已加载维度各查询一次。
 * {@link #findAllLoaded(MinecraftServer, FurkinArchiveEntry)} 是显式修复诊断用的全量扫描，
 * 禁止进入 summon、tick、入世或传送热路径。</p>
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
        return findLoadedByRecordedUuid(server, entry);
    }

    /**
     * 只按档案记录 UUID 查询已加载实体，不要求档案当前标为已召唤。
     *
     * <p>P0 用它守卫“档案标未召唤、实体却仍加载”的不一致状态；P1 还可复用该只读查询
     * 做 canonical 诊断。此方法不加载区块，也不修改任何档案。</p>
     */
    @Nullable
    public static LivingEntity findLoadedByRecordedUuid(MinecraftServer server, FurkinArchiveEntry entry) {
        if (server == null || entry == null) {
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

    /**
     * 扫描当前已加载维度，返回同 companionId 的全部存活候选实体。
     *
     * <p>仅允许 {@code repair list} / {@code repair choose} 调用；普通召唤、传送、入世和
     * tick 路径不得调用该全量扫描。结果以 canonical 优先，其余按实体 UUID 字符串排序，
     * 保证输出可复现。</p>
     */
    public static List<LivingEntity> findAllLoaded(MinecraftServer server, FurkinArchiveEntry entry) {
        if (server == null || entry == null || entry.getCompanionId() == null) {
            return List.of();
        }

        UUID companionId = entry.getCompanionId();
        UUID canonicalUuid = entry.getEntityUuid();
        List<LivingEntity> candidates = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                if (!living.isAlive()) {
                    continue;
                }
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data == null) {
                    continue;
                }
                UUID actualCompanionId = data.getCompanionId();
                if (companionId.equals(actualCompanionId)
                        || (actualCompanionId == null && canonicalUuid != null
                        && canonicalUuid.equals(living.getUUID()))) {
                    candidates.add(living);
                }
            }
        }

        candidates.sort((left, right) -> {
            boolean leftCanonical = canonicalUuid != null && canonicalUuid.equals(left.getUUID());
            boolean rightCanonical = canonicalUuid != null && canonicalUuid.equals(right.getUUID());
            if (leftCanonical != rightCanonical) {
                return leftCanonical ? -1 : 1;
            }
            return left.getUUID().toString().compareTo(right.getUUID().toString());
        });
        return candidates;
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
