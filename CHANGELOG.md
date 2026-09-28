# Changelog

All notable changes to **Furkin (绒亲)** are documented in this file.

本文件记录 Furkin（绒亲）的所有重要变更。

---

## 版本规范 / Versioning

本项目采用 **Forge 官方推荐的版本号格式**：

This project follows the **Forge-recommended version format**:

```
MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH
```

| 段 / Segment | 含义 / Meaning | 何时递增 / Incremented when |
|---|---|---|
| `MCVERSION` | 适用的 Minecraft 版本 / target Minecraft version | 始终与 MC 版本一致 |
| `MAJORMOD` | 模组主版本 / mod major | 删物品 / 改删既有机制 / 升 MC 版本 |
| `MAJORAPI` | **API 主版本 / API major** | ⭐ **破坏性 API 变更**：改枚举顺序或变量、改方法返回类型、整体移除 public 方法 |
| `MINOR` | 次版本 / minor | 加物品 / 加新机制 / 废弃 public 方法 |
| `PATCH` | 修订 / patch | 修 bug |

**派生两件事 / Two consequences**：

1. **读版本号就能判断 API 兼容性** —— 第三方只需比较 `MAJORAPI` 段。
   程序内可用 `FurkinApi.getApiVersion()` 查询该段的值。
   *Third parties only need to compare the `MAJORAPI` segment; it is queryable at runtime via `FurkinApi.getApiVersion()`.*
2. **本文件以 `### API Changes` 专节标记所有 API 变更** —— 破坏性的会在条目里明写。
   *All API changes are collected under a dedicated `### API Changes` section; breaking ones are called out explicitly.*

> 参考来源 / Reference：Forge 文档《Versioning》——
> `MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH` 能「区分世界不兼容与 API 不兼容的改动」。

---

## [1.19.2-0.0.4.0] - 2026-09-28

### Added / 新增

- **主人跨维度随行 / Owner dimension follow** —— 主人发生真实跨维度切换（地狱门、末地门、跨维度 `/tp`/`/execute in`）时，出发维度内、半径内、已加载、属于本人的 canonical 绒亲随行到目标维度；坐姿绒亲到达后清除坐姿并跟随。实现为「旅行前快照 + 到达后服务端 tick 完成校验」，不在旅行事件内提前移动实体，因此旅行被取消、玩家落到第三维度或快照超时都不会产生错误传送。只处理已加载实体：不申请 chunk ticket、不加载冷区、不重建实体、不复制档案。原版末地「终章返回」（末地出口传送门 -> 终章 -> 重生）按范围外处理，不随行。落点只接受已加载、向下最多 2 格内有可站立支撑且体积不含危险方块的位置；主人悬空于深坑/熔岩上方时，该只留在原维度，不冒险落点。
  *When the owner performs a real dimension change (nether portal, End portal, cross-dimension `/tp`/`/execute in`), nearby loaded companions in the departure dimension now follow to the target dimension; sitting companions stand up and follow on arrival. Implemented as a pre-travel snapshot plus a server-tick completion check, so pets are never moved inside the travel event itself; a cancelled travel, a third destination, or an expired snapshot never produce a wrong teleport. Only already-loaded entities are handled: no chunk tickets, no cold-chunk loading, no rebuilds, no archive copies. The vanilla End "credits return" (End exit portal -> credits -> respawn) is out of scope and does not follow.*
- **跨维度随行服务端配置 / Owner dimension follow server config** —— `furkin-server.toml` 新增 `ownerDimensionFollowEnabled`（默认 `true`）与 `ownerDimensionFollowRadius`（默认 `16`，范围 1-64，出发位置到绒亲中心的 3D 欧氏半径）。
  *`furkin-server.toml` now exposes `ownerDimensionFollowEnabled` (default `true`) and `ownerDimensionFollowRadius` (default `16`, range 1-64, 3D Euclidean radius from the departure position to the companion center).*
