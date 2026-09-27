# P2：真正的远距召唤（1.20.1）

- 状态：代码已落地（工作树叠加在 `a9870714` 之上）；2026-09-28 已由 `cold`（45）、`safety`（18）、`stop-pending`（3）、`restart`（9）、`perf`（59）、`commands`（21）六组夹具覆盖冷区跨维度、超时 / 区块失败注入、登出 / 死亡 / 收回 / 解绑 / 停服取消、重启收敛与热区 / 冷区 / 串行 20 次 / 并发 4 性能，全部 0 失败；仅真实客户端绒亲录在途态与交互仍待验证
- 依赖：P0、P1 完成
- 参考：`D:\frukin_dev\frukin_1_19_2\docs\remote-summon-1.19.2\p2-true-remote-summon.md`
- 主要目标：目标已召唤但区块未加载时，按最后已知位置临时加载目标区块，重新定位同一个 canonical 实体并传送
- 核心约束：不使用永久 `FORCED`；所有加载只在单个请求期间存在

## 1. 最终行为

玩家从绒亲录或 `/furkin summon` 请求一只 `summoned=true` 的绒亲时：

1. 服务端校验归属、存活、召唤状态；只有未召唤重建或复活路径校验活跃名额。
2. 若 canonical UUID 已在运行时实体索引中：立即传送同一实体。
3. 若 canonical 未加载：
   - 按档案读取最后已知维度和位置。
   - 维度或位置缺失则安全失败。
   - 增加有界临时 ticket。
   - 在后台等待目标区块及受限邻域加载到 `ChunkStatus.FULL`。
   - 等实体 section 真正完成入世后，按 `entity_uuid + companionId` 定位 canonical。
   - 检查重复冲突。
   - 成功后传送，刷新档案位置并释放 ticket。
   - 失败、超时、取消都只读档案并释放 ticket。
4. `summoned=false` 走合法快照重建，但要先做同 UUID 和同身份已加载实体守卫。
5. `alive=false` 只能走复活流程。

## 2. 架构拆分

建议新增内部类：

```text
internal/contract/RemoteSummonService.java
internal/contract/RemoteSummonRequest.java
internal/contract/RemoteSummonResult.java
internal/contract/RemoteSummonOrigin.java
internal/contract/RemoteSummonFeedback.java
internal/contract/FurkinDuplicateRegistry.java   // P1 引入，P2 只读检查
```

责任边界：

- `FurkinCompanionManager`
  - 负责召唤 / 传送 / 重建的领域规则。
  - 抽出 `teleportLoadedEntity(ServerPlayer, FurkinArchiveEntry, LivingEntity)`，立即传送和异步传送共用。
- `RemoteSummonService`
  - 维护 pending、ticket、future、deadline、cooldown、取消和终态回调。
  - 可变状态只在服务端线程读写；后台 future 创建只读取构造后不变的快照字段。
  - 不直接重建实体，不直接搬装备或行囊。
- `FurkinEntityLocator`
  - 只做已加载索引查询和显式修复诊断扫描。
  - 不加载区块，不承担 pending 生命周期。
- `FurkinDuplicateRegistry`
  - P1 维护已加载实体身份登记。
  - P2 只调用 `hasLoadedDuplicate(...)`，不做热路径全量扫描。
- `CommonEvents`
  - 负责位置刷新、入世守卫、离场清理和 service tick / stop 接线。
- `FurkinServerConfig`
  - 增加远召开关、半径、超时和并发限制。

不要把这些职责重新塞回 `FurkinCompanionManager`；否则 `teleportToOwner` 会变成区块加载、pending、UI 反馈和生命周期清理的混合体。

## 3. 数据模型

### 3.1 位置字段

在 `FurkinArchiveEntry` 增加：

```java
@Nullable
private BlockPos entityPos;
```

访问器：

```java
@Nullable BlockPos getEntityPos()
void setEntityPos(@Nullable BlockPos entityPos)
void setEntityLocation(Entity entity)
void clearEntityLocation()
```

`setEntityLocation(Entity)` 同步刷新：

- `entityUuid`
- `entityDimension`
- `entityPos = entity.blockPosition()`

`clearEntityLocation()` 同步清空三项。

