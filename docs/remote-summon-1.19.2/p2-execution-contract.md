# P2 执行契约：真正的远距召唤

- 状态：已冻结；P2.3、P2.4、P2.5 已按本契约实施并完成定向夹具验收；P2.6 服务端边界、客户端结构、生命周期边界、真实异步成功端到端、重复实体冲突守卫、加载前置重复检查及 `CHUNK_LOAD_FAILED` 注入、pending 换维度、服务器重启与性能收口已完成
- 依赖：P0、P1 完成
- 适用版本：Minecraft 1.19.2 / Forge 43.2.0
- 目标：目标实体未加载时，用最后已知位置和有界临时 ticket 重新定位同一实体并传送

## 1. 冻结口径

本功能包内默认采用以下口径：

1. 不使用 `TicketType.FORCED` 作为默认持久策略，也不把 `/forceload` 语义暴露给玩家。
2. 每次远召最多创建一个逻辑请求；请求期间使用一个有界临时 ticket。
3. 目标位置来自档案 `entity_dimension + entity_pos`，不使用“搜索所有区块”。
4. 只加载目标位置周围有界半径；默认半径 1，最大半径 2。
5. ticket 的添加和移除只在服务端线程执行；`getChunkFuture(...)` 必须从 `Util.backgroundExecutor()` 调用。不能在服务端主线程同步调用它，因为 1.19.2 的主线程分支会走 `managedBlock` 等待。
6. 找到实体后必须以 canonical UUID 命中；实体 `companionId` 非空时必须等于档案 `companionId`。仅允许 P1 已定义的“canonical UUID + null companionId”遗留清理例外，不能只按位置或类型选中。
7. 任何失败、超时、取消都只读档案，释放 ticket，不重建实体。
8. 只有 `summoned=false` 的合法离线状态才允许顺带走快照重建。
9. P2 暂不新增网络包；沿用 `RequestSummonPacket`，异步结果通过现有服务端消息回执。
10. 远召默认开启，但可由服务端配置关闭；关闭只拒绝“已召唤但未加载”的远距加载路径，不阻挡已加载传送，也不阻挡 `summoned=false` 的合法重建。
11. 默认不监听 `LivingEvent.LivingTickEvent`；位置更新以契约、重建、传送、入世、离场事件和请求完成时的刷新为主。

## 2. 精确数据模型

### 2.1 `FurkinArchiveEntry`

新增字段：

```java
@Nullable
private BlockPos entityPos;
```

访问方法：

```java
@Nullable
public BlockPos getEntityPos()

public void setEntityPos(@Nullable BlockPos entityPos)

public void setEntityLocation(Entity entity)
```

`setEntityLocation(Entity)` 必须同步设置：

- `entityUuid = entity.getUUID()`
- `entityDimension = entity.getLevel().dimension()`
- `entityPos = entity.blockPosition()`

`clearEntityLocation()` 必须同步清空：

- `entityUuid`
- `entityDimension`
- `entityPos`

### 2.2 NBT 格式

使用 1.19.2 已核实的官方工具：

```java
// 写入：NbtUtils.writeBlockPos 在 1.19.2 写的是大写 X/Y/Z 三个 TAG_INT。
tag.put("entity_pos", NbtUtils.writeBlockPos(entityPos));

// 读取：不要直接对空 compound 调 readBlockPos，否则缺失字段会被读成 (0,0,0)。
CompoundTag posTag = tag.getCompound("entity_pos");
boolean validPos = tag.contains("entity_pos", Tag.TAG_COMPOUND)
        && posTag.contains("X", Tag.TAG_INT)
        && posTag.contains("Y", Tag.TAG_INT)
        && posTag.contains("Z", Tag.TAG_INT);
entry.entityPos = validPos ? NbtUtils.readBlockPos(posTag) : null;
```

约束：

- 缺失 `entity_pos` 读作 `null`，不得回退到 `BlockPos.ZERO`。
- compound 存在但缺少 `X` / `Y` / `Z` 任一 `TAG_INT` 时读作 `null` 并记录 WARN。
- 必须在调用 `NbtUtils.readBlockPos(...)` 前完成字段类型检查；该方法本身不会把缺失字段判为非法位置。
- `entity_pos` 与 `entity_uuid` / `entity_dimension` 必须同时存在或同时缺失，修复/迁移除外。
- 不在 `entity_snapshot` 中重复保存位置字段。

### 2.3 数据版本迁移

当前档案数据版本为 `1`，其迁移逻辑承担旧版按维度档案合并。P2 将版本推进到 `2`，但必须拆开迁移步骤：

```text
v0 -> v1：保留现有旧维度档案合并逻辑。
v1 -> v2：位置字段为空的 no-op 迁移，不改实体、不改 summoned、不创建实体。
```

精确实现顺序：

1. `CURRENT_DATA_VERSION = 2`。
2. 增加 `LEGACY_GLOBAL_ARCHIVE_VERSION = 1`。
3. `FurkinArchiveData.get(...)` 调用分步 `migrate(server)`。
4. 若加载版本 `< 1`，只执行一次原有旧档案合并，成功后写 `dataVersion = 1`。
5. 若加载版本 `< 2`，不执行任何实体扫描或位置推导，只把 `dataVersion` 写为 `2`。
6. `setDirty()` 只在版本实际变化时调用。
7. 旧档缺少位置时，P2 返回 `NO_POSITION`；绝不因为位置缺失而把实体判死。

## 3. 位置更新契约

### 3.1 必须更新

以下服务端路径必须调用 `entry.setEntityLocation(entity)`：

- 契约成功。
- P2 重建召唤/复活成功（`rebuildCompanion` 落态后）。
- 已加载实体传送成功后。
- `EntityJoinLevelEvent` 中 canonical UUID 命中时。
- `EntityLeaveLevelEvent` 中 canonical UUID 命中时、实体离开 Level 前。
- P2 请求成功后再次刷新。

### 3.2 跨区块更新

P2 默认不挂 `LivingEvent.LivingTickEvent`。原因是该事件会对每个已加载的 `LivingEntity` 每 tick 触发，在大型整合包里会叠加全实体级能力查询成本；而 1.19.2 的 `EntityLeaveLevelEvent` 已在 `LevelCallback#onTrackingEnd(...)` 触发，可用于区块卸载前记录最后位置。

默认策略：

- `EntityJoinLevelEvent`：canonical UUID 命中时刷新档案位置。
- `EntityLeaveLevelEvent`：canonical UUID 命中时，在实体离开 Level / 区块卸载前刷新档案位置。
- 传送、重建、契约成功和远召成功后刷新。
- 玩家自己换维度不直接写宠物位置。只有宠物实体实际 `changeDimension` / `teleportTo`，或因随行被原版/其它模组搬运并触发入世/离场事件时，才刷新宠物的维度和位置。
- 本项目自己执行的 `teleportLoadedEntity(...)` 跨维度传送，在 `changeDimension` 成功并拿到返回实体后必须 `setEntityLocation(relocated)`；不能把玩家新 Level/坐标写进宠物档案，除非宠物实体也到了那里。
- 查询旧档或位置过期时，宁可返回 `NO_POSITION` 或安全失败，也不做全局实体扫描。

