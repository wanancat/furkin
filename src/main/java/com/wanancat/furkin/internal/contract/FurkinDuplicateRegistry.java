package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 已加载绒亲实体的内存索引。
 *
 * <p>该索引只服务重复体检测和 far-summon 重建守卫：实体入世时按
 * {@code companionId} 登记，实体离场或身份被清理时移除。查询时再把当前档案的
 * canonical UUID 排除，因此既能发现同身份的其它实体，也不会把合法 canonical
 * 自身误报为重复体。</p>
 *
 * <p>索引不持久化，不自动删除实体，也不替代 P1 的显式修复命令。所有读写都应由
 * 服务端线程执行。</p>
 */
public final class FurkinDuplicateRegistry {

    private static final Map<UUID, Set<UUID>> LOADED_ENTITIES = new HashMap<>();

    private FurkinDuplicateRegistry() {
    }

    /**
     * 实体入世时登记其 {@code companionId}。档案的 summoned 状态不参与登记：
     * {@code summoned=false} 时若仍有同身份实体入世，本身就是需要安全失败的孤儿状态。
     */
    public static void onEntityJoin(LivingEntity entity, FurkinArchiveEntry entry) {
        if (entity == null || entry == null || !entry.isAlive()) {
            return;
        }
        UUID companionId = entry.getCompanionId();
        if (companionId == null) {
            return;
        }
        FurkinData data = entity.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !companionId.equals(data.getCompanionId())) {
            return;
        }
        LOADED_ENTITIES.computeIfAbsent(companionId, ignored -> new HashSet<>())
                .add(entity.getUUID());
    }

    /** 实体离场、死亡或身份被清理时移除登记。 */
    public static void onEntityLeave(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        FurkinData data = entity.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || data.getCompanionId() == null) {
            return;
        }
        remove(data.getCompanionId(), entity.getUUID());
    }

    /** 清理流程已确认移除某实体身份时调用，避免实体仍加载但 capability 已清空造成假阳性。 */
    public static void onCompanionCleared(UUID companionId, UUID entityUuid) {
        remove(companionId, entityUuid);
    }

    /**
     * 只检查登记过的 UUID 是否仍在当前已加载维度中，并排除档案当前 canonical UUID。
     *
     * <p>{@code entityUuid == null} 表示档案没有合法 canonical；此时任何同
     * {@code companionId} 的已加载实体都属于冲突，不能用于重建。</p>
     */
    public static boolean hasLoadedDuplicate(MinecraftServer server, FurkinArchiveEntry entry) {
        if (server == null || entry == null || entry.getCompanionId() == null) {
            return false;
        }
        UUID companionId = entry.getCompanionId();
        UUID canonicalUuid = entry.getEntityUuid();
        Set<UUID> registered = LOADED_ENTITIES.get(companionId);
        if (registered == null || registered.isEmpty()) {
            LOADED_ENTITIES.remove(companionId);
            return false;
        }

        boolean found = false;
        Iterator<UUID> iterator = registered.iterator();
        while (iterator.hasNext()) {
            UUID entityUuid = iterator.next();
            LivingEntity loaded = findLoaded(server, entityUuid);
            if (loaded == null) {
                iterator.remove();
                continue;
            }
            FurkinData data = loaded.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
            if (data == null || !companionId.equals(data.getCompanionId())) {
                iterator.remove();
                continue;
            }
            if (canonicalUuid == null || !canonicalUuid.equals(entityUuid)) {
                found = true;
            }
        }
        if (registered.isEmpty()) {
            LOADED_ENTITIES.remove(companionId);
        }
        return found;
    }

    /** 服务端停止时释放全部内存状态。 */
    public static void clear(MinecraftServer server) {
        LOADED_ENTITIES.clear();
    }

    /** 删除或强制解绑某身份时清空其全部诊断登记。 */
    public static void clearCompanion(UUID companionId) {
        if (companionId != null) {
            LOADED_ENTITIES.remove(companionId);
        }
    }

    private static void remove(UUID companionId, UUID entityUuid) {
        if (companionId == null || entityUuid == null) {
            return;
        }
        Set<UUID> registered = LOADED_ENTITIES.get(companionId);
        if (registered == null) {
            return;
        }
        registered.remove(entityUuid);
        if (registered.isEmpty()) {
            LOADED_ENTITIES.remove(companionId);
        }
    }

    private static LivingEntity findLoaded(MinecraftServer server, UUID entityUuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityUuid);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }
}