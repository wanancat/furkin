# Furkin 1.20.1 远距召唤移植工作包

- 日期：2026-09-27
- 分支：`mc1.20.1`
- 记录基线：`5ad092473a48b411782c16ebdbd81893d7674c11`
- 参考实现：`mc1.19.2` / `206a92732c843a7f0aeea72fa7a3a6287b01356d`（远距传送功能包完成提交）
- 1.19.2 当前 HEAD：`3d3c8b2`（仅额外包含契约血量前置条件，不在本包范围；文档以 `206a927` 为行为冻结点）
- 参考工作文档：`D:\frukin_dev\frukin_1_19_2\docs\remote-summon-1.19.2\`
- 状态：实现已落在当前工作树；2026-09-27 完成独立复查、代码移植、P0/P1 核心夹具、P2 冷区重启夹具、已加载跨维度夹具和最终 `clean build` / 去夹具 `runServer` 烟测。完整客户端交互、故障注入、生命周期取消和性能矩阵仍待补证据；提交与推送前需乌狸确认。
- 目标版本：`1.20.1-0.0.3.0`；`gradle.properties`、CHANGELOG 和构建产物名称保持一致
- 适用环境：Minecraft 1.20.1 / Forge 47.2.0 / Java 17 / official 1.20.1 mappings

> 本功能包是行为移植，不是把 1.19.2 分支整体合并，也不做全局文本替换。每个受影响方法都要按 1.20.1 的真实接收者类型、返回类型和语义重新核对。

## 1. 交付目标

远距召唤的最终语义与 1.19.2 功能包一致：

1. `summoned=true` 且实体已在运行时索引中：立即传送同一实体。
2. `summoned=true` 且实体因区块卸载不在索引中：读取最后已知维度与位置，临时加载有界区块，按 canonical UUID 重新定位并传送同一实体。
3. 定位、加载、超时或传送失败：只返回失败，不修改 `summoned` / `alive` / `entity_uuid` / 维度 / 位置，不创建第二只实体，不把失败解释为“已收回”。
4. 只有档案本来就是 `summoned=false` 的合法离线状态，才允许从档案快照重建；重建前必须做同 UUID 与同身份已加载实体守卫。
5. 同 `companionId` 出现重复已加载实体时，热路径安全拒绝，管理员用显式 OP 命令诊断和修复，不自动删除或自动选择。
6. 不引入永久 `FORCED` 强加载，不把所有活跃绒亲所在区块长期加载，不监听全体 `LivingEntity` 的逐 tick 位置记录。

## 2. 开工前的 1.20.1 基线

> 本节记录移植前基线与缺口，是实施依据；代码落地后的当前状态见第 10 节。

### 2.1 已经具备

- `FurkinArchiveData` 已统一使用主世界服务器级实例，数据版本为 `1`。
- `FurkinArchiveData` 已有 v0 旧维度档案合并逻辑。
- `FurkinArchiveEntry` 已有 `entity_uuid`、`entity_dimension`、`setEntityLocation(Entity)` 和 `clearEntityLocation()`。
- `FurkinEntityLocator` 已按档案 UUID + 维度做已加载索引查询，并可回退查询其它已加载维度。
- `FurkinCompanionManager` 已支持已加载实体的同维度传送和跨维度 `changeDimension(ServerLevel, ITeleporter)`。
- 解绑流程已能区分未解析实体，不会因常规解绑直接删档。
- `RequestSummonPacket` 当前线格式为一个 `UUID`，`PROTOCOL_VERSION` 为 `2`。

### 2.2 当前缺口

| 缺口 | 1.20.1 当前事实 | 需要在 1.20.1 完成 |
|---|---|---|
| 未解析实体安全失败 | `teleportToOwner(...)` 找不到实体时会执行 `setSummoned(false)`、`clearEntityLocation()` 并返回 `false`，下一次召唤可能重建第二只实体 | P0：改成只读失败并返回明确结果 |
| 最后已知位置 | `FurkinArchiveEntry` 只有 UUID 和维度，没有持久位置 | P2.1：增加 `BlockPos`，数据版本 `1 -> 2` |
| 重复实体守卫 | 入世事件仍会把任一 UUID 不同的同身份实体刷新为档案定位 | P1：canonical 入世守卫 + 只读诊断索引 |
| 重复实体修复 | 没有 repair 命令或搬运流程 | P1：`/furkin repair list|choose` 与显式 keeper 选择 |
| 未加载实体远召 | 没有区块 ticket、future、pending、timeout 或取消流程 | P2：`RemoteSummonService` 与异步生命周期 |
| 传送公共路径 | 传送逻辑仍内嵌在 `teleportToOwner(...)` | P2.2：抽出 `teleportLoadedEntity(...)` |
| 配置 | `FurkinServerConfig` 没有远召键 | P2.5：加入 6 个 SERVER 配置项 |
| 绒亲录在途态 | 点击后直接等待同步结果，没有 pending 禁用态 | P2.4：客户端本地在途态；不新增线协议 |
| 生命周期清理 | 没有 `EntityLeaveLevelEvent`、远召 tick、停服取消、登出取消接线 | P1/P2：把事件与 service 接到 `CommonEvents` |
| 文案 | 只有旧召唤 / 传送通用文案 | P0/P1/P2：补中英文键并同步键集合 |

### 2.3 基线事实的取证方式

文档中所有“当前基线”结论按以下方式取得。实施时若代码基线与下表不符，先停下核对并更新文档，不要按旧结论硬改：

| 事实类别 | 取证方式 |
|---|---|
| Java 类型、方法签名、返回类型 | 直接读 `src/main/java` 目标文件，逐方法确认接收者类型、返回值与语义 |
| Minecraft / Forge 公开 API | 对 mapped jar 用 `javap -p` 或反编译：`C:\Users\wanancat\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.2.0_mapped_official_1.20.1\forge-1.20.1-47.2.0_mapped_official_1.20.1.jar` |
| 行为参考 | 1.19.2 源码 `D:\frukin_dev\frukin_1_19_2\src\main\java\...`，行为冻结点 `206a927` |
| 语言键 | `en_us.json` / `zh_cn.json` 键集合 diff，再 `rg` 反查每个键的 Java 调用点，区分“实际使用键”和“遗留死键” |
| 协议 | `FurkinNetwork` 包注册顺序 + `PROTOCOL_VERSION` + `RequestSummonPacket.encode/decode` |
| 配置路径 | Forge 47 `ModConfig.Type.SERVER` → 世界级 `serverconfig/furkin-server.toml`；专用服默认 `run/world/serverconfig/furkin-server.toml`，集成服为 `run/saves/<存档目录>/serverconfig/furkin-server.toml` |
## 3. 版本差异与移植原则

1. `FurkinArchiveData` 的初始基版本是 `1`，本包已把当前版本推进为 `2`。必须保留 `v0 -> v1` 的旧维度档案合并，再增加 `v1 -> v2` 的位置字段空迁移。
2. 1.19.2 使用 `Registry.DIMENSION_REGISTRY`；1.20.1 当前代码使用 `Registries.DIMENSION`，移植时保持 1.20.1 写法。
3. 1.19.2 指南中的 `getLevel()` 在 1.20.1 仓库应使用现有风格 `level()`；已有 `Entity#changeDimension(ServerLevel, ITeleporter)` 和 `Entity#level()` 均可直接使用。
4. 1.19.2 的 GUI 使用 `PoseStack` / `GuiComponent`；1.20.1 当前仓库使用 `GuiGraphics` / `Button.builder(...)`。P2.4 只移植“在途态、禁用、刷新结束、tick 兜底”的行为，不复制旧渲染代码。
5. 1.20.1 的 `ChunkMap#getDistanceManager()`、`DistanceManager#addTicket/removeTicket`、`ChunkLevel#byStatus(FullChunkStatus.ENTITY_TICKING)` 是公开 API。优先用 `ChunkLevel` 计算实体 tick 所需 level，不硬编码旧版本的注释数字。
6. 所有位置读写必须在服务端线程完成；ticket 添加和移除只在服务端线程完成；`getChunkFuture(...)` 只能从 `Util.backgroundExecutor()` 调用，主线程调用会阻塞等待。
7. 不把 1.19.2 的 fixture 源码、测试世界、日志或内部调试类复制到 1.20.1 产品代码。夹具只能作为一次性验证手段，完成后清理。