### 3.2 NBT

写入：

```java
tag.put("entity_pos", NbtUtils.writeBlockPos(entityPos));
```

读取前必须验证 `entity_pos` 是 compound 且 `X/Y/Z` 都是 int。缺失或残缺读作 `null`，不能回退到 `(0,0,0)`。如果位置存在但 UUID 或维度缺失，忽略位置并 WARN。

### 3.3 数据版本迁移

1. 当前 1.20.1 `CURRENT_DATA_VERSION=1`，已有 `migrateLegacyArchives(...)`（基线 `FurkinArchiveData.java:119`）把 v0 旧维度档案合并后直接写入 `CURRENT_DATA_VERSION`。
2. 目标：`CURRENT_DATA_VERSION=2`，`LEGACY_GLOBAL_ARCHIVE_VERSION=1`。
3. 把现有 `migrateLegacyArchives(...)` 改为 `migrate(MinecraftServer)` 的两步调度：

   ```text
   int loadedVersion = dataVersion;
   if (dataVersion >= CURRENT_DATA_VERSION) return;
   if (dataVersion < LEGACY_GLOBAL_ARCHIVE_VERSION) {
       migrateLegacyArchives(server);      // v0 -> v1：旧维度合并，冲突保留 overworld 并 WARN
       dataVersion = LEGACY_GLOBAL_ARCHIVE_VERSION;
   }
   if (dataVersion < CURRENT_DATA_VERSION) {
       dataVersion = CURRENT_DATA_VERSION; // v1 -> v2：位置字段空迁移，不改任何 entry
   }
   if (dataVersion != loadedVersion) { setDirty(); log "Furkin archive data version migrated: {} -> {}"; }
   ```

4. `migrateLegacyArchives(...)` 内部不再直接改 `dataVersion`，也不再无条件 `setDirty()`；脏标记统一由 `migrate(...)` 在版本实际变化时设置。
5. v1 -> v2 是空迁移：不扫描实体、不补位置、不改 `summoned` / `alive` / `entity_uuid` / `entity_dimension`。
6. `get(MinecraftServer)` 仍每次调用 `migrate(...)`，靠 `dataVersion >= CURRENT_DATA_VERSION` 短路。
7. 旧档首次加载 canonical 实体时，由入世事件补写位置；缺位置返回 `NO_POSITION`，不得把实体判死或重建。

### 3.4 位置刷新时机

必须更新：

- 契约成功。
- 召唤 / 复活重建成功。
- 已加载传送成功。
- `EntityJoinLevelEvent` 命中 canonical UUID。
- `EntityLeaveLevelEvent` 命中 canonical UUID，且区块卸载前记录。
- P2 请求终态成功后刷新。

owner 自己换维度不更新宠物位置；只有宠物实体实际移动或跨维度时才刷新。

## 4. 请求状态机

```text
IMMEDIATE
  -> COMPLETED_TELEPORT / COMPLETED_REBUILD / 终态失败

REMOTE
  -> WAIT_CHUNK
       -> WAIT_ENTITY_LOAD
            -> COMPLETED_TELEPORT
       -> CHUNK_LOAD_FAILED / TIMEOUT / CANCELLED
```

### 4.1 请求入口

`RemoteSummonService.request(player, companionId, origin, feedback)` 的精确顺序：

```text
1. assertServerThread()
2. cleanupExpiredCooldowns()
3. player == null || companionId == null                     -> NOT_FOUND
4. entry == null                                             -> NOT_FOUND
5. owner 不匹配                                              -> NOT_OWNER
6. !entry.isAlive()                                          -> NOT_ALIVE
7. byCompanion 已有同 companion pending                      -> ALREADY_PENDING
8. per-player 或 global 上限已满                             -> TOO_MANY_PENDING
9. cooldown 未过期                                           -> COOLDOWN
10. summoned=true && locate(entry) 命中：
      a. hasLoadedDuplicate -> DUPLICATE_CONFLICT
      b. 否则 teleportLoadedEntity(...) 映射为即时终态
11. !summoned：
      a. findLoadedByRecordedUuid 命中 -> ENTITY_UNRESOLVED
      b. hasLoadedDuplicate 命中       -> DUPLICATE_CONFLICT
      c. 否则 summonOrTeleport(...) 映射为即时终态
12. summoned=true && 未加载：
      a. !enabled                        -> DISABLED
      b. entityDimension == null         -> DIMENSION_MISSING
      c. server.getLevel(dim) == null    -> DIMENSION_MISSING
      d. entityUuid == null              -> INVALID_STATE
      e. entityPos == null               -> NO_POSITION
      f. hasLoadedDuplicate              -> DUPLICATE_CONFLICT（不加 ticket）
      g. startRemoteRequest(...)         -> PENDING
```

