package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.equipment.MobEquipmentContainer;
import com.wanancat.furkin.internal.inventory.FurkinInventory;
import com.wanancat.furkin.internal.inventory.PouchDrop;
import com.wanancat.furkin.internal.network.FurkinNetwork;
import com.wanancat.furkin.internal.network.SyncFurkinDataPacket;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import com.wanancat.furkin.internal.skill.SkillRuntimeCalibrator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * P1 重复实体显式修复。
 *
 * <p>此入口只服务 OP 命令，不做自动选择。当前 canonical 实体是核心运行时数据的唯一来源：
 * keeper 与 canonical 不同时，先把核心数据复制到 keeper，再搬运装备和行囊，最后才更新档案
 * 并删除重复体。任一步失败都不允许删除源实体或改写 canonical。</p>
 */
public final class FurkinDuplicateRepair {

    public enum Result {
        /** 预演成功：校验通过并已产出计划，未执行任何搬运、删除或档案写入。 */
        PREVIEWED,
        OK,
        NOT_FOUND,
        NOT_OWNER,
        NOT_SUMMONED,
        NOT_ALIVE,
        NO_LOADED_CANDIDATE,
        CANONICAL_NOT_LOADED,
        KEEP_UUID_REQUIRED,
        KEEP_UUID_NOT_LOADED,
        CLEANUP_FAILED
    }

    private static final ResourceLocation TRAVEL_POUCH =
            new ResourceLocation(FurkinMod.MODID, "travel_pouch");

    private FurkinDuplicateRepair() {
    }

    /**
     * 显式选择 keeper 并清理同 companionId 的其它已加载实体。
     *
     * <p>调用者必须保证命令在当前服务端线程执行。该方法不加载区块；候选集合只来自
     * {@link FurkinEntityLocator#findAllLoaded(MinecraftServer, FurkinArchiveEntry)}。</p>
     */
    public static Result choose(ServerPlayer player, UUID companionId, @Nullable UUID keepEntityUuid) {
        Preparation preparation = prepare(player, companionId, keepEntityUuid);
        if (preparation.prepared == null) {
            return preparation.result;
        }
        return repair(preparation.prepared, player);
    }

    /**
     * 只读预演：执行与 {@link #choose} 完全相同的前置校验和计划构建，但不搬运、不删除、
     * 不写档案。调用者必须保证命令在当前服务端线程执行。
     */
    public static Result plan(ServerPlayer player, UUID companionId, UUID keepEntityUuid,
                              @Nullable Consumer<RepairPlan> planOut) {
        Preparation preparation = prepare(player, companionId, keepEntityUuid);
        if (preparation.prepared == null) {
            return preparation.result;
        }
        if (planOut != null) {
            planOut.accept(preparation.prepared.plan);
        }
        return Result.PREVIEWED;
    }

    /** 供命令层展示的只读修复计划；所有字段都在校验通过后构建。 */
    public record RepairPlan(UUID companionId, UUID canonicalUuid, UUID keeperUuid,
                             List<UUID> discardUuids, boolean replacingCanonical,
                             int equipmentMoves, int equipmentDrops,
                             int pouchItems, int pouchOverflow) {
    }

    private record PreparedRepair(MinecraftServer server, FurkinArchiveData archive,
                                  FurkinArchiveEntry entry, UUID companionId, UUID canonicalUuid,
                                  LivingEntity canonical, LivingEntity keeper,
                                  FurkinData canonicalData, FurkinData keeperData,
                                  List<LivingEntity> discardCandidates, RepairPlan plan) {
    }

    private record Preparation(Result result, PreparedRepair prepared) {
        static Preparation failed(Result result) {
            return new Preparation(result, null);
        }

        static Preparation ready(PreparedRepair prepared) {
            return new Preparation(Result.PREVIEWED, prepared);
        }
    }

    private static Preparation prepare(ServerPlayer player, UUID companionId,
                                       @Nullable UUID keepEntityUuid) {
        if (player == null || companionId == null) {
            return Preparation.failed(Result.NOT_FOUND);
        }
        if (keepEntityUuid == null) {
            return Preparation.failed(Result.KEEP_UUID_REQUIRED);
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return Preparation.failed(Result.NOT_FOUND);
        }

        FurkinArchiveData archive = FurkinArchiveData.get(server);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return Preparation.failed(Result.NOT_FOUND);
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return Preparation.failed(Result.NOT_OWNER);
        }
        if (!entry.isSummoned()) {
            return Preparation.failed(Result.NOT_SUMMONED);
        }
        if (!entry.isAlive()) {
            return Preparation.failed(Result.NOT_ALIVE);
        }