- **显式远召优先 / Explicit remote summon takes priority** —— 同一绒亲在快照时或执行时存在同一玩家的活跃远召 pending 时，随行只读识别并让路：不移动、不取消 pending、不改写其终态；快照阶段记录 `remotePendingAtArm`，因此 pending 随后失败或取消也不会补做随行，避免「失败提示之后宠物又出现」。
  *If the same companion already has an active remote-summon pending for the same player at snapshot or execution time, owner-follow yields read-only: it never moves the pet, cancels the pending, or rewrites its terminal state. `remotePendingAtArm` also prevents a late catch-up follow after the pending fails or is cancelled.*

### API Changes / API 变更

- 无破坏性 API 变更；公开 API 与网络包结构不变，`PROTOCOL_VERSION` 保持 `"2"`，不新增存档字段。
  *No breaking API changes; the public API and packet structure are unchanged, `PROTOCOL_VERSION` remains `"2"`, and no save schema field was added.*

### Validation / 验证

- `compileJava`、`build` 通过；产物 `build/libs/furkin-1.19.2-0.0.4.0.jar`，展开后的 `mods.toml` 版本为 `1.19.2-0.0.4.0` 且保持纯 ASCII，jar 内无 `internal.debug` / fixture 类。
- `runServer` 启动到 `Done`；RCON `stop` 后完成 `Saving players`、`Saving worlds`、overworld/end/nether 三维度保存，Gradle 以 `BUILD SUCCESSFUL`、退出码 0 结束；日志无 `FATAL`，`ERROR` 仅为此前 `runServer` 日志同样存在的 vanilla/Forge `TagLoader` 标签噪声。
- `runClient` 启动到资源加载/标题界面；客户端日志 `ERROR=0`、`FATAL=0`。
- 静态审计：随行路径没有新增 `LivingTickEvent`、`getChunkFuture`、`addRegionTicket`、`setChunkForced`；`PROTOCOL_VERSION` 保持 `"2"`，公开 API、存档 schema 与网络包均未变更。
- 一次性服务端夹具覆盖 F-04 至 F-29，最终输出 `FURKIN_FIXTURE_ODF_OK checks=52 failures=0`；覆盖半径边界、取消/第三维度/超时、冷/卸载实体、其他玩家/野生/已收回/死亡/重复体、坐姿、部分失败、旧版落点回退（历史夹具，已被 F-19 重新打开）、远召 pending 让路、pending 后失败、随行后显式远召复用 canonical、停机清理等。日志为 `D:\frukin_dev\_research\owner_dimension_follow_fixture_20260928.log`；夹具源码、临时运行世界与 fixture 标记已删除，最终 jar 不含 fixture。
- 服务端性能：无夹具 30 分钟空闲长跑全程 20 TPS，overall mean tick time `0.859-0.910 ms`；临时压力夹具覆盖半径 16/64、`activeLimit=3/20`、80/150 只附近野生生物和 12 玩家同 tick。cold 半径16/20只 P95 `19.979 ms`，hot P95 `4.854 ms`、max `6.538 ms`；默认3只 P95 `1.410 ms`；半径64 P95 `5.883 ms`；12x3 P95 `7.027 ms`。这些数据采集于 F-19 支撑搜索修复前；修复只影响换维度落点选择的常数级方块读取/碰撞检查。
- 服务端性能日志：`D:\frukin_dev\_research\odf_perf_30m_20260928.txt`、`D:\frukin_dev\_research\odf_perf_stress_20260928.log`。夹具源码与临时世界已删除；这些结果只代表服务端性能口径，真实客户端帧率和双客户端 fanout 的最终补测见下。
- 落点安全聚焦夹具：临时 `OdfLandingFixture` 通过 `findSafeLanding(...)` 直接验证 5 个分支，输出 `FURKIN_FIXTURE_ODF_LANDING_OK checks=5 failures=0`；覆盖正常地面、向下超过 2 格无支撑、身体浸入熔岩、半砖支撑、身体浸入火。日志 `D:\frukin_dev\_research\odf_landing_fixture_20260928.log`，夹具源码已删除。
- 真实客户端核心矩阵已执行：主世界 ↔ 地狱往返通过，三只全员两段均为 `moved=3 failed=0 skipped=0`；坐姿随行通过，显式远召 pending 联动已部分验证（无取消、无重复实体）；末地终章返回通过范围外负向口径。F-19 落点安全已重新打开并完成修复复验：2026-09-28 17:37 实机中，主人从地狱传送门高处坠落，三只 `moved=3` 后约 3 秒在熔岩区死亡，旧实现未校验脚下支撑。当前实现改为向下最多 2 格搜索支撑面并拒绝无支撑/危险体积；17:55 正常安全门测试 `moved=3` 且无死亡，17:57 跨维度 `/tp` 到地狱 `y=250` 的无支撑确定性场景记录三只 `NO_SAFE_LANDING`，最终 `moved=0 failed=3`，没有新的 `Furkin teleported`。
- 最终代码补测：F-19 支撑搜索修复后，服务端压力复测默认 3 只 P95 `1.861 ms`、12×3 同 tick P95 `8.558 ms`、radius16/activeLimit20 cold P95 `23.352 ms`；默认 `activeLimit` 仍保持 3。
- F-26 记录口径：服务端状态机和真实客户端失败反馈均已通过；`OdfAlpha` 实际收到“远距召唤已取消，档案状态未改变”。真实客户端稳定窗口 FPS p50/p95 均为 60；双客户端 fanout 为 3×207 bytes、`chunkTrackers=2`，显式同步与 `PlayerEvent.StartTracking` 合计每客户端 6 次处理记录。

  *`compileJava` and `build` pass; the produced `build/libs/furkin-1.19.2-0.0.4.0.jar` expands `mods.toml` to version `1.19.2-0.0.4.0` (pure ASCII) and contains no `internal.debug` / fixture classes. `runServer` reached `Done`; an RCON `stop` completed `Saving players`, `Saving worlds`, and all three dimension saves, after which Gradle reported `BUILD SUCCESSFUL` and exited with code 0. No `FATAL` was logged; the only `ERROR` lines are the vanilla/Forge `TagLoader` tag noise already present in earlier `runServer` logs. `runClient` reached resource loading / the title screen with `ERROR=0` and `FATAL=0`. Static audit found no new `LivingTickEvent`, `getChunkFuture`, `addRegionTicket`, or `setChunkForced` on the follow path, and `PROTOCOL_VERSION` stays `"2"` with no public API, save-schema, or packet changes. A one-off server fixture covered F-04 through F-29 with `FURKIN_FIXTURE_ODF_OK checks=52 failures=0`, including radius boundaries, cancellation/third-dimension/timeout, cold/unloaded entities, other-owner/wild/recalled/dead/duplicate filtering, sitting pets, partial failure, the historical landing fallback (the old fixture assertion is obsolete after F-19 was reopened), remote-summon pending yielding, post-pending failure, canonical reuse for an explicit summon after follow, and shutdown cleanup. Fixed-prefix log: `D:\frukin_dev\_research\owner_dimension_follow_fixture_20260928.log`; fixture source, temporary run world, and fixture markers were removed, and the final jar contains no fixture classes. Server performance: a fixture-free 30-minute idle run stayed at 20 TPS with overall mean tick time 0.859-0.910 ms. Stress fixtures covered radius 16/64, `activeLimit=3/20`, 80/150 nearby wild entities, and 12 same-tick players: cold radius-16/20-companion P95 was 19.979 ms; hot P95 was 4.854 ms with a 6.538 ms max; default-three P95 was 1.410 ms; radius-64 P95 was 5.883 ms; 12x3 P95 was 7.027 ms. Logs: `D:\frukin_dev\_research\odf_perf_30m_20260928.txt` and `D:\frukin_dev\_research\odf_perf_stress_20260928.log`. Fixture source and temporary worlds were removed. These server metrics are separate from the real-client frame-rate and two-client fanout results reported below. Real-client portal matrix executed: Overworld <-> Nether round trips passed, including the all-three moved=3/failed=0/skipped=0 run; sitting-follow passed, and explicit remote-summon pending interplay was partially verified with no cancellation or duplicate entity. The End credits return passed the negative out-of-scope expectation. Focused landing checks cover normal ground, unsupported drop beyond 2 blocks, a body inside lava, slab support, and a body inside fire. F-19 is reopened: the 2026-09-28 17:37 real-client run logged `moved=3` and then all three pets died in lava because the old implementation did not validate support below the destination. The current implementation searches up to 2 blocks downward for support and rejects unsupported/hazardous destination volumes. Real-client revalidation passed: a normal safe landing moved all three pets without deaths, while a deterministic cross-dimension `/tp` to Nether `y=250` recorded `NO_SAFE_LANDING` for all three and finished with `moved=0 failed=3`, with no new `Furkin teleported` entries. F-26 is complete: the server state machine and real-client failure feedback both passed, and the final-code stress retest records default-three P95 1.861 ms, 12x3 P95 8.558 ms, and radius-16/activeLimit-20 cold P95 23.352 ms. Stable-window real-client FPS was 60/60 p50/p95, and the two-client fanout measured 3 x 207-byte packets with chunkTrackers=2. The alternate F-16/F-25 timing branch remains partially verified; production hardware/view-distance and long-run public-network traffic are not extrapolated from this environment.*