顺序之所以重要：配置、维度、位置、重复冲突必须在创建 ticket 之前完成，避免注定失败的请求产生区块加载成本。pending 上限与 cooldown 在实体分支之前，保证连点不会进入任何加载路径。

`request` 返回 `PENDING` 表示已受理，不是成功；除 `PENDING` 外的返回值都是即时终态，调用入口直接反馈，不触发 `feedback` 回调。`feedbackArmed` 只有在 `startRemoteRequest` 成功加 ticket、提交后台 future 并即将返回 `PENDING` 前才置 `true`（D-32）。

### 4.2 加载流程

1. 取 `ResourceKey<Level> targetDimension`；缺失返回 `DIMENSION_MISSING`。
2. `targetLevel = player.getServer().getLevel(targetDimension)`；缺失返回 `DIMENSION_MISSING`。
3. `center = new ChunkPos(entry.getEntityPos())`；位置缺失返回 `NO_POSITION`。
4. 使用服务级静态 `TicketType<ChunkPos>`，名称稳定为 `furkin:remote_summon`。
5. 在服务端主线程，对 `ChunkPos.rangeClosed(center, radius)` 的每个位置添加一张临时 ticket：
   - ticket level 取 `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)`（1.20.1 实测为 31）。
   - 不使用 `TicketType.FORCED`。
   - `REMOTE_SUMMON_TICKET = TicketType.create("furkin:remote_summon", Comparator.comparingLong(ChunkPos::toLong), 800)`，**owner 就是当前 `ChunkPos`**（D-23）；`requestId` 只用于日志与状态机标识，不作为 ticket owner。
   - add 与 remove 必须使用完全相同的 type、`ChunkPos`、level、owner 四元组。
   - 已知边界（D-24）：`Ticket` 等值只比较 type + ticketLevel + key + forceTicks，同一 `ChunkPos` 上的两次请求共享同一等值 ticket；先结束者 `remove` 会让后结束者可能提前得到 `ENTITY_UNRESOLVED`。本包继承 1.19.2，不做引用计数。
6. 在 `Util.backgroundExecutor()` 中调用 `getChunkFuture(x, z, ChunkStatus.FULL, true)`；主线程不调用该阻塞路径。
7. 聚合 future，检查每个 `Either`；出现 `ChunkLoadingFailure` 记录 `CHUNK_LOAD_FAILED`。
8. future 完成后回服务端线程：
   - 再次校验玩家、档案、owner、alive、canonical。
   - 进入 `WAIT_ENTITY_LOAD`。
   - 用 `ServerLevel#areEntitiesLoaded(long)` 检查中心半径内实体 section；未全部加载则保持 pending，由 `onServerTick` 在 deadline 前重试。
9. 检查重复注册表；有冲突返回 `DUPLICATE_CONFLICT`。
10. 用 `FurkinEntityLocator.locate(...)` 按 canonical UUID 查找 resident entity。
11. 找到后走 `teleportLoadedEntity(...)`；找不到返回 `ENTITY_UNRESOLVED`。
12. 所有非 pending 终态都释放 ticket，并调用 feedback；`PENDING` 不发终态刷新。

### 4.3 传送流程

- 同维度：`target.teleportTo(x, y, z)`。
- 跨维度：`target.changeDimension(serverLevel, new FixedTeleporter(...))`。
- 成功：刷新 `entityUuid`、维度、位置；清坐姿；同步 `SyncFurkinDataPacket`；刷新绒亲录。
- 失败：不改 `summoned`，不清位置，不重建，释放 ticket。

### 4.4 取消与清理

