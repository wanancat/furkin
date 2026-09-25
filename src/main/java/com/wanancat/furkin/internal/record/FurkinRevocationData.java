package com.wanancat.furkin.internal.record;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * WP-02B：服务器级注销墓碑。
 *
 * <p>强制解绑在实体当前不可解析时写入本数据；原实体以后任意维度入世时，
 * 由 {@code EntityJoinLevelEvent} 按能力中的 {@code companionId} 命中并执行延迟清理。
 * 本类只保存诊断所需的最小字段，不保存实体列表、物品内容或 tick 任务。</p>
 *
 * <p>数据锚定主世界 {@link MinecraftServer#overworld()}，因此不随玩家或实体所在维度分裂。</p>
 */
public final class FurkinRevocationData extends SavedData {

    /** SavedData 标识。 */
    public static final String NAME = "furkin_revocation";

    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_COMPANION_ID = "companion_id";
    private static final String KEY_OWNER_UUID = "owner_uuid";
    private static final String KEY_REQUESTED_AT_GAME_TIME = "requested_at_game_time";

    /** companionId → 注销墓碑。 */
    private final Map<UUID, Revocation> entries = new HashMap<>();

    /** 从 NBT 加载（读档时）。 */
    public static FurkinRevocationData load(CompoundTag tag) {
        FurkinRevocationData data = new FurkinRevocationData();
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (!entryTag.hasUUID(KEY_COMPANION_ID)) {
                FurkinMod.LOGGER.warn(
                        "Skipping invalid furkin revocation entry at index {}: missing companion UUID",
                        i);
                continue;
            }

            UUID companionId = entryTag.getUUID(KEY_COMPANION_ID);
            UUID ownerUuid = null;
            if (entryTag.hasUUID(KEY_OWNER_UUID)) {
                ownerUuid = entryTag.getUUID(KEY_OWNER_UUID);
            } else {
                FurkinMod.LOGGER.warn(
                        "Furkin revocation {} is missing owner UUID; keeping it for cleanup",
                        companionId);
            }

            long requestedAtGameTime = entryTag.contains(KEY_REQUESTED_AT_GAME_TIME, Tag.TAG_LONG)
                    ? entryTag.getLong(KEY_REQUESTED_AT_GAME_TIME)
                    : 0L;
            data.entries.put(companionId,
                    new Revocation(companionId, ownerUuid, requestedAtGameTime));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Revocation revocation : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(KEY_COMPANION_ID, revocation.companionId());
            if (revocation.ownerUuid() != null) {
                entryTag.putUUID(KEY_OWNER_UUID, revocation.ownerUuid());
            }
            entryTag.putLong(KEY_REQUESTED_AT_GAME_TIME, revocation.requestedAtGameTime());
            list.add(entryTag);
        }
        tag.put(KEY_ENTRIES, list);
        return tag;
    }

    /** 查询墓碑；不存在返回 {@code null}。 */
    @Nullable
    public Revocation get(UUID companionId) {
        if (companionId == null) {
            return null;
        }
        return entries.get(companionId);
    }

    /** 是否存在某身份的注销墓碑。 */
    public boolean contains(UUID companionId) {
        return companionId != null && entries.containsKey(companionId);
    }

    /** 全部墓碑（只读视图）。 */
    public Collection<Revocation> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** 新增或覆盖墓碑，并标记脏。 */
    public void put(UUID companionId, @Nullable UUID ownerUuid, long requestedAtGameTime) {
        Objects.requireNonNull(companionId, "companionId");
        entries.put(companionId, new Revocation(companionId, ownerUuid, requestedAtGameTime));
        setDirty();
    }

    /** 移除墓碑，返回是否实际移除；移除时标记脏。 */
    public boolean remove(UUID companionId) {
        if (companionId == null || entries.remove(companionId) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * 取服务器的注销墓碑实例（固定锚定主世界，不存在则创建）。
     */
    public static FurkinRevocationData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage()
                .computeIfAbsent(FurkinRevocationData::load, FurkinRevocationData::new, NAME);
    }

    /** 单条注销墓碑；字段不可变。 */
    public record Revocation(UUID companionId, @Nullable UUID ownerUuid,
                             long requestedAtGameTime) {
        public Revocation {
            Objects.requireNonNull(companionId, "companionId");
        }
    }
}