只有后续 P2.6 实机验证证明离场事件漏记、且位置误差确实导致远召失败时，才评估低频补偿方案。可选补偿必须满足：

- 使用 `ServerTickEvent` 每 N tick（建议 100 tick = 5 秒）处理一次。
- 只遍历档案中 `summoned=true` 的 canonical，最多调用 `ServerLevel#getEntity(UUID)`，不遍历所有 LivingEntity。
- 只在实际 chunk 变化时写 `SavedData`。
- 不在 tick 路径调用 `ServerChunkCache#getChunkFuture` 或做全量实体扫描。

任何实现都不能把 `LivingTickEvent` 重新变成默认的每实体每 tick 记录。

## 4. 服务端类型

### 4.1 结果枚举

```text
RemoteSummonResult
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
```

结果只用于服务端内部、命令/消息选择；不在 P2 第一版加入网络包字段。

调用来源单独定义，用于决定即时/pending 回执走界面文案还是命令文案：

```java
enum RemoteSummonOrigin {
    RECORD,
    COMMAND
}
```

### 4.2 服务实例

`RemoteSummonService` 是每个 `MinecraftServer` 一个实例：

```java
final class RemoteSummonService {
    static RemoteSummonService forServer(MinecraftServer server);
    RemoteSummonResult request(ServerPlayer player, UUID companionId,
                               RemoteSummonOrigin origin);
    void tick(MinecraftServer server);
    void cancel(UUID companionId, CancelReason reason);
    void cancelForPlayer(UUID playerUuid, CancelReason reason);
    void cancelAll(CancelReason reason);
    int pendingCountForTests();
    int ticketCountForTests();
}
```

服务实例存放在显式 registry 中，以 `MinecraftServer` 身份为键；`ServerStoppingEvent` 必须移除实例并取消全部 pending。

### 4.3 Pending 请求字段

```java
long requestId;
UUID playerUuid;
UUID companionId;
ServerPlayer player;
ResourceKey<Level> targetDimension;
ChunkPos center;
int radius;
long deadlineGameTime;
boolean ticketAdded;
List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> loadFutures;
RequestState state;
RemoteSummonResult terminalResult;
RemoteSummonOrigin origin;
```

所有字段只在服务端线程修改。后台 future 回调必须先通过 `server.execute(...)` 切回服务端线程，再比较 `requestId` 是否仍是当前 pending。

pending 索引至少需要：

- `Map<UUID companionId, RemoteSummonRequest> byCompanion`：同一绒亲最多一个逻辑请求。
- `Map<UUID playerUuid, Integer> pendingPerPlayer`：执行 `remoteSummonMaxPendingPerPlayer`。
- `Map<CooldownKey, Long> cooldownUntil`，其中 `record CooldownKey(UUID playerUuid, UUID companionId)`；P2 保证配置的 cooldown 只限制 `(玩家, 绒亲)`，不阻塞玩家其它绒亲。

`tick(...)` 或每次 `request(...)` 必须清理已过期的 cooldown 条目和计数为 0 的 pending 记录；`ServerStoppingEvent` 清空全部 map，避免服务实例移除后仍留下引用。

## 5. Ticket 契约

### 5.1 Ticket 类型

使用一个专用、非持久、带 timeout 的 ticket：

```java
private static final TicketType<ChunkPos> REMOTE_SUMMON_TICKET =
        TicketType.create("furkin:remote_summon",
                Comparator.comparingLong(ChunkPos::toLong),
                800);
```

1.19.2 已核实 `TicketType.create(String, Comparator<T>, int)`、`Ticket.timeout()`、`Ticket.timedOut(long)` 存在。第三个参数是 ticket 的 tick 级 timeout 安全网；`800` 大于 `remoteSummonTimeoutTicks` 的允许上限 `600`，避免配置为长超时时 ticket 先于 service 自行过期。正常路径仍必须显式 `removeRegionTicket(...)`，不能依赖 timeout 回收。

### 5.2 加载 API

使用以下公开 API：

```java
ServerChunkCache cache = targetLevel.getChunkSource();
cache.addRegionTicket(REMOTE_SUMMON_TICKET, center, radius, center);

// getChunkFuture 不得在这里直接调用；必须在 Util.backgroundExecutor() 中收集，见 5.3。
cache.removeRegionTicket(REMOTE_SUMMON_TICKET, center, radius, center);
```

约束：

- 每次请求固定使用 `center + radius`，不根据实体类型动态扩张。
- 不对每个半径区块重复添加 ticket；一个中心 ticket 使用 `radius` 传播。
- 对 `ChunkPos.rangeClosed(center, radius)` 内的区块创建 future。
- future 返回类型是 `Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>`；必须检查左值/右值，不能只依赖 future 是否异常。
- 1.19.2 的实体数据存放在维度目录下的独立 `entities/*.mca`，不是区块 `region/*.mca` 的 `Entities` 标签。`ChunkStatus.FULL` future 完成只保证区块可用，不能保证 `PersistentEntitySectionManager` 已完成实体 section 读盘。
- `ServerLevel#areEntitiesLoaded(long)` 是 1.19.2 的公开 API；进入 `WAIT_ENTITY_LOAD` 后必须对 `center + radius` 内的区块逐块检查该值，全部为 true 才允许定位。
- 未全部 LOADED 时不得判 `ENTITY_UNRESOLVED`；保持 pending，由服务端 tick 在 deadline 内重试。主线程不得读取实体 NBT、反射调用 `processPendingLoads` 或同步阻塞等待。
- 所有目标区块 future 成功且实体 section 全部 LOADED 后，才执行实体定位。
- ticket 添加成功后才记录 `ticketAdded=true`；释放时按同一 `center/radius` 精确移除。

### 5.3 非阻塞调用约束

1.19.2 的 `ServerChunkCache#getChunkFuture(...)` 在主线程调用时会通过 `managedBlock` 等待；因此禁止在 `RequestSummonPacket`、命令执行线程或 `ServerTickEvent` 中同步调用。

正确流程：

