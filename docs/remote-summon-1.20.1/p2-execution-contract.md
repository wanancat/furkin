# P2 执行契约：真正的远距召唤（1.20.1）

- 状态：口径已冻结；代码已落地，冷区同维度、已加载跨维度和核心 pending 夹具已通过；冷区跨维度、故障注入、生命周期取消、完整客户端与性能矩阵仍待验证
- 依赖：P0、P1 完成
- 适用环境：Minecraft 1.20.1 / Forge 47.2.0 / Java 17 / official 1.20.1 mappings
- 目的：规定异步加载、ticket、pending、超时、取消、重复冲突和 1.20.1 接入的精确行为

## 1. 冻结口径

1. 不使用 `TicketType.FORCED` 作为默认持久策略，不向玩家暴露 `/forceload` 语义。
2. 每次请求最多创建一个逻辑 pending；请求期间使用有界临时 ticket。
2.1 `REMOTE_SUMMON_TICKET` 的 owner 是 `ChunkPos`；`requestId` 只用于日志与状态机标识，不用来区分 ticket（D-23）。同 `ChunkPos` 并发 ticket 的重叠行为按 D-24 继承 1.19.2，不做引用计数。
2.2 `feedbackArmed` 是请求字段，只有在成功返回 `PENDING` 前才置 `true`（D-32）。
2.3 所有已契约 `LivingEntity` 都进入重复登记与 canonical 位置刷新；只有 `TamableAnimal` 额外重建战斗 AI（D-34）。
3. 目标位置来自 `entity_dimension + entity_pos`，不做全 region 搜索。
4. 只加载中心周围受限半径；默认 1，最大 2。
5. ticket 添加 / 移除只在服务端线程执行；`getChunkFuture(...)` 只在 `Util.backgroundExecutor()` 调用。
6. 定位必须按 canonical UUID，并校验 capability `companionId`；不能只按位置或类型选实体。
7. 任何失败、超时、取消都只读档案，释放 ticket，不重建实体。
8. 只有请求开始时 `summoned=false` 的合法离线状态才允许走快照重建。
9. 不新增网络包；复用 `RequestSummonPacket`，异步结果通过现有玩家消息和列表刷新下发。
10. 远召默认开启；关闭只拒绝“已召唤但未加载”的加载路径，不影响已加载传送和合法重建。
11. 默认不监听 `LivingEvent.LivingTickEvent`；位置更新以契约、重建、传送、入世、离场和请求终态为主。

## 2. 数据模型

### 2.1 `FurkinArchiveEntry`

新增：

```java
@Nullable
private BlockPos entityPos;
```

要求：

- `setEntityLocation(Entity entity)` 同步写 UUID、维度、`entity.blockPosition()`。
- `clearEntityLocation()` 同步清空三项。
- 传送成功后写 `relocated.blockPosition()`，不能只写玩家位置。
- 契约 / 召唤 / 复活 / 入世 / 离场和 P2 成功终态都刷新。
- `entity_pos` 是最后确认位置，不承诺实时精度。

### 2.2 NBT

写入：

```java
if (entityPos != null) {
    tag.put("entity_pos", NbtUtils.writeBlockPos(entityPos));
}
```

读取：

```java
CompoundTag posTag = tag.getCompound("entity_pos");
boolean validPos = tag.contains("entity_pos", Tag.TAG_COMPOUND)
        && posTag.contains("X", Tag.TAG_INT)
        && posTag.contains("Y", Tag.TAG_INT)
        && posTag.contains("Z", Tag.TAG_INT);
entry.entityPos = validPos ? NbtUtils.readBlockPos(posTag) : null;
```

规则：

- 缺失读 `null`，不得读成 `(0,0,0)`。
- compound 残缺时读 `null` 并 WARN。
- 位置存在但 UUID 或维度缺失时忽略位置并 WARN。
- 不在 `entity_snapshot` 中重复保存位置。

### 2.3 数据版本迁移

当前 1.20.1 档案版本是 `1`，目标为 `2`：

```text
v0 -> v1：保留当前旧维度档案合并逻辑。
v1 -> v2：position 字段空迁移，不扫描实体、不补位置、不改状态。
```

精确顺序：

