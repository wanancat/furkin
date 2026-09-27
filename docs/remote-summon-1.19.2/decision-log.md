# 远距召唤功能包决策记录

- 日期：2026-09-27
- 状态：默认冻结；实施前如需调整，只改本文件与对应执行契约
- 用途：记录 P0/P1/P2 中会影响实现、协议、存档兼容和服务器负载的口径，避免编码时再次猜测

## 1. 默认冻结口径

| 编号 | 决策 | 默认口径 | 主要影响 |
|---|---|---|---|
| D-01 | P0 失败语义 | `summoned=true` 且实体未解析时只返回 `ENTITY_UNRESOLVED`，不改档案、不重建 | 阻断复制实体和物品丢失 |
| D-02 | P1 修复入口 | 只做 `/furkin repair` OP 命令；P1 不增加 GUI、网络包，不升协议 | 降低误操面和发布风险 |
| D-03 | P1 保留者选择 | `repair choose` 必须显式给出 `keep_entity_uuid`；不自动选最近、最先加载或当前 canonical | 避免自动清理拿错实体 |
| D-04 | P1 装备冲突 | keeper 槽位已有装备时，重复体装备掉落到 keeper 脚下；不覆盖 keeper | 不静默销毁已有物品 |
| D-05 | P1 清理顺序 | 先完成所有核心运行时数据搬迁和物品搬运/掉落，再更新 canonical，最后删除重复体；任一步失败时不得删除实体 | 避免半清理、等级/技能回退和 canonical 悬挂 |
| D-06 | P2 加载策略 | 每次请求使用有界临时 ticket，不默认使用 `FORCED`，不做永久强加载 | 控制服务器负担和回滚面 |
| D-07 | P2 异步方式 | ticket 在主线程添加/移除；`getChunkFuture` 必须从 `Util.backgroundExecutor()` 调用，不能在主线程同步调用 | `ServerChunkCache#getChunkFuture` 在主线程会 `managedBlock` |
| D-08 | P2 位置跟踪 | 默认不挂 `LivingTickEvent`；以契约/召唤/传送/入世/离场事件为必选主路径 | 避免所有 LivingEntity 每 tick 能力查询 |
| D-09 | P2 网络协议 | P2 第一版复用 `RequestSummonPacket`，不新增 pending/result 网络包，不升 `PROTOCOL_VERSION` | 降低两端协议风险 |
| D-10 | P2 玩家换维度 | pending 期间玩家换维度不取消；终态执行时使用玩家当前 Level 作为落点 | 保留请求语义，避免无谓失败 |
| D-11 | P2 位置缺失 | 旧档缺 `entity_pos` 返回 `NO_POSITION`，不得判死或重建 | 兼容现有存档 |
| D-12 | P2 重复体处理 | 加载后发现同 `companionId` 的其他已加载实体，返回 `DUPLICATE_CONFLICT`，要求人工修复 | 不在热路径自动删实体 |
| D-13 | P1 修复权限 | 命令要求权限等级 2，但服务层仍要求调用者是档案主人；OP 身份不自动绕过归属校验 | 防止管理员误操作他人宠物；若需跨 owner 修复，另开明确的管理开关 |
| D-14 | P1 核心数据来源 | keeper 与当前 canonical 不同时，以当前 canonical 实体的实时 `FurkinData` 为核心数据来源复制到 keeper；不合并多个重复体的分歧进度 | 保留 canonical 的等级、经验、技能、战斗模式和冷却，避免选新实体时回退旧快照 |
| D-15 | owner 换维度与位置 | 玩家自己换维度不写宠物位置；只有宠物实体实际跨维度/位置改变时刷新 canonical 位置 | 防止档案指向玩家新维度而宠物仍留在旧维度 |
| D-16 | owner 换维度自动随行 | 不属于本远召包的默认修复范围；本包只保证宠物留在旧维度后可被显式远召。自动随行另立功能包，至少定义半径、仅处理已加载宠物、维度安全落点和配置开关 | 避免把玩家过门变成隐式批量区块加载和传送 |
| D-17 | P1 canonical 安全门槛 | `choose` 在搬运前校验 canonical 实时 capability 的 `companionId`、owner 和 `COMPANION` 状态；与档案不一致时返回 `CLEANUP_FAILED`，不复制残缺进度 | 防止半清理或身份冲突的旧实体污染 keeper |
| D-18 | P2 实体 section 读盘门槛 | `ChunkStatus.FULL` future 成功后进入 `WAIT_ENTITY_LOAD`；只有 `center + radius` 内 `ServerLevel#areEntitiesLoaded(long)` 全为 true，才定位实体。未就绪时保留 pending，由服务端 tick 在 deadline 内重试 | 1.19.2 实体数据在独立 `entities/*.mca`，FULL 不代表实体已反序列化；提前定位会误报 `ENTITY_UNRESOLVED` |
| D-19 | P2 GUI 在途态 | 客户端点击远召后进入单一本地在途态，按钮显示 `furkin.screen.record.summoning` 并禁用；服务端对每个非 `PENDING` 结果都复用既有 `RecordListPacket(openScreen=false)` 发一次结束刷新，`PENDING` 不发刷新。客户端 620 tick 兜底解锁，服务端 `ALREADY_PENDING` 仍是权威防线 | 不新增网络包或协议版本，给玩家明确加载反馈并避免同屏重复点击 |
| D-20 | P2 未召唤档案的已加载身份守卫 | `summoned=false` 时，先按 P0 的 canonical UUID 守卫拒绝已加载同 UUID 实体；再按 `companionId` 检查其它已加载的已契约实体。存在冲突时返回 `DUPLICATE_CONFLICT`，不创建第二只实体；无任何已加载同身份实体时才允许合法重建。已加载身份索引登记所有已契约实体，查询时排除档案当前 canonical UUID | 覆盖“档案未召唤但孤儿/重复实体仍在加载”的路径，避免再次生成第二只实体 |
| D-21 | P2 重复冲突的加载前置检查 | `summoned=true` 且 canonical 未加载时，在配置、维度和位置校验通过后、调用 `startRemoteRequest(...)` 前先查一次 `hasLoadedDuplicate(...)`；重复体已加载则立即返回 `DUPLICATE_CONFLICT`，不添加 ticket。异步实体 section 检查继续保留，覆盖重复体在加载期间才入世的情况 | 避免注定冲突的远召请求仍付出临时区块加载成本 |
| D-22 | P2.6 性能收口默认 | 连续 20 次资源回归、20 轮串行 MSPT、单请求 chunk-map 分解和 4 并发冷 / 热压测完成后，最终默认判定为 `remoteSummonTimeoutTicks=600`、`remoteSummonTicketRadius=1`、`remoteSummonMaxPendingGlobal=4`；不以未做 2 并发实测的更低上限替换已验证的 4 并发语义 | 保留已验证的并发能力，同时用 30 秒安全上限覆盖首轮冷区 434 tick 的极端读盘样本 |
| D-23 | P1 失败注入验收 | 以真实 `FurkinDuplicateRepair.choose(...)` 入口验证空物品、装备写入失败、部分搬运重试和技能重建失败；失败时 canonical 与实体保留，物品守恒，重试可收敛 | 防止因测试夹具绕过真实入口而掩盖半清理或物品丢失 |
| D-24 | P1 命令级验收与幂等 | P1-03/P1-04/P1-08/P1-10 通过真实 Brigadier dispatcher 与真实 `choose` 验证；`repair list` 诊断前后状态一致，权限不足 / 参数缺失 / 非法 UUID 被拒；同一 keeper 第二次 `choose` 为幂等 `OK`；canonical 未加载必须返回 `CANONICAL_NOT_LOADED` | 固定命令层权限、只读诊断、反向保留和重试语义 |
| D-25 | P0/P1 持久化闭环验收 | 两阶段夹具用真实 `summonOrTeleport` / `dismiss` / `RemoteSummonService.request` 验证：未加载失败只读、原区块回载不丢实体 / 装备 / 行囊、已加载传送不重建、`summoned=false` 的已加载 canonical 被守卫、`dismiss -> summon` 按档案快照恢复；夹具临时 force ticket 只用于确保测试样本落盘与回载，产品不新增永久强加载 | 固定 P0 卸载 / 回载和 P1-01 的直接验收证据，避免用推断替代闭环测试 |
| D-26 | 旧档 v0 / v1 迁移验收 | 一次性服务端夹具直接写旧格式 NBT 档案再调真实 `FurkinArchiveData.get(server)` 触发迁移：v0（无 `data_version` + Nether legacy / 同 ID 冲突）走 `0 -> 2`，v1（`data_version=1` + `summoned=false` 的 `AGGRESSIVE` / `summoned=true` 带 `entity_uuid`）走 `1 -> 2`；迁移前后 owner / entity UUID / 维度 / `summoned` / `alive` / level / 战斗模式逐项保持，`entity_pos` 仍为 `null`，落盘 `data_version=2` | 用直接证据固定“位置字段旧档兼容、迁移不修改实体状态”，替代仅靠实现描述的推断 |
| D-27 | 模块 E 最终门槛 | 静态检索 `FORCED` / `setChunkForced` / `LivingTickEvent` / `managedBlock` / `addRegionTicket` / `removeRegionTicket` / `getChunkFuture` 只命中 `getChunkFuture` 与注释；`clean build` 通过且 jar 无 `internal/debug`；无夹具 `runServer` 到 `Done (2.282s)`、`runClient` 到主菜单、`latest.log` 无 Furkin 专属 ERROR / FATAL | 用一次统一门槛替代“分散记录拼接推断”，作为 P0-P2 发布门槛的最终证据 |


