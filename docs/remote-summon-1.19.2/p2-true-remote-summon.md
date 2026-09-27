# P2：真正的远距召唤

- 状态：P2.1-P2.5 已实施并完成定向夹具验收；P2.6 服务端边界、客户端结构、生命周期边界、真实异步成功端到端、GUI 重复点击实际输入、重复实体冲突守卫、加载前置重复检查及 `CHUNK_LOAD_FAILED` 注入、pending 换维度、服务器重启与性能收口已完成
- 依赖：P0、P1 完成
- 目标：目标绒亲所在区块未加载时，按最后已知位置临时加载目标区块，重新定位同一实体并传送
- 核心约束：不使用永久强加载；所有加载只在单个远召请求期间存在
- 执行规格：[P2 执行契约](p2-execution-contract.md)

## 1. 最终行为

玩家从绒亲录或命令请求召唤一只 `summoned=true` 的绒亲时：

1. 服务端重新校验归属、存活、召唤状态；只有未召唤重建或复活路径才校验活跃名额。
2. 若实体已在运行时实体索引中：立即走现有传送流程。
3. 若实体未加载：
   - 读取最后已知维度和位置。
   - 添加短期区块 ticket。
   - 异步等待目标区块（及有界邻域）加载到 `ChunkStatus.FULL`。
   - 按 `entity_uuid + companionId` 重新定位实体。
   - 定位成功后传送，传送成功后刷新档案位置并释放 ticket。
   - 加载失败、超时、定位失败或传送失败都返回失败，档案状态不变。
4. 若档案本身就是 `summoned=false`：仍走合法的快照重建，不属于 P2 区块加载路径。
5. 若档案 `alive=false`：仍只能走复活流程。

## 2. 架构拆分

建议新增服务端内部类：

```text
internal/contract/RemoteSummonService.java
internal/contract/RemoteSummonRequest.java
internal/contract/RemoteSummonResult.java
internal/contract/FurkinDuplicateRegistry.java   # P1 引入，P2 只读检查
internal/record/FurkinEntityPosition.java        # 可选值对象，或直接用 BlockPos
```

建议责任边界：

- `FurkinCompanionManager`
  - 继续负责召唤/传送/重建的领域规则。
  - 抽出 `teleportLoadedEntity(ServerPlayer, FurkinArchiveEntry, LivingEntity)`，供立即传送和异步传送共用。
- `RemoteSummonService`
  - 维护待处理请求、ticket 生命周期、超时和异步回调。
  - 只调用 `FurkinEntityLocator` 和 `FurkinCompanionManager` 的既有入口。
  - 不直接重建实体，不直接改装备/行囊。
- `FurkinEntityLocator`
  - 继续只查已加载实体，不触发区块加载。
  - P2 的加载、future 聚合、超时和 ticket 生命周期全部留在 `RemoteSummonService`；不要让 locator 自己隐式加载。
- `FurkinDuplicateRegistry`
  - 由 P1 入世守卫维护已加载重复体 UUID。
  - P2 只调用 `hasLoadedDuplicate(...)`，不在远召热路径做全量扫描。
- `CommonEvents`
  - 负责最后已知位置更新、P0/P1 入世守卫和重复体诊断登记。
- `FurkinServerConfig`
  - 增加远召开关、超时、ticket 半径和并发限制。

## 3. 数据模型

### 3.1 档案新字段

在 `FurkinArchiveEntry` 增加可选字段：

- `entity_pos_x`
- `entity_pos_y`
- `entity_pos_z`

或者使用一个等价的 `BlockPos` 序列化结构。要求：

- 旧档缺字段时读出 `null`，不得默认成 `(0,0,0)`。
- `setEntityLocation(Entity)` 同步刷新 UUID、维度、位置。
- `clearEntityLocation()` 同步清空 UUID、维度、位置。
- 传送、召唤、复活、契约、实体入世、实体离开 Level 时更新。
- 记录的是“最后确认位置”，不是保证实体永远不移动的绝对位置。

### 3.2 数据版本迁移

`FurkinArchiveData` 当前有数据版本机制。实施时：