## 4. 已核实的 1.20.1 API 边界

以下符号已用本机 mapped official jar 的 `javap` 核对，可作为实施起点；编码时仍要以编译器最终符号为准：

- `ServerChunkCache#getChunkFuture(int, int, ChunkStatus, boolean)`
  - 返回 `CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>`。
  - 主线程路径包含 `managedBlock`，不得在生产代码中主线程调用。
- `ServerChunkCache#addRegionTicket(...)` / `removeRegionTicket(...)`：公开，但不满足“精确控制每张目标区块 ticket level”的需求时，使用 `DistanceManager#addTicket/removeTicket`。
- `ChunkMap#getDistanceManager()`：公开。
- `DistanceManager#addTicket(TicketType<T>, ChunkPos, int, T)` / `removeTicket(...)`：公开。
- `ChunkLevel#byStatus(FullChunkStatus.ENTITY_TICKING)`：公开；1.20.1 中实体 tick level 为 `<= 31`。
- `ServerLevel#areEntitiesLoaded(long)`：公开；用于实体 section 是否已经完成入世。
- `ChunkPos#rangeClosed(ChunkPos, int)`、`ChunkPos#toLong()`：公开。
- `Util#backgroundExecutor()`：公开。
- `MinecraftServer#getTickCount()`、`execute(...)` / `executeIfPossible(...)`、`isSameThread()`：可用于 pending tick、回主线程和线程断言。
- `Entity#level()`、`Entity#changeDimension(ServerLevel, ITeleporter)`、`Entity#teleportTo(double, double, double)`：公开且当前仓库已使用或可直接使用。
- `NbtUtils#writeBlockPos(BlockPos)` / `readBlockPos(CompoundTag)`：公开；读取前必须自行验证 `entity_pos` 是完整 compound。
- `EntityLeaveLevelEvent`、`ServerStoppedEvent`、`TickEvent.ServerTickEvent`：公开 Forge 事件；实现不依赖 `ServerStoppingEvent`，事件包名和生命周期以当前 Forge 47 编译结果为准。
- Forge 47 mapped 字节码核实：实体加入 / 回载由 `PersistentEntitySectionManager.addEntity(...)` post `EntityJoinLevelEvent`；区块 section 卸载 / 实体离场由 `ServerLevel$EntityCallbacks.onTrackingEnd(...)` post `EntityLeaveLevelEvent`。因此“入世刷新位置、离场记录最后位置”的可行链路存在，不需要 `LivingTickEvent`。