## [1.19.2-0.0.3.0] - 2026-09-27

### Added / 新增

- **契约血量前置 / Contract health precondition** —— 契约前按 `Enemy > NeutralMob > 其他` 检查生命值：Enemy 默认 `30%` 或 `4` 点、NeutralMob 默认 `50%` 或 `8` 点、其他默认不限制；发起命名与确认阶段共用服务端权威校验，生命值过高时显示对应提示并取消原版右键，非法生命值静默拒绝。Forge 多部件实体会先解析到父实体。
  *Contracts now check health before accepting a target, using `Enemy > NeutralMob > other` classification: Enemy defaults to 30% or 4 HP, neutral mobs to 50% or 8 HP, and other species are unlimited by default. Initiation and confirmation share the same server-authoritative check; a too-healthy target gets a localized threshold message and cancels the vanilla interaction, while invalid health fails silently. Forge multipart entities resolve to their parent first.*

- **真正远距召唤 / True remote summon** —— 已召唤绒亲所在区块未加载时，按档案记录的最后已知维度与位置添加有界临时 chunk ticket，在后台加载完成后按 canonical UUID 重新定位并传送同一实体；加载失败、超时、重复实体或状态变化只安全失败，不重建、不复制档案、不清定位。默认半径 1（3x3）、超时 600 tick，并按玩家/全服限制并发。
  *When a summoned companion's chunk is unloaded, Furkin now adds a bounded temporary chunk ticket at the recorded dimension/position, re-resolves the same entity by its canonical UUID after the chunks load, and teleports it. Load failure, timeout, duplicate entities, or state changes fail safely without rebuilding, copying the archive, or clearing the recorded location.*
