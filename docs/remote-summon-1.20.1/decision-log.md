# 远距召唤 1.20.1 移植决策记录

- 日期：2026-09-28
- 状态：口径冻结并经 2026-09-27 独立复查补正（D-23 起），2026-09-28 按取证结论补 D-35 ~ D-37；代码已落地，11 个一次性夹具模式全部 0 失败，异步收口线程与停服收敛已现场取证；性能已记录确定性上限与实测两档，但为单机单次、无改动前基线，**不能据此声称无回归**（并发 4 的 178.5ms 单 tick 尖峰列为优化项）；仅真实客户端绒亲录交互、v0 分维度旧档迁移、同 UUID 跨维度入世待补；提交 `39633b8` 与 changelog 提交 `c22b036` 已推送，发布 tag / Release 尚未创建
- 参考：`D:\frukin_dev\frukin_1_19_2\docs\remote-summon-1.19.2\decision-log.md`
- 用途：把 P0/P1/P2 中影响实现、协议、存档兼容、权限和服务器负载的口径固定下来，避免 1.20.1 编码时重复猜测

## 1. 继承自 1.19.2 的行为口径

下表的行为语义直接继承 1.19.2 功能包；1.20.1 只重新核对 API 和现有分支集成点。

| 编号 | 决策 | 1.20.1 默认口径 | 主要影响 |
|---|---|---|---|
| D-01 | P0 失败语义 | `summoned=true` 且实体未解析时只返回 `ENTITY_UNRESOLVED`，不改档案、不重建 | 阻断复制实体和物品丢失 |
| D-02 | P1 修复入口 | 只做 `/furkin repair` OP 命令；不增加 GUI、不增加网络包、不升协议 | 降低误操面和发布风险 |
| D-03 | P1 保留者选择 | `repair choose` 必须显式给出 `keep_entity_uuid`；不自动选最近、最先加载或当前 canonical | 避免自动清理拿错实体 |
| D-04 | P1 装备冲突 | keeper 槽位已有装备时，重复体装备掉落到 keeper 脚下；不覆盖 keeper | 不静默销毁已有物品 |
| D-05 | P1 清理顺序 | 先完成核心运行时数据和物品搬运/掉落，再更新 canonical，最后删除重复体；任一步失败不得删除实体 | 避免半清理、等级/技能回退和 canonical 悬挂 |
| D-06 | P2 加载策略 | 每次请求只用有界临时 ticket，不默认 `FORCED`，不做永久强加载 | 控制服务器负担和回滚面 |
| D-07 | P2 异步方式 | ticket 在主线程添加/移除；`getChunkFuture` 必须从 `Util.backgroundExecutor()` 调用 | 主线程同步调用会走 `managedBlock` |
| D-08 | P2 位置跟踪 | 默认不挂 `LivingTickEvent`；以契约、召唤、传送、入世、离场事件和请求完成为位置刷新主路径 | 避免所有 `LivingEntity` 每 tick 做能力查询 |
| D-09 | P2 网络协议 | 第一版复用 `RequestSummonPacket`，不新增 pending/result 包，不升 `PROTOCOL_VERSION` | 降低两端协议风险 |
| D-10 | P2 玩家换维度 | pending 期间玩家换维度不取消；终态使用玩家当前 Level 作为落点 | 保留请求语义，避免无谓失败 |
| D-11 | P2 位置缺失 | 旧档缺 `entity_pos` 返回 `NO_POSITION`，不得判死或重建 | 兼容现有存档 |
| D-12 | P2 重复体处理 | 加载后发现同 `companionId` 的其他已加载实体，返回 `DUPLICATE_CONFLICT`，要求人工修复 | 不在热路径自动删实体 |
| D-13 | P1 权限 | 命令要求权限等级 2，但服务层仍要求调用者是档案主人；OP 不自动绕过归属校验 | 防止管理员误操作他人宠物 |
| D-14 | P1 核心数据来源 | keeper 与当前 canonical 不同时，以当前 canonical 实体实时 `FurkinData` 为核心数据来源复制到 keeper；不合并多个重复体的分歧进度 | 保留等级、经验、技能、战斗模式和冷却 |
| D-15 | owner 换维度与位置 | 玩家自己换维度不写宠物位置；只有宠物实体实际改变位置或维度时刷新 canonical 位置 | 防止档案指向玩家新维度而宠物仍留在旧维度 |
| D-16 | owner 换维度自动随行 | **明确不在本远召包内**（2026-09-27 乌狸确认）；本包只保证宠物留在旧维度后可被显式远召 | 不把玩家过门变成隐式批量区块加载；需要时另立 `owner-dimension-follow` 功能包 |
| D-17 | P1 canonical 安全门槛 | `choose` 在搬运前校验 canonical 实时 capability 的 `companionId`、owner 和 `COMPANION` 状态；不一致返回 `CLEANUP_FAILED` | 防止残缺进度污染 keeper |
| D-18 | P2 实体 section 读盘门槛 | `ChunkStatus.FULL` future 成功后进入 `WAIT_ENTITY_LOAD`；只有 `center + radius` 内 `ServerLevel#areEntitiesLoaded(long)` 全为 true，才定位实体 | FULL 不等于实体 section 已经入世 |
| D-19 | P2 GUI 在途态 | 客户端点击远召后进入单一本地在途态，按钮显示 `furkin.screen.record.summoning` 并禁用；非 `PENDING` 结果复用既有 `RecordListPacket(openScreen=false)` 结束刷新；620 tick 本地兜底，服务端 `ALREADY_PENDING` 仍为权威防线 | 不新增网络包或协议版本 |
| D-20 | 未召唤档案的已加载身份守卫 | `summoned=false` 时先查已加载 canonical UUID；再查其它同 `companionId` 已加载已契约实体；冲突时拒绝重建 | 覆盖孤儿实体仍在加载的路径 |
| D-21 | 重复冲突的加载前置检查 | canonical 未加载时，在配置、维度和位置校验通过后、添加 ticket 前先查重复注册表；重复体已加载立即返回 `DUPLICATE_CONFLICT` | 避免注定冲突的请求付出区块加载成本 |
| D-22 | P2 配置默认值 | `enabled=true`、`radius=1`、`timeout=600`、`per-player=1`、`global=4`、`cooldown=20` | 与 1.19.2 最终默认保持一致 |