        List<LivingEntity> candidates = FurkinEntityLocator.findAllLoaded(server, entry);
        if (candidates.isEmpty()) {
            return Preparation.failed(Result.NO_LOADED_CANDIDATE);
        }

        UUID canonicalUuid = entry.getEntityUuid();
        if (canonicalUuid == null) {
            FurkinMod.LOGGER.warn(
                    "Furkin duplicate repair rejected: companion={}, keeper={}, reason=canonical-missing",
                    companionId, keepEntityUuid);
            return Preparation.failed(Result.CANONICAL_NOT_LOADED);
        }

        LivingEntity canonical = findCandidate(candidates, canonicalUuid);
        if (canonical == null) {
            FurkinMod.LOGGER.warn(
                    "Furkin duplicate repair rejected: companion={}, canonical={}, keeper={}, reason=canonical-not-loaded",
                    companionId, canonicalUuid, keepEntityUuid);
            return Preparation.failed(Result.CANONICAL_NOT_LOADED);
        }

        LivingEntity keeper = findCandidate(candidates, keepEntityUuid);
        if (keeper == null) {
            FurkinMod.LOGGER.warn(
                    "Furkin duplicate repair rejected: companion={}, keeper={}, reason=keeper-not-loaded",
                    companionId, keepEntityUuid);
            return Preparation.failed(Result.KEEP_UUID_NOT_LOADED);
        }
        if (canonical instanceof ServerPlayer || keeper instanceof ServerPlayer) {
            FurkinMod.LOGGER.error(
                    "Furkin duplicate repair rejected: companion={}, keeper={}, reason=player-candidate",
                    companionId, keepEntityUuid);
            return Preparation.failed(Result.CLEANUP_FAILED);
        }

        FurkinData canonicalData = capability(canonical);
        FurkinData keeperData = capability(keeper);
        if (canonicalData == null || keeperData == null) {
            FurkinMod.LOGGER.error(
                    "Furkin duplicate repair rejected: companion={}, keeper={}, reason=capability-missing",
                    companionId, keepEntityUuid);
            return Preparation.failed(Result.CLEANUP_FAILED);
        }
        String canonicalMismatch = canonicalMismatch(entry, canonicalData);
        if (canonicalMismatch != null) {
            FurkinMod.LOGGER.error(
                    "Furkin duplicate repair rejected: companion={}, canonical={}, keeper={}, reason=canonical-data-{}",
                    companionId, canonicalUuid, keepEntityUuid, canonicalMismatch);
            return Preparation.failed(Result.CLEANUP_FAILED);
        }

        List<LivingEntity> discardCandidates = new ArrayList<>();
        for (LivingEntity candidate : candidates) {
            if (!candidate.getUUID().equals(keeper.getUUID())) {
                if (candidate instanceof ServerPlayer) {
                    FurkinMod.LOGGER.error(
                            "Furkin duplicate repair rejected: companion={}, keeper={}, bad_candidate={}, reason=player-candidate",
                            companionId, keepEntityUuid, candidate.getUUID());
                    return Preparation.failed(Result.CLEANUP_FAILED);
                }
                discardCandidates.add(candidate);
            }
        }