- **重复实体恢复 / Duplicate companion recovery** —— 新增 `/furkin repair list <pet_id>` 与 `/furkin repair choose <pet_id> <keep_entity_uuid>`：先把 keeper 缺失的装备/行囊搬给它（槽位冲突时掉落在脚下），再删除重复实体；canonical 入世守卫不再把 UUID 不同的实体当作档案实体。
  *Added `/furkin repair list <pet_id>` and `/furkin repair choose <pet_id> <keep_entity_uuid>`: the keeper receives the equipment/pouch items it is missing (conflicts drop at its feet) before duplicates are removed; the canonical join guard no longer treats an entity with a different UUID as the archived companion.*
- **远召服务端配置 / Remote summon server config** —— `furkin-server.toml` 新增 `remoteSummonEnabled`、`remoteSummonTicketRadius`、`remoteSummonTimeoutTicks`、`remoteSummonMaxPendingPerPlayer`、`remoteSummonMaxPendingGlobal`、`remoteSummonCooldownTicks`；默认值与范围见 `docs/remote-summon-1.19.2/p2-true-remote-summon.md`。`remoteSummonEnabled=false` 只关闭「已召唤但未加载」的远程加载，已加载传送和合法重建仍可用。
  *`furkin-server.toml` now exposes `remoteSummonEnabled`, `remoteSummonTicketRadius`, `remoteSummonTimeoutTicks`, `remoteSummonMaxPendingPerPlayer`, `remoteSummonMaxPendingGlobal`, and `remoteSummonCooldownTicks`; defaults and ranges are documented in the remote-summon feature documentation under docs/remote-summon-1.19.2/. Disabling only rejects the remote-loading path for an already-summoned but unloaded companion; loaded-entity teleport and legal rebuild still work.*