## 2. 1.20.1 版本特定口径

| 编号 | 决策 | 默认口径 | 实施要求 |
|---|---|---|---|
| V-01 | 参考基线 | 行为参考 `mc1.19.2@206a92732c843a7f0aeea72fa7a3a6287b01356d`; 文档参考同目录 `remote-summon-1.19.2` | 不合并 1.19.2 分支，不做全局文本替换 |
| V-02 | 现有档案版本 | 1.20.1 当前 `CURRENT_DATA_VERSION=1`，已有 v0 旧维度合并 | 目标 `CURRENT_DATA_VERSION=2`；拆成 `v0 -> v1 -> v2` 分步迁移 |
| V-03 | 位置 NBT | 使用 `NbtUtils.writeBlockPos/readBlockPos`，`entity_pos` 缺失读为 `null` | `entity_uuid`、`entity_dimension`、`entity_pos` 必须同步存在或缺失；不得默认 `(0,0,0)` |
| V-04 | 维度 API | 当前 1.20.1 使用 `Registries.DIMENSION` | 保留现有写法；不要引入 1.19.2 的 `Registry.DIMENSION_REGISTRY` |
| V-05 | 事件生命周期 | 使用当前 Forge 47 的 `EntityJoinLevelEvent`、`EntityLeaveLevelEvent`、`TickEvent.ServerTickEvent`；停服取消 pending 用 `ServerStoppingEvent`，清诊断注册表用 `ServerStoppedEvent` | 依据 1.20.1 `MinecraftServer.stopServer()`：先 `removeTicketsOnClosing()` 再发 `ServerStoppedEvent`，只在 `ServerStoppedEvent` 释放 ticket 会留残留；见 D-35 |
| V-06 | 区块 ticket level | 用 `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)` 计算目标 level；1.20.1 当前值为 31 | 不继续复制 1.19.2 的裸数字注释；每个目标区块逐张 add/remove，保证可精确释放 |
| V-07 | 客户端适配 | 1.20.1 使用 `GuiGraphics`、`Button.builder(...)` | 只移植在途状态机，不复制 1.19.2 的 `PoseStack` / `GuiComponent` 代码 |
| V-08 | 协议与 API | 默认不改 `com.wanancat.furkin.api`，不新增网络包，`PROTOCOL_VERSION` 保持 `2` | 若改变包字段、方向、顺序或处理器语义，按协议治理规则先升版本 |
| V-09 | 配置默认沿用与复核 | 沿用 1.19.2 已验证默认；1.20.1 已在 P2.6 重测热区 / 冷区 / 串行 20 次 / 并发 4，指标与口径限制见验证矩阵「性能记录」 | 已获得 1.20.1 性能证据；但为单机单次、无改动前基线，只能给绝对量级与上界，不能给相对改动前的成本变化；1.19.2 的 434 tick / 3.69ms / 4 并发仍只作对照 |
| V-10 | 版本号 | 本功能包发布版本为 `1.20.1-0.0.3.0` | `gradle.properties`、CHANGELOG 版本节和构建产物名称必须一致 |