必须取消并释放 ticket。精确接线点：

| 触发 | 接线位置 | 调用 |
|---|---|---|
| 玩家登出 | `CommonEvents.onPlayerLoggedOut`（基线 `:186`） | `RemoteSummonService.cancelForPlayerIfPresent(server, playerUuid, PLAYER_LOGOUT)` |
| 绒亲死亡 | `CommonEvents.onLivingDeath`（基线 `:332`，`entry.setSummoned(false)` 附近 `:433`） | `RemoteSummonService.cancelIfPresent(server, companionId, ENTITY_DEATH)` |
| `dismiss` 成功 | `FurkinCompanionManager.dismiss(ServerPlayer, LivingEntity)` 成功路径（基线 `:193`；`FurkinRecordActionHandler.dismiss` `:85` 委托） | `cancelIfPresent(server, companionId, DISMISSED)` |
| 普通解绑 / 强制解绑 | `FurkinRecordActionHandler.unbind`（基线 `:111`）与强制分支 | `cancelIfPresent(server, companionId, UNBOUND)` |
| canonical 改变 | `FurkinDuplicateRepair` keeper 替换 canonical 时 | `cancelIfPresent(server, companionId, CANONICAL_CHANGED)` |
| 服务器停止 | `CommonEvents` 新增 `ServerStoppedEvent`（基线无此监听，和 1.19.2 实现一致） | `RemoteSummonService.stop(server)` |
| 服务端 tick | `CommonEvents.onServerTick`（基线 `:460`） | `RemoteSummonService.tickIfPresent(server)` 驱动 deadline 与 cooldown |
| 玩家换维度 | 不接线 | 不取消；终态以玩家当前 Level 作为落点 |

`CancelReason` 取值：`COMPLETED / PLAYER_LOGOUT / ENTITY_DEATH / DISMISSED / UNBOUND / CANONICAL_CHANGED / SERVER_STOPPING / TIMEOUT / CHUNK_LOAD_FAILED / STATE_CHANGED / DUPLICATE_CONFLICT`。

`cancel` 必须幂等：已终态请求直接返回；否则标记 terminal、释放 ticket、从 `byCompanion` / `pendingPerPlayer` 移除、记录 `requestId + reason`，再进入终态。`finishPending` 用 `byCompanion.get(companionId) != request || state == TERMINAL` 做幂等门禁。

`releaseTicket` 只使用 `request.ticketAdded` 作为一次释放凭证：先复制 `ticketChunks` 再 `clear()`，逐张 `removeTicket`；单张失败记 WARN 不抛异常。即使释放阶段的 setup 抛异常，`finishPending` 也必须移除 pending，只把 `ticketReleased` 记为 false 并 WARN，避免后续请求永久卡在 `ALREADY_PENDING`。`PLAYER_LOGOUT` 与 `SERVER_STOPPING` 不回调 `feedback`。

## 5. 配置项

在 `FurkinServerConfig` 增加：

| 配置 | 默认 | 范围 | 语义 |
|---|---:|---:|---|
| `remoteSummonEnabled` | `true` | boolean | 关闭只拒绝未加载远召；已加载传送和合法重建不受影响 |
| `remoteSummonTicketRadius` | `1` | `0..2` | 0 = 中心，1 = 3x3，2 = 5x5 |
| `remoteSummonTimeoutTicks` | `600` | `20..600` | 等待上限；超时释放 ticket |
| `remoteSummonMaxPendingPerPlayer` | `1` | `1..4` | 每玩家并发上限 |
| `remoteSummonMaxPendingGlobal` | `4` | `1..64` | 全服并发上限 |
| `remoteSummonCooldownTicks` | `20` | `0..200` | 同一玩家 + companion 终态后抑制 |

请求创建时快照 radius / deadline；运行中改配置只影响下一次请求。终态 cooldown 按当前配置读取。

## 6. 玩家交互

### 6.1 绒亲录（1.20.1 精确实施点）

只移植行为状态机，不复制 1.19.2 渲染代码。1.20.1 当前实现：基线 `FurkinRecordScreen.java` 使用 `GuiGraphics` 与 `Button.builder(...)`。