### Fixed / 修复

- **未加载绒亲不再丢装备 / No more equipment loss in unloaded chunks** —— `summoned=true` 但运行时索引未命中时只返回失败，不再把档案改成 `summoned=false`、不再清 `entity_uuid` / `entity_dimension` / `entity_pos`、不再从旧快照重建第二只实体，因此不会丢失活动实体上的实时装备与行囊。
  *When a companion is `summoned=true` but missing from the runtime entity index, the summon path now fails safely instead of flipping the record to `summoned=false`, clearing its identity/location, or rebuilding a second entity from an old snapshot, so live equipment and pouch contents on the original entity are no longer lost.*
- **未召唤档案不重建已加载身份 / No rebuild while identity is loaded** —— 已加载身份索引改为按 `companionId` 登记所有已契约实体；`summoned=false` 时若运行时仍存在同一身份实体（含 canonical UUID 为空或 UUID 不同的孤儿/重复体），返回 `DUPLICATE_CONFLICT` 并拒绝重建；无任何已加载同身份实体时，合法重建保持不变。
  *The loaded-identity index now tracks all contracted entities by `companionId`. When `summoned=false` and any same-identity entity is still loaded (including an orphan/duplicate with no canonical UUID or a different UUID), rebuild is rejected with `DUPLICATE_CONFLICT`; legal rebuild remains unchanged when none is loaded.*
- **远召异步反馈 / Asynchronous remote-summon feedback** —— 绒亲录与 `/furkin summon` 共用服务端远召服务：pending 有本地化提示，绒亲录按钮在请求期间显示“召唤中……”并禁用，所有非 pending 终态刷新列表；失败 / 超时 / 重复冲突给出对应原因且不误报成功。
  *The companion record and `/furkin summon` now share the server-side remote-summon service: pending requests get localized feedback, the record button shows a disabled loading state, every non-pending terminal state refreshes the list, and failures / timeouts / duplicate conflicts report their reason without a false success.*

### API / API Changes

- 无破坏性 API 变更；公开 API 与网络包结构未变化，`PROTOCOL_VERSION`（`"2"`）保持不变。
  *No breaking API changes; packet structure and `PROTOCOL_VERSION` (`"2"`) are unchanged.*

### Validation / 验证

- 一次性服务端夹具在 1.19.2 / Forge 43.2.0 下覆盖 33 个场景，`pass=33 fail=0`；原版 EnderDragon 多部件事件目标解析、契约、喂食、面板与收回路径均通过。
  *A one-off server fixture covered 33 scenarios with `pass=33 fail=0`; vanilla EnderDragon multipart event targets passed parent resolution, contract, feeding, panel, and recall paths under 1.19.2 / Forge 43.2.0.*
- `clean build` 成功；无夹具 `runServer` 到达 `Done`，无夹具 `runClient` 完成资源加载并启动；最终 JAR 不含 `internal.debug` / fixture。
  *`clean build` succeeded; a clean `runServer` reached `Done`, a clean `runClient` loaded resources and started, and the final JAR contains no `internal.debug` / fixture classes.*

## [1.19.2-0.0.2.0] - 2026-09-25

### Fixed / 修复