1. 将 `CURRENT_DATA_VERSION` 从现有值追加递增。
2. 把版本迁移改成分步执行，而不是只服务最初的旧维度档案合并。
3. 旧版本先完成既有维度档案迁移，再执行新版本的空迁移。
4. 新字段全部可空，迁移过程不得改 `summoned`、`alive`、`entity_uuid` 或清理任何实体。
5. 旧档首次加载实体时，由入世事件补写位置。

禁止把“旧档没有位置”解释为“实体不存在”。旧档在 P2 返回 `NO_POSITION`，等待实体下次入世或 P1 修复命令补录；P0 兼容路径的旧结果名仍可映射为 `ENTITY_UNRESOLVED`，但不得触发重建。

### 3.3 位置更新时机

必须在这些路径更新：

- 契约成功。
- 召唤/复活重建后。
- 传送成功后。
- 实体重新入世且 UUID 与 canonical 一致。
- 实体离开 Level / 区块卸载前，且 UUID 与 canonical 一致。
- 本项目执行的跨维度传送成功后，必须用返回的实体调用 `setEntityLocation(...)`；不能只更新玩家的 Level。
- owner 自己换维度但宠物没有实际移动时，不更新宠物位置；宠物随原版/其它模组搬运时，由入世/离场事件兜底。
- 默认不监听 `LivingEvent.LivingTickEvent`；使用 `EntityLeaveLevelEvent` 覆盖区块卸载前的最后位置。若实测证明离场事件漏记，才评估低频 `ServerTickEvent` 补偿。
- 玩家登出不清理位置；位置属于实体档案。

跨区块更新必须节流：

- 不每 tick 写 `SavedData`。
- 只比较 `ChunkPos`，相同则立即返回。
- 只处理 `FurkinData.isCompanion()` 的实体。
- `EntityLeaveLevelEvent` 是默认保护；任何低频补偿都必须只遍历 `summoned=true` 的 canonical 档案，不得对所有 LivingEntity 每 tick 做记录。

## 4. 请求状态机

建议状态：

```text
IDLE
LOOKUP_LOADED
WAIT_CHUNK
WAIT_ENTITY_LOAD
COMPLETED
FAILED
CANCELLED
```

请求字段至少包括：

```text
long requestId
UUID playerUuid
UUID companionId
ResourceKey<Level> targetDimension
ChunkPos targetChunk
long deadlineGameTime
boolean ticketAdded
List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> loadFutures
RemoteSummonResult terminalResult
RemoteSummonOrigin origin
```

只允许服务端线程修改这些状态。异步 future 回调必须先 `server.execute(...)` 回到服务端线程。

### 4.1 请求入口

`RemoteSummonService.request(ServerPlayer player, UUID companionId, RemoteSummonOrigin origin)`：

1. 校验玩家、档案、归属、存活；分别返回 `NOT_FOUND`、`NOT_OWNER`、`NOT_ALIVE`，不改变档案。
2. 若同一 companion 已有 pending 请求：返回 `ALREADY_PENDING`，不创建第二个 ticket、不重复加载。
3. 若玩家或全服待处理请求达到配置上限：返回 `TOO_MANY_PENDING`。
4. 若同一玩家 + companion 仍在 cooldown：返回 `COOLDOWN`，不创建 ticket。
5. 先 `FurkinEntityLocator.locate(...)`。命中且 `entry.isSummoned()` 时复用 `teleportLoadedEntity(...)`，立即完成。
6. 若 `entry.isSummoned() == false`：
   - 先按 P0 的同一 UUID 守卫在已加载索引查找；命中则拒绝重建并返回 `ENTITY_UNRESOLVED`，提示走 P1 修复。
   - 再按 D-20 检查同一 `companionId` 的其它已加载实体；命中则拒绝重建并返回 `DUPLICATE_CONFLICT`，不创建第二只实体。
   - 两项均未命中才调用既有 `FurkinCompanionManager.summon(...)`，保持原快照重建语义；受活跃上限和既有重建规则约束。
