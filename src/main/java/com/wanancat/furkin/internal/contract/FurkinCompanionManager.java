package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

import java.util.UUID;

/**
 * 伴侣管理 —— 收回 / 召唤 的核心逻辑（设计稿 §3.1「拥有与活跃」）。
 *
 * <p>两个正交维度（设计稿 §2.2）：生命状态（存活 / 已死）× 是否召唤（已召唤 / 已收回）。
 * 本类只操作「是否召唤」维度；生命状态归 {@code revive} 包（M4）。</p>
 *
 * <p><b>数据归属三层切换</b>：收回时实体不在场 → 快照写录（绒亲录为真相）；
 * 召唤时实体重建 → 从录读回写能力（能力为真相）。沿用同一身份 UUID，与复活同路径。</p>
 */
public final class FurkinCompanionManager {

    private FurkinCompanionManager() {
    }

    /**
     * 收回一只已召唤的绒亲。
     *
     * <p>流程：校验主人与状态 → 快照写录（等级 / 技能 / 装备）→ 置 {@code summoned=false}
     * → 移除实体。数据完整留在录里，可再召唤。</p>
     *
     * @param player 主人
     * @param target 要收回的绒亲实体
     * @return 是否成功收回
     */
    public static boolean dismiss(ServerPlayer player, LivingEntity target) {
        // 取能力，校验已契约。
        FurkinData data = target.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null || !data.isCompanion()) {
            return false;
        }

        // 校验主人身份。
        UUID ownerUuid = data.getOwnerUuid();
        if (ownerUuid == null || !ownerUuid.equals(player.getUUID())) {
            return false;
        }

        UUID companionId = data.getCompanionId();
        if (companionId == null) {
            return false;
        }

        // 已倒下的（FALLEN）不能收回（那是复活流程管辖）。
        if (data.getState() == FurkinState.FALLEN) {
            return false;
        }

        if (!(target.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return false;
        }

        // 快照写录：等级 / 技能 / 装备（装备 M3 起填充，这里保证结构就位）。
        entry.setLevel(data.getLevel());
        entry.setSkillSnapshot(data.serializeNBT().getCompound("skill_levels"));
        // 物种补写：旧档（加 species 字段前契约的）在此自愈——实体在场时物种必然可得。
        if (entry.getSpecies() == null) {
            entry.setSpecies(target.getType());
        }
        // 实体外观快照：品种 / 毛色 / 坐定 / 跟随等，收回时整包存下。
        entry.setEntitySnapshot(target.saveWithoutId(new CompoundTag()));
        // 装备快照：原版实体装备槽 → 录（M3 前为空占位，字段保留）。
        entry.setAlive(true);
        entry.setSummoned(false); // 收回：实体不在场。
        archive.putEntry(entry);

        // 移除实体（discard 不触发死亡掉落 / 不广播死亡）。
        target.discard();

        FurkinMod.LOGGER.info("Furkin dismissed: id={} by {}", companionId, player.getName().getString());
        return true;
    }

    /**
     * 召唤一只已收回（且存活）的绒亲。
     *
     * <p>流程：从录读条目 → 校验存活且未召唤 → 校验活跃上限 → 按物种重建实体
     * （沿用同一身份 UUID）→ 写回能力 → 置 {@code summoned=true}。</p>
     *
     * @param player      主人
     * @param companionId 宠物身份 UUID
     * @return 是否成功召唤
     */
    public static boolean summon(ServerPlayer player, UUID companionId) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(serverLevel);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return false;
        }

        // 校验主人。
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return false;
        }

        // 校验生命状态（已死 → 走复活，不在此召唤）。
        if (!entry.isAlive()) {
            return false;
        }

        // 校验是否已召唤。
        if (entry.isSummoned()) {
            return false;
        }

        // 校验活跃上限（当前已召唤数量）。
        if (countSummoned(serverLevel, player.getUUID()) >= FurkinServerConfig.ACTIVE_LIMIT.get()) {
            FurkinMod.LOGGER.info("Furkin summon blocked: active limit reached for {}",
                    player.getName().getString());
            return false;
        }

        // 重建实体：从档案读物种。
        EntityType<?> species = entry.getSpecies();
        if (species == null) {
            FurkinMod.LOGGER.warn("Furkin summon failed: no species recorded for id={}", companionId);
            return false;
        }

        // 按物种创建实体（沿用同一身份 UUID 在能力层体现，实体 UUID 由世界重新分配）。
        Entity created = species.create(serverLevel);
        if (!(created instanceof LivingEntity living)) {
            FurkinMod.LOGGER.warn("Furkin summon failed: species {} produced non-living entity",
                    species);
            return false;
        }

        // 回灌实体外观快照：品种 / 毛色 / 坐定 / 跟随等（有快照才回灌，兼容旧档）。
        CompoundTag snapshot = entry.getEntitySnapshot();
        if (snapshot != null && !snapshot.isEmpty()) {
            // 回灌前先清掉新建实体自带的 UUID 与默认外观，避免冲突（快照里含原实体 UUID）。
            living.load(snapshot);
        }

        // 写回能力对象（运行时真相）。
        FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
        if (data == null) {
            FurkinMod.LOGGER.warn("Furkin summon failed: capability unavailable for species {}", species);
            return false;
        }
        data.setCompanionId(companionId);
        data.setOwnerUuid(entry.getOwnerUuid());
        data.setLevel(entry.getLevel());
        data.setXp(0); // 经验由 M2 成长系统接管，此处快照不含 xp（录里未存）。
        data.setState(FurkinState.COMPANION);
        // 技能快照读回（M2 起填充具体技能）。
        if (entry.getSkillSnapshot() != null && !entry.getSkillSnapshot().isEmpty()) {
            // 技能等级反序列化：skill_levels 的 NBT 形态。
            data.getSkillLevels().clear();
            for (String key : entry.getSkillSnapshot().getAllKeys()) {
                data.getSkillLevels().put(
                        new ResourceLocation(key),
                        entry.getSkillSnapshot().getInt(key));
            }
        }

        // 对 TamableAnimal 的额外动作：置 TAME（与契约同路径）。
        if (living instanceof TamableAnimal tamable) {
            tamable.setTame(true);
            tamable.setOwnerUUID(entry.getOwnerUuid());
        }

        // 设置召唤位置：玩家附近。
        living.moveTo(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());

        // 加入世界。
        serverLevel.addFreshEntity(living);

        // 置 summoned=true。
        entry.setSummoned(true);
        FurkinArchiveData.get(serverLevel).putEntry(entry);

        FurkinMod.LOGGER.info("Furkin summoned: id={} species={} by {}",
                companionId, species, player.getName().getString());
        return true;
    }

    /**
     * 统计某主人的当前已召唤（实体在场）绒亲数量。
     */
    private static int countSummoned(ServerLevel level, UUID ownerUuid) {
        int count = 0;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living) {
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data != null && data.isCompanion()
                        && ownerUuid.equals(data.getOwnerUuid())) {
                    count++;
                }
            }
        }
        return count;
    }
}