1. `CURRENT_DATA_VERSION = 2`。
2. 增加 `LEGACY_GLOBAL_ARCHIVE_VERSION = 1`。
3. `FurkinArchiveData.get(...)` 调用分步 `migrate(server)`。
4. 加载版本 `< 1` 时执行旧维度档案合并，成功后 `dataVersion = 1`。
5. 加载版本 `< 2` 时只把 `dataVersion = 2`；不改任何 entry。
6. 版本实际变化时才 `setDirty()`。
7. 旧档缺位置时远召返回 `NO_POSITION`，等待入世或 P1 修复补录。

## 3. 服务端类型

### 3.1 `RemoteSummonResult`

```text
COMPLETED_TELEPORT
COMPLETED_REBUILD
NOT_FOUND
NOT_OWNER
NOT_ALIVE
ACTIVE_LIMIT
REBUILD_FAILED
DISABLED
INVALID_STATE
ALREADY_PENDING
TOO_MANY_PENDING
COOLDOWN
NO_POSITION
DIMENSION_MISSING
ENTITY_UNRESOLVED
DUPLICATE_CONFLICT
CHUNK_LOAD_FAILED
TIMEOUT
TELEPORT_FAILED
CANCELLED
PENDING
```

`PENDING` 只表示请求已受理，不是成功。只有 `COMPLETED_*` 允许成功文案。

### 3.2 `RemoteSummonRequest`

请求的可变状态只在服务端线程读写；后台 future 创建只读取构造后不变的快照字段。请求至少包含：

- `requestId`（`nextRequestId++`，只用于日志与状态机标识，不作为 ticket owner）
- `playerUuid`
- `companionId`
- `canonicalUuid`（请求开始时的快照，用于检测 canonical 改变）
- `targetDimension`
- `center`（`new ChunkPos(entry.getEntityPos())`）
- `radius`（请求创建时快照）
- `deadlineGameTime`（`server.getTickCount() + timeout`，请求创建时快照）
- `origin`
- `feedback`
- `feedbackArmed`（**必须存在**，见 D-32）
- `state = WAIT_CHUNK | WAIT_ENTITY_LOAD | TERMINAL`
- `ticketAdded`（一次释放凭证）
- `ticketChunks`
- `loadFutures`
- `terminalResult`

### 3.3 `RemoteSummonOrigin` / `RemoteSummonFeedback`

```java
public enum RemoteSummonOrigin { RECORD, COMMAND }

@FunctionalInterface
public interface RemoteSummonFeedback {
    void onTerminal(ServerPlayer player, UUID companionId, RemoteSummonResult result);
}
```

`feedback` 只在异步终态回调；立即命中结果由调用入口直接处理。回调必须在服务端线程执行。

## 4. ticket 契约

### 4.1 类型

服务级静态：

```java
private static final TicketType<ChunkPos> REMOTE_SUMMON_TICKET =
        TicketType.create("furkin:remote_summon",
                Comparator.comparingLong(ChunkPos::toLong), 800);
```

名称必须稳定，timeout 只作为 service 清理失败时的安全网；正常路径仍显式 remove。

### 4.2 精确定位 level

1.20.1 公开 `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)` 返回 31。不要依赖私有的 `ChunkLevel.ENTITY_TICKING_LEVEL`，也不要复制 1.19.2 的裸数字语义。

每张 ticket：

```java
DistanceManager distanceManager = targetLevel.getChunkSource().chunkMap.getDistanceManager();
int ticketLevel = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
distanceManager.addTicket(REMOTE_SUMMON_TICKET, chunkPos, ticketLevel, chunkPos);
```

释放时使用完全相同 type、ChunkPos、level 和 owner：

```java
distanceManager.removeTicket(REMOTE_SUMMON_TICKET, chunkPos, ticketLevel, chunkPos);
```

`addRegionTicket` 只在其语义和 level 经 1.20.1 实证满足要求时使用；默认不依赖它控制实体 tick level。

### 4.4 ticket 生命周期与重叠边界

`releaseTicket(request)` 的精确规则：