基线已有 `acceptRefresh(...)`（`FurkinRecordScreen.java:117`）、`refreshDetail()`（`:209`）、`selectedEntry()`（`:268`）、`addDetailButtons(...)`（`:285`）；基线**没有** `tick()`。因此：

- 新增字段 `@Nullable UUID pendingSummonCompanionId`、`int pendingSummonTicks`、`static final int SUMMON_UI_TIMEOUT_TICKS = 620`。
- 新增方法 `requestSummon(UUID, Button)`、`clearPendingSummon()`、`@Override public void tick()`。
- 在既有 `acceptRefresh(...)` 进入处补 `clearPendingSummon()`。
- `Screen#tick()` 在 1.20.1 中已用 mapped jar 核实为公开可覆写方法。

精确行为：

1. `requestSummon(...)`：`pendingSummonCompanionId != null` 时直接 return；否则写入 `companionId`、`pendingSummonTicks = 0`、`button.active = false`、`button.setMessage(Component.translatable("furkin.screen.record.summoning"))`，再发送 `RequestSummonPacket(companionId)`。
2. 召唤按钮渲染：`summonPending ? furkin.screen.record.summoning : furkin.screen.record.summon`；按钮 `active` 仅当 `pendingSummonCompanionId == null`。
3. `tick()`：`pendingSummonCompanionId == null` 直接返回；否则 `pendingSummonTicks++`，达到 `SUMMON_UI_TIMEOUT_TICKS` 时 `clearPendingSummon()` 并 `refreshDetail()`。
4. `acceptRefresh(...)`：进入即 `clearPendingSummon()`，再就地替换 `entries` 并按 companionId 找回选中项。
5. 服务端 `PENDING` 不回列表刷新；任一非 pending 终态由 `RequestSummonPacket` 复用 `FurkinRecordItem.refreshRecordList(player)`，客户端 `acceptRefresh(...)` 结束在途态。
6. 620 tick 只是异常丢包兜底；服务端 `ALREADY_PENDING` 仍是权威防线。

### 6.2 命令

`/furkin summon <id>` 改为调用 service，并保留已有成功、失败和 pending 文案通道。异步终态通过玩家消息回传，不要求在命令调用栈中同步返回。结果到命令文案键的精确映射见 [P2 执行契约第 9.2 节](p2-execution-contract.md)。

### 6.3 文案

必须同步中英文键（D-29 / D-30）。P2.4 为最终键集合：

绒亲录入口（`RequestSummonPacket.sendRecordFeedback`）：

```text
furkin.msg.remote_summon_pending           // PENDING 专用，不刷新列表
furkin.msg.remote_summon_completed         // 异步终态成功
furkin.msg.teleported                      // 立即 TELEPORTED 成功（既有键）
furkin.msg.summoned                        // 立即 COMPLETED_REBUILD 成功（既有键）
furkin.msg.summon_not_found                // NOT_FOUND（既有键）
furkin.msg.not_owner                       // NOT_OWNER（既有键）
furkin.msg.summon_not_alive                // NOT_ALIVE（既有键）
furkin.msg.active_limit                    // ACTIVE_LIMIT（既有键，带 ACTIVE_LIMIT 参数）
furkin.msg.remote_summon_unresolved        // ENTITY_UNRESOLVED
furkin.msg.summon_dimension_change_failed  // TELEPORT_FAILED
furkin.msg.remote_summon_dimension_missing // DIMENSION_MISSING
furkin.msg.remote_summon_no_position       // NO_POSITION
furkin.msg.remote_summon_timeout           // TIMEOUT
furkin.msg.remote_summon_chunk_failed      // CHUNK_LOAD_FAILED
furkin.msg.remote_summon_duplicate         // DUPLICATE_CONFLICT
furkin.msg.remote_summon_disabled          // DISABLED
furkin.msg.remote_summon_already_pending   // ALREADY_PENDING
furkin.msg.remote_summon_too_many_pending  // TOO_MANY_PENDING
furkin.msg.remote_summon_cooldown          // COOLDOWN
furkin.msg.remote_summon_cancelled         // CANCELLED
furkin.screen.record.summoning             // 绒亲录按钮在途文案
```

命令入口（`FurkinCommand.sendSummonFeedback`）：