## 2. P2 配置默认值

| 键 | 默认 | 范围 | 理由 |
|---|---:|---:|---|
| `remoteSummonEnabled` | `true` | boolean | 默认提供功能；服务器可一键关闭 |
| `remoteSummonTicketRadius` | `1` | `0..2` | 默认最多 3x3 区块；半径 2 为高成本选项 |
| `remoteSummonTimeoutTicks` | `600` | `20..600` | P2.6 冷区回归后改为 30 秒安全上限；超时优先于无限等待 |
| `remoteSummonMaxPendingPerPlayer` | `1` | `1..4` | 防止同一玩家连点制造并发请求 |
| `remoteSummonMaxPendingGlobal` | `4` | `1..64` | 防止多玩家同时触发大量区块加载 |
| `remoteSummonCooldownTicks` | `20` | `0..200` | 终态后至少 1 秒的再次请求抑制 |

## 3. 位置跟踪与服务器负担

默认位置更新路径：

1. 契约成功。
2. 召唤/复活重建后。
3. 已加载实体传送成功后。
4. `EntityJoinLevelEvent` 命中 canonical UUID。
5. `EntityLeaveLevelEvent` 命中 canonical UUID。
6. P2 请求成功后再次刷新。

`EntityLeaveLevelEvent` 在 1.19.2 由 `LevelCallback#onTrackingEnd` 触发，覆盖区块卸载导致的离场；因此不把 `LivingTickEvent` 作为默认保护。只有在后续实机验证证明“离场事件漏记且跨区块位置误差不可接受”时，才单独立项评估低频扫描；不得退化为对所有 LivingEntity 每 tick 的全局记录。