1. 在服务端线程校验请求，并在目标 `ServerChunkCache` 上添加 ticket。
2. 将“收集所有 `getChunkFuture(...)`”的任务提交给 `Util.backgroundExecutor()`。
3. 后台任务中，对 `ChunkPos.rangeClosed(center, radius)` 逐块收集 future；不得在主线程 join。
4. 使用 `CompletableFuture.allOf(...)` 或等价的聚合 future 等待全部完成；单个 future 的结果仍要检查 `Either` 左值/右值。
5. 回调统一 `server.execute(...)` 回到服务端线程，比较 `requestId` 后推进状态机。
6. 如果后台任务尚未开始、已经开始或已经完成时收到取消，只移除 ticket 和 pending 记录；后台回调通过 `requestId` 幂等忽略。

`loadFutures` 只保存 future 句柄，不在主线程做 `join()`；所有异常都必须在服务端线程转成 `CHUNK_LOAD_FAILED` 或 `CANCELLED`。

## 6. 请求状态机

判定顺序固定为：档案/归属/存活校验 → 同 companion pending 检查 → 玩家/全服 pending 上限 → cooldown → 已加载实体定位 → `summoned=false` 同 UUID 守卫/合法重建 → `summoned=true` 未加载远召开关/维度/位置/加载。不能先做实体定位再检查 pending，否则同一请求在加载期间可能绕过 `ALREADY_PENDING`。

| 当前状态 | 事件 | 下一状态 | 动作 |
|---|---|---|---|
| 无 | 档案缺失 | TERMINAL | 返回 `NOT_FOUND`，不改状态 |
| 无 | 非档案 owner | TERMINAL | 返回 `NOT_OWNER`，不改状态 |
| 无 | `alive=false` | TERMINAL | 返回 `NOT_ALIVE`，不改状态；复活仍走既有流程 |
| 无 | 同 companion 已有请求 | TERMINAL | 返回 `ALREADY_PENDING` |
| 无 | 玩家或全局 pending 达到上限 | TERMINAL | 返回 `TOO_MANY_PENDING` |
| 无 | 同一玩家 + companion 仍在 cooldown | TERMINAL | 返回 `COOLDOWN` |
| 无 | `summoned=true` 且已加载实体 | TERMINAL | 走已加载传送，返回 `COMPLETED_TELEPORT`；不添加 ticket |
| 无 | 请求 `summoned=false` | TERMINAL | 先在已加载索引按 `entity_uuid` 查同 UUID 实体；命中则返回 `ENTITY_UNRESOLVED` 拒绝重建，未命中才走合法快照重建，返回 `COMPLETED_REBUILD`；受活跃上限和既有重建规则约束，不受远召开关影响 |
| 无 | 已召唤、未加载、远召关闭 | TERMINAL | 返回 `DISABLED`，不改档案 |
| 无 | 已召唤且维度缺失 | TERMINAL | 返回 `DIMENSION_MISSING`，不改档案、不添加 ticket |
| 无 | 已召唤且无位置 | TERMINAL | 返回 `NO_POSITION`，不改档案、不添加 ticket |
| 无 | 已召唤且有位置、允许远召 | WAIT_CHUNK | 添加 ticket，后台注册 future；重建/复活分支不进入此状态 |
| WAIT_CHUNK | 超过 deadline | TERMINAL | 释放 ticket，返回 `TIMEOUT` |
| WAIT_CHUNK | future 成功 | WAIT_ENTITY_LOAD | 服务端线程重新校验，等待实体 section 完成异步读盘 |
| WAIT_CHUNK | future 失败/右值/异常 | TERMINAL | 释放 ticket，返回 `CHUNK_LOAD_FAILED` |
| WAIT_ENTITY_LOAD | 超过 deadline | TERMINAL | 释放 ticket，返回 `TIMEOUT` |
| WAIT_ENTITY_LOAD | `ServerLevel#areEntitiesLoaded(long)` 尚未全部为 true | WAIT_ENTITY_LOAD | 不定位、不释放 ticket；由服务端 tick 在 deadline 内重试 |
| WAIT_ENTITY_LOAD | 实体 section 全部 LOADED 且 UUID 命中 | TERMINAL | 传送，成功返回 `COMPLETED_TELEPORT` |
| WAIT_ENTITY_LOAD | 实体 section 全部 LOADED 且 UUID 未命中 | TERMINAL | 返回 `ENTITY_UNRESOLVED` |
| WAIT_ENTITY_LOAD | 发现重复实体 | TERMINAL | 返回 `DUPLICATE_CONFLICT`，不自动选择 |
| 任意 pending | 取消条件 | TERMINAL | 释放 ticket，返回 `CANCELLED` |

状态进入 `TERMINAL` 后必须幂等清理，重复的 future 回调只能记录并忽略，不能再次传送。除 `NOT_FOUND`、`NOT_OWNER`、`NOT_ALIVE`、`ACTIVE_LIMIT`、`REBUILD_FAILED`、`ALREADY_PENDING`、`TOO_MANY_PENDING`、`COOLDOWN` 这类“未创建待处理请求”的结果外，终态统一记录 `(playerUuid, companionId)` 的 cooldown 到期时间；`DISABLED`、`DIMENSION_MISSING`、`NO_POSITION`、`TIMEOUT`、`CHUNK_LOAD_FAILED`、`COMPLETED_TELEPORT` 都按服务端配置抑制连点。

重复实体检测使用 P1 的 `FurkinDuplicateRegistry`：实体 section 已 LOADED 后若 `hasLoadedDuplicate(server, companionId)` 为真，先释放 ticket，再返回 `DUPLICATE_CONFLICT`。不得为了这个检查在远召路径调用 `findAllLoaded(...)` 或遍历所有已加载实体。

## 7. 取消矩阵

以下事件必须调用对应 cancel：

| 事件 | 取消范围 | 原因 |
|---|---|---|
| `PlayerLoggedOutEvent` | 该玩家全部请求 | `PLAYER_LOGOUT` |
| 实体死亡 | 该 companion | `ENTITY_DEATH` |
| 收回成功 | 该 companion | `DISMISSED` |
| 解绑 / 强制解绑 | 该 companion | `UNBOUND` |
| canonical UUID 变化 | 该 companion | `CANONICAL_CHANGED` |
| `ServerStoppingEvent` | 当前 server 全部请求 | `SERVER_STOPPING` |
| deadline 到期 | 该请求 | `TIMEOUT` |
| 区块加载 future 失败 | 该请求 | `CHUNK_LOAD_FAILED` |

取消函数必须：

- 释放 ticket，即使加载 future 尚未完成。
- 从 pending map 移除请求。
- 幂等。
- 记录 `requestId`、`companionId`、`reason` 和 `ticketReleased`。
- 不修改 `summoned`、`alive`、`entityUuid`、`entityDimension`、`entityPos`。

玩家换维度不是取消条件。pending 请求只记录实体所在的 `targetDimension`；终态传送时重新读取 `player.getLevel()` 作为目标 Level，因此玩家在等待期间换维度仍可正常召唤到新位置。若实现选择缓存玩家旧 Level，则必须在终态比较当前 Level 与缓存值，并在不一致时使用当前 Level。