```text
furkin.command.summon.remote_pending       // PENDING
furkin.command.summon.teleported           // COMPLETED_TELEPORT（既有键）
furkin.command.summon.success              // COMPLETED_REBUILD（既有键）
furkin.command.companion.not_found         // NOT_FOUND（既有键）
furkin.msg.not_owner                       // NOT_OWNER（既有键）
furkin.command.summon.not_alive            // NOT_ALIVE（既有键）
furkin.command.summon.active_limit         // ACTIVE_LIMIT（既有键）
furkin.command.summon.entity_unresolved    // ENTITY_UNRESOLVED（P0 已引入）
furkin.command.summon.dimension_change_failed // TELEPORT_FAILED（P0 已引入）
furkin.msg.remote_summon_dimension_missing // DIMENSION_MISSING
furkin.msg.remote_summon_no_position       // NO_POSITION
furkin.msg.remote_summon_timeout           // TIMEOUT
furkin.msg.remote_summon_chunk_failed      // CHUNK_LOAD_FAILED
furkin.msg.remote_summon_duplicate         // DUPLICATE_CONFLICT
furkin.msg.remote_summon_disabled          // DISABLED
furkin.msg.remote_summon_already_pending   // ALREADY_PENDING
furkin.msg.remote_summon_too_many_pending  // TOO_MANY_PENDING
furkin.msg.remote_summon_cooldown          // COOLDOWN
furkin.msg.remote_summon_cancelled         // CANCELLED
furkin.command.summon.failed               // INVALID_STATE / REBUILD_FAILED（既有键）
```

收口要求：

- 本工作树从未引入 `furkin.msg.summon_entity_unresolved`；绒亲录直接使用 `furkin.msg.remote_summon_unresolved`（D-29）。
- 立即结果与异步结果分开取文案：立即成功用 `furkin.msg.teleported` / `furkin.msg.summoned`，异步成功用 `furkin.msg.remote_summon_completed`。
- 失败文案必须明确“未传送 / 档案状态未改变”和具体原因，不使用成功或“已收回”措辞。
- 键集合以“Java 调用点为准”反查，避免只存在于 lang 的死键再次出现。

## 7. 分步实施

### P2.1 档案位置字段与迁移

- 增加 `entityPos`、访问器、NBT 读写。
- 更新所有 `setEntityLocation(...)` 使用点。
- `CURRENT_DATA_VERSION=2`，拆分 v0 -> v1 -> v2。

### P2.2 传送公共路径

- 从 `teleportToOwner(...)` 抽出 `teleportLoadedEntity(...)`。
- 立即传送和 service 共用。
- 保持位置刷新、跨维度、坐姿清除和能力同步语义。

### P2.3 异步远招服务

- 增加 `RemoteSummonService`、`RemoteSummonRequest`、`RemoteSummonResult`、`RemoteSummonOrigin`、`RemoteSummonFeedback`。
- 增加 pending map、ticket 类型、future 聚合、deadline、cooldown、取消和线程断言。

### P2.4 接入入口与反馈

- `RequestSummonPacket` 改走 service。
- `/furkin summon` 改走 service。
- 绒亲录在途态和结果刷新。
- 中英文文案。

### P2.5 配置与文档

- 加 `FurkinServerConfig` 键。
- 更新 CHANGELOG 中英条目和配置说明。
- 不改公开 API；协议仍为 `2`，除非实际新增包。

### P2.6 验证与收口

- 服务端、客户端和生命周期夹具。
- 冷区 / 热区、串行和并发性能。
- ticket、`FORCED`、`managedBlock`、`LivingTickEvent` 静态审计。
- 四项 Gradle 门槛和日志检查。

## 8. 验收标准