7. 若 `entry.isSummoned()` 且运行时索引未命中：
   - 维度缺失：返回 `DIMENSION_MISSING`。
   - 位置缺失：返回 `NO_POSITION`。
   - 远召开关关闭：返回 `DISABLED`。
   - 维度、位置齐全且允许远召：进入 `WAIT_CHUNK`。

### 4.2 加载流程

1. `ResourceKey<Level> targetDimension = entry.getEntityDimension()`；为 `null` 时返回 `DIMENSION_MISSING`。
2. `ServerLevel targetLevel = player.getServer().getLevel(targetDimension)`；为 `null` 时返回 `DIMENSION_MISSING`。
3. 从 `entry.getEntityPos()` 得到 `ChunkPos center`；位置为 `null` 时返回 `NO_POSITION`。
4. 使用服务级静态 `TicketType<ChunkPos>`，不要每次请求新建类型：
   - 名称必须稳定，例如 `furkin:remote_summon`。
   - ticket 只作为加载优先级和生命周期标记，不使用持久 `FORCED` 语义。
   - `TicketType.create(...)` 只初始化一次，并设置大于 `remoteSummonTimeoutTicks` 上限的 timeout 安全网；正常路径仍以 service 的显式 `removeRegionTicket` 为主。
5. `targetLevel.getChunkSource().addRegionTicket(REMOTE_SUMMON_TICKET, center, radius, center)`。
6. 对中心及受限半径内的区块调用：

```java
// 必须在 Util.backgroundExecutor() 中调用；主线程同步调用会 managedBlock。
getChunkFuture(x, z, ChunkStatus.FULL, true)
```

7. 使用 `CompletableFuture.allOf(...)` 等待有界邻域完成，并逐个检查返回的 `Either`；出现 `ChunkLoadingFailure` 时按加载失败处理。
8. 在 `onServerTick` 中检查超时；超时后：
   - 标记请求 `FAILED`。
   - 释放 ticket。
   - 不修改档案。
9. future 完成后回到服务端线程：
   - 再次校验玩家在线、档案状态、owner、alive。
   - 进入 `WAIT_ENTITY_LOAD`；只有 `center + radius` 内 `ServerLevel#areEntitiesLoaded(long)` 全部为 true，才使用 `FurkinEntityLocator.locate(...)` 查找 canonical UUID。
   - 实体 section 尚未全部 LOADED：不判失败、不释放 ticket；由服务端 tick 在 deadline 内重试。
   - 实体 section 全部 LOADED 但未找到：返回 `ENTITY_UNRESOLVED`。
   - 找到：先用 `FurkinDuplicateRegistry` 检查同 companionId 的已加载重复体；有冲突则返回 `DUPLICATE_CONFLICT`。
   - 无冲突：进入传送。

禁止在服务端主线程使用阻塞式 `getChunk(..., true)` 作为生产路径；异步 future 是默认方案。若实现中确需 fallback，必须先把卡顿测量和理由写入 WP 文档。

### 4.3 传送流程

抽出的 `teleportLoadedEntity(...)` 应保持现有语义：

- 同维度：`target.teleportTo(targetX, targetY, targetZ)`。
- 跨维度：`target.changeDimension(serverLevel, new FixedTeleporter(...))`。
- 成功后：
  - 更新 `entityUuid`、维度、位置。
  - 清坐定和坐姿。
  - 同步 `SyncFurkinDataPacket`。
  - 调用现有 `FurkinRecordItem.refreshRecordList(player)` 刷新录屏列表。
- 失败后：
  - 不改 `summoned`。
  - 不清位置字段。
  - 释放 ticket。

### 4.4 请求清理

以下事件必须取消请求并释放 ticket：

- 玩家登出；玩家换维度不取消，终态按玩家当前 Level 落地。
- 档案被收回、死亡、解绑或强制解绑。
- 同一 companion 的 canonical UUID 改变。
- 服务器停止 / `ServerStoppingEvent`。
- 请求超时。
- future 异常或返回 chunk 加载失败。

清理函数必须幂等：

```text
cancel(requestId, reason)
  -> 若请求已结束则直接返回
  -> 移除 ticket（若已添加）
  -> 从 pending map 移除
  -> 记录带 requestId 和 reason 的 INFO/WARN
```

## 5. 配置项