- **技能退款与数据校验 / Skill refunds and schema validation** —— 洗点现按每级实际支付成本退款，热改技能 `cost` 不会重写已支付金额；旧档缺少实付表时按当前定义一次性迁移，已删除定义按 `cost=1` 兜底并告警。加载期拒绝非正 `cost`、非法 `maxLevel` / `tier` / `requiresLevel`，以及悬空或不可达的 `requires` / `requiresLevel` / `levelGate` 引用，避免非法技能数据制造或吞掉技能点。
  *Respec now refunds the amount actually paid per level, and hot-changing a skill's `cost` no longer rewrites past payments. Legacy saves without a payment ledger migrate from the current definition once; deleted definitions fall back to `cost=1` with a warning. Loading rejects non-positive `cost`, invalid `maxLevel` / `tier` / `requiresLevel`, and missing or unreachable `requires` / `requiresLevel` / `levelGate` references, preventing malformed skill data from creating or consuming skill points incorrectly.*
- **技能热重载一致性 / Skill hot-reload consistency** —— `/reload` 后会按当前技能树清理并重建已加载绒亲的 `furkin:attribute` modifier，删除技能、切换属性目标或修改数值不会留下重复/失效加成；重载时未加载实体在入世时校准。`bleeding_bite` 保持实时语义：立即采用新 DPS，不重写已施加持续时间，定义失效后停止伤害并自然到期。
  *After `/reload`, loaded companions now clear and rebuild their `furkin:attribute` modifiers from the current tree, preventing duplicate or stale bonuses when a skill is deleted, retargeted, or rebalanced; entities unloaded during the reload are calibrated on join. `bleeding_bite` keeps live semantics: new DPS applies immediately, existing duration is preserved, and damage stops if the definition becomes invalid.*
- **客户端网络包隔离 / Client packet isolation** —— 共享网络包不再直接承载 `Minecraft`、客户端界面和本地 capability 写入；五条客户端回调统一下沉到 `FurkinClientPacketHandler`，由 `DistExecutor` 仅在客户端分发。协议字段和包结构不变。
  *Shared network packets no longer directly contain `Minecraft`, client screen classes, or local capability writes. Five client callbacks now go through `FurkinClientPacketHandler` behind `DistExecutor` client-only dispatch. Packet fields and structure are unchanged.*
- **命令回执本地化 / Localized command feedback** —— `furkin` 命令的召唤、列表、解绑、改名、加经验、模式、技能、查看和行囊回执改为翻译键；中英文键集合同步，动态名称、数量、UUID 和技能 ID 作为参数传递。
  *`furkin` command feedback for summoning, listing, unbinding, renaming, XP, combat mode, skills, inspection, and pouches now uses translation keys. English and Chinese key sets stay in sync, while dynamic names, counts, UUIDs, and skill IDs are passed as arguments.*
- **存档与技能数据加固 / Save and skill data hardening** —— 非法 `FurkinState` / `FurkinCombatMode` 不再让实体或档案反序列化抛异常；损坏档案条目会被单条跳过并告警，不影响同档其它宠物；技能同 ID 重复定义会记录双方来源并保留首个定义，不再静默覆盖。
  *Invalid `FurkinState` / `FurkinCombatMode` values no longer make entity or archive deserialization throw. Bad archive entries are skipped individually with a warning, without taking down the rest of the save. Duplicate skill IDs now log both sources and keep the first definition instead of silently overriding it.*

---

## [1.19.2-0.0.1.0] - 2026-09-24

**Minecraft 1.19.2 移植版 / Minecraft 1.19.2 port** —— 在保留 `1.20.1-0.0.1.0` 功能范围的前提下，将构建目标迁移到 Minecraft 1.19.2 / Forge 43.x。
*Migrates the build target to Minecraft 1.19.2 / Forge 43.x while preserving the feature set of `1.20.1-0.0.1.0`.*

### Changed

- 目标运行环境改为 Minecraft `1.19.2`、Forge `43.2.0`；映射改为 `official 1.19.2`，资源包格式改为 `9`。
  *Target runtime changed to Minecraft `1.19.2` and Forge `43.2.0`; mappings now use `official 1.19.2`, and the resource pack format is `9`.*
- 客户端 GUI 从 1.20.1 的 `GuiGraphics` 移植到 1.19.2 的 `PoseStack`。
  *Client GUIs were ported from 1.20.1 `GuiGraphics` to 1.19.2 `PoseStack`.*