- [x] 已加载同维度和跨维度传送不回归（代码路径复用公共传送方法；实机回归待验证）。
- [x] 未加载同维度可临时加载、按 UUID 定位并传送同一实体（`verify_p2` 4 只冷区绒亲）。
- [x] 未加载跨维度可临时加载并按 UUID 定位传送（夹具 `cold`：`cold cross-dimension` 系列，档案维度刷新、终态落在 owner 当前 Level）。
- [x] 无位置、加载失败、超时、重复体冲突均安全失败，不改档案（夹具 `cold` NO_POSITION / DIMENSION_MISSING、`safety` TIMEOUT / CHUNK_LOAD_FAILED / duplicate conflict、`reload` NBT 不变）。
- [x] 重复请求不创建第二个 pending / ticket，pending 上限生效（代码路径）。
- [x] 玩家换维度期间 pending 不误取消，终态使用当前 Level（夹具 `safety`：`pending dimension change uses current owner level`）。
- [x] 登出、死亡、收回、解绑、停服均接线释放 ticket（夹具 `cold` 四路取消 + 夹具 `stop-pending`：`reason=SERVER_STOPPING ticketReleased=true`）。
- [x] 无永久 `FORCED` ticket；默认路径没有全体 LivingEntity 逐 tick 记录（静态审计）。
- [x] 位置字段旧档兼容，迁移不修改实体状态（夹具 `nbt`：`v1 archive migrates to v2`、`v1 migration leaves entries empty`、缺/坏 `entity_pos` 读 null）。
- [x] `REMOTE_SUMMON_TICKET` 的 owner 是 `ChunkPos`，没有按 `requestId` 区分 ticket（源码审计）。
- [x] 同 `ChunkPos` 并发 ticket 的行为符合 D-24，且失败均为安全失败（源码审计 + 继承口径）。
- [x] `feedbackArmed` 只在成功返回 `PENDING` 前才置 `true`（源码审计）。
- [x] 专用服世界级 `serverconfig/furkin-server.toml` 生成 6 个 `remoteSummon*` 键，范围与默认值符合决策记录（启动日志 + 文件核对；完整配置切换待验证）。
- [x] 两份 lang 键集合一致，且不存在 `furkin.msg.summon_entity_unresolved`（键集合 diff）。
- [x] `compileJava`、`build`、去夹具 `runServer` 和日志检查通过。
- [ ] `runClient` 完整绒亲录在途态、刷新和交互仍未完成实机验证。

## 9. 服务器负担

- 单请求默认关注 9 个区块；全局 4 pending 时最多约 36 个区块 future。
- 区块生成、I/O 和实体初始化可能造成 tick spike；有界加载不等于免费。
- 1.19.2 的性能数据不能直接作为 1.20.1 的完成证据；P2.6 已重新测量，详见 [verification-matrix.md](verification-matrix.md)「性能记录」：确定性上限默认 ≤36 区块 / ≤30s / 稳态 0；实测热区 ~14ms、冷区 ~511ms（区块已生成）~1897ms（需生成）、单次最差 3500ms；并发 4 单 tick 峰值 178.5ms（非默认配置，默认 `perPlayer=1` 发不出 4 路）。
- 紧急回滚：设置 `remoteSummonEnabled=false`，保留 P0/P1；不得改成永久强加载。

## 10. 实施状态（2026-09-28）

- 基线 `a9870714` 已推送；当前增量（P0-06 口径修正、`ServerStoppingEvent` 接线、`TRAVEL_POUCH` 空值防御 + 文档）未提交。
- 已完成：位置字段与 v1 -> v2 迁移、公共传送路径、异步 service、ticket / pending / timeout / 取消、命令与绒亲录接入、6 项配置、双语文案。
- 已执行：2026-09-28 一次性夹具 11 个模式全部 0 失败（prepare 65 / nbt 16 / repair 34 / cold 45 / reload 13 / orphan 20 / safety 18 / commands 21 / stop-pending 3 / restart 9 / perf 59）；`build` 成功，去夹具 `runServer` 到达 `Done (2.592s)`，日志无 Furkin 专属 ERROR / FATAL / 异常栈。详细证据见 [verification-matrix.md](verification-matrix.md)。
- 未完成：真实客户端绒亲录在途态 / 按钮禁用解锁 / 刷新交互（P2-25 / P2-26 / P2-39）；服务端侧冷区跨维度、timeout / chunk 失败、生命周期取消、重启 pending 收敛、故障注入与热区 / 冷区 / 串行 20 次 / 并发 4 性能记录已全部取证。性能侧遗留两项：并发 4 的 178.5ms 单 tick 尖峰（优化项），以及客户端负荷零测量。
