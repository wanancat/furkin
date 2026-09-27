# Furkin 1.19.2 真正远距召唤独立功能包

- 日期：2026-09-27
- 分支：`mc1.19.2`
- 基线：`642f29d8d3c029a2c80ede16d835b002d8c3a65c`
- 状态：模块 A-D 已完成，模块 E 的静态审计与统一四项 Gradle 门槛也已通过；除独立案 `owner-dimension-follow` 外，`remote-summon` 功能包 P0-P2 收口完成
- 目标：在 P0 安全失败与 P1 单实体一致性完成后，交付 P2「真正的远距召唤」。

> 本目录按项目约定放在 `docs/` 下；`doc/` 不作为仓库的新文档根目录。

## 1. 目标定义

真正的远距召唤不是“目标实体找不到时按旧档案重建”，而是：

1. 实体已经在运行时实体索引中：立刻传送。
2. 实体因区块卸载而不在运行时索引中：使用其最后已知位置，临时加载目标区块，按实体 UUID 重新定位，传送成功后释放临时 ticket。
3. 加载、定位或传送失败：只返回失败，档案状态不变，不创建新实体，不复制旧快照。
4. 只有档案本来就是 `summoned=false` 的“已收回 / 已死亡”状态，才允许走重建或复活。

## 2. 当前缺陷与修复顺序

当前 `summonOrTeleport` 的已召唤分支会调用 `teleportToOwner`。当实体所在区块未加载时，`FurkinEntityLocator.locate(...)` 返回 `null`，现有代码把它当成“实体丢失”，执行：

- `entry.setSummoned(false)`
- `entry.clearEntityLocation()`
- 返回失败

下一次召唤因此从旧档案重建一个同 `companionId` 的实体，而原实体仍在旧区块存档中。活动实体身上的实时装备和行囊不在档案快照中，所以新实体通常会丢失当前装备和物品。

修复必须按以下顺序执行：

| 阶段 | 目标 | 是否 P2 前置 |
|---|---|---|
| P0 | 未解析实体只失败，不改变状态、不重建 | 是 |
| P1 | 恢复唯一实体不变量，并提供重复实体修复入口 | 是 |
| P2 | 增加最后已知位置和临时区块加载，完成真实远距召唤 | 否，最终功能 |

不得跳过 P0/P1 直接实现 P2。否则 P2 的异步加载失败、超时或服务器重启路径仍可能重新触发复制实体和数据丢失。

## 3. 不变量

实施期间以下不变量不得破坏：

1. **活动实体是真相**：`summoned=true` 时，实体 capability 和实体 NBT 是实时设备、行囊、等级、技能的唯一真相。
2. **档案是离线真相**：`summoned=false` 或 `alive=false` 时，档案是重建依据。
3. **状态切换有唯一入口**：`summoned=true -> false` 只能由成功的 `dismiss`、死亡侧写或明确的管理员修复产生，普通传送失败不能触发。
4. **一身份一实体**：同一 `companionId` 最多只能有一只被档案承认的规范实体。
5. **失败只读**：定位、加载、超时、传送失败不得写 `summoned`、不得清 `entity_uuid` / `entity_dimension` / `entity_pos`，不得重建实体。
6. **ticket 有界**：P2 的区块 ticket 只在单个请求期间存在，所有成功、失败、超时、登出和服务停止路径都必须释放或自然过期。
7. **没有永久强加载默认行为**：不得把所有已召唤绒亲所在区块永久 `FORCED`；远端加载必须是按请求、可配置、有限半径的临时操作。

## 4. 工作包索引

- [决策记录与待确认口径](decision-log.md)
- [P0：未解析实体安全失败](p0-safe-failure.md)
- [P1：重复实体恢复与规范实体守卫](p1-duplicate-recovery.md)
- [P1 执行契约：重复实体恢复与 canonical 守卫](p1-execution-contract.md)
- [P2：真正的远距召唤](p2-true-remote-summon.md)
- [P2 执行契约：真正的远距召唤](p2-execution-contract.md)
- [统一验证矩阵](verification-matrix.md)
- [P0-P2 收口审计](closing-audit.md)

## 4.1 当前文档粒度

- P0/P1/P2 均已有独立方向文档和执行契约；每个工作包都能按“改哪些类、按什么顺序、如何失败、如何验证”逐步实施。
- P1/P2 的细节以执行契约为准：P1 的两阶段搬运顺序、P2 的非阻塞 `getChunkFuture`、pending 上限和位置跟踪均已在契约中冻结。
- 所有实现口径与默认值已冻结在 [决策记录与口径状态](decision-log.md)；实施冲突以该文件为准。

### 4.2 P2.1 实施状态