## 5. 工作包索引

- [决策记录与冻结口径](decision-log.md)
- [P0：未解析实体安全失败](p0-safe-failure.md)
- [P1：重复实体恢复与 canonical 守卫](p1-duplicate-recovery.md)
- [P1 执行契约](p1-execution-contract.md)
- [P2：真正的远距召唤](p2-true-remote-summon.md)
- [P2 执行契约](p2-execution-contract.md)
- [统一验证矩阵](verification-matrix.md)
- [实施清单](implementation-checklist.md)


### 5.1 口径冻结索引

以下问题已在独立复查中冻结。实施与评审时直接引用本表，不再逐次讨论：

| 主题 | 冻结结论 | 权威位置 |
|---|---|---|
| ticket owner | `ChunkPos`，不是 `requestId`；`requestId` 只做日志与状态机标识 | D-23 |
| 同 `ChunkPos` 并发 ticket | 继承 1.19.2，不做引用计数；失败表现为安全失败 | D-24 |
| `syncArchiveCore` 名字 | 不同步名字 | D-25 |
| keeper 战斗 AI | `setTarget(null)` + `combatMode.applyTo(tamable)`，禁止 `clearCombatAiState()` | D-26 |
| P1 阶段名 | `DATA / POUCH / EQUIPMENT / POSTCONDITION / ARCHIVE` | D-27 |
| repair 校验顺序 | 档案 → owner → summoned → alive | D-28 |
| P0 未解析键生命周期 | 同一工作树直接使用终态键 `furkin.msg.remote_summon_unresolved`，不引入过渡键 | D-29 |
| 失败 / pending 键归属 | 按入口区分 `furkin.msg.*` 与 `furkin.command.*`，见 D-30 | D-30 |
| 配置发现 | 世界级 `serverconfig/furkin-server.toml`（专用服 `run/world/...`；集成服 `run/saves/<存档目录>/...`） | D-31 |
| `feedbackArmed` | 仅在成功返回 `PENDING` 前才置 `true` | D-32 |
| `repair choose` 二次确认 | 默认只预演，必须追加 `confirm` 才执行删除 | D-33 |
| 重复登记覆盖范围 | 所有已契约 `LivingEntity` 都登记；非 `TamableAnimal` 只跳过战斗 AI 重建 | D-34 |
## 6. 依赖与提交切片

```text
P0 安全失败
  └─ P1 canonical 守卫与显式修复
       └─ P2.1 位置字段与档案迁移
            └─ P2.2 传送公共路径
                 └─ P2.3 异步远招服务
                      └─ P2.4 命令 / 绒亲录接入与双语文案
                           └─ P2.5 配置与发布文档
                                └─ P2.6 实机与性能收口
```

建议每个切片独立编译、独立准备验证证据，不把 P0/P1/P2 混成一次大提交：