## 8. 入口接入

### 8.1 网络包

`RequestSummonPacket` 字段、方向、网络 ID 不变：

1. 服务端 handler 继续在 `ctx.enqueueWork(...)` 内执行。
2. 调用 `RemoteSummonService.request(player, companionId, RemoteSummonOrigin.RECORD)`。
3. 即时完成：按 `RemoteSummonResult` 发送既有成功/失败提示。
4. pending：发送 `remote_summon_pending` action bar。
5. 异步终态：service 通过服务端消息通道向仍在线的玩家发送终态提示；同一结果只发送一次。成功终态同时调用现有 `FurkinRecordItem.refreshRecordList(player)`，失败终态不伪造列表状态。
6. 不新增 `RemoteSummonResult` 的线格式字段，P2 第一版不升 `PROTOCOL_VERSION`。

### 8.2 命令

`/furkin summon <id>` 继续复用同一服务，`origin=COMMAND`：

- 命令源必须是 `ServerPlayer`。
- 即时成功、即时失败和 pending 都按结果发送命令回执；pending 使用 `furkin.command.summon.remote_pending`。
- 终态异步发送给玩家；pending 终态不能改变命令退出码。
- 不允许命令阻塞等待未来完成，也不允许在主线程调用 `getChunkFuture`。

## 9. 配置契约

`FurkinServerConfig` 新增：

| 键 | 默认 | 允许范围 | 语义 |
|---|---:|---:|---|
| `remoteSummonEnabled` | `true` | boolean | 总开关 |
| `remoteSummonTicketRadius` | `1` | `0..2` | 中心 ticket 传播半径 |
| `remoteSummonTimeoutTicks` | `600` | `20..600` | service 等待 deadline |
| `remoteSummonMaxPendingPerPlayer` | `1` | `1..4` | 每玩家 pending 上限 |
| `remoteSummonMaxPendingGlobal` | `4` | `1..64` | 全服 pending 上限 |
| `remoteSummonCooldownTicks` | `20` | `0..200` | 同一玩家 + companion 终态后的再次请求抑制 |

约束：

- 配置读取只在服务端生效；客户端不根据配置预测结果。
- `remoteSummonEnabled=false` 时，已加载实体传送仍可成功，`summoned=false` 的合法重建也不受影响；只有 `summoned=true` 且运行时索引未命中的远距加载请求返回 `DISABLED`。
- `remoteSummonMaxPendingGlobal` 达到上限时，任何新增 pending 返回 `TOO_MANY_PENDING`；已创建的 pending 不受影响。
- 配置变更不允许更改已经创建的 pending 请求语义；下一次请求使用新值。
- 不增加永久强加载配置；长期保留加载必须另立功能包。

实施状态（2026-09-27）：6 个键已在 `FurkinServerConfig` 落地，范围与上表一致。`RemoteSummonService` 不再使用冻结常数；`remoteSummonEnabled=false`、cooldown 配置、radius=2 与每玩家 pending 上限均已用一次性服务端夹具验证。`remoteSummonTimeoutTicks` 只在请求创建时读取并写入 deadline。P2.5 历史默认值是 100；P2.6 性能收口后当前默认值为 600，见 11.14。

## 10. 文案契约

必须同步 `en_us.json` 与 `zh_cn.json`：

- `furkin.msg.remote_summon_pending`
- `furkin.msg.remote_summon_completed`
- `furkin.msg.remote_summon_no_position`
- `furkin.msg.remote_summon_unresolved`
- `furkin.msg.remote_summon_timeout`
- `furkin.msg.remote_summon_chunk_failed`
- `furkin.msg.remote_summon_duplicate`
- `furkin.msg.remote_summon_disabled`
- `furkin.msg.remote_summon_already_pending`
- `furkin.msg.remote_summon_too_many_pending`
- `furkin.msg.remote_summon_cooldown`
- `furkin.msg.remote_summon_cancelled`
- `furkin.command.summon.remote_pending`
- `NOT_FOUND` / `NOT_OWNER` / `NOT_ALIVE` / `ACTIVE_LIMIT` / `REBUILD_FAILED` 复用现有 `furkin.msg.*` 或 `furkin.command.summon.*` 键，不另造同义文案。

持久化包和网络同步包不得加入行囊物品或完整实体 NBT。

## 11. 验证夹具

### 11.1 位置夹具

临时服务端命令或调试入口：

```text
/furkin debug remote setpos <companion_id> <dimension> <x> <y> <z>
/furkin debug remote unload <companion_id>
/furkin debug remote failchunk <companion_id>
/furkin debug remote timeout <companion_id>
```

要求：

- 仅开发环境可用，验证后移除。
- `setpos` 只写档案位置，不创建或移动实体。
- `unload` 只方便制造未加载场景，不替代真实远召验证。
- `failchunk` 注入一次 future 右值。
- `timeout` 注入一次不完成 future。
- 所有夹具必须走服务端线程，不留下永久 ticket。

### 11.2 最小实机步骤

1. 契约、穿装备、放行囊，记录 `companionId`、`entityUuid`、位置。
2. 让目标区块卸载；用 `setpos` 确认档案位置与实体最后位置一致。
3. 从远处执行远召。
4. 预期日志按顺序出现：

```text
remote summon request=<id> companion=<uuid> center=<chunk> radius=<n>
remote summon loaded request=<id> elapsed=<ticks>
remote summon completed request=<id> result=COMPLETED_TELEPORT elapsed=<ticks>
remote summon cleanup request=<id> reason=COMPLETED ticketReleased=true
```

5. 确认实体 UUID 不变，装备、行囊、等级、技能不变。
6. 连续请求 20 次，确认 `pendingCountForTests()` 最终为 0、额外实体为 0、ticket 记录不持续增长。

### 11.3 P2.4 入口与反馈夹具（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_04=1`，验证命令与绒亲录两条入口的即时 / 异步反馈接线：

- 夹具用 `FakePlayer` 子类捕获 `displayClientMessage`，并用自定义 `CommandSource` 捕获命令 `sendSuccess` / `sendFailure`。
- 档案条目指向 Nether `(1024, 64, 1024)`、`summoned=true`，但不创建真实实体；异步区块加载完成后按预期返回 `ENTITY_UNRESOLVED`。
- 夹具预先把目标 3x3 区块载入为 `FULL`，避免首次世界生成把 100 tick 超时误当产品失败；真实冷加载由 P2.3 夹具覆盖。
- 断言：
  - 命令路径：即时 `furkin.command.companion.not_found`、`PENDING` 的 `furkin.command.summon.remote_pending`、异步 `furkin.command.summon.entity_unresolved`。
  - 绒亲录路径：即时 `furkin.msg.summon_not_found`、`PENDING` 的 `furkin.msg.remote_summon_pending`、异步 `furkin.msg.remote_summon_unresolved`。
  - 两次异步终态后 `pendingCountForTests()` / `ticketCountForTests()` 归零。