- `FurkinArchiveEntry` 已增加可空 `entityPos`；`setEntityLocation(...)` / `clearEntityLocation()` 同步维护 UUID、维度、位置。
- `FurkinArchiveData` 数据版本已推进到 2，并按 v0→v1→v2 分步迁移；v1→v2 是空迁移，不推导位置、不改实体状态。
- 契约、重建、已加载传送、入世与离场路径通过 `setEntityLocation(...)` 刷新最后位置；没有新增 `LivingTickEvent`。
- 2026-09-27 双阶段夹具已验证位置保存、重启读取、缺失位置保持 `null`；一次性夹具源码已移除，最终 jar 不含调试类。
- 尚未覆盖：旧 v0/v1 档案文件的人工迁移夹具；区块加载、异步远召和 pending/ticket 路径从 P2.3 开始。

### 4.3 P2.2 实施状态

- 已从 `teleportToOwner(...)` 抽出 `teleportLoadedEntity(ServerPlayer, FurkinArchiveEntry, LivingEntity)`。
- `teleportToOwner(...)` 继续负责档案归属、存活、召唤状态和 canonical 定位校验，命中后委托公共传送方法。
- 公共方法保持原行为：同维度 `teleportTo(...)`，跨维度 `changeDimension(...)`；成功后刷新档案位置、解除坐定、同步能力数据。
- 2026-09-27 夹具验证同维度经 `teleportToOwner(...)`、跨维度经已解析实体调用 `teleportLoadedEntity(...)` 均返回 `TELEPORTED`，实体 UUID 与档案状态保持；一次性夹具已移除。

### 4.4 P2.3 实施状态

- 新增 `internal.contract.RemoteSummonResult`、`RemoteSummonOrigin`、`RemoteSummonRequest` 与 `RemoteSummonService`；服务按 `MinecraftServer` 弱引用注册，pending、每玩家/全服上限、同一 companion 去重和 cooldown 只在服务端线程维护。
- 请求路径已按“档案/owner/alive 校验 → 同 companion pending → pending 上限 → cooldown → 已加载实体立即传送 → `summoned=false` 合法重建 → 未加载实体临时加载”的顺序拆开。
- 未加载实体使用 `furkin:remote_summon` 临时 ticket；默认半径 1、超时 600 tick、每玩家 1 个 pending、全服 4 个 pending、cooldown 20 tick。`getChunkFuture(...)` 从 `Util.backgroundExecutor()` 收集，回调经 `MinecraftServer#execute(...)` 回到服务端线程。
- 成功、失败、超时、玩家登出、实体死亡、收回、解绑、canonical 变化和服务器停止均有清理入口；ticket 释放与 pending map 移除幂等。
- 2026-09-27 夹具验证已加载立即传送与 cooldown、跨维度未加载（Nether 目标区块卸载后临时加载并传送回 Overworld）、同一 companion 重复请求拒绝、异步完成、同 UUID 定位、ticket 释放和显式取消；一次性夹具已移除。
- P2.3 阶段默认值仍是代码内冻结值；`RequestSummonPacket`、命令、GUI 路径与语言键已在 P2.4 接入，`FurkinServerConfig` 接入已在 P2.5 完成。`RemoteSummonResult.PENDING` 只表示服务端受理，不进入网络线格式。

### 4.5 P2.4 实施状态

