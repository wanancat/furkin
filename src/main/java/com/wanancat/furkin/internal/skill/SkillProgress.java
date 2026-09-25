package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.contract.FurkinCompanionManager;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 技能点结算 —— 加点校验、洗点退点（设计稿 §3.2）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>{@link #tryUnlock} 加点：校验（归属/已召唤/技能存在/物种可见/前置满足/点数够/未满级）
 *       → 扣点 → 写 {@code skillLevels} → 挂效果 → 回写档案 → 广播。</li>
 *   <li>{@link #resetSkills} 洗点：清空 {@code skillLevels} → 移除效果 → 按实际累计支付点数退点。</li>
 * </ul>
 */
public final class SkillProgress {

    private SkillProgress() {
    }

    /** 加点结果。 */
    public enum Result {
        OK,
        NOT_FOUND,        // 宠物不存在
        NOT_OWNER,        // 非本人
        NOT_SUMMONED,     // 未召唤
        SKILL_UNKNOWN,    // 技能不存在
        SPECIES_MISMATCH, // 物种不可见此技能
        PREREQUISITES,    // 前置不满足
        NOT_ENOUGH_POINTS,// 技能点不足
        MAXED             // 已满级
    }

    /**
     * 给某只在场绒亲的某技能投 1 级。
     *
     * @param player      操作的玩家（须是主人）
     * @param companionId 宠物身份
     * @param skillId     技能标识
     * @param tree        技能树（加载好的只读视图）
     */
    public static Result tryUnlock(ServerPlayer player, UUID companionId, ResourceLocation skillId, SkillTree tree) {
        ServerLevel level = player.serverLevel();
        FurkinArchiveData archive = FurkinArchiveData.get(level);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Result.NOT_FOUND;
        }
        if (!player.getUUID().equals(entry.getOwnerUuid())) {
            return Result.NOT_OWNER;
        }
        if (!entry.isSummoned()) {
            return Result.NOT_SUMMONED;
        }

        LivingEntity target = findLivingByCompanionId(level, companionId);
        if (target == null) {
            return Result.NOT_FOUND;
        }
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            return Result.NOT_FOUND;
        }

        Skill skill = tree.get(skillId).orElse(null);
        if (skill == null) {
            return Result.SKILL_UNKNOWN;
        }

        FurkinSpecies species = FurkinSpeciesRegistry.byEntityType(target.getType()).orElse(null);
        ResourceLocation speciesId = species == null ? null : species.getId();
        if (!skill.availableTo(speciesId)) {
            return Result.SPECIES_MISMATCH;
        }

        int current = data.getSkillLevels().getOrDefault(skillId, 0);
        if (!skill.isInfinite() && current >= skill.getMaxLevel()) {
            return Result.MAXED;
        }

        // 前置校验（含等级门限）：升到 current+1 级需满足。
        if (!tree.prerequisitesMet(skillId, data.getSkillLevels(), current + 1)) {
            return Result.PREREQUISITES;
        }

        if (data.getSkillPoints() < skill.getCost()) {
            return Result.NOT_ENOUGH_POINTS;
        }

        // 旧档没有实际支付表时先按当前定义迁移；成功加点后只追加本次实际支付成本。
        if (!data.hasKnownSkillInvestments()) {
            migrateInvestments(tree, data.getSkillLevels(), data.getSkillInvestments());
            data.setSkillInvestmentsKnown(true);
        }

        // 落账：扣点 + 升级 + 累计实付成本 + 挂效果。
        data.setSkillPoints(data.getSkillPoints() - skill.getCost());
        int newLevel = current + 1;
        data.getSkillLevels().put(skillId, newLevel);
        data.getSkillInvestments().merge(skillId, skill.getCost(), Integer::sum);
        SkillEffectApplier.applySkill(target, tree, skillId, newLevel);

        // 行囊格数是「invested 等级的派生值」，投了 travel_pouch 就即时刷新容量
        // （只扩不缩，故此处安全；缩容与超格掉落属批 2 第 2 步）。
        data.refreshPouchSize();

        // 回写档案（技能快照 + 技能点），使收回/召唤跨实体持久化一致。
        syncToArchive(target, data, archive, entry);

        // 广播能力数据到客户端。
        FurkinNetwork.channel().send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
                new SyncFurkinDataPacket(target.getId(), data.syncNBT()));

        FurkinMod.LOGGER.info("Furkin skill unlocked: id={} skill={} -> Lv.{}",
                companionId, skillId, newLevel);
        return Result.OK;
    }

    /**
     * 洗点：清空全部技能 + 按实际累计支付点数全额退点。
     *
     * <p>目标须在场（摘效果需要实体引用）。未召唤时退化为清档案快照 + 退点。</p>
     *
     * @return 退还的技能点数（未召唤且档案有技能时也返回退点数；找不到/非本人返回 0）。
     */
    public static int resetSkills(ServerPlayer player, UUID companionId, SkillTree tree) {
        ServerLevel level = player.serverLevel();
        FurkinArchiveData archive = FurkinArchiveData.get(level);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null || !player.getUUID().equals(entry.getOwnerUuid())) {
            return 0;
        }

        LivingEntity target = findLivingByCompanionId(level, companionId);
        if (target != null) {
            FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
            if (data != null) {
                SkillEffectApplier.clearAll(target, tree, data.getSkillLevels());
                if (!data.hasKnownSkillInvestments()) {
                    migrateInvestments(tree, data.getSkillLevels(), data.getSkillInvestments());
                    data.setSkillInvestmentsKnown(true);
                }
                int refund = totalInvestments(data.getSkillInvestments());
                data.setSkillPoints(data.getSkillPoints() + refund);
                data.getSkillLevels().clear();
                data.getSkillInvestments().clear();
                // 技能已清空 ⇒ 其产出节拍记录随之作废。不清的话，玩家重学该技能时那条过期
                // 记录会立刻命中，绕过「首次只布计时、不产出」的保证（症状 = 刚学就产一份）。
                SkillPassiveDispatcher.clearPeriodicTimers(data);
                // 容量是 travel_pouch 等级的派生值：技能清空后归 0（缩容），被挤出的物品
                // 倒在宠物脚下（D6「只在场才有包裹」）—— 容器层不提供静默吞物品的路径。
                PouchDrop.dropStacks(target, data.resizePouchToLevel());
                syncToArchive(target, data, archive, entry);
                FurkinNetwork.channel().send(
                        PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
                        new SyncFurkinDataPacket(target.getId(), data.syncNBT()));
                return refund;
            }
        } else {
            // 未召唤：技能等级在档案 skillSnapshot 里，清快照 + 退实际支付点数。
            Map<ResourceLocation, Integer> archivedLevels = levelsFromSnapshot(entry.getSkillSnapshot());
            if (!entry.hasKnownSkillInvestments()) {
                migrateInvestments(tree, archivedLevels, entry.getSkillInvestments());
                entry.setSkillInvestmentsKnown(true);
            }
            int refund = totalInvestments(entry.getSkillInvestments());
            entry.setSkillPoints(entry.getSkillPoints() + refund);
            entry.setSkillSnapshot(new CompoundTag());
            entry.getSkillInvestments().clear();
            archive.putEntry(entry);
            return refund;
        }
        return 0;
    }

    /** 判断某绒亲是否为「本人契约且已召唤」——洗点等动作的前置校验（避免白耗道具）。 */
    public static boolean isOwnedAndSummoned(ServerPlayer player, UUID companionId) {
        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        return entry != null
                && player.getUUID().equals(entry.getOwnerUuid())
                && entry.isSummoned();
    }

    /** 读取某绒亲当前技能点数（优先在场实体能力，未召唤则读档案）。 */
    public static int skillPointsOf(ServerPlayer player, UUID companionId) {
        LivingEntity target = findLivingByCompanionId(player.serverLevel(), companionId);
        if (target != null) {
            FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
            if (data != null) {
                return data.getSkillPoints();
            }
        }
        FurkinArchiveData archive = FurkinArchiveData.get(player.serverLevel());
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        return entry == null ? 0 : entry.getSkillPoints();
    }

    /** 把能力对象里的技能等级 + 技能点同步回写档案（与 dismiss 的快照口径一致）。 */
    private static void syncToArchive(LivingEntity target, FurkinData data,
                                      FurkinArchiveData archive, FurkinArchiveEntry entry) {
        entry.setLevel(data.getLevel());
        entry.setXp(data.getXp());
        entry.setSkillPoints(data.getSkillPoints());
        entry.setSkillSnapshot(data.syncNBT().getCompound("skill_levels"));
        entry.setSkillInvestments(data.getSkillInvestments());
        entry.setSkillInvestmentsKnown(data.hasKnownSkillInvestments());
        archive.putEntry(entry);
    }

    /** 从档案技能快照反解技能等级，用于旧档实付成本迁移。 */
    private static Map<ResourceLocation, Integer> levelsFromSnapshot(CompoundTag snapshot) {
        Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
        if (snapshot == null) {
            return levels;
        }
        for (String key : snapshot.getAllKeys()) {
            levels.put(new ResourceLocation(key), snapshot.getInt(key));
        }
        return levels;
    }

    /**
     * 旧档缺少实际支付表时的一次性迁移。优先使用当前技能定义的成本；
     * 技能定义已删除时无法还原历史成本，按每级 1 点保守兜底并留 WARN。
     */
    private static void migrateInvestments(SkillTree tree,
                                           Map<ResourceLocation, Integer> levels,
                                           Map<ResourceLocation, Integer> investments) {
        investments.clear();
        for (Map.Entry<ResourceLocation, Integer> entry : levels.entrySet()) {
            ResourceLocation skillId = entry.getKey();
            Skill skill = tree.get(skillId).orElse(null);
            int cost;
            if (skill == null) {
                cost = 1;
                FurkinMod.LOGGER.warn(
                        "Skill investment migration for {}: definition is missing; assuming cost 1",
                        skillId);
            } else {
                cost = skill.getCost();
            }
            long paid = (long) Math.max(0, entry.getValue()) * Math.max(1, cost);
            if (paid > Integer.MAX_VALUE) {
                FurkinMod.LOGGER.warn(
                        "Skill investment migration for {} overflows int: {}; clamping", skillId, paid);
                paid = Integer.MAX_VALUE;
            }
            investments.put(skillId, (int) paid);
        }
    }

    /** 汇总实际累计支付点数；损坏数据溢出时钳制到 int 上限并留 WARN。 */
    private static int totalInvestments(Map<ResourceLocation, Integer> investments) {
        long total = 0L;
        for (int value : investments.values()) {
            total += Math.max(0, value);
        }
        if (total > Integer.MAX_VALUE) {
            FurkinMod.LOGGER.warn("Skill investment total overflows int: {}; clamping", total);
            return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    /** 按服务器级档案的 UUID / 维度定向查找在场绒亲实体。 */
    private static LivingEntity findLivingByCompanionId(ServerLevel level, UUID companionId) {
        return FurkinCompanionManager.findLivingByCompanionId(level, companionId);
    }
}