## 3. 1.20.1 实施粒度冻结（独立复查补正）

本节是 2026-09-27 对 1.20.1 工作文档独立复查后新增的补正项。凡与 1.19.2 工作文档叙述或 1.19.2 源码行为冲突处，以本节为准。1.19.2 尚未处理的重叠边界在此显式冻结为“继承”，不引入未验证改进。

| 编号 | 决策 | 冻结口径 | 证据 / 理由 |
|---|---|---|---|
| D-23 | ticket owner 类型 | `REMOTE_SUMMON_TICKET` 的 owner 是 `ChunkPos`，**不是 `requestId`**：`TicketType.create("furkin:remote_summon", Comparator.comparingLong(ChunkPos::toLong), 800)`，add / remove 两侧 owner 都用当前 `ChunkPos` | 1.19.2 `RemoteSummonService` 实际实现；`requestId` 只用于日志与状态机标识。任何 “不同请求用 `requestId` 作 owner 区分” 的表述作废 |
| D-24 | 同 `ChunkPos` 并发 ticket 重叠 | 继承 1.19.2 行为，**不做引用计数**：不同 companion 的 pending 覆盖同一 `ChunkPos` 时，先结束者 `removeTicket` 会移除共享 ticket，后结束者可能提前得到 `ENTITY_UNRESOLVED` | `Ticket.equals/hashCode` 只比较 `type + ticketLevel + key + forceTicks`，不区分请求。该边界只造成安全失败，不造成档案或物品损坏。若需严格化，另立改进项，不在本包默认范围内 |
| D-25 | `syncArchiveCore` 的名字 | **不写名字**。`syncArchiveCore` 只刷新 `level / xp / skillPoints / skillSnapshot / skillInvestments / skillInvestmentsKnown / combatMode / species(仅 null 时) / alive / summoned / entityLocation` | 与 1.19.2 `FurkinDuplicateRepair.syncArchiveCore` 源码逐行一致；`repair` 不隐式改名。文档中所有“同步名字”表述删除 |
| D-26 | keeper 战斗 AI 清理 | 必须 `tamable.setTarget(null)` 后调用 `keeperData.getCombatMode().applyTo(tamable)`；`applyTo` 返回 `false` 时抛异常。**禁止 `clearCombatAiState()`** | `applyTo` 负责清理本模组 owned goals 并保留 / 建立原版 goal 快照；直接 `clearCombatAiState()` 会丢失快照引用 |
| D-27 | P1 阶段名 | 固定为语义阶段 `DATA / POUCH / EQUIPMENT / ARCHIVE / POSTCONDITION`：`DATA`=核心数据 + AI + 技能，`POUCH`=行囊容量与搬运，`EQUIPMENT`=装备搬运，`ARCHIVE`=档案落定与同步包，`POSTCONDITION`=discard 清场与复核。异常注入点按语义阶段归位 | 1.19.2 `stage` 变量与 catch 标签不一致（`ARCHIVE` 只作第一个后置 try 的 catch 标签，`POSTCONDITION` 只作删除 try 的 catch 标签）；1.20.1 统一按语义，不复制该瑕疵 |
| D-28 | repair 校验顺序 | `档案存在 → owner → summoned → alive`，`repair list` 与 `repair choose` 完全一致；canonical / keeper / candidate 校验随后 | 1.19.2 `FurkinCommand.repairList` 与 `FurkinDuplicateRepair.choose` 的实际校验顺序 |
| D-29 | P0 未解析文案键的生命周期 | 本包按乌狸“其余按默认开始实施”的拍板，在同一工作树内实施 P0 + P2，直接从终态键开始：绒亲录 `ENTITY_UNRESOLVED` 使用 `furkin.msg.remote_summon_unresolved`；不引入、不写入、也不保留过渡键 `furkin.msg.summon_entity_unresolved`。 | 1.19.2 最终 lang 同时保留两键，其中 `summon_entity_unresolved` 没有任何 Java 调用点，是死键；1.20.1 不复制死键。P0 文档中的过渡键描述只保留为历史计划，已被本决策覆盖。 |
| D-30 | 各入口失败 / pending 键归属 | 绒亲录 `ENTITY_UNRESOLVED` → `furkin.msg.remote_summon_unresolved`（终态键，无 P0 过渡键）；绒亲录 `DIMENSION_CHANGE_FAILED / TELEPORT_FAILED` → `furkin.msg.summon_dimension_change_failed`；命令 `ENTITY_UNRESOLVED` → `furkin.command.summon.entity_unresolved`；命令 `DIMENSION_CHANGE_FAILED / TELEPORT_FAILED` → `furkin.command.summon.dimension_change_failed`；命令 `PENDING` → `furkin.command.summon.remote_pending` | 逐入口核对 1.19.2 `RequestSummonPacket.sendRecordFeedback` 与 `FurkinCommand.sendSummonFeedback` 的实际键，不按语义猜键名；1.20.1 已按终态键落地。 |
| D-31 | 配置发现路径 | 1.20.1 Forge 47 的 `ModConfig.Type.SERVER` 是**世界级 serverconfig**，不是 `run/config`：专用服默认 `run/world/serverconfig/furkin-server.toml`；集成服为 `run/saves/<存档目录>/serverconfig/furkin-server.toml`。专用服验收用 `rg -n "remoteSummon" run/world/serverconfig/furkin-server.toml` 取证。首次加载旧世界时，缺少 6 个新键会由 Forge 自动补默认值并输出 `Incorrect key ... corrected from null` WARN，这是预期迁移行为。 | 1.20.1 `FurkinMod` 已注册 SERVER 配置；Forge 47 对 SERVER 类型按世界隔离保存，2026-09-27 `runServer` 实测路径为 `run/world/serverconfig/furkin-server.toml`。原“固定 run/config”结论作废。 |
| D-32 | `feedbackArmed` 字段 | `RemoteSummonRequest` 必须含 `feedbackArmed`；只有 `startRemoteRequest` 成功添加 ticket、提交后台 future 并即将返回 `PENDING` 前才置 `true`。同步返回的即时错误不触发入口终态回调 | 1.19.2 实际实现；否则同步失败会再触发一次终态反馈，造成重复消息 |
| D-33 | `repair choose` 二次确认 | 默认形式 `/furkin repair choose <companion_id> <keep_entity_uuid>` 只做**预演**（只读，输出计划，不搬运、不删除、不改档案），返回新增结果 `PREVIEWED`；真正执行必须追加字面量 `confirm`：`/furkin repair choose <companion_id> <keep_entity_uuid> confirm`。预演与执行复用同一段前置校验与计划构建，禁止出现两套规则 | 2026-09-27 乌狸拍板（2B）：删除实体属高风险 OP 操作；1.19.2 无此保护，1.20.1 主动增加 |
| D-34 | 重复登记覆盖范围 | 所有已契约 `LivingEntity` 在入世时都按 `companionId` 登记，并可按 canonical UUID 刷新位置；只有 `TamableAnimal` 进入战斗 AI 重建。非 `TamableAnimal` 已支持契约 / 收回 / 重建，不能漏出重复体检测。 | 2026-09-27 独立复查：1.19.2 与原始 1.20.1 计划把登记放在 `TamableAnimal` 分支后，会漏掉该版本已支持的非可驯服物种；本工作树已修正。 |
| D-35 | 停服 pending 收敛接线点 | 取消 pending 与释放 ticket 必须挂在 `ServerStoppingEvent`；`ServerStoppedEvent` 只清 `FurkinDuplicateRegistry`。`RemoteSummonService.stop(server)` 在 `ServerStoppingEvent` 内执行 | 1.20.1 `MinecraftServer.stopServer()` 先 `removeTicketsOnClosing()` 关闭区块调度器，之后才发 `ServerStoppedEvent`；在 `ServerStoppedEvent` 释放会打空并留残留。取证：`reason=SERVER_STOPPING ticketReleased=true` 后紧接 `All dimensions are saved` |
| D-36 | P0-06 拒绝重建的两条分支 | ① `summoned=false` 且档案记录的 canonical UUID 仍加载 → `ENTITY_UNRESOLVED`（reason `loaded-but-not-summoned`）；② `summoned=false`、记录的 canonical 不存在但存在其它同身份已加载实体 → `DUPLICATE_CONFLICT`（reason `loaded-duplicate`）。不得把两者统一写成 `ENTITY_UNRESOLVED` | 两条分支的守卫顺序固定为 canonical UUID 守卫在前、同身份守卫在后；两条都必须拒绝重建且不产生第二只实体 |
| D-37 | dismiss 后再 summon 的实体 UUID | 当前实现按档案快照重建并恢复快照中的实体 UUID；本包不要求实体 UUID 必须变化，也不要求必须生成新 UUID | 夹具断言 `dismiss-ok rebuild preserves snapshot entity uuid` 即该口径的直接证据；把它当成回归去改成新 UUID 会破坏 P0/P1 的 UUID 一致性口径 |