- `RequestSummonPacket` 与 `/furkin summon` 已统一改走 `RemoteSummonService`，不在入口各自重复实现定位/加载判定。
- 新增 `RemoteSummonFeedback` 异步终态回调；service 只在请求成功进入 `WAIT_CHUNK` 后武装回调，`PLAYER_LOGOUT` / `SERVER_STOPPING` 不回调，避免离线或停服时误发消息。
- 立即命中仍由各入口按原风格直接反馈；异步终态通过回调反馈，service 不承担玩家文案，避免同一次请求出现重复消息。
- 绒亲录入口：`PENDING` 在 action bar 显示 `furkin.msg.remote_summon_pending`；异步成功显示 `furkin.msg.remote_summon_completed` 并调用现有 `refreshRecordList`；失败按 `RemoteSummonResult` 映射文案。
- 命令入口：`PENDING` 通过 command source 返回 `furkin.command.summon.remote_pending`；异步终态通过 `displayClientMessage` 回执。
- 新增 `furkin.msg.remote_summon_*` 与 `furkin.command.summon.remote_pending` 中英文键；成功/失败复用既有 `furkin.msg.teleported` / `furkin.msg.summoned` 等，不制造同义重复键。
- 未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`；`FurkinServerConfig` 接入与 CHANGELOG 收口已在 P2.5 完成；配置说明位于 `p2-true-remote-summon.md` 第 5 节。
- 2026-09-27 一次性夹具验证两条入口的即时 `NOT_FOUND`、`PENDING`、异步 `ENTITY_UNRESOLVED` 反馈与 ticket 释放；夹具源码已移除，最终 jar 不含 `internal.debug` / fixture 类。

### 4.6 P2.5 实施状态

- `FurkinServerConfig` 新增 6 个 SERVER 类型键：`remoteSummonEnabled`、`remoteSummonTicketRadius`、`remoteSummonTimeoutTicks`、`remoteSummonMaxPendingPerPlayer`、`remoteSummonMaxPendingGlobal`、`remoteSummonCooldownTicks`；范围与冻结契约一致（`0..2` / `20..600` / `1..4` / `1..64` / `0..200`）。
- `RemoteSummonService` 不再持有冻结常数，改为按请求读取配置；半径与 deadline 在请求创建时快照进 `RemoteSummonRequest`，所以运行中改配置只影响下一次请求，不改变已创建 pending 的加载语义。
- `remoteSummonEnabled=false` 只拒绝「已召唤但不在运行时索引」的远程加载路径；已加载传送与 `summoned=false` 的合法重建不受影响。
- 2026-09-27 一次性夹具验证默认值、disabled 语义、cooldown 配置、radius=2 与每玩家 pending 上限；夹具源码已移除，最终 jar 不含 `internal.debug` / fixture 类。
- 配置表位于 `p2-true-remote-summon.md` 第 5 节；中英 CHANGELOG 已补 `[Unreleased]` 条目。README 不承载配置参考。`mod_version` 暂未提升；乌狸已确认本次先不升版本号，发布日期留待后续决定。
- 未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`；超时配置夹具已完成，实机 MSPT、客户端刷新与剩余联调仍属 P2.6。

### 4.7 P2.6 服务端边界验收（2026-09-27）

- timeout：夹具将 pending 请求的 deadline 置为当前 tick 之前，service tick 返回 `TIMEOUT`，`ticketReleased=true`，pending / ticket 归零。
- 全服 pending：`remoteSummonMaxPendingGlobal=2` 时，前两条请求返回 `PENDING`，第三条返回 `TOO_MANY_PENDING`；清理后 pending / ticket 归零。
- 夹具使用已加载出生区块，避免远坐标触发额外区块生成；验收后源码删除，最终 jar 不含 `internal.debug`。
- 干净回归：`clean build` 通过；无夹具 `runServer` 达到 `Done (2.174s)`；`latest.log` 无夹具记录，仅有基线 Minecraft tag `ERROR` 与历史 `Legacy Furkin AI state` WARN。

### 4.8 P2.6 客户端定向验收（2026-09-27）

- 使用一次性客户端夹具通过真实 `FurkinClientPacketHandler.handleRecordList(...)` 打开 `FurkinRecordScreen`，验证远召语言键、召唤按钮状态、`openScreen=false` 就地刷新和 `onClose()` 返回路径。
- 第二次运行日志 `FURKIN_FIXTURE_P2_06_CLIENT_OPENED`、`..._STATE summonPresent=true summonActive=true pendingFeedback=server-action-bar`、`..._REFRESH_OK`、`..._OK` 全部出现；`run/logs/latest.log` 无 Furkin 专属 `ERROR` / `FATAL`。
- 第一次运行崩溃已定位为夹具主动 `Minecraft.close()` 后主循环访问已关闭 GLFW 的临时夹具问题，非产品路径；移除该行为后第二次通过。
- 夹具源码已删除，最终 jar 不含 `RemoteSummonClientP206Fixture` / `internal.debug` / fixture 类。真实玩家点击后的服务端异步成功文案、完整列表刷新和 MSPT 仍待 P2.6 后续完成。

### 4.9 P2.6 生命周期边界验收（2026-09-27）

- 一次性服务端夹具覆盖玩家登出、绒亲死亡、玩家收回、解绑、canonical UUID 变化和服务停止；六条路径均记录 `ticketReleased=true`，终态 pending / ticket 归零。
- `PLAYER_LOGOUT` 与 `SERVER_STOPPING` 不回调异步反馈；`ENTITY_DEATH`、`DISMISSED`、`UNBOUND`、`CANONICAL_CHANGED` 各回调一次 `CANCELLED`。
- 夹具源码已删除；夹具后 `clean build` 通过，无夹具 `runServer` 达到 `Done (2.357s)`，最终 jar 不含 fixture / `internal.debug`。
- 仍待覆盖：连续 20 次远召资源回归和 MSPT；服务器重启已由 4.15 补测，pending 换维度已由 4.14 补测；真实玩家点击后的异步成功 action bar / 列表刷新已由 4.10 补测，重复实体冲突已由 4.12 补测。

