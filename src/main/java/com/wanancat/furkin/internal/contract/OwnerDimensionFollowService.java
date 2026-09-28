package com.wanancat.furkin.internal.contract;

import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.capability.FurkinCapability;
import com.wanancat.furkin.internal.capability.FurkinData;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 主人跨维度随行：旅行前快照 + 到达后的服务端 tick 完成校验。
 *
 * <p>工作文档：{@code docs/owner-dimension-follow-1.20.1/README.md}。</p>
 *
 * <p><b>两阶段</b>：{@link #arm(ServerPlayer, ResourceKey)} 在
 * {@code EntityTravelToDimensionEvent} 阶段只做无副作用快照（出发维度内、半径内、本人、
 * canonical、存活的已召唤绒亲）；玩家真正到达目标维度后，由
 * {@link #tickIfPresent(MinecraftServer)} 逐只复核并复用
 * {@link FurkinCompanionManager#teleportLoadedEntity} 的显式落点重载完成迁移。</p>
 *
 * <p><b>边界</b>：只处理出发维度中查询时已加载的实体，不申请 chunk ticket、不加载冷区、
 * 不重建实体、不复制档案；同一 companion 若在快照时或执行时存在同一玩家的活跃远召
 * pending，则随行让路（不移动、不取消、不改写 pending）。快照只存在于内存，服务停止即清空。</p>
 */
public final class OwnerDimensionFollowService {

    /** 快照在内存中的安全上限（tick）；正常换维度同一逻辑调用内完成。 */
    private static final int MAX_WAIT_TICKS = 20;

    /** 单只随行落点：玩家朝向正前方 1.5 格（与既有单只传送语义一致）。 */
    private static final double SINGLE_FORWARD_DISTANCE = 1.5D;

    /** 多只随行落点：以玩家为圆心的环形半径。 */
    private static final double MULTI_RING_DISTANCE = 1.75D;

    /** 允许从玩家脚点向下搜索支撑面的最大距离，避免把宠物放进深坑/熔岩上方。 */
    private static final double MAX_LANDING_DROP = 2.0D;

    /** 方块边界判定余量，避免实体恰好贴边时把相邻方块算入危险体积。 */
    private static final double BLOCK_BOUNDARY_EPSILON = 1.0E-7D;

    private static final Map<MinecraftServer, OwnerDimensionFollowService> SERVICES =
            new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, ArmedRequest> armedByPlayer = new HashMap<>();

    private OwnerDimensionFollowService(MinecraftServer server) {
        this.server = server;
    }

    /** 快照候选：只保存 UUID 与值，不跨 tick 持有 {@link Entity} 引用。 */
    private record Candidate(UUID entityUuid, UUID companionId,
                             double distanceSquared, boolean remotePendingAtArm) {
    }

    /** 一次待执行的随行快照。 */
    private record ArmedRequest(UUID playerUuid,
                                ResourceKey<Level> fromDimension,
                                ResourceKey<Level> toDimension,
                                Vec3 sourceAnchor,
                                long armedTick,
                                List<Candidate> candidates) {
    }

    /**
     * 旅行前快照入口：只读查询出发维度半径内的合法 canonical 绒亲并记录其远召 pending 状态。
     *
     * <p>本方法不移动实体、不查 chunk、不改档案、不改远召状态。任何异常都只放弃本次快照，
     * 不影响原版维度切换。</p>
     */
    public static void arm(ServerPlayer player, ResourceKey<Level> toDimension) {
        if (player == null || toDimension == null) {
            return;
        }
        if (!FurkinServerConfig.OWNER_DIMENSION_FOLLOW_ENABLED.get()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel fromLevel = player.serverLevel();
        if (toDimension.equals(fromLevel.dimension())) {
            return;
        }
        forServer(server).snapshot(player, fromLevel, toDimension);
    }

    /** 每个服务端 tick 检查已建立的快照；服务实例不存在时零开销返回。 */
    public static void tickIfPresent(MinecraftServer server) {
        OwnerDimensionFollowService service = SERVICES.get(server);
        if (service != null) {
            service.tick(server);
        }
    }

    /** 服务器停止：清空内存快照并移除实例。 */
    public static void stop(MinecraftServer server) {
        if (server == null) {
            return;
        }
        OwnerDimensionFollowService service = SERVICES.remove(server);
        if (service != null) {
            int cleared = service.armedByPlayer.size();
            service.armedByPlayer.clear();
            if (cleared > 0) {
                FurkinMod.LOGGER.debug("owner dimension follow stopped: cleared={}", cleared);
            }
        }
    }

    private static OwnerDimensionFollowService forServer(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("server");
        }
        return SERVICES.computeIfAbsent(server, OwnerDimensionFollowService::new);
    }

    /** 阶段 A：在出发维度做一次有界空间查询并写入候选快照。 */
    private void snapshot(ServerPlayer player, ServerLevel fromLevel, ResourceKey<Level> toDimension) {
        UUID playerUuid = player.getUUID();
        Vec3 anchor = player.position();
        int radius = FurkinServerConfig.OWNER_DIMENSION_FOLLOW_RADIUS.get();
        double radiusSquared = (double) radius * radius;
        AABB searchBox = new AABB(
                anchor.subtract(radius, radius, radius),
                anchor.add(radius, radius, radius));

        FurkinArchiveData archive = FurkinArchiveData.get(server);
        List<Candidate> collected = new ArrayList<>();
        // 谓词按“廉价到昂贵”排序：位置/存活 -> 距离 -> capability/owner -> 档案/canonical。
        fromLevel.getEntitiesOfClass(LivingEntity.class, searchBox, living -> {
            if (living == player || living.isRemoved() || !living.isAlive()) {
                return false;
            }
            double distanceSquared = living.distanceToSqr(anchor);
            if (distanceSquared > radiusSquared) {
                return false;
            }
            FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
            if (data == null || !data.isCompanion()) {
                return false;
            }
            UUID companionId = data.getCompanionId();
            if (companionId == null) {
                return false;
            }
            if (data.getOwnerUuid() == null || !data.getOwnerUuid().equals(playerUuid)) {
                return false;
            }
            FurkinArchiveEntry entry = archive.getEntry(companionId);
            if (entry == null || !entry.isAlive() || !entry.isSummoned()) {
                return false;
            }
            if (entry.getEntityUuid() == null || !entry.getEntityUuid().equals(living.getUUID())) {
                return false;
            }
            collected.add(new Candidate(living.getUUID(), companionId, distanceSquared,
                    RemoteSummonService.isPendingFor(server, playerUuid, companionId)));
            return true;
        });

        if (collected.isEmpty()) {
            return;
        }
        collected.sort(Comparator.comparingDouble(Candidate::distanceSquared)
                .thenComparing(candidate -> candidate.entityUuid().toString()));
        int limit = FurkinServerConfig.ACTIVE_LIMIT.get();
        if (collected.size() > limit) {
            FurkinMod.LOGGER.debug(
                    "owner dimension follow snapshot truncated: player={} candidates={} limit={}",
                    playerUuid, collected.size(), limit);
            collected.subList(limit, collected.size()).clear();
        }
        List<Candidate> candidates = List.copyOf(collected);

        ArmedRequest previous = armedByPlayer.put(playerUuid,
                new ArmedRequest(playerUuid, fromLevel.dimension(), toDimension,
                        anchor, server.getTickCount(), candidates));
        if (previous != null) {
            FurkinMod.LOGGER.warn(
                    "owner dimension follow snapshot overwritten: player={} oldTo={} newTo={}",
                    playerUuid, previous.toDimension().location(), toDimension.location());
        }
        long pending = candidates.stream().filter(Candidate::remotePendingAtArm).count();
        FurkinMod.LOGGER.debug(
                "owner dimension follow armed: player={} from={} to={} candidates={} pending={}",
                playerUuid, fromLevel.dimension().location(), toDimension.location(),
                candidates.size(), pending);
    }

    /** 阶段 B：按当前玩家维度决定保留、执行或淘汰快照。 */
    private void tick(MinecraftServer server) {
        if (server != this.server || armedByPlayer.isEmpty()) {
            return;
        }
        List<ArmedRequest> snapshot = new ArrayList<>(armedByPlayer.values());
        long now = server.getTickCount();
        for (ArmedRequest request : snapshot) {
            ServerPlayer player = server.getPlayerList().getPlayer(request.playerUuid());
            if (player == null || !player.isAlive() || player.isRemoved()) {
                armedByPlayer.remove(request.playerUuid(), request);
                FurkinMod.LOGGER.debug(
                        "owner dimension follow dropped: reason=player-unavailable player={}",
                        request.playerUuid());
                continue;
            }
            ResourceKey<Level> current = player.serverLevel().dimension();
            if (current.equals(request.toDimension())) {
                armedByPlayer.remove(request.playerUuid(), request);
                try {
                    process(request, player);
                } catch (RuntimeException exception) {
                    FurkinMod.LOGGER.warn(
                            "owner dimension follow batch failed: player={}",
                            request.playerUuid(), exception);
                }
                continue;
            }
            if (!current.equals(request.fromDimension())) {
                armedByPlayer.remove(request.playerUuid(), request);
                FurkinMod.LOGGER.warn(
                        "owner dimension follow dropped: player={} expected={} actual={}",
                        request.playerUuid(), request.toDimension().location(), current.location());
                continue;
            }
            if (now - request.armedTick() > MAX_WAIT_TICKS) {
                armedByPlayer.remove(request.playerUuid(), request);
                FurkinMod.LOGGER.debug(
                        "owner dimension follow expired: player={} from={} to={} age={}",
                        request.playerUuid(), request.fromDimension().location(),
                        request.toDimension().location(), now - request.armedTick());
            }
        }
    }

    /** 逐只复核并执行批量随行；一只失败不影响其余候选。 */
    private void process(ArmedRequest request, ServerPlayer player) {
        ServerLevel fromLevel = server.getLevel(request.fromDimension());
        FurkinArchiveData archive = FurkinArchiveData.get(server);
        int total = request.candidates().size();
        int moved = 0;
        int yielded = 0;
        int failed = 0;
        int skipped = 0;
        int index = 0;
        for (Candidate candidate : request.candidates()) {
            int slot = index++;
            try {
                if (fromLevel == null) {
                    skipped++;
                    continue;
                }
                Entity found = fromLevel.getEntity(candidate.entityUuid());
                if (found == null) {
                    skipped++;
                    continue;
                }
                if (!(found instanceof LivingEntity living)) {
                    failed++;
                    continue;
                }
                if (!living.isAlive() || living.isRemoved()) {
                    failed++;
                    continue;
                }
                FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null);
                if (data == null || !data.isCompanion()) {
                    failed++;
                    continue;
                }
                if (data.getCompanionId() == null
                        || !data.getCompanionId().equals(candidate.companionId())) {
                    failed++;
                    continue;
                }
                if (data.getOwnerUuid() == null
                        || !data.getOwnerUuid().equals(player.getUUID())) {
                    failed++;
                    continue;
                }
                FurkinArchiveEntry entry = archive.getEntry(candidate.companionId());
                if (entry == null || !entry.isAlive() || !entry.isSummoned()) {
                    failed++;
                    continue;
                }
                if (entry.getEntityUuid() == null
                        || !entry.getEntityUuid().equals(candidate.entityUuid())) {
                    failed++;
                    continue;
                }
                if (candidate.remotePendingAtArm()
                        || RemoteSummonService.isPendingFor(server, player.getUUID(),
                                candidate.companionId())) {
                    yielded++;
                    FurkinMod.LOGGER.debug(
                            "owner dimension follow yielded: player={} companion={} remotePendingAtArm={}",
                            player.getUUID(), candidate.companionId(), candidate.remotePendingAtArm());
                    continue;
                }
                if (FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry)) {
                    failed++;
                    FurkinMod.LOGGER.warn(
                            "owner dimension follow blocked: companion={} canonical={} reason=loaded-duplicate player={}",
                            candidate.companionId(), candidate.entityUuid(), player.getUUID());
                    continue;
                }
                Vec3 landing = chooseLanding(player, living, slot, total);
                if (landing == null) {
                    failed++;
                    FurkinMod.LOGGER.warn(
                            "owner dimension follow failed: companion={} result=NO_SAFE_LANDING from={} to={}",
                            candidate.companionId(), request.fromDimension().location(),
                            request.toDimension().location());
                    continue;
                }
                FurkinCompanionManager.TeleportResult result =
                        FurkinCompanionManager.teleportLoadedEntity(player, entry, living,
                                landing.x(), landing.y(), landing.z(),
                                player.getYRot(), player.getXRot());
                if (result == FurkinCompanionManager.TeleportResult.TELEPORTED) {
                    moved++;
                } else {
                    failed++;
                    FurkinMod.LOGGER.warn(
                            "owner dimension follow failed: companion={} result={} from={} to={}",
                            candidate.companionId(), result, request.fromDimension().location(),
                            request.toDimension().location());
                }
            } catch (RuntimeException exception) {
                failed++;
                FurkinMod.LOGGER.warn(
                        "owner dimension follow per-candidate failure: player={} companion={}",
                        player.getUUID(), candidate.companionId(), exception);
            }
        }
        FurkinMod.LOGGER.info(
                "owner dimension follow processed player={} from={} to={} moved={} yielded={} failed={} skipped={} total={}",
                player.getUUID(), request.fromDimension().location(),
                request.toDimension().location(), moved, yielded, failed, skipped, total);
    }

    /**
     * 目标落点：单只沿用“朝向正前方 1.5 格”，多只在玩家周围按 yaw 起始均匀展开。
     *
     * <p>best-effort 安全校验只接受玩家脚点下方 {@link #MAX_LANDING_DROP} 格内的可站立支撑面，
     * 并要求实体体积无碰撞且不包含传送门、末地门、末地折跃门、岩浆、火或灵魂火。主环形点失败时
     * 尝试镜像点和八个替代环形点；全部失败则返回 {@code null}，由调用方跳过该只，不回退到玩家
     * 自身坐标，不调用 {@code randomTeleport(...)}，也不为落点加载区块。</p>
     */
    private Vec3 chooseLanding(ServerPlayer player, LivingEntity target, int index, int total) {
        Level targetLevel = player.serverLevel();
        Vec3 candidate = ringPoint(player, index, total);
        Vec3 landing = findSafeLanding(targetLevel, target, candidate);
        if (landing != null) {
            return landing;
        }

        Vec3 mirrored = new Vec3(player.getX() * 2.0D - candidate.x(),
                candidate.y(), player.getZ() * 2.0D - candidate.z());
        landing = findSafeLanding(targetLevel, target, mirrored);
        if (landing != null) {
            FurkinMod.LOGGER.debug(
                    "owner dimension follow landing adjusted: entity={} index={} total={}",
                    target.getUUID(), index, total);
            return landing;
        }

        for (int attempt = 1; attempt <= 6; attempt++) {
            double angle = Math.toRadians(player.getYRot())
                    + Math.PI * 2.0D * attempt / 8.0D;
            Vec3 alternate = new Vec3(
                    player.getX() - Math.sin(angle) * MULTI_RING_DISTANCE,
                    player.getY(),
                    player.getZ() + Math.cos(angle) * MULTI_RING_DISTANCE);
            landing = findSafeLanding(targetLevel, target, alternate);
            if (landing != null) {
                FurkinMod.LOGGER.debug(
                        "owner dimension follow landing adjusted: entity={} index={} total={}",
                        target.getUUID(), index, total);
                return landing;
            }
        }

        FurkinMod.LOGGER.warn(
                "owner dimension follow landing blocked: entity={} index={} total={}",
                target.getUUID(), index, total);
        return null;
    }

    /**
     * 从给定水平位置向下搜索可站立支撑面，返回实际脚点。
     *
     * <p>只检查已加载方块；最大落差受 {@link #MAX_LANDING_DROP} 限制。支撑面必须可让目标实体
     * 站立，落点体积不得碰撞，且实体占据的方块中不得出现本功能识别的危险方块。</p>
     */
    private Vec3 findSafeLanding(Level level, LivingEntity target, Vec3 start) {
        int x = Mth.floor(start.x);
        int z = Mth.floor(start.z);
        int topBlockY = Mth.floor(start.y);
        int minBlockY = Mth.floor(start.y - MAX_LANDING_DROP - BLOCK_BOUNDARY_EPSILON);
        for (int blockY = topBlockY; blockY >= minBlockY; blockY--) {
            BlockPos supportPos = new BlockPos(x, blockY, z);
            if (!level.isLoaded(supportPos)) {
                continue;
            }
            BlockState supportState = level.getBlockState(supportPos);
            if (isUnsafeLandingBlock(supportState)) {
                continue;
            }
            VoxelShape supportShape = supportState.getCollisionShape(level, supportPos);
            if (supportShape.isEmpty()) {
                continue;
            }
            double supportTop = blockY + supportShape.max(Direction.Axis.Y);
            double drop = start.y - supportTop;
            if (drop < -BLOCK_BOUNDARY_EPSILON
                    || drop > MAX_LANDING_DROP + BLOCK_BOUNDARY_EPSILON) {
                continue;
            }
            Vec3 candidate = new Vec3(start.x, supportTop, start.z);
            if (isSafeLanding(level, target, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSafeLanding(Level level, LivingEntity target, Vec3 candidate) {
        AABB movedBox = target.getBoundingBox().move(candidate.subtract(target.position()));
        if (!level.noCollision(target, movedBox)) {
            return false;
        }
        return !containsUnsafeLandingBlock(level, movedBox);
    }

    private boolean containsUnsafeLandingBlock(Level level, AABB box) {
        int minX = Mth.floor(box.minX + BLOCK_BOUNDARY_EPSILON);
        int maxX = Mth.floor(box.maxX - BLOCK_BOUNDARY_EPSILON);
        int minY = Mth.floor(box.minY + BLOCK_BOUNDARY_EPSILON);
        int maxY = Mth.floor(box.maxY - BLOCK_BOUNDARY_EPSILON);
        int minZ = Mth.floor(box.minZ + BLOCK_BOUNDARY_EPSILON);
        int maxZ = Mth.floor(box.maxZ - BLOCK_BOUNDARY_EPSILON);
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!level.isLoaded(pos) || isUnsafeLandingBlock(level.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnsafeLandingBlock(BlockState state) {
        return state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.END_GATEWAY)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE);
    }

    private Vec3 ringPoint(ServerPlayer player, int index, int total) {
        if (total <= 1) {
            double angle = Math.toRadians(player.getYRot());
            return new Vec3(player.getX() - Math.sin(angle) * SINGLE_FORWARD_DISTANCE,
                    player.getY(),
                    player.getZ() + Math.cos(angle) * SINGLE_FORWARD_DISTANCE);
        }
        double angle = Math.toRadians(player.getYRot())
                + (Math.PI * 2.0D) * index / total;
        return new Vec3(player.getX() - Math.sin(angle) * MULTI_RING_DISTANCE,
                player.getY(),
                player.getZ() + Math.cos(angle) * MULTI_RING_DISTANCE);
    }
}