```text
1. request.ticketAdded == false        -> return false（幂等，防止二次释放）
2. request.ticketAdded = false
3. tickets = List.copyOf(request.ticketChunks); request.ticketChunks.clear()
4. level = server.getLevel(request.targetDimension)
5. level == null                       -> WARN，return false
6. 逐张 distanceManager.removeTicket(REMOTE_SUMMON_TICKET, pos, level, pos)
     单张抛异常 -> 记 WARN，released = false，继续处理其余
7. return released
```

`startRemoteRequest` 在每张 ticket 成功加入后立即把 `ticketAdded` 置为 `true`；添加过程中若后续抛异常，`finishPending(...)` 会释放已加入的 ticket。

**重叠边界（D-24，继承 1.19.2，不做引用计数）**：

- `Ticket` 等值只比较 `type + ticketLevel + key(ChunkPos) + forceTicks`，不区分 `requestId`。
- 两个请求看护同一 `ChunkPos` 时，第二次 `addTicket` 对等值 ticket 不产生新条目；先结束者 `removeTicket` 会让该 chunk 失去 `furkin:remote_summon` ticket。
- 后结束者可能因此在 `WAIT_ENTITY_LOAD` 阶段提前得到 `ENTITY_UNRESOLVED`。
- 这是安全失败（不损坏档案、不产生实体），本包接受并记录；若必须严格覆盖，另立 ticket 引用计数改进项，不在默认实现中做。
- 同一 companion 的重复请求由 `byCompanion` 拦截，不会进入上述重叠；重叠只可能发生在不同 companion 的 pending 落在同一 `ChunkPos` 时。

### 4.3 future 调用

必须在后台线程：

```java
Util.backgroundExecutor().execute(() -> {
    List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures =
            ChunkPos.rangeClosed(center, radius)
                    .map(pos -> chunkSource.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true))
                    .toList();
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, error) -> server.execute(() ->
                    onLoadFinished(request, futures, error)));
});
```

不能在服务端主线程直接 `join()` 或调用 `getChunkFuture(...)`；1.20.1 字节码已确认主线程路径会使用 `managedBlock`。

## 5. 请求状态机

```text
request(...)
  -> 立即终态，或 PENDING
       -> WAIT_CHUNK
            -> WAIT_ENTITY_LOAD
                 -> COMPLETED_TELEPORT
            -> CHUNK_LOAD_FAILED / TIMEOUT / CANCELLED
```

`WAIT_ENTITY_LOAD` 的规则：

1. `ChunkStatus.FULL` future 成功不等于实体 section 已入世。
2. 在 server tick 中检查 `center + radius` 内每张 `ChunkPos.toLong()` 的 `ServerLevel#areEntitiesLoaded(long)`。
3. 未全为 true：保持 pending，不提前判 `ENTITY_UNRESOLVED`，直到 deadline。
4. 全为 true 后，按顺序重新校验：
   - `server.getLevel(targetDimension) == null` → `DIMENSION_MISSING`（reason `STATE_CHANGED`）。
   - 玩家已下线 → `CANCELLED`（reason `PLAYER_LOGOUT`）。
   - 档案缺失 / 不再 summoned / 不再 alive / owner 不匹配 → `CANCELLED`（reason `STATE_CHANGED`）。
   - canonical UUID 改变或为 null → `CANCELLED`（reason `CANONICAL_CHANGED`）。
   - `hasLoadedDuplicate` 命中 → `DUPLICATE_CONFLICT`。
   - `locate(...)` 找不到 → `ENTITY_UNRESOLVED`（reason `STATE_CHANGED`）。
   - 找到后 `teleportLoadedEntity(...)`；成功 reason `COMPLETED`，失败 reason `STATE_CHANGED`。
5. `onLoadFinished` 先做 `isCurrent(request)` 检查；future 失败或任一 `Either.right()` 存在 → `CHUNK_LOAD_FAILED`。
6. future 聚合在后台线程完成，回调通过 `server.execute(...)` 回主线程。

### 5.1 终态清理与反馈精确规则