- 适配 1.19.2 的实体/世界访问方法、命令消息、按钮与 `EditBox`、滚动控件、创造模式标签页和世界渲染阶段 API。
  *Adapted entity/world accessors, command messages, buttons and `EditBox`, scroll widgets, the creative tab, and world rendering-stage APIs for 1.19.2.*

### Fixed

- 调整按钮贴图尺寸、技能列表行距、首次滚轮聚焦、列表滚动位置恢复与长名字截断，保持 1.20.1 的界面行为。
  *Adjusted button texture sizing, skill-row spacing, initial mouse-wheel focus, scroll-position restoration, and long-name truncation to preserve the 1.20.1 UI behavior.*
- 召唤或传送成功后复用现有绒亲录刷新逻辑，界面就地更新而不重新打开。
  *After a successful summon or teleport, the existing record refresh path updates the screen in place instead of reopening it.*

### API Changes

- 公开 API 的 7 个类型及其签名保持不变；本次为 Minecraft/Forge 平台迁移，不是 public API 破坏性变更。
  *The seven public API types and their signatures are unchanged; this is a Minecraft/Forge platform port, not a breaking public API change.*
- `1.19.2-0.0.1.0` 与 `1.20.1-0.0.1.0` 是按 Minecraft 主线分开的构建产物；第三方应将 `MCVERSION` 视为加载兼容边界。
  *`1.19.2-0.0.1.0` and `1.20.1-0.0.1.0` are separate artifacts per Minecraft line; third parties should treat `MCVERSION` as the load-compatibility boundary.*

### Validation

- 使用 JDK 17.0.2 执行 `compileJava` 通过，javac 错误为 0。
  *`compileJava` passed with JDK 17.0.2 and zero javac errors.*
- 客户端可启动并进入单人世界；集成服务端可保存世界并正常退出，未产生崩溃报告。
  *The client starts and enters a single-player world; the integrated server saves and exits cleanly without a crash report.*
- WP9d GUI 回归 D1–D9 全部通过，覆盖契约命名、绒亲录、改名、技能、装备、行囊和复活流程。
  *WP9d GUI regression D1–D9 passed, covering contract naming, the record, renaming, skills, equipment, the pouch, and revival flows.*
- 专用服务端冒烟测试可启动至 `Done`；专用服务端正常关闭与保存验证仍待补做。
  *The dedicated-server smoke test reaches `Done`; graceful shutdown and save verification for the dedicated server remain outstanding.*

### Known Issues

- 不保证 1.20.1 世界存档可直接在 1.19.2 中加载；跨版本使用前应备份并单独验证。
  *Minecraft 1.20.1 worlds are not guaranteed to load directly in 1.19.2; back up and verify separately before crossing versions.*

## [1.20.1-0.0.1.0] - 2026-09-22

**首个完整版本 / Initial complete version** —— M0 至 M5 全部功能完成。
*All functionality from M0 through M5 is complete.*

### Added

- **契约系统 / Contract system** —— 将原版猫、狗契约成伙伴：契约物品、命名界面、档案持久化。
  *Contract vanilla cats and dogs into companions: contract item, naming screen, archive persistence.*
- **伴侣管理 / Companion management** —— 召唤 / 收回 / 解除契约 / 改名；头顶状态图标；丢失与死亡状态追踪。
  *Summon / retract / release / rename; overhead status icon; lost & fallen state tracking.*
- **成长与技能树 / Growth & skill tree** —— 升级曲线、技能点、13 条技能（主干 6 + 猫 4 + 狗 3）；技能数据驱动（JSON，`/reload` 即时生效）。
  *Level curve, skill points, 13 skills (6 trunk + 4 cat + 3 dog); skill data is JSON-driven and hot-reloadable.*
- **随身行囊 / Travel pouch** —— 随伙伴同行的背包，格数随 `travel_pouch` 技能等级派生；缩容 / 回收 / 死亡掉落处理。
  *Carry-along inventory sized by the `travel_pouch` skill level; handles shrinking, reclaiming, and death drops.*