在 `FurkinServerConfig` 增加服务端配置，建议默认值如下：

| 配置 | 默认 | 说明 |
|---|---:|---|
| `remoteSummonEnabled` | `true` | 远召加载总开关；关闭后已加载传送和合法重建仍可用 |
| `remoteSummonTicketRadius` | `1` | 目标位置周围最多 3x3 区块，范围上限 2 |
| `remoteSummonTimeoutTicks` | `600` | 服务端等待上限，超时释放 ticket |
| `remoteSummonMaxPendingPerPlayer` | `1` | 每名玩家同时进行的远召请求数 |
| `remoteSummonMaxPendingGlobal` | `4` | 全服同时进行的远召请求数 |
| `remoteSummonCooldownTicks` | `20` | 同一玩家 + companion 失败/完成后的重复请求抑制 |

要求：

- 所有配置只影响服务端；客户端不按配置自行决定是否发送请求。
- 配置值必须有合理上限，禁止把 `remoteSummonTicketRadius` 配成无限值。
- 配置变更不改变已存在实体或档案状态。
- 如果服务器管理员更保守，可将 `remoteSummonEnabled` 设为 `false`，P0 安全失败仍然生效。

## 6. 玩家交互

### 6.1 绒亲录

现有 `RequestSummonPacket` 可以继续承担请求：

- 载荷仍只有 `companionId`，因此协议不变。
- 服务端收到后交给 `RemoteSummonService.request(..., RemoteSummonOrigin.RECORD)`。
- 立即命中时行为与现有一致。
- 进入区块加载时发送 action bar 或聊天提示：

```text
正在定位绒亲，请在区块加载完成前保持在线……
```

- 成功后发送既有召唤/传送成功提示，并刷新录屏列表。
- 失败后发送对应原因，不关闭录屏，不改变列表状态。

### 6.2 命令

`/furkin summon <id>` 复用同一个 service：

- 命令线程只在服务端线程调度请求。
- 返回“已提交远召请求”或明确错误。
- 异步完成后通过玩家聊天反馈。
- 不能为了让命令立即返回成功而绕过加载/超时。

### 6.3 文案键

中英文同步增加：

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

不得使用只在服务端命令侧存在的硬编码文案替代玩家可见文本。

## 7. 与 P0/P1 的接口

### 7.1 P0

- `RemoteSummonService` 不使用旧 `teleportToOwner` 的自愈分支。
- 所有 unresolved 路径都返回 `ENTITY_UNRESOLVED` 或更细的 P2 错误，不写 `summoned=false`。
- P0 的失败只读不变。

### 7.2 P1

- chunk 加载会让实体重新入世，必须经过 P1 canonical 守卫。
- 若加载后发现有多个同 `companionId` 实体，不能自动选择：
  - 通过 `FurkinDuplicateRegistry` 检测已加载重复体。
  - 记录 `DUPLICATE_CONFLICT`。
  - 返回 `DUPLICATE_CONFLICT`，要求先执行 P1 修复命令。
- 档案 canonical UUID 在请求期间发生变化时，立即取消请求并释放 ticket。

## 8. 分步实施

### P2.1 档案位置字段与迁移

- 修改 `FurkinArchiveEntry`。
- 更新 `FurkinArchiveData` 数据版本。
- 在契约、召唤、传送、入世、离场路径补写入。
- 只写字段，不启用区块加载。
- 验证旧档读出、保存、重启后位置仍正确。

- 实施状态（2026-09-27）：位置字段、数据版本迁移、契约/重建/传送/入世/离场刷新已落地；双阶段夹具通过并已移除一次性夹具。

### P2.2 提取传送公共路径

- 从 `teleportToOwner` 抽出 `teleportLoadedEntity`。
- 保持已加载实体行为完全不变。
- P0 结果类型继续适用。
- 新增单元/夹具覆盖同维度和跨维度。
- 实施状态（2026-09-27）：`teleportLoadedEntity(...)` 已抽出并由 `teleportToOwner(...)` 复用；同维度经完整入口、跨维度经已解析实体夹具均通过。跨维度夹具使用已解析实体，避免同一 tick 新建实体尚未进入运行时实体索引的夹具时序问题。