```text
finishPending(request, result, reason, throwable)
1. byCompanion.get(companionId) != request || state == TERMINAL -> return（幂等）
2. state = TERMINAL ; terminalResult = result
3. try { ticketReleased = releaseTicket(request) }
     catch RuntimeException -> ticketReleased = false + WARN（不中断终态）
4. removePendingMaps(request)：必须执行；释放异常也不能让 pending 残留并永久触发 ALREADY_PENDING
     byCompanion.remove(companionId, request)
     pendingPerPlayer 计数减 1，为 0 时移除 key
5. 记日志：requestId / companion / result / reason / ticketReleased（throwable 非空时带 cause）
6. recordCooldown(playerUuid, companionId, result)
7. notifyFeedback(request, result, reason)

notifyFeedback(request, result, reason)
1. !request.feedbackArmed                       -> return
2. reason == PLAYER_LOGOUT || SERVER_STOPPING   -> return
3. server.getPlayerList().getPlayer(playerUuid) == null -> return
4. request.feedback.onTerminal(player, companionId, result)
5. 回调抛异常只 WARN，不改变终态
```

cooldown 规则（`recordsCooldown`）：

- 不计入：`NOT_FOUND / NOT_OWNER / NOT_ALIVE / ACTIVE_LIMIT / REBUILD_FAILED / ALREADY_PENDING / TOO_MANY_PENDING / COOLDOWN / PENDING`。
- 其余结果计入，到期时间 = `server.getTickCount() + REMOTE_SUMMON_COOLDOWN_TICKS`。
- `cleanupExpiredCooldowns()` 在每次 `request(...)` 与 `tick(...)` 开头执行。

`stop(server)`：`SERVICES.remove(server)`，对已创建实例执行 `cancelAll(SERVER_STOPPING)`，再清空 `cooldownUntil` 与 `pendingPerPlayer`。`cancelAll` 复用 `finishPending`，因此 ticket 仍会被显式释放；服务器关闭时原版 `removeTicketsOnClosing()` 只是兜底，不作为正常释放路径。

## 6. 入口决策

### 6.1 已加载 canonical

1. `FurkinEntityLocator.locate(...)` 命中：立即 `teleportLoadedEntity(...)`。
2. 不添加 ticket，不创建 pending。
3. 传送成功后刷新位置；失败映射为 `TELEPORT_FAILED` 或 `ENTITY_UNRESOLVED`。

### 6.2 `summoned=false`

1. 先查 `findLoadedByRecordedUuid(...)`；命中返回 `ENTITY_UNRESOLVED`。
2. 再查 `FurkinDuplicateRegistry.hasLoadedDuplicate(...)`；命中返回 `DUPLICATE_CONFLICT`。
3. 无冲突才调用现有 `summon(...)`，仍受 active limit 和重建规则约束。
4. 不得因为同 UUID 已加载而进入 P2 区块加载。

### 6.3 `summoned=true` 但未加载

校验顺序：

1. 档案、owner、alive。
2. enabled；关闭返回 `DISABLED`。
3. target dimension 存在；缺失返回 `DIMENSION_MISSING`。
4. `entityPos` 存在；缺失返回 `NO_POSITION`。
5. `hasLoadedDuplicate(...)`；存在返回 `DUPLICATE_CONFLICT`，不添加 ticket。
6. pending / cooldown / global limit。
7. 创建 pending，添加 ticket，进入 `WAIT_CHUNK`。

顺序很重要：配置、维度、位置和重复冲突必须在创建 ticket 之前完成，避免注定失败的请求产生区块加载成本。

## 7. 终态与清理

### 7.1 终态映射

- 成功传送：`COMPLETED_TELEPORT`
- 合法重建：`COMPLETED_REBUILD`
- 未找到实体：`ENTITY_UNRESOLVED`
- 区块加载失败：`CHUNK_LOAD_FAILED`
- deadline：`TIMEOUT`
- 取消：`CANCELLED`
- 维度 / 位置缺失：`DIMENSION_MISSING` / `NO_POSITION`
- 重复体：`DUPLICATE_CONFLICT`
- 跨维度变更失败：`TELEPORT_FAILED`

### 7.2 finish 流程

```text
finish(request, result)
  -> 若 request 已不是当前 pending，直接返回
  -> 标记 TERMINAL
  -> 释放所有 ticket
  -> 从 byCompanion 和 pendingPerPlayer 移除
  -> 记录 requestId / reason / result
  -> 在服务端线程调用 feedback
```

finish 必须幂等。`PENDING` 不调用终态 feedback，不刷新绒亲录结束态。

### 7.3 取消触发