1. 本目录工作文档。
2. P0：安全失败与回归验证。
3. P1：入世 canonical 守卫、重复注册表和只读诊断。
4. P1：显式修复命令、核心数据复制和物品守恒。
5. P2.1：位置字段、NBT 兼容和 `1 -> 2` 迁移。
6. P2.2：抽取已加载实体传送公共路径。
7. P2.3：异步远招服务、ticket、pending、timeout、取消。
8. P2.4：命令 / 绒亲录接入、在途反馈和中英文文案。
9. P2.5：配置、README 说明边界、CHANGELOG 收口。
10. P2.6：服务端 / 客户端实机验证、性能与静态边界审计。

## 7. 范围内

- 未解析实体的只读安全失败。
- 重复实体 diagnostic registry、canonical 守卫、显式修复命令。
- 最后已知位置持久化与档案版本迁移。
- 有界临时 ticket、异步区块加载、按 UUID 定位、同实体传送。
- pending 的重复请求、并发上限、超时、登出、死亡、收回、解绑、停服清理。
- 服务端权威校验、配置、双语文案、客户端在途反馈。
- 服务端和客户端实机验证、日志检查、性能记录。

## 8. 范围外

- 将 1.19.2 分支整体合并或做全局替换。
- 默认永久 `FORCED` 所有活跃绒亲区块。
- 自动扫描或重写 region 文件。
- 自动合并两只都带物品的重复实体；keeper 必须显式指定。
- 为远召新增公开 API。
- 用远召替代正常收回、死亡或复活语义。
- owner 换维度时自动把身边绒亲批量随行（2026-09-27 乌狸明确不在本包）；若需要，另立 `owner-dimension-follow` 功能包。

## 9. 完成定义

P0、P1、P2 全部满足各自验收项，并且：

- 未加载实体远召成功后仍是同一实体 UUID，装备、行囊、等级、技能、战斗模式和冷却不丢失。
- 任何失败、超时、重复请求、登出、死亡、收回、解绑或停服后都没有残留 ticket、没有第二只实体、档案没有被错误改写。
- `RequestSummonPacket` 线格式和 `PROTOCOL_VERSION` 保持不变；若实现不得不变，先更新协议治理文档并递增版本。
- `FurkinArchiveData` 能读写 v0 / v1 旧档，`entity_pos` 缺失读为 `null`，不得回退到 `(0,0,0)`。
- `compileJava`、`build`、`runServer`、`runClient` 都有执行证据；`run/logs/latest.log` 无新增 Furkin 专属 `ERROR`、`FATAL`、异常栈或资源缺失。
- 中英文语言键集合同步，且不存在“只存在于 lang、没有 Java 调用点”的死键；`furkin.msg.summon_entity_unresolved` 不进入 1.20.1 lang。
- 世界级 `serverconfig/furkin-server.toml` 生成 6 个 `remoteSummon*` 键，范围与默认值符合决策记录；旧世界首次加载的 6 条 Forge 补键 WARN 已纳入预期。
- `REMOTE_SUMMON_TICKET` 的 owner 是 `ChunkPos`，不存在按 `requestId` 区分 ticket 的实现。
- CHANGELOG 只记录玩家可感知变更，不把工作文档当成发布说明。

## 10. 实施状态（2026-09-27）

- 代码已落在当前工作树，未提交、未推送。
- 已完成：P0 安全失败、P1 canonical 守卫与显式 repair、P2.1 位置字段 / v1→v2 迁移、P2.2 公共传送路径、P2.3 异步 service、P2.4 命令与绒亲录接入、P2.5 六项配置与中英文文案。
- 已执行证据：`compileJava`、`build` 通过；`runServer` 到达 `Done (2.685s)` 且 `run/logs/latest.log` 无 Furkin 专属 `ERROR` / `FATAL` / 异常栈；`runClient` 启动到客户端渲染初始化；专用服实测配置路径为 `run/world/serverconfig/furkin-server.toml`；中英文 lang 均 201 键且键集合一致；最终 jar 未含 fixture / debug 类。
- 已完成的静态审计：无永久 `FORCED` / `setChunkForced`，无主线程 `managedBlock`，无 `LivingTickEvent`，`findAllLoaded` 只出现在 repair 路径。
- 待验证：P0/P1/P2 游戏内交互、旧档 NBT 夹具、故障注入、物品守恒、重启收敛、冷热区与并发性能。统一状态见 [验证矩阵](verification-matrix.md)。
- 发布版本已按乌狸确认收口为 `1.20.1-0.0.3.0`；当前构建为 `furkin-1.20.1-0.0.3.0.jar`，与 `gradle.properties` 和 changelog 版本节一致。
- owner 换维度自动随行明确不在本包；需要时另立 `owner-dimension-follow` 功能包。