### 4.10 P2.6 真实异步成功端到端验收（2026-09-27）

- 一次性客户端集成夹具在真实单人世界复制品中制造“实体已存档、所在区块已卸载”的现场：实体与 `ForgeCaps/furkin:furkin_data` 正确写入 1.19.2 独立目录 `DIM-1/entities/*.mca`，运行时索引与区块均已卸载。
- 通过真实 `FurkinRecordItem.sendRecordList(...)` 打开真实 `FurkinRecordScreen`，点击真实“召唤”按钮触发真实 `RequestSummonPacket`，验证完整客户端→服务端→异步区块加载→实体 section 读盘→传送→回执→列表刷新的链路。
- 首次运行发现产品缺陷：`ChunkStatus.FULL` future 完成时，`PersistentEntitySectionManager` 尚未完成实体 section 异步读盘，原实现立即 `locate(...)`，错误返回 `ENTITY_UNRESOLVED`。该缺陷与夹具保存无关，NBT 证据已确认实体与能力数据完整写盘。
- 修复：`RemoteSummonRequest` 增加 `WAIT_ENTITY_LOAD`；future 成功后逐块等待 `ServerLevel#areEntitiesLoaded(long) == true`，未就绪则保留 pending，由服务端 tick 在 deadline 内重试，不阻塞主线程、不反射、不读实体 NBT。
- 成功日志：`remote summon completed ... result=COMPLETED_TELEPORT reason=COMPLETED ticketReleased=true`；夹具断言 `pendingSeen=true`、`successText=远距召唤完成`、`refreshed=true`、`sameScreen=true`。
- 夹具源码与临时世界复制品已删除；`clean build` 通过，无夹具 `runServer` 达到 `Done`，无夹具 `runClient` 正常启动，最终 jar 不含 fixture / `internal.debug`。
- 性能边界：夹具期间的 `Can't keep up!` 来自一次性世界复制、同步生成/保存与夹具调试，不计入产品 MSPT 结论；连续 20 次请求与真实 MSPT / tick spike 仍待后续收口；`CHUNK_LOAD_FAILED` 注入已由 4.13 补测，服务器重启已由 4.15 补测；重复实体冲突已由 4.12 补测。

### 4.11 P2.6 GUI 加载态 / 重复点击实际输入验收（2026-09-27）

- 一次性客户端集成夹具复制并自动加载 `新的世界` 临时副本，服务端通过既有 `RecordListPacket` 打开真实 `FurkinRecordScreen`，不修改原存档。
- 夹具对真实屏幕调用 `Screen#mouseClicked(...)`：首次点击后按钮 `active=false`、文案 `召唤中……`；第二次同位置点击 `consumed=false`，状态保持不变。
- 服务端观测 `pending=1`；终态 `ENTITY_UNRESOLVED` 后，既有列表刷新包到达并恢复按钮为可用的“召唤”，日志 `FURKIN_FIXTURE_P2_06_GUI_OK ... refreshed=true unlocked=true`。
- 实现按 D-19：客户端只保留一个本地在途请求；服务端每个非 `PENDING` 结果都发一次既有列表刷新，`PENDING` 不刷新；未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。
- 夹具源码与临时世界已删除；`clean build` 通过；无夹具 `runServer` 达到 `Done (2.260s)`；`latest.log` 无 fixture 与 Furkin 专属 `ERROR` / `FATAL`；最终 jar 不含 fixture 类。

### 4.12 P2.6 重复实体冲突与合法重建验收（2026-09-27）

