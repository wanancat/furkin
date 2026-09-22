package com.wanancat.furkin.internal.record;

import com.wanancat.furkin.internal.item.FurkinRecordItem;
import com.wanancat.furkin.internal.network.RecordListPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.UUID;

/**
 * 绒亲列表的统一显示排序（口径唯一真源）。
 *
 * <p><b>口径（2026-09-22 乌狸定）：物种 &gt; 等级（降序）&gt; id（升序）。</b></p>
 *
 * <p>物种键取 {@code EntityType} 的注册名（{@code furkin:cat} / {@code minecraft:wolf}），
 * <b>不是</b>本地化显示名 —— 按 key 排不受客户端语言影响，且同物种必相邻。
 * 第三级 id 升序是<b>稳定性兜底</b>：前两级相同的条目若不定序，会退化成
 * {@code HashMap} 的哈希顺序（每次登录都可能变），看着像抖动。</p>
 *
 * <p>为什么要有这个类：同一口径有<b>两个消费方</b> —— 命令 {@code /furkin list}
 * 排的是 {@link FurkinArchiveEntry}（档案侧），绒亲录界面排的是
 * {@link RecordListPacket.Entry}（网络 DTO 侧）。两边字段形态不同，
 * 但比较逻辑必须一致，故在此集中定义两个重载，<b>任何口径调整只改这里</b>。</p>
 */
public final class FurkinDisplayOrder {

    private FurkinDisplayOrder() {
    }

    /** 档案条目排序（{@code /furkin list} 用）。 */
    public static final Comparator<FurkinArchiveEntry> ARCHIVE =
            Comparator.comparing((FurkinArchiveEntry e) -> speciesKey(e.getSpecies()))
                    .thenComparing(FurkinArchiveEntry::getLevel, Comparator.reverseOrder())
                    .thenComparing(e -> e.getCompanionId().toString());

    /** 绒亲录列表排序（网络 DTO 侧用）。物种键已是下发的注册名，无需再解析。 */
    public static final Comparator<RecordListPacket.Entry> RECORD_ENTRY =
            Comparator.comparing(RecordListPacket.Entry::getSpeciesName,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(RecordListPacket.Entry::getLevel, Comparator.reverseOrder())
                    .thenComparing(e -> e.getCompanionId().toString());

    /**
     * 物种排序键：EntityType 的注册名；未知物种回退空串（排最前，不抛异常）。
     *
     * <p>未注册时 {@link ForgeRegistries#ENTITY_TYPES}{@code .getKey} 返回 {@code null}，
     * 此时用空串而非 {@code null} —— 避免比较器 NPE。</p>
     */
    private static String speciesKey(EntityType<?> species) {
        if (species == null) {
            return "";
        }
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(species);
        return key == null ? "" : key.toString();
    }
}
