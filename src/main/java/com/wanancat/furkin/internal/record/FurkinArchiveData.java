package com.wanancat.furkin.internal.record;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 绒亲录 —— 世界级 SavedData，宠物数据的唯一档案（设计稿 §2.2）。
 *
 * <p>主键＝宠物身份 UUID（一宠一条）。实体在场时以 capability 为运行时真相，
 * 实体不在场（死亡 / 收回）时以本档案为真相。数据归属三层之一。</p>
 *
 * <p>WP-03：所有玩法路径统一使用主世界服务器级实例；旧版本按维度分裂的档案在首次
 * 读取时合并。同 ID 冲突无法安全判定新旧，因此保留主世界条目并记录 WARN，不静默覆盖。</p>
 */
public final class FurkinArchiveData extends SavedData {

    /** SavedData 标识。 */
    public static final String NAME = "furkin_archive";

    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final int CURRENT_DATA_VERSION = 1;

    /** 宠物身份 UUID → 档案条目。 */
    private final Map<UUID, FurkinArchiveEntry> entries = new HashMap<>();

    /** 数据版本；旧档没有该字段时视为 0。 */
    private int dataVersion;

    /** 从 NBT 加载（读档时）。 */
    public static FurkinArchiveData load(CompoundTag tag) {
        FurkinArchiveData data = new FurkinArchiveData();
        data.dataVersion = tag.getInt(KEY_DATA_VERSION);
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                FurkinArchiveEntry entry = FurkinArchiveEntry.deserializeNBT(list.getCompound(i));
                data.entries.put(entry.getCompanionId(), entry);
            } catch (Exception e) {
                FurkinMod.LOGGER.warn(
                        "Skipping invalid furkin archive entry at index {}: {}",
                        i, e.toString());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (FurkinArchiveEntry entry : entries.values()) {
            list.add(entry.serializeNBT());
        }
        tag.put(KEY_ENTRIES, list);
        tag.putInt(KEY_DATA_VERSION, dataVersion);
        return tag;
    }

    /** 获取档案条目（不存在返回 null）。 */
    @Nullable
    public FurkinArchiveEntry getEntry(UUID companionId) {
        return entries.get(companionId);
    }

    /** 全部档案条目（只读视图）。 */
    public java.util.Collection<FurkinArchiveEntry> allEntries() {
        return java.util.Collections.unmodifiableCollection(entries.values());
    }

    /** 是否存在某身份的档案。 */
    public boolean contains(UUID companionId) {
        return entries.containsKey(companionId);
    }

    /** 新增或覆盖档案条目，并标记脏。 */
    public void putEntry(FurkinArchiveEntry entry) {
        entries.put(entry.getCompanionId(), entry);
        setDirty();
    }

    /** 移除档案条目，并标记脏。 */
    public void removeEntry(UUID companionId) {
        entries.remove(companionId);
        setDirty();
    }

    /**
     * 兼容旧调用点：不再按传入维度读取，统一转发到服务器主世界档案。
     */
    public static FurkinArchiveData get(ServerLevel level) {
        return get(level.getServer());
    }

    /**
     * 取服务器的绒亲录实例（固定主世界 overworld）。
     *
     * <p>首次读取旧版本数据时，会扫描当前已加载的其它维度并合并旧档案。</p>
     */
    public static FurkinArchiveData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        FurkinArchiveData data = server.overworld().getDataStorage()
                .computeIfAbsent(FurkinArchiveData::load, FurkinArchiveData::new, NAME);
        data.migrateLegacyArchives(server);
        return data;
    }

    /**
     * 将旧版本按维度保存的档案合并到主世界实例。
     *
     * <p>迁移只在数据版本低于当前版本时执行。旧档文件保留作为回滚副本，不主动删除。
     * 同 ID 冲突没有可靠时间戳，保留主世界条目并记录诊断日志。</p>
     */
    private void migrateLegacyArchives(MinecraftServer server) {
        if (dataVersion >= CURRENT_DATA_VERSION) {
            return;
        }

        int imported = 0;
        int conflicts = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().equals(Level.OVERWORLD)) {
                continue;
            }

            FurkinArchiveData legacy = level.getDataStorage().get(FurkinArchiveData::load, NAME);
            if (legacy == null) {
                continue;
            }

            for (FurkinArchiveEntry entry : legacy.entries.values()) {
                UUID companionId = entry.getCompanionId();
                if (entries.containsKey(companionId)) {
                    conflicts++;
                    FurkinMod.LOGGER.warn(
                            "Furkin archive migration conflict: id={}, keeping overworld entry; legacy dimension={}",
                            companionId, level.dimension().location());
                } else {
                    entries.put(companionId, entry);
                    imported++;
                }
            }
        }

        dataVersion = CURRENT_DATA_VERSION;
        setDirty();
        if (imported > 0 || conflicts > 0) {
            FurkinMod.LOGGER.info("Furkin archive migration completed: imported={}, conflicts={}",
                    imported, conflicts);
        }
    }
}