P2 的服务器负担边界：

- 单次请求最多关注 `(2 * radius + 1)^2` 个区块，默认 9 个。
- 全局默认最多 4 个 pending，默认合计最多 36 个待完成区块 future。
- 加载完成后立即释放 ticket；timeout 仅作为 service 清理失败时的安全网。
- 目标区块若需要生成，仍可能产生明显 I/O/生成开销；有界不等于免费。

## 4. 口径状态

第 1 节 D-01..D-27 与第 2 节配置默认值均已冻结，无待确认的 P2 阻塞项。owner 自动随行明确不属于本包，另开 `owner-dimension-follow` 功能包。

## 5. 已确认记录

- 2026-09-26：P1 装备冲突按 D-04：keeper 槽位已有装备时，重复体装备掉落到 keeper 脚下，不覆盖 keeper。
- 2026-09-26：P2 配置默认值按第 2 节冻结：`remoteSummonEnabled=true`、`remoteSummonTicketRadius=1`、`remoteSummonTimeoutTicks=100`、`remoteSummonMaxPendingPerPlayer=1`、`remoteSummonMaxPendingGlobal=4`、`remoteSummonCooldownTicks=20`。
- 2026-09-26：P1 入口与权限按 1-A：只做 OP 命令、不做 GUI；命令需权限等级 2，服务层仍按 owner 校验，不默认允许 OP 跨 owner。
- 2026-09-26：P1 核心数据来源按 3-A：keeper 与 canonical 不同时，以当前 canonical 的实时 `FurkinData` 为准复制，不合并分歧进度。
- 2026-09-26：位置更新技术澄清按 D-15：owner 自己换维度不直接更新宠物位置；宠物实体实际跨维度或位置改变时才更新。
- 2026-09-27：P1 canonical 安全门槛按 D-17 固化：实时身份、owner 或状态不一致时拒绝搬运并返回 `CLEANUP_FAILED`。
- 2026-09-27：P2.1 按第 3 节默认路径实施；位置字段为追加字段，v1→v2 为空迁移，不修改实体状态。
- 2026-09-27：P2.2 已抽出 `teleportLoadedEntity(...)`；已加载传送的落点、跨维度、档案刷新与能力同步语义保持原样。
- 2026-09-27：P2.3 已实现 `RemoteSummonService` 的 pending/cooldown/临时 ticket/后台 future 聚合/超时与取消清理；夹具验证已加载立即传送、跨维度未加载加载后传送、重复请求拒绝、成功/取消 ticket 释放。当前使用冻结默认值，P2.5 再接 `FurkinServerConfig`。
- 2026-09-27：`RemoteSummonResult.PENDING` 只作为服务端受理态，不进入网络线格式；P2.4 前不得把 pending 当作成功结果反馈给客户端。
- 2026-09-27：P2.4 反馈契约按“立即命中由入口直接反馈；异步终态由 `RemoteSummonFeedback` 回调”拆分。service 不产出玩家文案，入口各自决定反馈通道，避免同一次请求重复消息；`PENDING` 只是受理态，不作为成功。
- 2026-09-27：P2.4 未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。绒亲录异步成功复用既有 `refreshRecordList`；命令入口的同步消息走 command source，异步消息走 `displayClientMessage`。
- 2026-09-27：P2.4 验证夹具只构造“档案存在、实体不存在”的未加载场景，异步终态固定为 `ENTITY_UNRESOLVED`，用于覆盖失败反馈；真实加载后传送成功路径由 P2.3 夹具覆盖，二者不互相替代。
- 2026-09-27：P2.5 配置键与范围按契约落地：`remoteSummonEnabled`(boolean)、`remoteSummonTicketRadius`(0..2)、`remoteSummonTimeoutTicks`(20..600)、`remoteSummonMaxPendingPerPlayer`(1..4)、`remoteSummonMaxPendingGlobal`(1..64)、`remoteSummonCooldownTicks`(0..200)。远召期间不缓存这些值。
- 2026-09-27：半径与 deadline 在请求创建时快照进 `RemoteSummonRequest`，cooldown 在终态按当前配置写入；运行中改配置只影响下一次请求，不改变已创建 pending 的加载语义。
- 2026-09-27：P2.5 暂未提升 `mod_version`（仍为 `1.19.2-0.0.2.0`），中英 CHANGELOG 先把远召条目落在 `[Unreleased]`。乌狸同日确认：本次先不升版本号，发布版本号与发布日期留到后续再定。
- 2026-09-27：P2.6 服务端边界夹具通过：deadline 超时返回 `TIMEOUT` 且释放 ticket；`remoteSummonMaxPendingGlobal=2` 时第三条请求返回 `TOO_MANY_PENDING`；夹具删除后 clean build、无夹具 runServer 与日志检查通过。
- 2026-09-27：P2.6 客户端定向夹具通过：真实封包处理入口可打开 `FurkinRecordScreen`，远召控件与中文字段正常，`openScreen=false` 就地刷新及关闭返回通过；夹具源码删除，最终 jar 不含 fixture。首次崩溃确认为夹具主动 `Minecraft.close()` 导致，非产品路径；真实点击后的异步端到端与 MSPT 仍待验证。
- 2026-09-27：P2.6 生命周期边界夹具通过：登出、死亡、收回、解绑、canonical 变化、服务停止均释放 ticket；登出/停服抑制回调，其余取消路径回调 `CANCELLED`；夹具删除后 clean build、无夹具 runServer 与日志检查通过。
- 2026-09-27：P2.6 真实异步成功端到端验证出 D-18。夹具把实体与 `ForgeCaps/furkin:furkin_data` 写入 1.19.2 `entities/*.mca` 后卸载区块；`ChunkStatus.FULL` future 完成时实体 section 尚未异步读回，原实现立即定位并误报 `ENTITY_UNRESOLVED`。修复为 `WAIT_ENTITY_LOAD` + `ServerLevel#areEntitiesLoaded(long)` 轮询，成功后返回 `COMPLETED_TELEPORT`，action bar `furkin.msg.remote_summon_completed` 与真实 `FurkinRecordScreen` 就地刷新均通过。
- 2026-09-27：P2.6 GUI 加载态 / 重复点击实际输入夹具通过。真实 `FurkinRecordScreen` 的召唤按钮首次鼠标点击后 `active=false`、文案为 `召唤中……`；第二次同位置点击 `consumed=false`，服务端观测到 `pending=1`。目标最终 `ENTITY_UNRESOLVED` 后，既有列表刷新包到达并解锁按钮。生产实现按 D-19；未新增网络包，`PROTOCOL_VERSION` 仍为 `"2"`。
- 2026-09-27：P2.6 重复实体冲突与合法重建守卫按 D-20 固化并通过最终源码夹具。`FurkinDuplicateRegistry` 改为登记所有已加载的已契约实体；`summoned=false` 且存在同 `companionId` 的已加载孤儿/重复实体时返回 `DUPLICATE_CONFLICT`，不重建；无已加载同身份实体时合法 rebuild 仍通过。三组日志分别为 `FURKIN_FIXTURE_P2_06_DUP_OK`、`..._ORPHAN_OK`、`..._LEGAL_OK`，且合法 rebuild 的“archive not summoned”入世日志已降为 `DEBUG`。
- 2026-09-27：P2.6 `CHUNK_LOAD_FAILED` 故障注入通过。夹具用真实 `RemoteSummonService.request(...)` 建立 pending 与临时 ticket，再向真实 `onLoadFinished(...)` 注入 `Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED)`；终态为 `CHUNK_LOAD_FAILED`，`ticketReleased=true`，pending / ticket 归零，档案 UUID / 维度 / 位置 / `summoned` / `alive` / owner 不变且无新实体。日志为 `FURKIN_FIXTURE_P2_06_CHUNK_FAIL_OK result=CHUNK_LOAD_FAILED pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 archiveUnchanged=true entityAbsent=true`；这是验证夹具，不修改生产路径。
- 2026-09-27：按 D-21 增加远召加载前置重复检查。重复体已加载且 canonical 未加载时，`RemoteSummonService.request(...)` 在配置、维度和位置校验后直接返回 `DUPLICATE_CONFLICT`，不添加 ticket；一次性夹具日志为 `FURKIN_FIXTURE_P2_06_DUP_PRECHECK_OK result=DUPLICATE_CONFLICT registered=true canonicalLoaded=false duplicateLoaded=true pending=0 tickets=0 archiveUnchanged=true`。异步检查保留为兜底。
- 2026-09-27：P2.6 pending 换维度通过。主人切到 Nether 后，真实 pending 请求未取消，终态按当前 Nether Level 传送；成功日志为 `FURKIN_FIXTURE_P2_06_DIMENSION_OK result=COMPLETED_TELEPORT pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 playerDimension=minecraft:the_nether targetDimension=minecraft:the_nether archiveInNether=true canonicalPreserved=true nearPlayer=true`。夹具首次在终态后立即查询目的地实体，因目标 chunk 尚未完成实体入世而失败；预加载目的地 chunk 并等待异步入世后通过，产品代码未修改。
- 2026-09-27：P2.6 服务器重启通过。两阶段夹具第一阶段建立真实 pending 和 ticket 后保存世界并正常停服，停服日志为 `CANCELLED reason=SERVER_STOPPING ticketReleased=true`；第二阶段确认新 service 无持久 pending / ticket，档案持久字段保持，A 可再次进入 `PENDING` 并取消，缺位置档案返回 `NO_POSITION`。成功日志为 `FURKIN_FIXTURE_P2_06_RESTART_OK stalePending=0 staleTickets=0 reRequest=PENDING reRequestPending=1 reRequestTickets=1 cancelPending=0 cancelTickets=0 missingPosition=NO_POSITION missingPending=0 missingTickets=0 archiveUnchanged=true`。
- 2026-09-27：P2.6 性能收口完成。连续 20 次远召在 timeout 临时放宽为 900 tick 时通过，首轮冷区 `maxElapsedTicks=434`；20 轮串行 MSPT 的 measure `avg=3.69ms,max=54.35ms,over50=16,over100/200/500=0`；4 并发冷区 26 ticks / max 378.22ms、热区 22 ticks / max 431.41ms，9/9 `COMPLETED_TELEPORT`，无 `Can't keep up!`。据此当前默认 timeout 由 100 调整为 600，radius 与 global 保持 1 / 4；玩家侧 4 并发为一次约 0.38–0.43 秒的全服短暂卡顿，不是持续掉 TPS。
- 2026-09-27：P1 失败注入与重试按 D-23 验证通过。真实 `FurkinDuplicateRepair.choose(...)` 夹具覆盖空物品重复体、装备写入失败、部分搬运重试与技能重建失败重试；失败时 canonical 与实体均保留，物品总数守恒，第二次重试可收敛。证据见 `p1-duplicate-recovery.md` §14、`p1-execution-contract.md` §10、`verification-matrix.md` §4.2；日志为 `D:\frukin_dev\_research\p1_failure_20260927.log`。
- 2026-09-27：P1 命令、反向修复与幂等按 D-24 验证通过。真实 Brigadier dispatcher 覆盖 `repair list` 权限 / 只读 / 参数拒绝；反向 keeper 完成物品转移与重复体清理；同一 keeper 第二次 `choose` 为无操作 `OK`；canonical 未加载返回 `CANONICAL_NOT_LOADED` 且现场不变。证据见 `p1-duplicate-recovery.md` §15、`p1-execution-contract.md` §11、`verification-matrix.md` §4.3；日志为 `D:\frukin_dev\_research\p1_command_20260927.log`。
- 2026-09-27：P0/P1 持久化闭环按 D-25 验证通过。两阶段夹具输出 `FURKIN_FIXTURE_C_REMOTE_FAIL_OK`、`FURKIN_FIXTURE_C_P1_01_RELOAD_OK`、`FURKIN_FIXTURE_C_P2_LOADED_TELEPORT_OK`、`FURKIN_FIXTURE_C_SAME_UUID_GUARD_OK`、`FURKIN_FIXTURE_C_RECALL_OK`、`FURKIN_FIXTURE_C_REMOTE_PERSIST_OK`；未加载失败不改档、回载保持同一实体与物品、已加载传送不重建、同 UUID 已加载守卫有效、正常收召恢复档案快照。证据见 `p0-safe-failure.md` §8、`p1-duplicate-recovery.md` §16、`verification-matrix.md` §4.4；日志为 `D:\frukin_dev\_research\p1p0p2_closure_20260927.log`。
- 2026-09-27：旧档 v0 / v1 迁移按 D-26 验证通过。夹具输出 `FURKIN_FIXTURE_D_V0_MIGRATION_OK` 与 `FURKIN_FIXTURE_D_V1_MIGRATION_OK`：v0 无 `data_version` 主世界条目 + Nether legacy / 同 ID 冲突条目走 `0 -> 2`（`imported=1, conflicts=1`，冲突保留主世界条目），v1 的 `data_version=1` 条目（含 `AGGRESSIVE` 非默认战斗模式）走 `1 -> 2`；两分支字段逐项保持、`entity_pos` 仍为 `null`、落盘 `data_version=2`。证据见 `p2-execution-contract.md` §11.15、`verification-matrix.md` §5.16、`closing-audit.md` §1.4；日志为 `D:\frukin_dev\_research\p1_migration_v0_20260927.log`、`D:\frukin_dev\_research\p1_migration_v1_20260927.log`。
- 2026-09-27：模块 E 静态审计与统一四项 Gradle 门槛按 D-27 通过。检索无 `FORCED` / `setChunkForced` / `LivingTickEvent` / `managedBlock` / `addRegionTicket` / `removeRegionTicket`，仅临时 `furkin:remote_summon` ticket 且终态释放；`clean build` 通过、jar 无 `internal/debug`；`runServer` 到 `Done (2.282s)`、`runClient` 到主菜单，日志无 Furkin 专属 ERROR / FATAL。证据见 `verification-matrix.md` §5.17、`closing-audit.md` §1.5；日志为 `D:\frukin_dev\_research\p1_e_clean_build_20260927.log`、`p1_e_clean_server_20260927.log`、`p1_e_clean_client_20260927.log`。