- **装备系统 / Equipment system** —— 4 格装备槽（头 / 胸 / 腿 / 脚），入档与死亡快照，`setDropChance(0)` 配对关闭掉落。
  *Four equipment slots (head/chest/legs/feet), archived with the companion, paired death snapshot and drop-chance suppression.*
- **复活系统 / Revival system** —— 魂石（防火，绑定 `companion_id`）、12 朵花 + 羊毛复活仪式、绒亲录内「重获魂石」兜底（1 钻石 + 600s 每宠物冷却）。
  *Soulstone (fire-resistant, bound by `companion_id`), a 12-flower + wool revival ritual, and an in-record "reacquire soulstone" fallback (1 diamond + 600s per-pet cooldown).*
- **绒亲面板 / Furkin panel** —— 容器屏，技能 / 行囊 / 装备三页签；技能页当前属性区（三行抬头 + 悬停全属性明细）。
  *Container screen with Skill / Pouch / Equipment tabs; the skill tab shows a live attribute area with a hover breakdown.*
- **绒亲录属性区 / Record attribute area** —— 档案界面内展示伙伴属性。
  *Companion attributes displayed inside the record screen.*
- **命令层 / Command layer** —— `/furkin` 命令树，覆盖召唤、档案、技能等调试与操作入口。
  *A `/furkin` command tree covering summon, archive, skills and other operations.*
- **自建创造标签页 / Custom creative tab** —— 绒亲品牌 tab，解决四件物品在创造搜索中搜不到的问题。
  *A dedicated Furkin creative tab so all four items are searchable in creative.*
- **配置项 / Configuration** —— 客户端与服务端 TOML 配置（含平衡数值）。
  *Client and server TOML configs, including balance values.*
- **公开 API / Public API** —— `com.wanancat.furkin.api` 包，供第三方注册物种与响应升级事件。
  *The `com.wanancat.furkin.api` package for third parties to register species and react to level-ups.*

### API Changes

⭐ **本版本为 API 首次发布 / First public API release.**

- **新增公开 API 包 `com.wanancat.furkin.api`**，含 7 个公开类型：
  *New public API package `com.wanancat.furkin.api` with 7 public types:*
  - `FurkinApi` —— 静态入口：`registerSpecies()` / `isRegistered()` / `getSpecies()` / `getApiVersion()`
  - `api.companion.FurkinSpecies` —— 物种标识
  - `api.companion.FurkinSpeciesRegistry` —— 物种注册表
  - `api.companion.IFurkin` —— 伴侣只读查询接口
  - `api.skill.FurkinSkillEffectType` —— 自定义技能效果类型
  - `api.skill.FurkinSkillEffectTypeRegistry` —— 效果类型注册表
  - `api.event.FurkinLevelUpEvent` —— 升级事件（可取消，不可覆写）
- **`MAJORAPI` 段当前为 `0`** —— 表示 API 尚未承诺稳定，首次正式发布时将进位为 `1`。
  *`MAJORAPI` is currently `0`, signalling that the API is not yet declared stable.*
- ⚠️ **边界说明 / Boundary note**：`api` 包以外的所有 furkin 类型（尤其
  `com.wanancat.furkin.internal.*`）均为**内部实现**，结构随时可能变更，**不在兼容承诺范围内**。
  本版本**不发布独立的 api jar**，故该边界是**约定**而非编译期约束 —— 请自行只依赖 `api` 包。
  *Everything outside the `api` package — especially `internal` — is implementation detail and carries no
  compatibility promise. No separate api jar is published, so this boundary is a convention, not a compile-time
  enforcement.*

### Notes

- 平衡数值为**初版冻结值**，后续版本可能调整。
  *Balance values are frozen at their initial set; later versions may adjust them.*
- ⚠️ 四件物品（契约 / 绒亲录 / 洗点药水 / 魂石）**未挂任何原版创造标签页**，仅出现在自建 furkin tab 中。
  *The four items appear only in Furkin's own creative tab, not in any vanilla tab.*