- `FurkinDuplicateRegistry` 从“只记录重复体”改为按 `companionId` 登记所有已加载的已契约实体，查询时排除档案当前 canonical UUID；`entity_uuid=null` 时任何同身份已加载实体都视为冲突。
- 冲突夹具：canonical 未加载、重复体已加载时，真实远召返回 `DUPLICATE_CONFLICT`，不传送 canonical、不删除重复体，档案不变，pending/ticket 归零。
- 孤儿重建夹具：档案 `summoned=false` 且 canonical 为空，同身份孤儿实体已加载时，请求返回 `DUPLICATE_CONFLICT`，不创建第二只实体，档案保持未召唤。
- 合法重建回归：没有已加载同身份实体时，`summoned=false` 仍返回 `COMPLETED_REBUILD`，证明守卫正确放行。
- 最终源码日志：`FURKIN_FIXTURE_P2_06_DUP_OK`、`..._ORPHAN_OK`、`..._LEGAL_OK`；合法 rebuild 的“archive not summoned”入世记录为 `DEBUG`，不再刷 WARN。
- 加载前置检查（D-21）：canonical 未加载、重复体已加载时，真实请求在添加临时 ticket 前返回 `DUPLICATE_CONFLICT`；夹具日志 `FURKIN_FIXTURE_P2_06_DUP_PRECHECK_OK ... pending=0 tickets=0 archiveUnchanged=true`。异步实体 section 阶段仍保留同检查作为兜底。
- 未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。夹具源码和临时世界已删除；`clean build` 通过，最终 jar `build/libs/furkin-1.19.2-0.0.2.0.jar`（SHA-256 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`）无 `internal.debug` / fixture 类，无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 Furkin 专属 `ERROR` / `FATAL`。

### 4.13 P2.6 CHUNK_LOAD_FAILED 故障注入验收（2026-09-27）

- 一次性服务端夹具通过真实 `RemoteSummonService.request(...)` 建立 `PENDING` 请求，再向真实 `onLoadFinished(...)` 注入 `Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED)`。
- 终态为 `CHUNK_LOAD_FAILED`，服务日志记录 `ticketReleased=true`；pending / ticket 从 1 归零，档案 UUID、维度、位置、`summoned`、`alive` 和 owner 不变，目标位置无新实体。
- 成功日志：`FURKIN_FIXTURE_P2_06_CHUNK_FAIL_OK result=CHUNK_LOAD_FAILED pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 archiveUnchanged=true entityAbsent=true`。
- 未修改生产代码，未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。夹具源码和临时世界已删除，运行配置已恢复；最终 jar 无 `internal.debug` / fixture 类。

### 4.14 P2.6 pending 换维度验收（2026-09-27）

- 一次性服务端夹具通过真实 `RemoteSummonService.request(...)` 建立 pending 请求和临时 ticket，随后将 FakePlayer 从 Overworld 传到 Nether。
- 在请求处于 `WAIT_ENTITY_LOAD` 时，夹具在 Overworld 目标位置构造 canonical 实体，并调用真实 `onLoadFinished(...)`；夹具等待异步实体入世后验收，避免把目的地 chunk 尚未完成入世误判为产品失败。
- 成功日志：`FURKIN_FIXTURE_P2_06_DIMENSION_OK result=COMPLETED_TELEPORT pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 playerDimension=minecraft:the_nether targetDimension=minecraft:the_nether archiveInNether=true canonicalPreserved=true nearPlayer=true`。
- 断言：请求不因玩家换维度取消；终态使用玩家当前 Nether Level；档案与实体均在 Nether；canonical UUID 未变；pending / ticket 归零；实体在主人身边。
- 未修改生产代码，未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，jar 无 `internal.debug` / fixture 类，SHA-256 为 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`；无夹具 `runServer` 达到 `Done (2.251s)`。

### 4.15 P2.6 服务器重启验收（2026-09-27）

- 两阶段服务端夹具在第一阶段建立真实 pending 请求和临时 ticket，保存有效位置档案、缺位置档案与 canonical 状态后正常停服；停服日志为 `result=CANCELLED reason=SERVER_STOPPING ticketReleased=true`。
- 第二阶段使用同一临时世界重启：新 service 的 `stalePending=0`、`staleTickets=0`，证明 pending / ticket 不持久化；档案 A、B 的 owner、UUID、维度、位置或缺失位置、alive / summoned 均保持。
- 重启后 A 再次请求返回 `PENDING`，显式取消后 pending / ticket 归零；B 请求返回 `NO_POSITION`，不添加 ticket，档案不变。
- 成功日志：`FURKIN_FIXTURE_P2_06_RESTART_OK stalePending=0 staleTickets=0 reRequest=PENDING reRequestPending=1 reRequestTickets=1 cancelPending=0 cancelTickets=0 missingPosition=NO_POSITION missingPending=0 missingTickets=0 archiveUnchanged=true`。
- 未修改生产代码，未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，jar 无 `internal.debug` / fixture 类，SHA-256 为 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`；无夹具 `runServer` 达到 `Done (2.251s)`。

### 4.16 P2.6 性能与玩家侧体验收口（2026-09-27）

- 连续 20 次远召资源回归在 timeout 临时放宽为 900 tick 时通过，首轮冷区 `maxElapsedTicks=434`；最终无 pending / ticket 泄漏、无额外实体。该结果不等同于默认 100 tick 通过。
- 20 轮串行 MSPT 中 measure `avg=3.69ms,max=54.35ms,over50=16,over100/200/500=0`，loaded `2209 -> 3170 -> 2209`，无 `Can't keep up!`、ERROR、FATAL。
- 4 并发：冷区 4 请求 26 ticks / 1675.2ms，最慢 tick 378.22ms；热区 4 请求 22 ticks / 1529.0ms，最慢 tick 431.41ms。9/9 均为 `COMPLETED_TELEPORT`，没有重复实体、超时或 ticket 泄漏。
- 玩家侧：单次冷区约一帧级轻微停顿；4 并发会出现一次约 0.38–0.43 秒的全服短暂卡顿，但不会持续掉 TPS。热区不会显著更便宜。
  - 上述体感由服务端主线程 tick 推导，不是客户端 FPS 抓帧。