精确接线点（基线 `5ad0924`）：

| 触发 | 接线位置 | 调用 |
|---|---|---|
| 玩家登出 | `CommonEvents.onPlayerLoggedOut`（`:186`） | `cancelForPlayerIfPresent(server, playerUuid, PLAYER_LOGOUT)` |
| 绒亲死亡 | `CommonEvents.onLivingDeath`（`:332`，档案侧写 `:433`） | `cancelIfPresent(server, companionId, ENTITY_DEATH)` |
| `dismiss` 成功 | `FurkinCompanionManager.dismiss(ServerPlayer, LivingEntity)` 成功路径（基线 `:193`；`FurkinRecordActionHandler.dismiss` `:85` 委托） | `cancelIfPresent(server, companionId, DISMISSED)` |
| 普通解绑 | `FurkinRecordActionHandler.unbind`（基线 `:111`） | `cancelIfPresent(server, companionId, UNBOUND)` |
| 强制解绑 | `FurkinRecordActionHandler` 强制解绑分支（与 `unbind` 同入口） | `cancelIfPresent(server, companionId, UNBOUND)` |
| canonical 改变 | `FurkinDuplicateRepair` keeper 替换 canonical | `cancelIfPresent(server, companionId, CANONICAL_CHANGED)` |
| 服务器停止 | `CommonEvents` 新增 `ServerStoppedEvent`（基线无此监听；不使用 `ServerStoppingEvent`） | `RemoteSummonService.stop(server)` |
| 服务端 tick | `CommonEvents.onServerTick`（`:460`） | `tickIfPresent(server)` 驱动 deadline 与 cooldown |
| 玩家换维度 | 不接线 | 不取消；终态以玩家当前 Level 作为落点 |

`CancelReason`：`COMPLETED / PLAYER_LOGOUT / ENTITY_DEATH / DISMISSED / UNBOUND / CANONICAL_CHANGED / SERVER_STOPPING / TIMEOUT / CHUNK_LOAD_FAILED / STATE_CHANGED / DUPLICATE_CONFLICT`。

`SERVICES = WeakHashMap<MinecraftServer, RemoteSummonService>`；静态入口 `forServer / tickIfPresent / stop / cancelIfPresent / cancelForPlayerIfPresent` 只操作已创建实例（`forServer` 除外，它按需创建）。

## 8. 配置

在 `FurkinServerConfig` 增加：

```java
REMOTE_SUMMON_ENABLED
REMOTE_SUMMON_TICKET_RADIUS
REMOTE_SUMMON_TIMEOUT_TICKS
REMOTE_SUMMON_MAX_PENDING_PER_PLAYER
REMOTE_SUMMON_MAX_PENDING_GLOBAL
REMOTE_SUMMON_COOLDOWN_TICKS
```

范围与默认按决策记录：

- enabled `true`
- radius `1`，范围 `0..2`
- timeout `600`，范围 `20..600`
- per-player `1`，范围 `1..4`
- global `4`，范围 `1..64`
- cooldown `20`，范围 `0..200`

请求创建时快照 radius 和 deadline；终态 cooldown 使用当时配置。专用服实现后用 `rg -n "remoteSummon" run/world/serverconfig/furkin-server.toml` 取证；集成服对应 `run/saves/<存档目录>/serverconfig/furkin-server.toml`。1.20.1 `ModConfig.Type.SERVER` 是世界级 serverconfig（D-31），不再使用 `run/config` 或“项目当前配置路径”这类错误/不可判定表述。

## 9. 玩家反馈

### 9.1 绒亲录（`RequestSummonPacket`）

精确反馈映射（1.19.2 `RequestSummonPacket.sendRecordFeedback`）：