### P2.3 异步远召服务

- 新增 `RemoteSummonService`、请求、结果和 pending map。
- 接入 `onServerTick` 做超时和状态推进。
- 接入 `addRegionTicket` / `removeRegionTicket`，并在 `Util.backgroundExecutor()` 中收集 `getChunkFuture`。
- 先不接入 GUI，只通过临时调试入口或命令验证。

- 实施状态（2026-09-27）：`RemoteSummonService`、请求/结果模型、pending 限制、cooldown、临时 ticket、后台 future 聚合和超时/取消清理已落地；接入 `CommonEvents` 的 tick、登出、死亡、服务器停止，以及收回/解绑/canonical 变化取消钩子。
- 夹具覆盖：已加载实体立即传送及 cooldown；目标实体位于 Nether 远距未加载区块，先异步加载为 `FULL`，再按 canonical UUID 定位并传送回 Overworld；重复请求返回 `ALREADY_PENDING`；成功和显式取消均释放 ticket 且档案 `summoned` / `alive` 不变。
- P2.4 阶段的冻结默认值为 enabled `true`、radius `1`、timeout `100`、max pending/player `1`、max pending/global `4`、cooldown `20`；P2.5 已接入 `FurkinServerConfig`，P2.6 性能收口后当前默认 timeout 改为 `600`，其余保持。
- P2.3 阶段尚未接入 `RequestSummonPacket`、`/furkin summon`、GUI 或语言键；该接入已在 P2.4 完成。`RemoteSummonResult.PENDING` 是内部受理态，不进入线格式。

### P2.4 接入召唤入口与反馈

- `RequestSummonPacket` 转交 service。
- `/furkin summon` 转交 service。
- 增加 pending/success/failure 文案。
- 确认没有新增网络包时 `PROTOCOL_VERSION` 不变。