- 资源侧：开发 JVM used heap 峰值约 2781MB；低内存服务器应按至少 4GB 堆评估。`loaded` 峰值 6053 是 chunk-map 工作集，不是 6053 个可玩区块。
- 最终判定：`remoteSummonTimeoutTicks=600`、`remoteSummonTicketRadius=1`、`remoteSummonMaxPendingGlobal=4`。不采用未经 2 并发实测的更低上限替换已验证语义。
- 收尾：夹具、临时世界和 `server.properties` 已恢复；`clean build` 通过且 jar 不含 fixture；无夹具 `runServer` 达到 `Done (20.190s)`，生成的配置已确认 `remoteSummonTimeoutTicks=600`，日志无 Furkin 专属 ERROR / FATAL 或 `Can't keep up!`。

### 4.17 P1 失败注入与重试收口（2026-09-27）

- 一次性服务端夹具通过真实 `FurkinDuplicateRepair.choose(...)` 覆盖 P1-06、P1-07、P1-12、P1-14；故障点由临时实体子类在真实装备写入与技能属性重建路径触发，不使用反射访问产品私有实现。
- P1-06：`FURKIN_FIXTURE_P1_FAILURE_EMPTY_OK`，空物品重复体安全清理。
- P1-07：`FURKIN_FIXTURE_P1_FAILURE_EQUIPMENT_OK`，装备写入失败返回 `CLEANUP_FAILED`，两个实体保留、档案不变、物品总数守恒。
- P1-12：`FURKIN_FIXTURE_P1_FAILURE_PARTIAL_OK`，第一次部分搬运失败，第二次重试成功，`totalBefore=2 totalAfter=2`。
- P1-14：`FURKIN_FIXTURE_P1_FAILURE_CORE_OK`，技能重建失败时安全返回，第二次重试成功并保留等级 17 / 经验 42 / 技能点 5 / `AGGRESSIVE` / cooldown `12345`。
- 原始日志：`D:\frukin_dev\_research\p1_failure_20260927.log`；干净回归日志：`D:\frukin_dev\_research\p1_failure_clean_20260927.log`。
- 收尾：夹具、临时世界和 `server.properties` 已恢复；`clean build` 通过，jar 无 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (21.399s)`，无 Furkin 专属 ERROR / FATAL。

### 4.18 模块 B：P1 命令、反向修复与幂等收口（2026-09-27）

- 一次性服务端夹具通过真实 Brigadier dispatcher 与 `FurkinDuplicateRepair.choose(...)` 覆盖 P1-03、P1-04、P1-08、P1-10。
- P1-03：`repair list` 权限 2 输出 3 条候选，诊断前后档案 / 实体快照一致；权限 1、缺少 keeper UUID 和非法 UUID 均被拒绝。
- P1-04：keeper 为当前 canonical 的反向修复通过；重复体装备与 7 个骨头先转移，canonical UUID 不变，重复体删除且最终只剩 1 个已加载候选。
- P1-08：同一 keeper 连续两次 `choose` 均为 `OK`；第二次 `discards=none`，状态快照稳定，不重复搬运或误删 canonical。
- P1-10：canonical 未加载时直接返回 `CANONICAL_NOT_LOADED`，重复体与档案 UUID 保持不变。
- 原始日志：`D:\frukin_dev\_research\p1_command_20260927.log`；干净回归日志：`D:\frukin_dev\_research\p1_command_clean_20260927.log`。
- 收尾：夹具、临时世界和 `server.properties` 已清理；`clean build` 通过，jar 无 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (24.177s)`，该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

### 4.19 模块 C：P0/P1 实体持久化闭环收口（2026-09-27）