| `RemoteSummonResult` | 文案键 | 是否刷新列表 |
|---|---|---|
| `PENDING` | `furkin.msg.remote_summon_pending` | **否**（保留在途态） |
| `COMPLETED_TELEPORT` 异步 | `furkin.msg.remote_summon_completed` | 是 |
| `COMPLETED_REBUILD` 异步 | `furkin.msg.remote_summon_completed` | 是 |
| `COMPLETED_TELEPORT` 立即 | `furkin.msg.teleported` | 是 |
| `COMPLETED_REBUILD` 立即 | `furkin.msg.summoned` | 是 |
| `NOT_FOUND` | `furkin.msg.summon_not_found` | 是 |
| `NOT_OWNER` | `furkin.msg.not_owner` | 是 |
| `NOT_ALIVE` | `furkin.msg.summon_not_alive` | 是 |
| `ACTIVE_LIMIT` | `furkin.msg.active_limit`（带 `ACTIVE_LIMIT` 配置值） | 是 |
| `ENTITY_UNRESOLVED` | `furkin.msg.remote_summon_unresolved` | 是 |
| `DIMENSION_MISSING` | `furkin.msg.remote_summon_dimension_missing` | 是 |
| `TELEPORT_FAILED` | `furkin.msg.summon_dimension_change_failed` | 是 |
| `NO_POSITION` | `furkin.msg.remote_summon_no_position` | 是 |
| `TIMEOUT` | `furkin.msg.remote_summon_timeout` | 是 |
| `CHUNK_LOAD_FAILED` | `furkin.msg.remote_summon_chunk_failed` | 是 |
| `DUPLICATE_CONFLICT` | `furkin.msg.remote_summon_duplicate` | 是 |
| `DISABLED` | `furkin.msg.remote_summon_disabled` | 是 |
| `ALREADY_PENDING` | `furkin.msg.remote_summon_already_pending` | 是 |
| `TOO_MANY_PENDING` | `furkin.msg.remote_summon_too_many_pending` | 是 |
| `COOLDOWN` | `furkin.msg.remote_summon_cooldown` | 是 |
| `CANCELLED` | `furkin.msg.remote_summon_cancelled` | 是 |
| `INVALID_STATE` / `REBUILD_FAILED` | `furkin.msg.summon_failed` | 是 |

- 立即成功（返回即 `COMPLETED_*`）复用 `furkin.msg.teleported` / `furkin.msg.summoned`；异步终态成功统一 `furkin.msg.remote_summon_completed`。
- `feedback` 回调里 `asynchronous=true`，只影响成功文案；失败文案两者相同。
- 非 `PENDING` 结果统一调用 `FurkinRecordItem.refreshRecordList(player)`，客户端 `acceptRefresh(...)` 清在途态。
- 客户端字段与行为见 `p2-true-remote-summon.md` 第 6.1 节（`pendingSummonCompanionId`、`pendingSummonTicks`、`SUMMON_UI_TIMEOUT_TICKS = 620`）。

### 9.2 命令（`FurkinCommand.sendSummonFeedback`）

- `/furkin summon <id>` 走 `RemoteSummonService.forServer(player.getServer()).request(player, petId, COMMAND, feedback)`。
- 立即结果：`successSink = src::sendSuccess`，`failureSink = src::sendFailure`。
- `PENDING`：`src.sendSuccess(furkin.command.summon.remote_pending)` 并返回 `1`。
- 异步终态：`feedback` 用 `player.displayClientMessage(message, false)` 发送同一套 `sendSummonFeedback` 结果，不保留 `CommandSourceStack` 引用。
- 结果到键的映射与第 9.1 节表一致，但命令入口改用 `furkin.command.summon.*`；`ENTITY_UNRESOLVED` → `furkin.command.summon.entity_unresolved`，`TELEPORT_FAILED` → `furkin.command.summon.dimension_change_failed`。
- `INVALID_STATE / REBUILD_FAILED / PENDING`（异步分支不应出现 `PENDING`）→ `furkin.command.summon.failed`。

## 10. 文案键

最终的完整键 -> 结果映射表见 [p2-true-remote-summon.md 第 6.3 节](p2-true-remote-summon.md)；本节只列出与 1.19.2 一致的关键约束：