- 日志顺序：`FURKIN_FIXTURE_P2_04_PREPARED`、`..._COMMAND_NOT_FOUND`、`..._COMMAND_PENDING`、`..._COMMAND_OK`、`..._PACKET_NOT_FOUND`、`..._PACKET_PENDING`、`..._PACKET_OK`、`FURKIN_FIXTURE_P2_04_OK`。
- 夹具验证后已从源码树删除；不得作为正式入口保留。

### 11.4 P2.5 配置夹具（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_05=1`：

- 断言 6 个键的默认值与冻结契约一致。
- `remoteSummonEnabled=false`：`summoned=false` 的合法重建返回 `COMPLETED_REBUILD`，随后已加载传送返回 `COMPLETED_TELEPORT`，证明关闭只作用于远程加载。
- `remoteSummonCooldownTicks=0` 时同一 companion 连续两次 `DIMENSION_MISSING` 不被冷却拦截；改回 20 后下一次重复请求返回 `COOLDOWN`。
- `remoteSummonTicketRadius=2` 时日志出现 `radius=2`；pending 期间第二个 companion 返回 `TOO_MANY_PENDING`；终态 `ENTITY_UNRESOLVED ... ticketReleased=true`，pending / ticket 归零。
- 生成的 `furkin-server.toml` 含 6 个键与注释；夹具结束时把运行中的临时值恢复默认。
- 夹具验证后源码移除；最终 jar 与干净回归均不含 fixture / `internal.debug`。

### 11.5 P2.6 服务端边界夹具（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_06=1`：

- timeout：夹具把请求 deadline 置为当前 tick 之前，再调用 service tick；结果为 `TIMEOUT`、`ticketReleased=true`，pending / ticket 归零。
- global pending：`remoteSummonMaxPendingGlobal=2`、每玩家上限 4；三次请求依次返回 `PENDING`、`PENDING`、`TOO_MANY_PENDING`；随后 `cancelAll(SERVER_STOPPING)` 清理已受理请求，两条均释放 ticket。
- 夹具使用已加载出生区块构造“档案存在、目标实体未解析”场景，只验证 deadline 清理和全服 pending 上限，不替代真实未加载区块的 P2.3 夹具。
- 夹具验证后源码从 `internal.debug` 删除；最终 jar 与干净回归均不含 fixture / `internal.debug`。

### 11.6 P2.6 客户端定向夹具（2026-09-27 已完成）

一次性客户端夹具，环境变量 `FURKIN_FIXTURE_P2_06_CLIENT=1`：

- 通过真实 `FurkinClientPacketHandler.handleRecordList(...)` 打开 `FurkinRecordScreen`，验证远召控件存在且可用，并覆盖 `openScreen=false` 的就地刷新与 `onClose()` 返回路径。
- 第二次运行日志 `FURKIN_FIXTURE_P2_06_CLIENT_OPENED`、`..._STATE summonPresent=true summonActive=true pendingFeedback=server-action-bar`、`..._REFRESH_OK`、`..._OK` 全部出现；简体中文下无缺失远召翻译键。
- 第一次运行因夹具主动 `Minecraft.close()` 后主循环访问已关闭 GLFW 而崩溃，定位为夹具生命周期错误；产品代码未因此修改。第二次运行通过。
- 夹具源码已删除；夹具后的 `clean build` 通过，最终 jar 不含 `RemoteSummonClientP206Fixture` / `internal.debug` / fixture 类。
- 边界：该夹具只证明客户端结构路径，不替代真实玩家点击后的异步成功 action bar、完整列表刷新端到端与 MSPT / tick spike 实测。

### 11.7 P2.6 生命周期边界夹具（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_06_LIFECYCLE=1`：

- 夹具用真实事件处理方法与生产入口覆盖 `PLAYER_LOGOUT`、`ENTITY_DEATH`、`DISMISSED`、`UNBOUND`、`CANONICAL_CHANGED`、`SERVER_STOPPING`；每次终态均断言 pending / ticket 归零。
- 除登出/停服按契约抑制回调外，其余取消路径均验证一次 `CANCELLED` 回调；死亡和收回同时校验档案状态语义。
- 夹具为验证回调临时把 FakePlayer 放入 `PlayerList` UUID 索引，验证后移除，不修改产品代码。
- 夹具源码删除后 `clean build` 通过，无夹具 `runServer` 达到 `Done (2.357s)`；最终 jar 不含 fixture / `internal.debug`。
- 边界：当时仍未覆盖 `CHUNK_LOAD_FAILED` 注入、服务器重启、pending 换维度、连续 20 次远召资源回归与 MSPT；其中服务器重启已由 11.13 补测，pending 换维度已由 11.12 补测，真实玩家点击的异步成功 action bar 已由 11.8 补测，重复实体冲突已由 11.10 补测。

### 11.8 P2.6 真实异步成功端到端（2026-09-27 已完成）

一次性客户端集成夹具，环境变量 `FURKIN_FIXTURE_P2_06_ASYNC=1`、`FURKIN_FIXTURE_P2_06_ASYNC_LEVEL=<临时世界>`：

- 夹具在真实单人世界复制品中创建带 `FurkinData` 的 `ARMOR_STAND` 存档实体，写入 `summoned=true`、canonical UUID、维度与位置，然后通过公开 `ServerLevel#save(null, true, false)` 让 `PersistentEntitySectionManager#saveAll(...)` 写入 1.19.2 的 `entities/*.mca`。
- 移除临时 ticket、确认 chunk 与运行时实体索引都已卸载；通过真实 `FurkinRecordItem.sendRecordList(...)` 打开真实 `FurkinRecordScreen`，调用真实“召唤”按钮触发 `RequestSummonPacket`。
- 首次运行暴露产品缺陷：目标区块 `ChunkStatus.FULL` future 完成后，实体 section 仍在异步读盘，原实现立即 `locate(...)` 并错误返回 `ENTITY_UNRESOLVED`。夹具 NBT 证据显示 `DIM-1/entities/r.1.1.mca` 中实体、UUID、位置、`ForgeCaps/furkin:furkin_data` 全部存在。
- 修复后 service 新增 `WAIT_ENTITY_LOAD` 状态：只有 `ChunkPos.rangeClosed(center, radius)` 全部 `ServerLevel#areEntitiesLoaded(long) == true` 才定位；未就绪则在 pending/deadline 内由服务端 tick 重试。
- 成功运行日志：`remote summon request=1 ... pending=1`、`remote summon completed request=1 ... result=COMPLETED_TELEPORT reason=COMPLETED ticketReleased=true`、`FURKIN_FIXTURE_P2_06_ASYNC_OK pendingSeen=true successText=远距召唤完成 refreshed=true sameScreen=true`。
- 同时验证 action bar `furkin.msg.remote_summon_completed`、真实 `FurkinRecordScreen` 实例不变、`entries` 列表对象被替换，以及成功后 ticket 释放。
- 夹具运行期间的 `Can't keep up!` 来自一次性世界复制、同步生成/保存和夹具调试，不计入产品 MSPT 结论；连续 20 次请求和真实性能网格仍待 P2.6 后续。
- 夹具源码已删除，临时世界复制品已删除；`clean build` 通过，无夹具 `runServer` 达到 `Done`，无夹具 `runClient` 正常进入标题界面，最终 jar 不含 fixture / `internal.debug`。

