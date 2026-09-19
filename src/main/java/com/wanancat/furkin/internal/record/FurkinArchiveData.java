package com.wanancat.furkin.internal.record;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 绒亲录 —— 世界级 SavedData，宠物数据的唯一档案（设计稿 §2.2）。
 *
 * <p>主键＝宠物身份 UUID（一宠一条）。实体在场时以 capability 为运行时真相，
 * 实体不在场（死亡 / 收回）时以本档案为真相。数据归属三层之一。</p>
 */
public final class FurkinArchiveData extends SavedData {

    /** SavedData 标识。 */
    public static final String NAME = "furkin_archive";

    /** 宠物身份 UUID → 档案条目。 */
    private final Map<UUID, FurkinArchiveEntry> entries = new HashMap<>();

    /** 从 NBT 加载（读档时）。 */
    public static FurkinArchiveData load(CompoundTag tag) {
        FurkinArchiveData data = new FurkinArchiveData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            FurkinArchiveEntry entry = FurkinArchiveEntry.deserializeNBT(list.getCompound(i));
            data.entries.put(entry.getCompanionId(), entry);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (FurkinArchiveEntry entry : entries.values()) {
            list.add(entry.serializeNBT());
        }
        tag.put("entries", list);
        return tag;
    }

    /** 获取档案条目（不存在返回 null）。 */
    @Nullable
    public FurkinArchiveEntry getEntry(UUID companionId) {
        return entries.get(companionId);
    }

    /** 全部档案条目（只读视图）。 */
    public java.util.Collection<FurkinArchiveEntry> allEntries() {
        return entries.values();
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
     * 取指定世界的绒亲录实例（不存在则创建）。
     */
    public static FurkinArchiveData get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(FurkinArchiveData::load, FurkinArchiveData::new, NAME);
    }

    /**
     * 取服务器的绒亲录实例（取主世界 overworld）。
     */
    public static FurkinArchiveData get(MinecraftServer server) {
        return get(server.overworld());
    }
}