- 两阶段服务端夹具在临时世界 `furkin_p1p0p2_closure_20260927` 中验证：远区块 canonical 落盘后，目标区块未加载时真实远召只返回 `ENTITY_UNRESOLVED`，档案 UUID / 维度 / 位置 / `summoned` / `alive` 不变且不创建第二只实体。
- 原区块回载后按档案 UUID 命中同一实体，钻石头盔、7 个骨头、维度与位置保持；随后已加载传送返回 `TELEPORTED`，canonical UUID 不变且实体靠近主人。
- 补验真实 `dismiss -> summon`：档案快照恢复等级 9 / 经验 11 / 技能点 2 / `FOLLOW` / 铁头盔 / 自定义名字；当前契约不要求实体 UUID 必须变化。
- 补验 `summoned=false` 且 `entity_uuid` 非空、该 UUID 实体已加载：真实远召返回 `ENTITY_UNRESOLVED`，档案 UUID 不变，同身份已加载候选为 1。
- 关键日志：`FURKIN_FIXTURE_C_REMOTE_FAIL_OK`、`FURKIN_FIXTURE_C_P1_01_RELOAD_OK`、`FURKIN_FIXTURE_C_P2_LOADED_TELEPORT_OK`、`FURKIN_FIXTURE_C_SAME_UUID_GUARD_OK`、`FURKIN_FIXTURE_C_RECALL_OK`、`FURKIN_FIXTURE_C_REMOTE_PERSIST_OK`。
- 原始日志：`D:\frukin_dev\_research\p1p0p2_closure_prepare_20260927.log`、`D:\frukin_dev\_research\p1p0p2_closure_20260927.log`；干净回归日志：`D:\frukin_dev\_research\p1p0p2_closure_clean_20260927.log`。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，最终 jar 不含 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (20.112s)`，日志无 `ERROR` / `FATAL`。该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

### 4.20 模块 D：旧档 v0 / v1 迁移收口（2026-09-27）

- 一次性服务端夹具在临时世界 `furkin_d_migration_v0_20260927` / `furkin_d_migration_v1_20260927` 中，先写旧格式 NBT 档案，再调真实 `FurkinArchiveData.get(server)` 触发迁移并断言字段。
- v0：无 `data_version` 主世界条目 + Nether legacy / 同 ID 冲突条目；迁移 `0 -> 2`，`imported=1, conflicts=1`，owner / entity UUID / 维度 / `summoned` / `alive` / level 保持，legacy 被导入，同 ID 冲突保留主世界条目，落盘 `data_version=2`。
- v1：`data_version=1`，一条 `summoned=false`（`AGGRESSIVE`）+ 一条 `summoned=true`（`entity_uuid` + overworld 维度）条目；迁移 `1 -> 2`，逐项字段（含非默认战斗模式）保持。
- 两条分支均断言迁移后 `entity_pos` 仍为 `null`：迁移不补位置、不扫描实体、不改实体状态。P2 完成定义中“位置字段旧档兼容，数据版本迁移不修改实体状态”据此勾选。
- 关键日志：`FURKIN_FIXTURE_D_V0_MIGRATION_OK`、`FURKIN_FIXTURE_D_V1_MIGRATION_OK`；原始日志 `D:\frukin_dev\_research\p1_migration_v0_20260927.log`、`D:\frukin_dev\_research\p1_migration_v1_20260927.log`。
- 口径：v0 首轮失败为夹具断言主键与顶层 NBT wrapper 写错，属夹具缺陷；修正后重跑通过，产品代码未改。
- 收尾：夹具源码、临时世界与 `server.properties` 已清理；统一 `clean build` / `runServer` / 日志检查与 jar 核验由模块 E 完成，见 §4.21 / `verification-matrix.md` §5.17。
### 4.21 模块 E：静态审计与统一门槛（2026-09-27）

- 危险路径静态检索（`FORCED` / `setChunkForced` / `LivingTickEvent` / `managedBlock` / `addRegionTicket` / `removeRegionTicket` / `getChunkFuture`）仅命中 `RemoteSummonService` 的 `getChunkFuture(...)` 与注释；唯一 ticket 为临时 `furkin:remote_summon`，终态成对释放。
- `clean build` 通过；最终 jar `furkin-1.19.2-0.0.2.0.jar`（SHA-256 `6F9B42BAE9C684DF6A890D22E7C9DC3BCD01398F3091EC919119E4B5DA157CCC`）不含 `internal/debug` / fixture。
- 无夹具 `runServer` 达到 `Done (2.282s)`；无夹具 `runClient` 启动到主菜单；`latest.log` 无 Furkin 专属 `ERROR` / `FATAL`，服务端仅基线 `TagLoader`。
- 终止口径：服务端 / 客户端在达到 `Done` / 主菜单后由批处理终止，Gradle 报 `non-zero exit value -1`，非产品失败。
- 详细门槛与日志见 `verification-matrix.md` §5.17、`closing-audit.md` §1.5。

## 5. 依赖关系

```text
P0 安全失败
  └─ P1 唯一实体不变量与修复入口
       └─ P2 最后已知位置、临时区块加载、异步传送