        RepairPlan plan = buildPlan(entry, canonicalUuid, keeper, discardCandidates,
                canonicalData, keeperData);
        if (plan == null) {
            return Preparation.failed(Result.CLEANUP_FAILED);
        }
        logSnapshot(entry, canonical, keeper, discardCandidates);
        return Preparation.ready(new PreparedRepair(server, archive, entry, companionId,
                canonicalUuid, canonical, keeper, canonicalData, keeperData,
                List.copyOf(discardCandidates), plan));
    }

    private static Result repair(PreparedRepair prepared, ServerPlayer operator) {
        MinecraftServer server = prepared.server;
        FurkinArchiveData archive = prepared.archive;
        FurkinArchiveEntry entry = prepared.entry;
        UUID companionId = prepared.companionId;
        UUID canonicalUuid = prepared.canonicalUuid;
        LivingEntity canonical = prepared.canonical;
        LivingEntity keeper = prepared.keeper;
        FurkinData canonicalData = prepared.canonicalData;
        FurkinData keeperData = prepared.keeperData;
        List<LivingEntity> discardCandidates = prepared.discardCandidates;
        boolean replacingCanonical = prepared.plan.replacingCanonical();

        String stage = "DATA";
        try {
            if (replacingCanonical) {
                keeperData.copyCoreFrom(canonicalData);
                // 档案主键 / owner 是修复入口的授权身份，不能被 canonical capability 中的
                // 残缺身份字段覆盖；实体进度仍全部来自 canonical 的实时数据。
                keeperData.setCompanionId(entry.getCompanionId());
                keeperData.setOwnerUuid(entry.getOwnerUuid());

                // applyTo 会清掉 keeper 旧的自有攻击目标与 Furkin owned goals，并保留/建立
                // keeper 自己的原版 goal 快照；不能直接 clearCombatAiState，否则快照引用会丢。
                if (keeper instanceof TamableAnimal tamable) {
                    tamable.setTarget(null);
                    if (!keeperData.getCombatMode().applyTo(tamable)) {
                        throw new IllegalStateException("keeper combat AI apply rejected");
                    }
                }
                SkillRuntimeCalibrator.rebuild(keeper, keeperData);

                // 行囊容量由 travel_pouch 等级派生；核心数据复制后必须重算，溢出物不能静默丢失。
                stage = "POUCH";
                int pouchSizeBefore = keeperData.getPouch().getContainerSize();
                List<ItemStack> overflow = keeperData.resizePouchToLevel();
                dropOverflow(keeper, keeperData, pouchSizeBefore, overflow);
            }

            stage = "EQUIPMENT";
            for (LivingEntity discard : discardCandidates) {
                transferEquipment(discard, keeper);
            }

            stage = "POUCH";
            for (LivingEntity discard : discardCandidates) {
                FurkinData discardData = capability(discard);
                if (discardData == null) {
                    throw new IllegalStateException("discard capability missing: " + discard.getUUID());
                }
                transferPouch(discard, discardData, keeper, keeperData);
            }
        } catch (RuntimeException exception) {
            logFailure(companionId, keeper.getUUID(), stage, exception);
            return Result.CLEANUP_FAILED;
        }

        // 阶段 A 已保证物品和核心数据仍可重试；先把档案指向 keeper，再删除重复体。
        stage = "ARCHIVE";
        try {
            if (replacingCanonical) {
                RemoteSummonService.cancelIfPresent(server, companionId,
                        RemoteSummonService.CancelReason.CANONICAL_CHANGED);
            }
            syncArchiveCore(entry, keeper, keeperData);
            archive.putEntry(entry);
            FurkinNetwork.channel().send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> keeper),
                    new SyncFurkinDataPacket(keeper.getId(), keeperData.syncNBT()));
            FurkinDuplicateRegistry.onCompanionCleared(companionId, keeper.getUUID());
        } catch (RuntimeException exception) {
            logFailure(companionId, keeper.getUUID(), stage, exception);
            return Result.CLEANUP_FAILED;
        }

        stage = "POSTCONDITION";
        try {
            for (LivingEntity discard : discardCandidates) {
                clearDiscardRuntime(discard);
            }
            for (LivingEntity discard : discardCandidates) {
                discard.discard();
                FurkinDuplicateRegistry.onCompanionCleared(companionId, discard.getUUID());
            }
        } catch (RuntimeException exception) {
            logFailure(companionId, keeper.getUUID(), stage, exception);
            return Result.CLEANUP_FAILED;
        }

        List<LivingEntity> remaining = FurkinEntityLocator.findAllLoaded(server, entry);
        if (!isOnlyKeeper(remaining, keeper)) {
            StringBuilder remainingIds = new StringBuilder();
            for (LivingEntity candidate : remaining) {
                if (remainingIds.length() > 0) {
                    remainingIds.append(',');
                }
                remainingIds.append(candidate.getUUID());
            }
            FurkinMod.LOGGER.error(
                    "Furkin duplicate repair cleanup failed: companion={}, keeper={}, remaining={}",
                    companionId, keeper.getUUID(), remainingIds);
            return Result.CLEANUP_FAILED;
        }

        FurkinMod.LOGGER.info(
                "Furkin duplicate repair complete: companion={}, keeper={}, removed={}, operator={}",
                companionId, keeper.getUUID(), removedIds(discardCandidates),
                operator.getName().getString());
        return Result.OK;
    }

    @Nullable
    private static RepairPlan buildPlan(FurkinArchiveEntry entry, UUID canonicalUuid,
                                        LivingEntity keeper, List<LivingEntity> discards,
                                        FurkinData canonicalData, FurkinData keeperData) {
        List<UUID> discardUuids = new ArrayList<>();
        int equipmentMoves = 0;
        int equipmentDrops = 0;
        int pouchItems = 0;
        for (LivingEntity discard : discards) {
            discardUuids.add(discard.getUUID());
            for (int index = 0; index < MobEquipmentContainer.SLOT_COUNT; index++) {
                EquipmentSlot slot = MobEquipmentContainer.slotFor(index);
                if (discard.getItemBySlot(slot).isEmpty()) {
                    continue;
                }
                if (keeper.getItemBySlot(slot).isEmpty()) {
                    equipmentMoves++;
                } else {
                    equipmentDrops++;
                }
            }
            FurkinData discardData = capability(discard);
            if (discardData == null) {
                FurkinMod.LOGGER.error(
                        "Furkin duplicate repair rejected: companion={}, keeper={}, reason=capability-missing",
                        entry.getCompanionId(), keeper.getUUID());
                return null;
            }
            for (int index = 0; index < discardData.getPouch().getContainerSize(); index++) {
                pouchItems += discardData.getPouch().getItem(index).getCount();
            }
        }

        boolean replacingCanonical = !keeper.getUUID().equals(canonicalUuid);
        int pouchOverflow = replacingCanonical
                ? countPouchOverflow(keeperData, canonicalData) : 0;
        return new RepairPlan(entry.getCompanionId(), canonicalUuid, keeper.getUUID(),
                List.copyOf(discardUuids), replacingCanonical,
                equipmentMoves, equipmentDrops, pouchItems, pouchOverflow);
    }

    private static int countPouchOverflow(FurkinData keeperData, FurkinData canonicalData) {
        int targetSize = Math.max(0, canonicalData.getSkillLevels()
                .getOrDefault(TRAVEL_POUCH, 0)) * FurkinServerConfig.POUCH_SLOTS_PER_LEVEL.get();
        int currentSize = keeperData.getPouch().getContainerSize();
        if (targetSize >= currentSize) {
            return 0;
        }
        int overflow = 0;
        for (int index = targetSize; index < currentSize; index++) {
            overflow += keeperData.getPouch().getItem(index).getCount();
        }
        return overflow;
    }

    private static void transferEquipment(LivingEntity discard, LivingEntity keeper) {
        for (int index = 0; index < MobEquipmentContainer.SLOT_COUNT; index++) {
            EquipmentSlot slot = MobEquipmentContainer.slotFor(index);
            ItemStack stack = discard.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack moving = stack.copy();
            if (keeper.getItemBySlot(slot).isEmpty()) {
                try {
                    keeper.setItemSlot(slot, moving);
                    if (keeper.getItemBySlot(slot).isEmpty()) {
                        throw new IllegalStateException(
                                "keeper equipment write was rejected: " + keeper.getUUID() + " slot=" + slot);
                    }
                } catch (RuntimeException exception) {
                    keeper.setItemSlot(slot, ItemStack.EMPTY);
                    throw exception;
                }
            } else {
                Containers.dropItemStack(keeper.level(), keeper.getX(), keeper.getY(), keeper.getZ(), moving);
            }
            discard.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void transferPouch(LivingEntity discard, FurkinData discardData,
                                      LivingEntity keeper, FurkinData keeperData) {
        FurkinInventory source = discardData.getPouch();
        FurkinInventory target = keeperData.getPouch();
        for (int index = source.getContainerSize() - 1; index >= 0; index--) {
            ItemStack stack = source.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }

            CompoundTag targetBefore = target.createTag();
            try {
                ItemStack remainder = target.addItem(stack.copy());
                if (!remainder.isEmpty()
                        && !PouchDrop.dropStacks(keeper, List.of(remainder))) {
                    throw new IllegalStateException(
                            "keeper pouch drop failed: " + keeper.getUUID() + " source=" + discard.getUUID());
                }
            } catch (RuntimeException exception) {
                // addItem 可能已部分写入；失败时恢复 keeper 行囊，源槽保持原样，避免重复计数。
                target.fromTag(targetBefore);
                throw exception;
            }
            source.setItem(index, ItemStack.EMPTY);
        }
        if (!source.isEmpty()) {
            throw new IllegalStateException("discard pouch not empty: " + discard.getUUID());
        }
    }

    private static void dropOverflow(LivingEntity keeper, FurkinData keeperData,
                                     int pouchSizeBefore, List<ItemStack> overflow) {
        if (overflow.isEmpty()) {
            return;
        }
        for (int index = 0; index < overflow.size(); index++) {
            ItemStack stack = overflow.get(index);
            try {
                if (!stack.isEmpty() && !PouchDrop.dropStacks(keeper, List.of(stack))) {
                    throw new IllegalStateException("keeper pouch overflow drop failed: " + keeper.getUUID());
                }
            } catch (RuntimeException exception) {
                // 已成功丢弃的靠前项保持丢弃；把当前项及后续项放回原容量，避免重复恢复。
                keeperData.getPouch().resize(pouchSizeBefore);
                for (int restore = index; restore < overflow.size(); restore++) {
                    ItemStack unsettled = overflow.get(restore);
                    if (unsettled.isEmpty()) {
                        continue;
                    }
                    ItemStack leftover = keeperData.getPouch().addItem(unsettled.copy());
                    if (!leftover.isEmpty()) {
                        exception.addSuppressed(new IllegalStateException(
                                "keeper pouch overflow restore left " + leftover.getCount()
                                        + " items for " + keeper.getUUID()));
                    }
                }
                throw exception;
            }
        }
    }

    private static void clearDiscardRuntime(LivingEntity discard) {
        if (discard instanceof Mob mob) {
            mob.setTarget(null);
        }
        FurkinData data = capability(discard);
        if (data != null) {
            data.clearCombatAiState();
            if (!data.getPouch().isEmpty()) {
                if (!PouchDrop.dropAll(discard, data.getPouch())) {
                    throw new IllegalStateException("discard pouch cleanup failed: " + discard.getUUID());
                }
            }
        }

        for (int index = 0; index < MobEquipmentContainer.SLOT_COUNT; index++) {
            EquipmentSlot slot = MobEquipmentContainer.slotFor(index);
            ItemStack stack = discard.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(discard.level(), discard.getX(), discard.getY(), discard.getZ(), stack.copy());
                discard.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    private static void syncArchiveCore(FurkinArchiveEntry entry, LivingEntity keeper, FurkinData data) {
        entry.setLevel(data.getLevel());
        entry.setXp(data.getXp());
        entry.setSkillPoints(data.getSkillPoints());
        entry.setSkillSnapshot(data.syncNBT().getCompound("skill_levels"));
        entry.setSkillInvestments(data.getSkillInvestments());
        entry.setSkillInvestmentsKnown(data.hasKnownSkillInvestments());
        entry.setCombatMode(data.getCombatMode());
        if (entry.getSpecies() == null) {
            entry.setSpecies(keeper.getType());
        }
        entry.setAlive(true);
        entry.setSummoned(true);
        entry.setEntityLocation(keeper);
    }

    private static boolean isOnlyKeeper(List<LivingEntity> remaining, LivingEntity keeper) {
        return remaining.size() == 1 && remaining.get(0).getUUID().equals(keeper.getUUID());
    }

    private static LivingEntity findCandidate(List<LivingEntity> candidates, UUID uuid) {
        for (LivingEntity candidate : candidates) {
            if (candidate.getUUID().equals(uuid)) {
                return candidate;
            }
        }
        return null;
    }

    private static FurkinData capability(LivingEntity entity) {
        return entity.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
    }

    /**
     * canonical 实时数据必须仍能证明自己就是档案条目对应的存活伴侣。
     * 若它已处于半清理状态，不能把残缺等级 / 技能静默复制到 keeper。
     */
    private static String canonicalMismatch(FurkinArchiveEntry entry, FurkinData data) {
        if (!entry.getCompanionId().equals(data.getCompanionId())) {
            return "identity";
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(data.getOwnerUuid())) {
            return "owner";
        }
        if (data.getState() != FurkinState.COMPANION) {
            return "state";
        }
        return null;
    }

    private static void logSnapshot(FurkinArchiveEntry entry, LivingEntity canonical,
                                    LivingEntity keeper, List<LivingEntity> discards) {
        FurkinMod.LOGGER.info(
                "Furkin duplicate repair snapshot: companion={}, canonical={}, keeper={}, discards={}",
                entry.getCompanionId(), canonical.getUUID(), keeper.getUUID(), removedIds(discards));
    }

    private static String removedIds(List<LivingEntity> entities) {
        StringBuilder builder = new StringBuilder();
        for (LivingEntity entity : entities) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(entity.getUUID());
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private static void logFailure(UUID companionId, UUID keeperUuid, String stage,
                                   RuntimeException exception) {
        FurkinMod.LOGGER.error(
                "Furkin duplicate repair failed: companion={}, keeper={}, stage={}, cause={}",
                companionId, keeperUuid, stage, exception.toString(), exception);
    }
}