### 3.1 与 1.19.2 源码不一致的文档旧表述

以下表述已在本次复查中作废，实施时不得再出现：

- “不同请求的 ticket 使用 `requestId` 或等价唯一 owner 区分” → 见 D-23。
- “`syncArchiveCore` 同步名字”“档案有名字时同步” → 见 D-25。
- “清空 keeper 旧战斗 AI 状态”单步描述 → 见 D-26（必须 `applyTo`）。
- P1 阶段名 `VALIDATION / CORE_DATA / SKILLS / ARCHIVE` → 见 D-27。
- “重复登记只覆盖 `TamableAnimal`” → 见 D-34。
- `repair list` 先 `alive` 再 `summoned` → 见 D-28。
- 把 `dismiss` 后拒绝重建统一写成 `ENTITY_UNRESOLVED` → 见 D-36（canonical 仍加载才是 `ENTITY_UNRESOLVED`；canonical 不在、另有同身份实体才是 `DUPLICATE_CONFLICT`）。
- 只在 `ServerStoppedEvent` 释放远召 ticket → 见 D-35。
- `dismiss -> summon` 必须换新实体 UUID → 见 D-37（按快照恢复 UUID 才是预期）。
## 3. 配置默认值

| 键 | 默认 | 范围 | 说明 |
|---|---:|---:|---|
| `remoteSummonEnabled` | `true` | boolean | 关闭只拒绝“已召唤但未加载”的临时加载路径；已加载传送和合法重建不受影响 |
| `remoteSummonTicketRadius` | `1` | `0..2` | 半径 1 关注 3x3 区块；半径 2 为高成本选项 |
| `remoteSummonTimeoutTicks` | `600` | `20..600` | 30 秒上限；超时优先于无限等待 |
| `remoteSummonMaxPendingPerPlayer` | `1` | `1..4` | 防止同一玩家连点制造并发加载 |
| `remoteSummonMaxPendingGlobal` | `4` | `1..64` | 防止多玩家同时触发大量区块加载 |
| `remoteSummonCooldownTicks` | `20` | `0..200` | 同一玩家 + companion 终态后的重复请求抑制；0 表示关闭 |

## 4. 位置刷新路径

必须更新最后已知位置的路径：

1. 契约成功。
2. 召唤 / 复活重建成功。
3. 已加载实体传送成功。
4. `EntityJoinLevelEvent` 命中 canonical UUID。
5. `EntityLeaveLevelEvent` 命中 canonical UUID，且在区块卸载前记录。
6. P2 异步请求终态成功后再次刷新。

默认不把 `LivingEvent.LivingTickEvent` 纳入方案。若实机证明离场事件漏记且位置误差会影响远召，再单独立项评估低频补偿，不能退化为对所有 LivingEntity 每 tick 记录。

## 5. 服务器负担边界

- 单请求最多关注 `(2 * radius + 1)^2` 个区块；默认 9 个。
- 全局默认最多 4 个 pending；默认最多约 36 个待完成区块 future。
- 加载完成、失败、超时或取消后立即释放 ticket。
- 目标区块若需要生成，仍可能产生 I/O、生成和实体初始化开销；有界临时加载不等于零成本。
- 紧急回滚优先使用 `remoteSummonEnabled=false`，P0/P1 安全门槛必须继续生效。
