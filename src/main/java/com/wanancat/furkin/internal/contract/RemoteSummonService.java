package com.wanancat.furkin.internal.contract;

import com.mojang.datafixers.util.Either;
import com.wanancat.furkin.internal.FurkinMod;
import com.wanancat.furkin.internal.config.FurkinServerConfig;
import com.wanancat.furkin.internal.record.FurkinArchiveData;
import com.wanancat.furkin.internal.record.FurkinArchiveEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.Util;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * P2 服务端远距召唤服务：负责 pending、临时 ticket、异步区块加载和超时清理。
 *
 * <p>本类只在服务端线程拥有和修改状态。区块 future 的创建与聚合提交到
 * {@link Util#backgroundExecutor()}，回调统一通过 {@link MinecraftServer#execute(Runnable)}
 * 回到服务端线程；主线程不调用 {@link ServerChunkCache#getChunkFuture(int, int, ChunkStatus, boolean)}
 * 的阻塞路径。</p>
 */
public final class RemoteSummonService {

    /**
     * 所有可调值来自 {@link FurkinServerConfig}（SERVER 类型 TOML）；本类不缓存，
     * 每次请求 / 终态按当前配置读取。已创建的 pending 会把半径和 deadline 快照进
     * {@link RemoteSummonRequest}，因此运行中改配置只影响下一次请求。
     */
    private static final TicketType<ChunkPos> REMOTE_SUMMON_TICKET =
            TicketType.create("furkin:remote_summon",
                    Comparator.comparingLong(ChunkPos::toLong), 800);

    /**
     * 1.20.1 公开 {@link ChunkLevel#byStatus(FullChunkStatus)} 可计算实体 tick
     * 所需 level。这里按区块逐张添加该 level 的临时 ticket，保持搜索半径与配置
     * 一致，同时让半径内实体真正可解析，不依赖私有常量。
     */
    private static final int REMOTE_SUMMON_ENTITY_TICKET_LEVEL =
            ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);

    private static final Map<MinecraftServer, RemoteSummonService> SERVICES = new WeakHashMap<>();

    /** 取消原因用于日志和后续玩家反馈；不进入线格式。 */
    public enum CancelReason {
        COMPLETED,
        PLAYER_LOGOUT,
        ENTITY_DEATH,
        DISMISSED,
        UNBOUND,
        CANONICAL_CHANGED,
        SERVER_STOPPING,
        TIMEOUT,
        CHUNK_LOAD_FAILED,
        STATE_CHANGED,
        DUPLICATE_CONFLICT
    }

    private record CooldownKey(UUID playerUuid, UUID companionId) {
    }

    private final MinecraftServer server;
    private final Map<UUID, RemoteSummonRequest> byCompanion = new HashMap<>();
    private final Map<UUID, Integer> pendingPerPlayer = new HashMap<>();
    private final Map<CooldownKey, Long> cooldownUntil = new HashMap<>();
    private long nextRequestId = 1L;

    private RemoteSummonService(MinecraftServer server) {
        this.server = server;
    }

    /** 取得当前服务器的服务实例；实例只在服务端线程访问。 */
    public static RemoteSummonService forServer(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("server");
        }
        return SERVICES.computeIfAbsent(server, RemoteSummonService::new);
    }

    /** 如果服务实例已经创建，则驱动一次超时/cooldown 清理。 */
    public static void tickIfPresent(MinecraftServer server) {
        RemoteSummonService service = SERVICES.get(server);
        if (service != null) {
            service.tick(server);
        }
    }
    /** 服务器停止：取消全部 pending 并移除实例。 */
    public static void stop(MinecraftServer server) {
        if (server == null) {
            return;
        }
        RemoteSummonService service = SERVICES.remove(server);
        if (service != null) {
            service.cancelAll(CancelReason.SERVER_STOPPING);
            service.cooldownUntil.clear();
            service.pendingPerPlayer.clear();
        }
    }

    /** 仅当服务已经创建时取消某 companion 的 pending。 */
    public static void cancelIfPresent(MinecraftServer server, UUID companionId, CancelReason reason) {
        RemoteSummonService service = SERVICES.get(server);
        if (service != null) {
            service.cancel(companionId, reason);
        }
    }

    /** 仅当服务已经创建时取消某玩家的全部 pending。 */
    public static void cancelForPlayerIfPresent(MinecraftServer server, UUID playerUuid, CancelReason reason) {
        RemoteSummonService service = SERVICES.get(server);
        if (service != null) {
            service.cancelForPlayer(playerUuid, reason);
        }
    }

    /**
     * 校验并请求远距召唤。
     *
     * @return {@link RemoteSummonResult#PENDING} 表示请求已受理；其它返回值均为即时终态
     */
    public RemoteSummonResult request(ServerPlayer player, UUID companionId, RemoteSummonOrigin origin) {
        return request(player, companionId, origin, RemoteSummonFeedback.NONE);
    }

    /**
     * 校验并请求远距召唤，并在异步终态时回调入口。
     *
     * <p>{@code feedback} 只在请求成功进入 {@code WAIT_CHUNK} 后由异步完成、失败或取消路径调用；
     * 返回 {@link RemoteSummonResult#PENDING} 以外的即时结果不会触发回调，避免入口重复反馈。</p>
     */
    public RemoteSummonResult request(ServerPlayer player, UUID companionId, RemoteSummonOrigin origin,
                                      RemoteSummonFeedback feedback) {
        assertServerThread();
        RemoteSummonFeedback terminalFeedback =
                feedback == null ? RemoteSummonFeedback.NONE : feedback;
        if (player == null || companionId == null) {
            return RemoteSummonResult.NOT_FOUND;
        }

        cleanupExpiredCooldowns();
        FurkinArchiveData archive = FurkinArchiveData.get(server);
        FurkinArchiveEntry entry = archive.getEntry(companionId);
        if (entry == null) {
            return RemoteSummonResult.NOT_FOUND;
        }
        if (entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(player.getUUID())) {
            return RemoteSummonResult.NOT_OWNER;
        }
        if (!entry.isAlive()) {
            return RemoteSummonResult.NOT_ALIVE;
        }

        // P2-true 第 4.1 节与 1.19.2 基线：重复 pending、上限和 cooldown 在实体分支前检查，
        // 避免连续点击绕过冷却或进入任何即时传送 / 重建路径。
        if (byCompanion.containsKey(companionId)) {
            return RemoteSummonResult.ALREADY_PENDING;
        }
        if (pendingPerPlayer.getOrDefault(player.getUUID(), 0)
                >= FurkinServerConfig.REMOTE_SUMMON_MAX_PENDING_PER_PLAYER.get()
                || byCompanion.size() >= FurkinServerConfig.REMOTE_SUMMON_MAX_PENDING_GLOBAL.get()) {
            return RemoteSummonResult.TOO_MANY_PENDING;
        }
        if (isCoolingDown(player.getUUID(), companionId)) {
            return RemoteSummonResult.COOLDOWN;
        }

        LivingEntity loaded = FurkinEntityLocator.locate(server, entry);
        if (entry.isSummoned() && loaded != null) {
            if (FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry)) {
                return finishDuplicateConflict(player, entry);
            }
            return finishImmediate(player, companionId,
                    mapTeleportResult(FurkinCompanionManager.teleportLoadedEntity(player, entry, loaded)));
        }

        if (!entry.isSummoned()) {
            return finishImmediate(player, companionId,
                    mapSummonResult(FurkinCompanionManager.summonOrTeleport(player, companionId)));
        }

        if (!FurkinServerConfig.REMOTE_SUMMON_ENABLED.get()) {
            return finishImmediate(player, companionId, RemoteSummonResult.DISABLED);
        }
        if (entry.getEntityDimension() == null) {
            return finishImmediate(player, companionId, RemoteSummonResult.DIMENSION_MISSING);
        }
        ServerLevel targetLevel = server.getLevel(entry.getEntityDimension());
        if (targetLevel == null) {
            return finishImmediate(player, companionId, RemoteSummonResult.DIMENSION_MISSING);
        }
        if (entry.getEntityUuid() == null) {
            return finishImmediate(player, companionId, RemoteSummonResult.INVALID_STATE);
        }
        BlockPos recordedPos = entry.getEntityPos();
        if (recordedPos == null) {
            return finishImmediate(player, companionId, RemoteSummonResult.NO_POSITION);
        }

        // 若重复体在请求开始时已经加载，直接拒绝，避免为注定冲突的请求添加 ticket。
        // 异步阶段仍保留同一检查，以覆盖重复体在区块加载期间才入世的情况。
        if (FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry)) {
            return finishDuplicateConflict(player, entry);
        }


        return startRemoteRequest(player, entry, targetLevel, origin, terminalFeedback);
    }

    /** 每个服务端 tick 检查 deadline 并清理过期 cooldown。 */
    public void tick(MinecraftServer server) {
        assertServerThread();
        if (server != this.server) {
            return;
        }
        cleanupExpiredCooldowns();
        long now = server.getTickCount();
        List<RemoteSummonRequest> snapshot = new ArrayList<>(byCompanion.values());
        for (RemoteSummonRequest request : snapshot) {
            if (request.state == RemoteSummonRequest.State.WAIT_CHUNK
                    || request.state == RemoteSummonRequest.State.WAIT_ENTITY_LOAD) {
                if (now >= request.deadlineGameTime) {
                    finishPending(request, RemoteSummonResult.TIMEOUT, CancelReason.TIMEOUT, null);
                } else if (request.state == RemoteSummonRequest.State.WAIT_ENTITY_LOAD) {
                    resolveWhenEntitiesLoaded(request);
                }
            }
        }
    }

    /** 取消某 companion 的 pending；幂等。 */
    public void cancel(UUID companionId, CancelReason reason) {
        assertServerThread();
        RemoteSummonRequest request = byCompanion.get(companionId);
        if (request != null) {
            finishPending(request, RemoteSummonResult.CANCELLED, reason, null);
        }
    }

    /** 取消某玩家的全部 pending；幂等。 */
    public void cancelForPlayer(UUID playerUuid, CancelReason reason) {
        assertServerThread();
        List<RemoteSummonRequest> snapshot = new ArrayList<>(byCompanion.values());
        for (RemoteSummonRequest request : snapshot) {
            if (request.playerUuid.equals(playerUuid)) {
                finishPending(request, RemoteSummonResult.CANCELLED, reason, null);
            }
        }
    }

    /** 取消当前服务实例的全部 pending；幂等。 */
    public void cancelAll(CancelReason reason) {
        assertServerThread();
        List<RemoteSummonRequest> snapshot = new ArrayList<>(byCompanion.values());
        for (RemoteSummonRequest request : snapshot) {
            finishPending(request, RemoteSummonResult.CANCELLED, reason, null);
        }
    }

    public int pendingCountForTests() {
        return byCompanion.size();
    }

    public int ticketCountForTests() {
        int count = 0;
        for (RemoteSummonRequest request : byCompanion.values()) {
            if (request.ticketAdded) {
                count++;
            }
        }
        return count;
    }

    private RemoteSummonResult startRemoteRequest(ServerPlayer player, FurkinArchiveEntry entry,
                                                  ServerLevel targetLevel, RemoteSummonOrigin origin,
                                                  RemoteSummonFeedback feedback) {
        ChunkPos center = new ChunkPos(entry.getEntityPos());
        RemoteSummonRequest request = new RemoteSummonRequest(
                nextRequestId++,
                player.getUUID(),
                entry.getCompanionId(),
                entry.getEntityUuid(),
                entry.getEntityDimension(),
                center,
                FurkinServerConfig.REMOTE_SUMMON_TICKET_RADIUS.get(),
                server.getTickCount() + FurkinServerConfig.REMOTE_SUMMON_TIMEOUT_TICKS.get(),
                origin,
                feedback);

        byCompanion.put(request.companionId, request);
        pendingPerPlayer.merge(request.playerUuid, 1, Integer::sum);

        ServerChunkCache chunkSource = targetLevel.getChunkSource();
        try {
            DistanceManager distanceManager = chunkSource.chunkMap.getDistanceManager();
            for (ChunkPos ticketPos : ChunkPos.rangeClosed(request.center, request.radius)
                    .collect(Collectors.toList())) {
                distanceManager.addTicket(REMOTE_SUMMON_TICKET, ticketPos,
                        REMOTE_SUMMON_ENTITY_TICKET_LEVEL, ticketPos);
                request.ticketChunks.add(ticketPos);
                request.ticketAdded = true;
            }
            FurkinMod.LOGGER.info(
                    "remote summon request={} companion={} center=({}, {}) radius={} tickets={} pending={}",
                    request.requestId, request.companionId,
                    request.center.x, request.center.z, request.radius,
                    request.ticketChunks.size(), byCompanion.size());
            Util.backgroundExecutor().execute(() -> collectLoadFutures(request, chunkSource));
            request.feedbackArmed = true;
        } catch (RuntimeException exception) {
            finishPending(request, RemoteSummonResult.CHUNK_LOAD_FAILED,
                    CancelReason.CHUNK_LOAD_FAILED, exception);
            return RemoteSummonResult.CHUNK_LOAD_FAILED;
        }
        return RemoteSummonResult.PENDING;
    }

    private void collectLoadFutures(RemoteSummonRequest request, ServerChunkCache chunkSource) {
        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures;
        try {
            futures = ChunkPos.rangeClosed(request.center, request.radius)
                    .map(pos -> chunkSource.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true))
                    .collect(Collectors.toList());
        } catch (Throwable throwable) {
            executeOnServer(() -> finishPending(request, RemoteSummonResult.CHUNK_LOAD_FAILED,
                    CancelReason.CHUNK_LOAD_FAILED, throwable));
            return;
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new));
        all.whenComplete((ignored, throwable) ->
                executeOnServer(() -> onLoadFinished(request, futures, throwable)));
    }

    private void onLoadFinished(RemoteSummonRequest request,
                                List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures,
                                Throwable throwable) {
        if (!isCurrent(request)) {
            return;
        }
        request.loadFutures = futures;
        if (throwable != null || !allChunksLoaded(futures)) {
            finishPending(request, RemoteSummonResult.CHUNK_LOAD_FAILED,
                    CancelReason.CHUNK_LOAD_FAILED, throwable);
            return;
        }

        request.state = RemoteSummonRequest.State.WAIT_ENTITY_LOAD;
        resolveWhenEntitiesLoaded(request);
    }

    /**
     * 区块 future 成功后，实体 section 仍可能处于异步读盘状态。本方法只在实体 section
     * 已由 {@code PersistentEntitySectionManager} 标记为 LOADED 后定位；否则保留 pending，
     * 由服务端 tick 重试，避免把“刚加载完成但实体尚未反序列化”误判为实体不存在。
     */
    private void resolveWhenEntitiesLoaded(RemoteSummonRequest request) {
        if (!isCurrent(request)
                || request.state != RemoteSummonRequest.State.WAIT_ENTITY_LOAD) {
            return;
        }

        ServerLevel targetLevel = server.getLevel(request.targetDimension);
        if (targetLevel == null) {
            finishPending(request, RemoteSummonResult.DIMENSION_MISSING,
                    CancelReason.STATE_CHANGED, null);
            return;
        }
        if (!entitySectionsLoaded(targetLevel, request)) {
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(request.playerUuid);
        if (player == null) {
            finishPending(request, RemoteSummonResult.CANCELLED,
                    CancelReason.PLAYER_LOGOUT, null);
            return;
        }

        FurkinArchiveData archive = FurkinArchiveData.get(server);
        FurkinArchiveEntry entry = archive.getEntry(request.companionId);
        if (entry == null || !entry.isSummoned() || !entry.isAlive()
                || entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(request.playerUuid)) {
            finishPending(request, RemoteSummonResult.CANCELLED,
                    CancelReason.STATE_CHANGED, null);
            return;
        }
        if (entry.getEntityUuid() == null
                || !entry.getEntityUuid().equals(request.canonicalUuid)) {
            finishPending(request, RemoteSummonResult.CANCELLED,
                    CancelReason.CANONICAL_CHANGED, null);
            return;
        }
        if (FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry)) {
            finishPending(request, RemoteSummonResult.DUPLICATE_CONFLICT,
                    CancelReason.DUPLICATE_CONFLICT, null);
            return;
        }

        LivingEntity target = FurkinEntityLocator.locate(server, entry);
        if (target == null) {
            finishPending(request, RemoteSummonResult.ENTITY_UNRESOLVED,
                    CancelReason.STATE_CHANGED, null);
            return;
        }

        RemoteSummonResult result = mapTeleportResult(
                FurkinCompanionManager.teleportLoadedEntity(player, entry, target));
        CancelReason reason = result == RemoteSummonResult.COMPLETED_TELEPORT
                ? CancelReason.COMPLETED : CancelReason.STATE_CHANGED;
        finishPending(request, result, reason, null);
    }

    private boolean entitySectionsLoaded(ServerLevel level, RemoteSummonRequest request) {
        return ChunkPos.rangeClosed(request.center, request.radius)
                .allMatch(pos -> level.areEntitiesLoaded(pos.toLong()));
    }

    private RemoteSummonResult finishImmediate(ServerPlayer player, UUID companionId,
                                               RemoteSummonResult result) {
        recordCooldown(player.getUUID(), companionId, result);
        return result;
    }

    private RemoteSummonResult finishDuplicateConflict(ServerPlayer player, FurkinArchiveEntry entry) {
        FurkinMod.LOGGER.warn(
                "Furkin remote resolve failed: id={}, canonical={}, dimension={}, reason=loaded-duplicate, player={}",
                entry.getCompanionId(), entry.getEntityUuid(), entry.getEntityDimension(), player.getUUID());
        return finishImmediate(player, entry.getCompanionId(), RemoteSummonResult.DUPLICATE_CONFLICT);
    }

    private void finishPending(RemoteSummonRequest request, RemoteSummonResult result,
                               CancelReason reason, Throwable throwable) {
        if (byCompanion.get(request.companionId) != request
                || request.state == RemoteSummonRequest.State.TERMINAL) {
            return;
        }

        request.state = RemoteSummonRequest.State.TERMINAL;
        request.terminalResult = result;
        boolean ticketReleased;
        try {
            ticketReleased = releaseTicket(request);
        } catch (RuntimeException releaseException) {
            ticketReleased = false;
            FurkinMod.LOGGER.warn(
                    "remote summon ticket release threw request={} companion={} result={} reason={}",
                    request.requestId, request.companionId, result, reason, releaseException);
        }
        // 无论 ticket 释放是否成功，都必须移除 pending；否则后续请求会被永久判定为 ALREADY_PENDING。
        removePendingMaps(request);
        if (throwable != null) {
            FurkinMod.LOGGER.warn(
                    "remote summon failed request={} companion={} result={} reason={} ticketReleased={}",
                    request.requestId, request.companionId, result, reason, ticketReleased, throwable);
        } else {
            FurkinMod.LOGGER.info(
                    "remote summon completed request={} companion={} result={} reason={} ticketReleased={}",
                    request.requestId, request.companionId, result, reason, ticketReleased);
        }
        recordCooldown(request.playerUuid, request.companionId, result);
        notifyFeedback(request, result, reason);
    }

    private void notifyFeedback(RemoteSummonRequest request, RemoteSummonResult result,
                                CancelReason reason) {
        if (!request.feedbackArmed
                || reason == CancelReason.PLAYER_LOGOUT
                || reason == CancelReason.SERVER_STOPPING) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerUuid);
        if (player == null) {
            return;
        }
        try {
            request.feedback.onTerminal(player, request.companionId, result);
        } catch (RuntimeException exception) {
            FurkinMod.LOGGER.warn(
                    "remote summon feedback failed request={} companion={} result={}",
                    request.requestId, request.companionId, result, exception);
        }
    }

    private boolean releaseTicket(RemoteSummonRequest request) {
        if (!request.ticketAdded) {
            return false;
        }
        request.ticketAdded = false;
        List<ChunkPos> tickets = List.copyOf(request.ticketChunks);
        request.ticketChunks.clear();
        ServerLevel level = server.getLevel(request.targetDimension);
        if (level == null) {
            FurkinMod.LOGGER.warn(
                    "remote summon ticket release failed request={} companion={} reason=dimension-missing",
                    request.requestId, request.companionId);
            return false;
        }
        DistanceManager distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
        boolean released = true;
        for (ChunkPos ticketPos : tickets) {
            try {
                distanceManager.removeTicket(REMOTE_SUMMON_TICKET, ticketPos,
                        REMOTE_SUMMON_ENTITY_TICKET_LEVEL, ticketPos);
            } catch (RuntimeException exception) {
                released = false;
                FurkinMod.LOGGER.warn(
                        "remote summon ticket release failed request={} companion={} chunk=({}, {})",
                        request.requestId, request.companionId, ticketPos.x, ticketPos.z, exception);
            }
        }
        return released;
    }

    private void removePendingMaps(RemoteSummonRequest request) {
        byCompanion.remove(request.companionId, request);
        pendingPerPlayer.computeIfPresent(request.playerUuid, (ignored, count) -> {
            int remaining = count - 1;
            return remaining > 0 ? remaining : null;
        });
    }

    private boolean isCurrent(RemoteSummonRequest request) {
        return byCompanion.get(request.companionId) == request
                && request.state != RemoteSummonRequest.State.TERMINAL;
    }

    private boolean allChunksLoaded(
            List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures) {
        for (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future : futures) {
            try {
                Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> value = future.join();
                if (value == null || value.right().isPresent()) {
                    return false;
                }
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return true;
    }

    private void executeOnServer(Runnable task) {
        try {
            server.execute(task);
        } catch (RuntimeException exception) {
            FurkinMod.LOGGER.debug("remote summon callback dropped during server shutdown", exception);
        }
    }

    private RemoteSummonResult mapTeleportResult(FurkinCompanionManager.TeleportResult result) {
        return switch (result) {
            case TELEPORTED -> RemoteSummonResult.COMPLETED_TELEPORT;
            case ENTITY_UNRESOLVED -> RemoteSummonResult.ENTITY_UNRESOLVED;
            case DIMENSION_CHANGE_FAILED -> RemoteSummonResult.TELEPORT_FAILED;
        };
    }

    private RemoteSummonResult mapSummonResult(FurkinCompanionManager.SummonResult result) {
        return switch (result) {
            case SUMMONED -> RemoteSummonResult.COMPLETED_REBUILD;
            case TELEPORTED -> RemoteSummonResult.COMPLETED_TELEPORT;
            case NOT_FOUND -> RemoteSummonResult.NOT_FOUND;
            case NOT_OWNER -> RemoteSummonResult.NOT_OWNER;
            case NOT_ALIVE -> RemoteSummonResult.NOT_ALIVE;
            case ACTIVE_LIMIT -> RemoteSummonResult.ACTIVE_LIMIT;
            case ENTITY_UNRESOLVED -> RemoteSummonResult.ENTITY_UNRESOLVED;
            case DUPLICATE_CONFLICT -> RemoteSummonResult.DUPLICATE_CONFLICT;
            case DIMENSION_CHANGE_FAILED -> RemoteSummonResult.TELEPORT_FAILED;
            case REBUILD_FAILED -> RemoteSummonResult.REBUILD_FAILED;
        };
    }

    private boolean isCoolingDown(UUID playerUuid, UUID companionId) {
        Long until = cooldownUntil.get(new CooldownKey(playerUuid, companionId));
        return until != null && server.getTickCount() < until;
    }

    private void cleanupExpiredCooldowns() {
        long now = server.getTickCount();
        Iterator<Map.Entry<CooldownKey, Long>> iterator = cooldownUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private void recordCooldown(UUID playerUuid, UUID companionId, RemoteSummonResult result) {
        if (!recordsCooldown(result)) {
            return;
        }
        cooldownUntil.put(new CooldownKey(playerUuid, companionId),
                (long) server.getTickCount() + FurkinServerConfig.REMOTE_SUMMON_COOLDOWN_TICKS.get());
    }

    private boolean recordsCooldown(RemoteSummonResult result) {
        return switch (result) {
            case NOT_FOUND, NOT_OWNER, NOT_ALIVE, ACTIVE_LIMIT, REBUILD_FAILED,
                    ALREADY_PENDING, TOO_MANY_PENDING, COOLDOWN, PENDING -> false;
            default -> true;
        };
    }

    private void assertServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("RemoteSummonService must run on the server thread");
        }
    }
}