- 实施状态（2026-09-27）：`RequestSummonPacket` 与 `/furkin summon` 已统一改走 `RemoteSummonService`。
- 新增 `RemoteSummonFeedback`：只用于异步终态回调；service 在成功提交后台加载后把回调武装为活跃状态，`PLAYER_LOGOUT` / `SERVER_STOPPING` 不回调。立即命中仍由各入口按原风格直接反馈，service 不产出玩家文案。
- 绒亲录入口：`PENDING` 显示 `furkin.msg.remote_summon_pending`（action bar）；异步成功显示 `furkin.msg.remote_summon_completed` 并调用现有 `refreshRecordList`；失败按 `RemoteSummonResult` 映射现有或新增文案。
- 命令入口：`PENDING` 通过 command source 返回 `furkin.command.summon.remote_pending`；异步终态通过 `displayClientMessage` 回执。
- 新增 `furkin.msg.remote_summon_*` 与 `furkin.command.summon.remote_pending` 中英文键；成功 / 失败复用既有 `furkin.msg.teleported` / `furkin.msg.summoned` 等，不新增同义重复文案。
- 未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`；配置接入与 CHANGELOG 收口已在 P2.5 完成，配置说明位于本文件第 5 节。
- 2026-09-27 一次性夹具验证两条入口的即时 `NOT_FOUND`、`PENDING`、异步 `ENTITY_UNRESOLVED` 反馈与 ticket 释放；一次性夹具已移除，最终 jar 不含 `internal.debug` / fixture 类。

### P2.5 配置与文档

- 增加 `remoteSummon*` 配置和注释。
- 更新中英 CHANGELOG；配置说明保留在本目录功能文档，不把 README 扩成配置参考。
- 记录 ticket 半径、超时、并发上限和服务器负担说明。

- 实施状态（2026-09-27）：6 个键已落在 `FurkinServerConfig`（SERVER 类型 TOML），范围与第 5 节冻结值一致。
- `RemoteSummonService` 已改为按请求读配置；`remoteSummonEnabled=false` 的语义（远程加载 `DISABLED`、已加载传送与合法重建不受影响）、cooldown 配置、radius=2 与每玩家 pending 上限均已用一次性服务端夹具验证。
- 配置表位于本文件第 5 节；中英 CHANGELOG 已补 `[Unreleased]` 远召条目。README 未加入配置表，避免只列远召键造成全局配置参考不完整。实机性能、客户端刷新与剩余联调留在 P2.6；超时注入已在 11.5 完成。
- 当前生成示例（`run/world/serverconfig/furkin-server.toml`）：`remoteSummonEnabled=true`、`remoteSummonTicketRadius=1`、`remoteSummonTimeoutTicks=600`、`remoteSummonMaxPendingPerPlayer=1`、`remoteSummonMaxPendingGlobal=4`、`remoteSummonCooldownTicks=20`。

### P2.6 实机验证与收口

- 执行统一验证矩阵。
- 记录 MSPT、加载耗时、ticket 成功/失败/超时日志。
- 确认服务器停止、玩家登出、死亡和收回时无残留 ticket。
- 更新本目录对应 WP 文档，不回写历史未执行结果。

- 实施状态（2026-09-27）：timeout deadline 与全服 pending 上限已完成一次性服务端夹具验收；详细证据见 `p2-execution-contract.md` 的 11.5 与 `verification-matrix.md` 的 5.6。
- 客户端定向夹具（2026-09-27）：通过真实 `FurkinClientPacketHandler.handleRecordList(...)` 打开 `FurkinRecordScreen`，验证远召语言键、控件可见/可用、`openScreen=false` 就地刷新与 `onClose()` 返回路径；第二次运行日志 `FURKIN_FIXTURE_P2_06_CLIENT_OK`，夹具源码已删除，最终 jar 不含 fixture。首次运行崩溃已定位为夹具主动 `Minecraft.close()` 的 GLFW 生命周期问题，非产品回归。
- 生命周期边界夹具（2026-09-27）：玩家登出、绒亲死亡、收回、解绑、canonical 变化和服务停止六条路径均释放 ticket；登出/停服不误回调，其余取消路径回调 `CANCELLED`。夹具源码已删除；无夹具 `clean build`、`runServer` 与日志检查通过。
- GUI 输入夹具（2026-09-27）：真实 `FurkinRecordScreen` 首次鼠标点击后按钮进入 `召唤中……` 且禁用，第二次同位置点击未被消费；服务端观测 `pending=1`，终态失败后的既有列表刷新包使按钮恢复。实现按 D-19，未新增网络包或协议版本。
- 重复实体冲突与合法重建（2026-09-27）：`FurkinDuplicateRegistry` 按 `companionId` 登记所有已加载的已契约实体，查询时排除档案 canonical；`summoned=false` 且存在同身份孤儿/重复实体时返回 `DUPLICATE_CONFLICT`，不创建第二只实体；无已加载同身份实体时合法 rebuild 仍通过。夹具日志为 `FURKIN_FIXTURE_P2_06_DUP_OK`、`..._ORPHAN_OK`、`..._LEGAL_OK`，合法 rebuild 的“archive not summoned”入世记录已降为 `DEBUG`。
- 加载前置检查（D-21，2026-09-27）：canonical 未加载、重复体已加载时，请求在添加临时 ticket 前返回 `DUPLICATE_CONFLICT`；夹具日志 `FURKIN_FIXTURE_P2_06_DUP_PRECHECK_OK ... pending=0 tickets=0 archiveUnchanged=true`。异步实体 section 阶段继续保留同检查，覆盖加载期间才入世的重复体。
- CHUNK_LOAD_FAILED 故障注入（2026-09-27）：真实请求进入 pending 后，向真实 `onLoadFinished(...)` 注入 `Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED)`；终态为 `CHUNK_LOAD_FAILED`，`ticketReleased=true`，pending / ticket 归零，档案不变且无新实体。日志 `FURKIN_FIXTURE_P2_06_CHUNK_FAIL_OK ... archiveUnchanged=true entityAbsent=true`。
- pending 换维度（2026-09-27）：主人切到 Nether 后，pending 请求未被取消，真实异步路径按当前 Nether Level 落点；实体与档案均为 Nether，canonical UUID 保持，pending / ticket 归零，实体位于主人身边。日志为 `FURKIN_FIXTURE_P2_06_DIMENSION_OK result=COMPLETED_TELEPORT ... canonicalPreserved=true nearPlayer=true`。
- 服务器重启（2026-09-27）：两阶段夹具第一阶段让真实 pending / ticket 停服，第二阶段确认新 service 的 pending / ticket 为 0，档案持久字段保持；再次远召返回 `PENDING` 并可取消，缺位置档案返回 `NO_POSITION`。日志为 `FURKIN_FIXTURE_P2_06_RESTART_OK stalePending=0 staleTickets=0 reRequest=PENDING ... missingPosition=NO_POSITION ... archiveUnchanged=true`。
- 性能收口（2026-09-27）：连续 20 次资源回归（临时 timeout=900，首轮冷区 `maxElapsedTicks=434`）、20 轮串行 MSPT、单请求 chunk-map 分解和 4 并发冷 / 热压测均完成。4 并发最慢 tick 为冷区 378.22ms、热区 431.41ms，9/9 `COMPLETED_TELEPORT`，无 ticket / pending 泄漏、无 `Can't keep up!`。玩家侧对应一次约 0.38–0.43 秒的全服短暂卡顿，而非持续掉 TPS。最终判定 `timeout=600`、`radius=1`、`global=4`；详见 `p2-execution-contract.md` 11.14 与 `verification-matrix.md` 5.15。