### 11.9 P2.6 GUI 加载态 / 重复点击实际输入（2026-09-27 已完成）

一次性客户端集成夹具，环境变量 `FURKIN_FIXTURE_P2_06_GUI=1`、`FURKIN_FIXTURE_P2_06_GUI_LEVEL=<临时世界>`：

- 复制 `新的世界` 后由客户端夹具自动加载临时世界；服务端夹具创建一条只用于测试的 `summoned=true` 档案，并通过真实 `RecordListPacket` 打开真实 `FurkinRecordScreen`，不修改原存档。
- 在真实屏幕坐标系上调用 `Screen#mouseClicked(...)` 点击真实“召唤”按钮。首次点击后断言按钮 `active=false`、文案为 `召唤中……`；随后同一位置再次点击，断言事件未被消费且按钮状态不变。
- 服务端 tick 观测到 `pending=1`；目标档案最终返回 `ENTITY_UNRESOLVED`，既有 `RecordListPacket(openScreen=false)` 到达后按钮恢复为可用的“召唤”。
- 成功日志：`FURKIN_FIXTURE_P2_06_GUI_FIRST_CLICK consumed=true active=false label=召唤中……`、`..._SECOND_CLICK consumed=false active=false label=召唤中……`、`..._SERVER_PENDING pending=1`、`..._OK ... refreshed=true unlocked=true`。
- 实现口径按 D-19：GUI 本地只保留一个在途请求；服务端对每个非 `PENDING` 结果刷新列表，客户端 620 tick 兜底解锁。未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`；服务端 `ALREADY_PENDING` 仍是权威防线。
- 夹具源码与临时世界已删除；随后 `clean build` 通过，无夹具 `runServer` 达到 `Done (2.260s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`，最终 jar 不含 fixture 类。
- 边界：本夹具验证了真实屏幕鼠标输入、在途按钮态、重复点击抑制、服务端 pending 和终态刷新解锁；不替代实机 MSPT / tick spike、连续 20 次资源回归与真实成功实体传送时的装备/行囊保留验证。

### 11.10 P2.6 重复实体冲突与合法重建（2026-09-27 已完成）

一次性服务端夹具，使用最终源码版本重跑：

- 重复实体冲突：环境变量 `FURKIN_FIXTURE_P2_06_DUP_MODE=prepare|verify`。canonical 位于远处未加载位置，同 `companionId` 的重复实体已在出生区块加载；真实 `RemoteSummonService.request(...)` 返回 `DUPLICATE_CONFLICT`，不传送 canonical、不删除重复体，档案 UUID / 维度 / 位置 / `summoned` / `alive` 不变，pending / ticket 归零。日志：`FURKIN_FIXTURE_P2_06_DUP_OK result=DUPLICATE_CONFLICT canonicalLoaded=false ... duplicateLoaded=true ... pending=0 tickets=0`。
- 孤儿/重复体重建守卫：环境变量 `FURKIN_FIXTURE_P2_06_ORPHAN_MODE=orphan-prepare|orphan-verify`。档案为 `summoned=false` 且 `entity_uuid=null`，同 `companionId` 的孤儿实体已加载；请求返回 `DUPLICATE_CONFLICT`，`candidateCount=1`，孤儿实体仍加载，档案保持未召唤且定位字段为空。日志：`FURKIN_FIXTURE_P2_06_ORPHAN_OK result=DUPLICATE_CONFLICT ...`。
- 合法重建回归：环境变量 `FURKIN_FIXTURE_P2_06_LEGAL_MODE=legal-prepare|legal-verify`。档案为 `summoned=false` 且没有任何已加载同身份实体；请求仍返回 `COMPLETED_REBUILD`，最终 `candidateCount=1`。日志：`FURKIN_FIXTURE_P2_06_LEGAL_OK result=COMPLETED_REBUILD ...`。
- 日志级别：合法 rebuild 时 `CommonEvents` 的“archive not summoned”入世记录为 `DEBUG`；重复/孤儿冲突则由请求路径输出 `WARN` 诊断，避免合法重建刷异常日志。
- 实现口径：`FurkinDuplicateRegistry` 的记录集从“只记录重复体”改为“记录所有已加载的已契约实体”，查询时排除档案当前 canonical UUID；`entry.getEntityUuid()==null` 时任何同 `companionId` 的已加载实体都算冲突。
- 加载成本前置检查（D-21，2026-09-27）：canonical 未加载且重复体已加载时，在配置、维度、位置校验通过后、添加临时 ticket 前直接返回 `DUPLICATE_CONFLICT`。夹具日志：`FURKIN_FIXTURE_P2_06_DUP_PRECHECK_OK result=DUPLICATE_CONFLICT registered=true canonicalLoaded=false duplicateLoaded=true pending=0 tickets=0 archiveUnchanged=true`。异步 `WAIT_ENTITY_LOAD` 阶段保留同一检查，覆盖重复体在加载期间才入世的情况。
- 夹具验收后从源码删除；`clean build` 通过，最终 jar 为 `build/libs/furkin-1.19.2-0.0.2.0.jar`（SHA-256 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`），jar 内无 `internal.debug` / fixture 类。无夹具 `runServer` 达到 `Done (2.251s)`，日志无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`；PTY 未转发 `stop`，终止批处理后无残留 Java 进程。

### 11.11 P2.6 CHUNK_LOAD_FAILED 故障注入（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_06_CHUNK_FAIL=1`，使用临时世界 `furkin_p2_06_chunk_fail_20260927`：

- 夹具创建 `summoned=true`、canonical UUID 未加载、维度与位置有效的档案，并通过真实 `RemoteSummonService.request(...)` 进入 pending；断言 `pendingBefore=1`、`ticketBefore=1`。
- 在请求仍为当前 pending 时，向真实 `onLoadFinished(...)` 注入 `Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED)`，模拟 chunk future 返回加载失败。
- 终态断言为 `CHUNK_LOAD_FAILED`；服务日志记录 `ticketReleased=true`，pending / ticket 归零；档案 UUID、维度、位置、`summoned`、`alive`、owner 均不变，目标位置没有生成新实体。
- 成功日志：`FURKIN_FIXTURE_P2_06_CHUNK_FAIL_OK result=CHUNK_LOAD_FAILED pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 archiveUnchanged=true entityAbsent=true`。
- 边界：该夹具覆盖 1.19.2 `ChunkHolder.ChunkLoadingFailure` 的正常右值失败形态以及 ticket / pending / 档案清理；异常 future 的 throwable 分支沿用同一 `finishPending(...)` 清理路径，玩家反馈已由 11.5、11.8 独立覆盖。
- 夹具源码、临时世界和运行配置已清理；最终 `clean build` 通过，jar 内无 `internal.debug` / fixture 类。无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`。

### 11.12 P2.6 pending 换维度（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_06_DIMENSION=1`，使用临时世界 `furkin_p2_06_dimension_20260927`：

- 夹具在 Overworld 创建 `summoned=true`、canonical UUID、维度与位置的档案，通过真实 `RemoteSummonService.request(...)` 进入 `PENDING`；确认 `pendingBefore=1`、`ticketBefore=1`。
- 请求处于 `WAIT_ENTITY_LOAD` 时，将 FakePlayer 传送到 Nether，再在 Overworld 目标位置创建带 `FurkinData` 的 canonical 实体，反射调用真实 `onLoadFinished(request, List.of(), null)`。
- 首次夹具运行在终态后立即查询 Nether 实体，因目标区块尚未完成实体入世查询而失败；夹具改为预加载目的地 chunk 并在终态后等待异步实体入世，产品代码未因此修改。
- 成功日志：`FURKIN_FIXTURE_P2_06_DIMENSION_OK result=COMPLETED_TELEPORT pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 playerDimension=minecraft:the_nether targetDimension=minecraft:the_nether archiveInNether=true canonicalPreserved=true nearPlayer=true`。
- 断言覆盖：请求不因玩家换维度取消；终态使用玩家当前 Nether Level；实体与档案均为 Nether；canonical UUID 未变化；pending / ticket 归零；目标实体位于主人身边。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；最终 `clean build` 通过，jar 无 `internal.debug` / fixture 类，SHA-256 为 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`；无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`，仅保留基线 Minecraft `TagLoader` `ERROR` 与历史 `Legacy Furkin AI state` WARN。

### 11.13 P2.6 服务器重启（2026-09-27 已完成）

一次性服务端两阶段夹具，环境变量 `FURKIN_FIXTURE_P2_06_RESTART=1`，使用临时世界 `furkin_p2_06_restart_20260927`：

- 第一阶段创建两条持久档案：A 带 Overworld 远距位置与 canonical UUID，B 带 canonical UUID / 维度但缺 `entity_pos`；通过真实 `RemoteSummonService.request(...)` 让 A 进入 `PENDING`，确认 `pending=1`、`tickets=1`。
- 第一阶段调用 `MinecraftServer#saveEverything(false, true, false)` 后正常停服。停服日志为 `remote summon completed ... result=CANCELLED reason=SERVER_STOPPING ticketReleased=true`，说明临时 ticket 不进入持久状态。
- 第二阶段用同一临时世界重启：新 service 的 `stalePending=0`、`staleTickets=0`；A、B 的 owner、UUID、维度、位置或缺失位置以及 alive / summoned 均保持。
- 重启后对 A 再次真实请求返回 `PENDING`，证明旧 pending 没有被恢复；随后显式取消，`cancelPending=0`、`cancelTickets=0`。
- 对 B 请求返回 `NO_POSITION`，`missingPending=0`、`missingTickets=0`，档案不变。成功日志：`FURKIN_FIXTURE_P2_06_RESTART_OK stalePending=0 staleTickets=0 reRequest=PENDING reRequestPending=1 reRequestTickets=1 cancelPending=0 cancelTickets=0 missingPosition=NO_POSITION missingPending=0 missingTickets=0 archiveUnchanged=true`。
- 该夹具验证服务器重启不持久化内存 pending / ticket，也不会让缺位置档案绕过安全失败；未修改生产代码，未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；最终 `clean build` 通过，jar 无 `internal.debug` / fixture 类，SHA-256 为 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`；无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`，仅保留基线 Minecraft `TagLoader` `ERROR` 与历史 `Legacy Furkin AI state` WARN。

### 11.14 P2.6 性能收口与 4 并发压测（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_P2_06_CONCURRENT`，临时世界 `furkin_p2_06_concurrent_20260927`。夹具先以 seed 阶段把 5 个真实可解析的绒亲实体写入目标区块并保存；随后以 measure 阶段冷启动，执行 1 次单请求、4 次冷区并发；再把实体移回原目标位置并保持目标区块加载，完成 4 次热区并发。

- 连续 20 次远召资源回归：timeout 临时放宽至 900 tick 后通过，首轮冷区 `maxElapsedTicks=434`，后续请求回落；最终 pending / tickets 为 0，无额外实体、无 ticket 泄漏。该结果不能写成默认 100 tick 通过；据此把默认 timeout 判定为 600 tick。
- 20 轮串行 MSPT：baseline（100 tick）`avg=4.43ms,max=10.38ms,over50=0`；measure（1320 tick）`avg=3.69ms,max=54.35ms,over50=16,over100/200/500=0`；post（100 tick）`avg=1.63ms,max=40.74ms`；loaded `2209 -> 3170 -> 2209`；`Can't keep up=0`，ERROR/FATAL=0。
- 单请求 chunk-map 分解：baseline loaded=2209；冷 radius=0 峰值 3050（+841）；冷 radius=1 峰值 3170（+961）；热 radius=0 峰值 3050（+841）；热 radius=1 峰值 3170（+961）。loaded 是 `visibleChunkMap` 工作集，包含生成管线与 ticket 传播 holder，不等于实体 ticking 区块数；radius 从 1 降到 0 只省约 12.5%，故保留默认 radius=1。
- 4 并发冷 / 热结果：

| 批次 | 完成 | 主线程最慢 tick | >50ms | >200ms | 窗口等效 TPS | 结果 |
|---|---:|---:|---:|---:|---:|---|
| 冷区单请求 | 16 ticks / 846.9ms | 51.84ms | 1 | 0 | 18.9 | `COMPLETED_TELEPORT` |
| 冷区 4 并发 | 26 ticks / 1675.2ms | 378.22ms | 1 | 1 | 15.5 | 4/4 `COMPLETED_TELEPORT` |
| 热区 4 并发 | 22 ticks / 1529.0ms | 431.41ms | 1 | 1 | 14.4 | 4/4 `COMPLETED_TELEPORT` |

- 9/9 请求成功；pending / tickets 最终为 0；无 duplicate、timeout、chunk-load failure、ticket 泄漏；无 `Can't keep up!`、ERROR、FATAL。4 并发 loaded 峰值 6053，但该数是工作集，不等于 6053 个可玩区块；快照 full=529、tickingChunk=441。
- 玩家侧成本：单次冷区最慢约 52ms，通常只是轻微一帧停顿；4 并发 cold / hot 各有一次约 0.38–0.43 秒的主线程尖峰，表现为全服一次性短暂卡顿，之后恢复，不是持续掉 TPS。热区并不显著便宜，主要成本来自同时加载 / 定位 / 传送 / 同步 / GC，而不是区块是否已缓存。
- 该结论由服务端主线程 tick 指标推导，不是客户端 FPS 抓帧；客户端帧率仍受渲染、网络和本机性能影响。
- 资源判定：开发 JVM used heap 峰值约 2781MB；低内存生产环境应按至少 4GB 堆评估，不能把该峰值当作固定每请求开销。由于 20 次回归和 4 并发成功，最终判定 `remoteSummonTimeoutTicks=600`、`remoteSummonTicketRadius=1`、`remoteSummonMaxPendingGlobal=4`；不采用未经 2 并发实测的更低上限替换已验证语义。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；`clean build` 通过；最终 jar 无 `internal.debug` / fixture 类，SHA-256 为 `A5CC444CE2229AF4795C7206741F68E15F75383ABCC0CC560F5FC4B0AC2B5A08`；无夹具 `runServer` 达到 `Done (20.190s)`，生成配置已确认 `remoteSummonTimeoutTicks=600`；`latest.log` 无 fixture、Furkin 专属 ERROR / FATAL 或 `Can't keep up!`。

### 11.15 旧档 v0 / v1 迁移夹具（2026-09-27 已完成）

一次性服务端夹具，环境变量 `FURKIN_FIXTURE_D_MIGRATION=verify-v0|verify-v1`，临时世界 `furkin_d_migration_v0_20260927` / `furkin_d_migration_v1_20260927`，不修改其他世界。夹具在 `ServerAboutToStartEvent` 阶段直接写旧格式 NBT 档案（主世界 `data/furkin_archive.dat`，兼容分支再写 `DIM-1/data/furkin_archive.dat`），在 `ServerStartedEvent` 阶段调用真实 `FurkinArchiveData.get(server)` 触发迁移，并断言迁移前后字段一致。

- v0：主世界写无 `data_version` 的条目，Nether 写一条 legacy 条目和一条同 ID 冲突条目。迁移日志 `Furkin archive data version migrated: 0 -> 2`、`imported=1, conflicts=1`；`FURKIN_FIXTURE_D_V0_STATE_OK ... conflictKeptOverworld=true` 证明 owner / entity UUID / 维度 / `summoned` / `alive` / level 保持，Nether legacy 条目被导入，同 ID 冲突保留主世界条目且不静默覆盖。
- v1：主世界写 `data_version=1`，含一条 `summoned=false`（战斗模式 `AGGRESSIVE`）和一条 `summoned=true`（`entity_uuid` + overworld 维度）条目，并删除 Nether 旧档案。迁移日志 `Furkin archive data version migrated: 1 -> 2`；`FURKIN_FIXTURE_D_V1_STATE_OK` 证明两个条目字段（含非默认战斗模式）逐项保持。
- 两条分支都断言迁移后 `entity_pos` 仍为 `null`：迁移不补位置、不扫描实体、不改 `summoned` / `alive` / `entity_uuid`。
- 版本断言 `FURKIN_FIXTURE_D_VERSION_OK dataVersion=2`：迁移后落盘的档案 `data_version` 为 `2`。
- 关键日志：`FURKIN_FIXTURE_D_V0_MIGRATION_OK`、`FURKIN_FIXTURE_D_V1_MIGRATION_OK`。
- 原始日志：`D:\frukin_dev\_research\p1_migration_v0_20260927.log`、`D:\frukin_dev\_research\p1_migration_v1_20260927.log`（DEBUG 明细分别见同名 `.debug.log`）。
- 口径：v0 首轮夹具运行因夹具断言主键与顶层 NBT wrapper 写错而失败，属夹具缺陷，已修正后重跑通过；产品代码在该轮未改动。旧 Nether 档案文件保留作回滚副本，不主动删除。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；最终 jar 不含 fixture / `internal.debug`。统一四项 Gradle 门槛与日志检查由模块 E 完成，见 `verification-matrix.md` §5.17。
## 12. 性能与回滚

- `remoteSummonTicketRadius=1` 时每个请求最多关注 3x3 区块；半径 2 时是 5x5，必须按 25 个区块估算。
- 全局 pending 上限默认 4，因此默认最多 36 个待完成区块 future；每次加载完成、失败或取消后立即释放 ticket。
- 默认 600 tick（30 秒）内仍加载不完时，超时优先于无限等待；ticket 的 800 tick timeout 只是清理失败时的安全网。
- 如果服务器主线程出现明显阻塞，不得改成永久强加载，应减小半径、降低全局 pending、增加超时或关闭功能。
- 区块生成、磁盘 I/O 和模组实体初始化仍可能造成 tick spike；“有界临时 ticket”不等于零成本，必须在 P2.6 记录实际数据。
- 紧急回滚：将 `remoteSummonEnabled=false`，P0/P1 继续生效。
- 代码回滚不得移除 P0/P1；数据字段为追加字段，旧版本读取时忽略即可。

## 13. P2 完成定义

- [x] 已加载同维度和跨维度传送无回归（§5.2 / §5.3 / §5.9 / §5.12 / §5.13 / §5.15）。
- [x] 未加载同维度和跨维度均可临时加载、按 UUID 定位并传送（§5.3 / §5.9 / §5.12 / §5.13 / §5.15）。
- [x] 无位置、加载失败、超时均安全失败，不改档案；重复实体冲突已由 D-20、D-21 / 11.10 覆盖，`CHUNK_LOAD_FAILED` 已由 11.11 覆盖。
- [x] 同一请求重复点击不会创建第二个 ticket 或第二只实体；全服 pending 上限生效。
- [x] 玩家换维度期间 pending 不误取消，终态使用当前 Level（见 11.12）。
- [x] 登出、死亡、收回、解绑、服务停止均释放 ticket（§5.8 六个生命周期场景 + 11.7）。
- [x] 无永久 `FORCED` ticket 默认行为；默认路径没有 `LivingTickEvent` 全实体逐 tick 记录（模块 E 静态审计）。
- [x] 位置字段旧档兼容，数据版本迁移不修改实体状态（见 5.16 / §11.15）。
- [x] `compileJava`、`build`、`runServer`、`runClient` 和日志检查通过（模块 E 统一门槛，见 `verification-matrix.md` §5.17）。