```

P2 可以复用 P0 的 `ENTITY_UNRESOLVED` 结果和 P1 的规范实体定位，但不得把 P0/P1 的修复混成“远召时重建”的替代路径。


## 5.1 建议提交切片

按下面顺序提交，每个切片独立编译和验证：

1. 本目录计划文档。
2. P0：安全失败与回归验证。
3. P1：入世 canonical 守卫与只读诊断。
4. P1：显式重复实体修复命令与物品搬运验证。
5. P2.1：档案最后位置字段与数据版本迁移。
6. P2.2：抽取已加载实体传送公共路径。
7. P2.3：异步远召服务、temporary ticket 与超时清理。
8. P2.4：命令 / 绒亲录接入与中英文反馈文案。
9. P2.5：FurkinServerConfig 接入、功能目录配置说明与 CHANGELOG 收口。
10. P2.6：实机性能、客户端刷新与最终收口。

如果某一提交无法通过 P0/P1 的安全门槛，不得开始下一提交。

## 6. 范围内

- 修正已召唤但实体未加载时的状态破坏。
- 增加重复实体检测、诊断和显式修复流程。
- 增加最后已知位置持久化。
- 增加一次请求最多一个临时区块 ticket 的远距召唤服务。
- 异步加载、超时、登出、死亡、收回、重复请求和服务停止时的清理。
- 服务端权威校验、状态日志、配置项和双语文案。
- 服务端和客户端实机验证。

## 7. 范围外

- 1.20.1 分支代码整体合并。
- 默认永久 `FORCED` 所有活跃绒亲所在区块。
- 自动扫描或重写整个 Minecraft region 文件。
- 自动合并或删除两只都带物品的重复实体；这种冲突必须人工选择保留对象或先转入隔离流程。
- 为远距召唤新增公开 API；本功能包默认全部落在 `internal`。
- 以远距召唤替代正常收回、死亡或复活语义。
- owner 换维度时自动把身边 N 格内宠物随行传送到新维度；本包只保证宠物留在旧维度后可被显式远召。自动随行若要实现，另开 `owner-dimension-follow` 功能包，复用 P2 的 `teleportLoadedEntity`。

## 8. API、协议与数据版本边界

- 预期不修改 `com.wanancat.furkin.api`，因此 `MAJORAPI` 段不变。
- P0 只增加服务端内部结果枚举和文案，不改变 `RequestSummonPacket` 字段与方向，因此不升 `PROTOCOL_VERSION`。
- P1 修复入口建议只做 OP 命令，不增加客户端网络包；若改为 GUI 入口并新增包，必须升 `PROTOCOL_VERSION`。
- P2 给 `FurkinArchiveEntry` 增加可选位置字段时，必须按 `FurkinArchiveData` 的版本迁移规则递增数据版本，并兼容旧档缺字段。
- P2 如果只复用既有 `RequestSummonPacket`，仍不改变线格式；若新增 pending/result 包，则同步更新 `FurkinNetwork` 包 ID、协议版本和 WP 文档。
- P2 默认不新增 GUI 或网络包。`getChunkFuture(...)` 必须从 `Util.backgroundExecutor()` 调用；主线程只执行 ticket 添加/移除、状态校验和最终传送。

## 9. 官方 API 取证边界

本计划只使用 1.19.2 / Forge 43.2.0 已核实的公开 API：

- `ServerLevel#getEntity(UUID)`：只查已加载实体，不加载区块。
- `ServerChunkCache#addRegionTicket(...)` / `removeRegionTicket(...)`：公开。
- `ServerChunkCache#getChunkFuture(int, int, ChunkStatus, boolean)`：公开；主线程调用会走 `managedBlock`，P2 必须从 `Util.backgroundExecutor()` 收集 future。
- `ChunkStatus.FULL`：目标区块至少加载到 `FULL` 后再查找实体。
- `ChunkPos#rangeClosed(...)`：可生成有界搜索范围。
- `EntityLeaveLevelEvent`：用于区块卸载/离场前记录 canonical 最后位置。
- `LivingEvent.LivingTickEvent`：不作为默认方案；只允许在证明离场事件漏记后单独立项评估。

不在本计划中使用 1.20.1 专有 API。实施前每个新增符号仍要按项目规则用 `javap` 或官方源码逐项复核。

## 10. 完成定义

P2 完成必须同时满足：

- P0、P1 的验收项全部通过。
- `compileJava`、`build`、`runServer`、`runClient` 均有证据。
- 未加载实体远召成功后，原实体是同一只实体 UUID，装备、行囊、等级、技能、战斗模式与冷却不丢失。
- P1 手工修复若 keeper 不是当前 canonical，核心运行时数据按 D-14 复制，不能因选新实体而回退进度。
- 失败、超时、重复请求、登出、死亡、收回和服务停止后没有残留 ticket、没有第二只实体、档案状态未被错误改写。
- `run/logs/latest.log` 无新增 `ERROR`、`FATAL`、异常栈或资源缺失。
- 双语 CHANGELOG、功能目录配置说明、协议/数据版本记录与本功能包一致；README 不新增部分配置表。