- 性能与玩家侧成本（2026-09-27）：单次冷区最慢 tick 51.84ms，接近一帧级轻微停顿；4 并发冷 / 热最慢 tick 分别为 378.22ms / 431.41ms，表现为一次约 0.38–0.43 秒的全服短暂卡顿，之后恢复；热区不显著便宜，不能以永久强加载替代有界 ticket。当前默认 `timeout=600`、`radius=1`、`global=4`。

## 9. 关键验收场景

1. 同维度已加载：行为与旧版本一致。
2. 跨维度已加载：`changeDimension` 成功，位置刷新。
3. 同维度未加载：临时加载成功后传送同一实体 UUID。
4. 跨维度未加载：在目标维度加载后传送，源维度 ticket 释放。
5. 最后位置缺失：安全失败，档案不变，不重建。
6. 区块加载超时：安全失败，ticket 释放，档案不变。
7. 重复点击：最多一个 pending 请求，最多一只实体。
8. 原实体在加载期间死亡：取消请求，不复活。
9. 原实体在加载期间被收回：取消请求，不重复传送。
10. 加载后发现重复实体：拒绝自动处理，要求 P1 修复。
11. 玩家登出：请求取消，ticket 释放。
12. 服务器停止：请求清理，临时 ticket 不进入永久持久状态。
13. 服务器重启：无持久 pending 请求；旧档案缺少位置时只安全失败。
14. pending 期间玩家跨维度：请求不取消，终态按玩家当前 Level 落点，档案 / 实体 UUID 与 ticket 状态正确。

## 10. 服务器负担边界

P2 默认成本是“每次远召请求临时加载一个受限邻域”，不是“每个活跃绒亲永久占有一个强制区块”：

- 只有实体不在已加载索引时才加载。
- `remoteSummonTicketRadius` 默认 1，最多 3x3。
- 每名玩家默认最多一个 pending 请求，全服默认最多四个 pending。
- 请求完成、失败或超时立即释放 ticket；`getChunkFuture` 从后台执行器收集，不阻塞服务端主线程。
- 不把 `FORCED` 作为默认持久策略。
- 不为远召主动扫描所有维度或所有实体。
- 不对未生成区块做无上限生成；如果目标区块生成失败或超时，返回失败。

如果后续需要“某些宠物长期强加载”，应另开功能包，不能在本远召包中偷偷加入。

## 11. 回滚策略

- 紧急情况下先设置 `remoteSummonEnabled=false`，保留 P0 安全失败。
- 回滚 P2 只移除 service 接入、位置字段写入和配置项；不回滚 P0/P1。
- 位置字段是追加字段，旧版本读取时应忽略或保持未知，不得把未知位置误判为实体丢失。
- 任何回滚都不得写回历史 WP 文档的伪造结果。