- 立即成功复用 `furkin.msg.teleported` / `furkin.msg.summoned`；异步成功用 `furkin.msg.remote_summon_completed`。
- `PENDING` 使用 `furkin.msg.remote_summon_pending`，**不刷新绒亲录列表**。
- `ENTITY_UNRESOLVED`：绒亲录用 `furkin.msg.remote_summon_unresolved`，命令用 `furkin.command.summon.entity_unresolved`。
- `TELEPORT_FAILED`：绒亲录用 `furkin.msg.summon_dimension_change_failed`，命令用 `furkin.command.summon.dimension_change_failed`。
- `DIMENSION_MISSING / NO_POSITION / TIMEOUT / CHUNK_LOAD_FAILED / DUPLICATE_CONFLICT / DISABLED / ALREADY_PENDING / TOO_MANY_PENDING / COOLDOWN / CANCELLED` 统一使用 `furkin.msg.remote_summon_*` 键。
- 命令 `PENDING` 用 `furkin.command.summon.remote_pending`。
- 新增 UI 键 `furkin.screen.record.summoning`。
- `furkin.msg.summon_entity_unresolved` 从未进入 1.20.1 lang；绒亲录直接使用终态键 `furkin.msg.remote_summon_unresolved`。
- 失败文案必须明确“未传送 / 档案状态未改变”和具体原因；不要在失败路径使用成功或“已收回”措辞。
- 中英文键集合必须完全一致，并以 Java 调用点反查，避免死键。
## 11. 定向验证

### 11.1 静态与编译

```powershell
rg -n "FORCED|setChunkForced|LivingTickEvent|managedBlock|addRegionTicket|removeRegionTicket|addTicket|removeTicket|getChunkFuture" src/main/java
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
```

允许命中 `REMOTE_SUMMON_TICKET` 和明确的 `getChunkFuture` 后台调用；不得命中永久强制加载或主线程阻塞调用。

### 11.2 服务端场景

必需：

1. 已加载同维度立即传送。
2. 已加载跨维度立即传送。
3. 未加载同维度：按位置加载后传送同一 UUID。
4. 未加载跨维度：按区域加载后传送同一 UUID。
5. 旧档无位置：`NO_POSITION`，档案不变。
6. 目标维度缺失：`DIMENSION_MISSING`，档案不变。
7. 重复实体：`DUPLICATE_CONFLICT`，不添加 ticket，不生产实体。
8. 重复请求：`ALREADY_PENDING`，不增加 ticket。
9. per-player / global pending 上限：`TOO_MANY_PENDING`。
10. deadline：`TIMEOUT`，释放 ticket。
11. chunk failure 故障注入：`CHUNK_LOAD_FAILED`，释放 ticket。
12. pending 期间换维度：不取消，终态使用当前 Level。
13. 登出 / 死亡 / 收回 / 解绑 / 停服：释放 ticket，清理 pending。
14. 服务器重启：不持久 pending，不残留 ticket，档案字段保持。
15. 同 UUID canonical 已加载但 `summoned=false`：拒绝重建。
16. `dismiss -> summon`：快照重建正常。

### 11.3 客户端与性能

- 绒亲录按钮从可点到 loading，再到终态刷新，不允许同一次点击发出两个 pending。
- `openScreen=false` 刷新不重开屏。
- 冷区、热区、串行 20 次和并发请求都要记录 tick / MSPT 或等价指标。
- 1.19.2 的 434 tick、3.69ms、4 并发数据只作对照，不能作为 1.20.1 通过证据。

## 12. 完成定义

- [ ] P0、P1 定向门槛先全部通过（源码门槛已落地；实机与故障注入待验证）。
- [ ] P2 所有服务端、客户端、生命周期和重复冲突场景有直接证据（状态见统一矩阵）。
- [ ] 旧档 v0 -> v1 -> v2 迁移不修改实体状态，位置缺失保持 `null`（代码路径已落地；NBT 夹具待验证）。
- [ ] ticket 在所有终态释放，停止 / 重启后无残留 pending（代码路径 + 静态审计已写；两阶段实机待验证）。
- [x] 没有永久 `FORCED`，没有主线程 `managedBlock` 生产路径，没有全体 LivingEntity 逐 tick 记录（源码审计）。
- [x] 协议仍为 `2`，公开 API 未变（源码 diff）。
- [x] `compileJava`、`build`、`runServer` 已通过；`runClient` 已启动到客户端渲染初始化，完整在途态实机仍待验证；最新 `runServer` 日志无 Furkin 专属 `ERROR` / `FATAL` / 异常栈 / 资源缺失。
- [x] README、中英 CHANGELOG、配置说明和语言键集合已同步；发布版本已归入 `1.20.1-0.0.3.0`。
