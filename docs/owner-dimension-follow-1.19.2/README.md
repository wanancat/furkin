# Furkin 1.19.2 主人跨维度随行工作文档

- 日期：2026-09-28
- 分支：`mc1.19.2`
- 基线：`3d3c8b2e6b3f9798facd49c96fce26bf11cc4064`
- 状态：需求、冲突口径、实现、服务端压力与真实客户端核心矩阵均已于 2026-09-28 完成；最终补测记录见 §11.4，代码与文档已提交并推送到 `origin/mc1.19.2`。
- 目标运行时：Minecraft 1.19.2 / Forge 43.2.0 / Java 17
- 前置能力：`docs/remote-summon-1.19.2` 的 P0-P2 已收口；本项目复用其 canonical 定位、失败只读和跨维度传送原则
- 关联但不同项：1.20.1 的 `NV-01` 记录的是原版 `FollowOwnerGoal` 同维度自动传送距离；本功能只处理主人发生真实跨维度变化时的随行

## 0. 结论摘要

推荐实现一个仅服务端、内存态、无区块加载的 `owner-dimension-follow` 功能包：

1. 监听 `EntityTravelToDimensionEvent`。当实体是 `ServerPlayer` 时，在玩家仍处于出发维度、原始坐标仍有效的阶段，快照“身边 N 格内、已经加载且属于该玩家的 canonical 绒亲”。
2. 不在旅行事件里立刻移动宠物。该事件发生在真正的维度切换之前且可取消，此时玩家落点尚未完成，提前传送会产生取消泄漏和重入问题。
3. 在现有 `TickEvent.ServerTickEvent` 的 `Phase.END` 阶段检查快照。只有确认玩家已经到达目标维度后，才用既有 `FurkinCompanionManager.teleportLoadedEntity(...)` 路径逐只传送。
4. 只处理出发维度中已经加载的实体；不添加 chunk ticket、不加载冷区宠物、不重建、不复制档案、不处理远维度未加载宠物。
5. 每只宠物在真正传送前重新校验 `companionId`、owner、存活/召唤状态、canonical UUID 和档案状态；任一不一致就跳过该只，继续处理其余宠物。
6. 若快照时或执行时同一 companion 已有同一玩家的活跃远召 pending，随行让路：不移动、不取消 pending、不修改其终态，由现有 `RemoteSummonService` 按既有规则继续。
7. 不新增网络包、不新增公开 API、不新增存档字段、不提升 `PROTOCOL_VERSION`。发布时按“新机制”将 `mod_version` 从 `1.19.2-0.0.3.0` 推进到 `1.19.2-0.0.4.0`。

已冻结默认口径（2026-09-28 乌狸确认）：

- 总开关：开启。
- 半径：默认 16 格，服务端配置可调，范围 1-64；已确认。
- 半径计算：出发玩家中心点到绒亲位置的 3D 欧氏距离，`<= N` 通过。
- 只处理同一出发维度内已加载的实体。
- 坐下/坐姿绒亲也在范围内随行；到达后清坐姿并唤醒跟随；已确认。
- 其他玩家的绒亲、未契约动物、已死亡/已收回档案、重复体和未能解析的实体均不处理。

已确认的冲突口径（2026-09-28 乌狸确认）：**显式远召 pending 优先于自动随行。** 快照候选会记录 `remotePendingAtArm`；只要出发时已有 pending，本次旅行就不补做随行，即使 pending 随后失败或取消也一样。这样不覆盖 `RemoteSummonService` 的异步状态机、重复体检查和玩家换维度语义，也避免“先提示远召失败、随后宠物又出现”的矛盾反馈。代价是宠物可能留在出发维度，需再次召唤或下次换维度。

## 1. 需求口径

### 1.1 触发条件

只有同时满足以下条件才建立随行快照：

- 事件实体是 `ServerPlayer`。
- 玩家确实开始一次跨维度旅行，目标维度与当前维度不同。
- `EntityTravelToDimensionEvent` 在本次处理时未被取消。
- 功能配置开启。
- 出发维度至少有一只符合本文件第 1.2 节的已加载绒亲。

原版地狱门、末地门（主世界->末地）、跨维度传送命令以及其他通过 `Entity#changeDimension(...)` 完成切换的模组路径都应自然覆盖。两个例外：玩家死亡重生不走 `EntityTravelToDimensionEvent`；原版末地出口传送门的“终章返回”（末地->主世界）不覆盖，见 §1.3 与 §10 R-01 选项 1。

### 1.2 候选实体定义

候选实体必须同时满足：

- 是 `LivingEntity`，不是玩家。
- 位于出发 `ServerLevel`，且当前已加载。
- `FurkinData.isCompanion() == true`。
- `FurkinData.getOwnerUuid()` 等于该玩家 UUID。
- 实体存活、未移除。
- 档案 `FurkinArchiveEntry.isSummoned() == true` 且 `isAlive() == true`。
- 档案 `entityUuid` 等于实体 UUID，保证处理的是 canonical 实体而不是重复体。
- 到玩家出发位置的 3D 距离平方不大于半径平方。

不把 `Cat`、`Wolf` 或 `TamableAnimal` 写成新的物种白名单。当前契约入口仍只接受 `TamableAnimal`，但随行判断应以 Furkin 身份和档案为准；坐姿清理只在 `TamableAnimal` 上执行。

### 1.3 范围外

- 出发维度中超出 N 格、未加载或已卸载的宠物自动强加载后随行。
- 用随行替代远距召唤、收回、死亡、复活或解绑语义。
- 自动迁移物品、坐骑、乘客、拴绳结或其他非绒亲实体。
- 为宠物保留旧维度中的门户、锚点或返回路径。
- 同维度跟随寻路、原版 `FollowOwnerGoal` 阈值调整或 1.20.1 `NV-01`。
- 新增公开 API、数据版本迁移或客户端 UI。

## 2. 1.19.2 / Forge 43.2.0 API 取证

以下符号已通过本机 1.19.2 mapped official jar 的 `javap` 核对。

| API | 1.19.2 结论 | 对本功能的用途 |
|---|---|---|
| `EntityTravelToDimensionEvent` | 构造参数为 `(Entity, ResourceKey<Level>)`，公开 `getDimension()`，类带 `@Cancelable` | 旅行前取得出发玩家、出发坐标和目标维度 |
| `ForgeHooks.onTravelToDimension(...)` | 发布 `EntityTravelToDimensionEvent`；事件取消时返回 `false`，`changeDimension` 返回 `null` | 证明宠物不能在旅行事件中提前移动；可能被其他模组取消 |
| `PlayerEvent.PlayerChangedDimensionEvent` | 构造参数为 `(Player, fromDim, toDim)`，在正常切换完成后触发，但不提供旧位置 | 不能单独承担本功能；旧位置只能由旅行前快照保存 |
| `ServerPlayer#changeDimension(...)` | 1.19.2 的末地->主世界 `isVanilla()` 分支会在触发 `PlayerChangedDimensionEvent` 之前返回，且该分支不改变玩家维度、玩家要到终章重生时才真正回到主世界（见 §10 R-01） | 若只依赖“完成维度”事件，会漏掉原版末地返回路径；tick 兜底仍保留以覆盖其它真实换维度路径，但该终章路径已按 §10 R-01 选项 1 列为范围外 |
| `Entity#changeDimension(ServerLevel, ITeleporter)` | 公开；返回迁移后的实体，失败可返回 `null` | 复用现有跨维度传送实现 |
| `Entity#getLevel()` | 返回 `Level` | 判断实体当前实际所在维度 |
| `ServerPlayer#getLevel()` | 1.19.2 已直接返回 `ServerLevel` | 不需要 `instanceof ServerLevel` 或 1.20.1 的 `serverLevel()` |
| `ServerLevel#getEntitiesOfClass(Class<T>, AABB, Predicate)` | 公开 | 在出发玩家周围做一次有界空间查询 |
| `ServerLevel#getEntity(UUID)` | 公开；只查运行时实体索引，不加载区块 | 完成阶段按快照 UUID 复核实体是否仍加载 |
| `AABB#inflate(double)` | 公开 | 构造半径查询盒 |
| `Entity#distanceToSqr(Entity)` | 公开 | 在 AABB 结果上做精确 3D 半径过滤 |
| `Level#noCollision(Entity, AABB)` | 公开 | 传送前对候选落点做 best-effort 碰撞检查 |
| `ServerLevel#areEntitiesLoaded(long)` | 公开 | 不用于本功能；随行只接受查询时已加载实体 |
| `TickEvent.ServerTickEvent` | 现有 `CommonEvents.onServerTick(...)` 已在 `Phase.END` 挂载 | 作为完成后的统一执行点，不再新增第二套 tick 入口 |

### 2.1 为什么不用 `PlayerChangedDimensionEvent` 单独实现

有四个问题：

1. 事件发生时玩家已经在目标维度，无法可靠取得出发维度中的原始坐标。
2. 它不提供“身边 N 格”的稳定选择时机。
3. 1.19.2 的原版末地->主世界特殊分支不一定触发该事件，而且它不改变玩家维度：玩家真正回到主世界发生在终章结束后的重生。本功能因此不覆盖该路径（§10 R-01 选项 1）；其余真实换维度路径仍由 ServerTick END 兜底。
4. 该事件在玩家维度切换调用链末端触发，直接批量改其他实体维度会把玩家和宠物的传送重入交织在一起。

因此只把它当作可选的日志观察点，不作为实现依赖。

### 2.2 为什么不在 `EntityTravelToDimensionEvent` 中直接传送宠物

- 该事件可被后续监听器取消；提前移动宠物后，玩家可能最终留在原维度。
- 事件触发时目标落点、`PortalInfo` 和玩家正式 `setLevel(...)` 尚未完成。
- 宠物 `changeDimension(...)` 会再次触发事件总线；即使当前监听器只处理玩家，也会扩大重入和监听器顺序耦合。
- 如果玩家最终落到第三方改写的另一个维度，提前传送无法可靠对应。

## 3. 推荐架构

### 3.1 组件边界

新增内部服务建议放在：

- `com.wanancat.furkin.internal.contract.OwnerDimensionFollowService`

事件接线仍在：

- `com.wanancat.furkin.internal.event.CommonEvents`

共享传送核心在：

- `com.wanancat.furkin.internal.contract.FurkinCompanionManager`

只读 pending 查询入口在：

- `com.wanancat.furkin.internal.contract.RemoteSummonService`

不新增公开 API 类型，不新增网络包，不把服务端状态放到 client 包。

### 3.2 内存状态

服务按 `MinecraftServer` 维护，参照 `RemoteSummonService` 的 `WeakHashMap` 生命周期。每个玩家最多保留一个待执行快照：

```text
ArmedRequest（OwnerDimensionFollowService 内部类型）
  playerUuid
  fromDimension
  toDimension
  sourceAnchor(Vec3)
  armedTick
  companions[
    entityUuid,
    companionId,
    distanceSquared,
    remotePendingAtArm
  ]
```

约束：

- 只保存 UUID 和值，不跨 tick 保存 `Entity` 强引用。
- 快照按距离升序、再按 `entityUuid` 字符串排序，保证结果稳定。
- 候选数量以 `ACTIVE_LIMIT` 为上限；若异常数据超过上限，只取最近的合法 canonical。
- 同一玩家再次旅行时覆盖旧快照并记一条 `WARN`；不在第一版引入排队/回溯传送。
- 待执行快照只存在于内存，服务端重启不恢复。

### 3.3 阶段 A：旅行前快照

事件入口伪代码：

```text
on EntityTravelToDimensionEvent:
    if entity is not ServerPlayer: return
    if event.isCanceled(): return
    if disabled: return

    service.arm(player, event.getDimension())
```

`arm(...)` 在出发 `ServerLevel` 中执行：

1. 记录 `sourceAnchor = player.position()`、`fromDimension`、`toDimension`、当前 server tick。
2. 用 `getEntitiesOfClass(LivingEntity.class, searchBox, predicate)` 做一次空间查询；`searchBox` 以 `player.position()` 为中心、边长 `2 * radius` 构造，与 §3.12.3 第 4 步一致（不要用 `getBoundingBox().inflate(radius)`，那会让查询盒比半径大约 0.3 格）。
3. 在谓词和二次精确过滤中校验 owner、companion、存活、canonical 和 `distanceToSqr <= radius * radius`。
4. 对通过校验的候选只读查询 `RemoteSummonService`，把“同一玩家同一 companion 当前已有 pending”写入 `remotePendingAtArm`。
5. 只把 `entityUuid + companionId + remotePendingAtArm` 写入内存快照。
6. 没有合法候选时不创建快照，不写日志噪声。

注意：事件是否会在本监听器之后被取消无法由本监听器预知。因此这里只做无副作用快照；即使取消，阶段 B 的维度校验和超时也会自然淘汰该快照。

### 3.4 阶段 B：tick 完成检查

在 `CommonEvents.onServerTick(...)` 的 `Phase.END` 中调用新服务。建议顺序是：

1. 先执行 `OwnerDimensionFollowService.tickIfPresent(server)`。
2. 再执行现有 `RemoteSummonService.tickIfPresent(server)`。

该顺序保证随行在同一 tick 先读到 pending 并让路，随后由远召服务继续推进。随行不会取消 pending，也不会抢跑远召的异步回调。

每个快照按下表处理：

| 当前状态 | 条件 | 动作 |
|---|---|---|
| 等待中 | 玩家仍在 `fromDimension`，且 `age <= 20 tick` | 保留 |
| 已完成 | 玩家当前维度等于 `toDimension` | 执行一次批量随行，然后移除快照 |
| 已偏离 | 玩家在第三个维度 | 丢弃并记 `WARN` |
| 已取消 | 玩家仍在出发维度但超过 20 tick | 丢弃；不移动宠物 |
| 玩家离线/死亡/移除 | 无法取得合法 `ServerPlayer` | 丢弃；不移动宠物 |

20 tick 是内存待决状态的安全上限。对地狱门、跨维度 `/tp`/`/execute in` 与其它走 `changeDimension(...)` 普通分支的路径，玩家在本次逻辑调用内完成换维度，最多跨一个 server tick。原版末地“终章返回”是例外：该分支不会在同一 tick 把玩家送到目标维度，20 tick 上限会直接淘汰快照；该路径已按 §10 R-01 选项 1 列为范围外，不承诺随行。

### 3.5 阶段 B：逐只执行

对快照中的每只宠物执行以下顺序：

1. `fromLevel.getEntity(entityUuid)`，只查出发维度运行时索引；未命中直接跳过，不加载区块。
2. 复核实体仍是 `LivingEntity`、`FurkinData.isCompanion()`、owner 匹配。
3. 复核 `companionId` 与快照一致。
4. 从全局档案读取 entry，复核 `isSummoned()`、`isAlive()`、`entityUuid` 等于当前实体 UUID。
5. 当 `remotePendingAtArm == true` 或当前查询发现同一玩家、同一 companion 仍有活跃 pending 时，跳过该只并记 `yielded`；不取消、不等待、不写档案，由本 tick 后续的远召服务按 D-10 使用玩家当前 Level 处理。`remotePendingAtArm` 为真时，即使 pending 已在两次检查之间结束，本次旅行也不补做随行。
6. 没有活跃 pending 时，复核 `FurkinDuplicateRegistry.hasLoadedDuplicate(...)`；存在重复体则跳过该只并记诊断，不移动、不删除。
7. 计算目标落点并调用既有的 `teleportLoadedEntity(...)` 路径；若该方法在实现时只能处理单一前向落点，应抽取一个接受明确落点的内部重载，保持原公开/内部入口的语义不变。
8. 成功时继续下一只；失败时记录该只失败并继续，不整体回滚。
9. 批量结束后记录 `moved/yielded/failed/skipped/total`，不向聊天栏刷屏。

### 3.6 目标落点

当前显式召唤语义是玩家朝向正前方 1.5 格。随行需要一次处理最多 `activeLimit` 只，不能全部堆在同一点：

- 单只：保持现有朝向正前方 1.5 格，减少与远召行为的观感差异。
- 多只：在玩家周围按玩家 yaw 起始的环形分布，固定半径 1.75 格，角度按 `index / total` 均分；数量最多来自 `ACTIVE_LIMIT`。
- 每个候选点从玩家脚点向下最多 2 格搜索可站立支撑面；支撑面必须具有非空碰撞形状且自身不是危险方块。落点体积用目标维度 `Level#noCollision(target, target.getBoundingBox().move(...))` 做 best-effort 检查，并拒绝传送门、末地门、末地折跃门、岩浆、火和灵魂火。
- 环形点在支撑搜索、`noCollision` 或危险方块检查失败时，依次尝试以玩家为中心的镜像点和八个方向的替代环形点；全部失败则跳过该只并记录 `NO_SAFE_LANDING`，不回退到玩家自身坐标，不调用会随机改变距离的 `randomTeleport(...)`，也不为找落点加载区块。玩家悬空超过 2 格或处于深坑/熔岩上方时，宁可不传送。
- 传送成功后沿用现有逻辑清 `setOrderedToSit(false)` / `setInSittingPose(false)`、刷新档案位置并发送 `SyncFurkinDataPacket`。

### 3.7 失败与一致性

必须保持以下不变量：

1. 随行不创建新实体，只迁移 canonical 实体。
2. 未成功执行 `changeDimension(...)` 的宠物不更新档案位置。
3. 失败不修改 `summoned`、`alive`、`entity_uuid`、`entity_dimension` 或 `entity_pos`。
4. 一只失败不影响其他已成功传送的宠物，不伪造事务回滚。
5. 随行不申请 chunk ticket，不调用 `getChunkFuture(...)`，不永久强加载。
6. 同一 companion 在快照时已有 pending，或执行时发现活跃 pending 时，随行不移动、不取消、不改写 pending；pending 的 ticket、超时、重复体检测和终态反馈全部保持现有语义。
7. 只有确认当前不存在同一玩家的 pending 后，随行才执行传送；因此同一 tick 内不会出现“远召 pending 与随行同时处理同一 canonical”的竞争。若随行完成后才收到新的显式已加载远召请求，那是玩家后发的同维度重定位，不属于本冲突。
8. 重复体和身份冲突只跳过并记诊断，不在热路径删除实体。

### 3.8 与现有远召代码的覆盖关系

“跨维度随行”不取代整个远召功能，也不覆盖远召的 pending 状态机。它只复用“已经加载的 canonical 实体如何安全迁移到主人当前维度”这段共享传送核心；未加载实体的临时区块加载、超时、ticket、重复体检查和终态反馈继续由远召独占。

| 现有代码 | 本次关系 | 具体影响 |
|---|---|---|
| `FurkinCompanionManager.teleportLoadedEntity(...)` | 复用并可能重构 | 这是三种入口共用的已加载实体传送核心。本次预计增加可显式传入落点的内部重载，原调用语义保持：`changeDimension`、档案位置刷新、清坐姿、`SyncFurkinDataPacket` |
| `FurkinCompanionManager.teleportToOwner(...)` | 不覆盖 | 继续服务绒亲录/命令的显式单只传送；只会在必要时改为调用共享重载 |
| `FurkinCompanionManager.summonOrTeleport(...)` | 不覆盖 | 继续负责“未召唤重建 / 已召唤传送”的分流；随行不重建、不复活 |
| `RemoteSummonService.request(...)` 的已加载分支 | 不覆盖 | 显式请求仍在自己的调用时点处理；若请求晚于随行，看到的是已经迁移后的 canonical 实体，按现有已加载分支处理 |
| `RemoteSummonService` 的 `WAIT_CHUNK` / `WAIT_ENTITY_LOAD` 路径 | 不覆盖 | 临时 ticket、异步区块加载、超时和实体 section 等待全部保留 |
| `RemoteSummonService` 的 pending 状态机与 `cancelIfPresent(...)` | 不覆盖 | 随行不取消、不完成、不改写任何远召 pending；只新增只读查询判断该 companion 是否应让路 |
| `CommonEvents.onServerTick(...)` 中的远召 tick | 仅调整执行顺序 | 随行服务必须先于 `RemoteSummonService.tickIfPresent(...)` 运行，先读取 pending 并让路，再由远召服务正常推进同一 tick 的请求 |
| `FurkinEntityLocator.locate(...)` | 不覆盖，也不作为主快照入口 | 远召继续用它定位记录维度；随行阶段 A 用出发 `ServerLevel` 的局部实体查询，阶段 B 优先用出发维度的 `getEntity(UUID)` 复核，避免全维度定位扫描 |
| `FurkinDuplicateRegistry.hasLoadedDuplicate(...)` | 复用不改语义 | 随行在真正移动前执行同一守卫；有活跃 pending 时先让路，让远召自己的前置/异步检查继续负责异常重复体 |
| `FurkinArchiveEntry.setEntityLocation(...)`、档案写回、数据同步、清坐姿 | 直接复用 | 不修改 schema，不新增网络包；行为与显式传送保持一致 |
| `RequestSummonPacket`、绒亲录按钮、`RemoteSummonResult`、命令反馈 | 不覆盖 | 不新增终态、不修改结果枚举和反馈分支；远召的 pending 加载态、成功/失败消息和冷却规则保持原样 |
| `remoteSummon*` 配置 | 不覆盖 | 新功能使用独立的 `ownerDimensionFollow*` 配置；关闭远端区块加载不等于关闭跨维度随行 |

因此，需要修改的现有代码只有三类：共享传送核心增加显式落点重载、服务器 tick 增加随行执行点并调整顺序、`RemoteSummonService` 增加只读 pending 查询。`RemoteSummonService` 的未加载路径、终态结果、反馈、冷却和 ticket 流程均不应被删除、合并或改写。

### 3.9 与远距传送的业务优先级

结论：两者会出现业务路径重叠，但不会产生 Java 级并发、存档 schema 或网络协议冲突。`OwnerDimensionFollowService` 与 `RemoteSummonService` 都只由服务端线程驱动，且共享同一个已加载实体传送核心；活跃 pending 与随行不会在同一 tick 竞争同一 canonical。

推荐优先级：**显式远召 pending 优先，自动随行让路。**

若同一 companion 在快照时已有同一玩家的 pending，或执行时仍存在活跃 pending，随行跳过该只，不移动实体、不修改档案，也不触碰 pending；由远召在随后的 `RemoteSummonService.tickIfPresent(...)` 或已排队回调中按 D-10 继续，终态使用玩家当前 Level 作为落点。只有 `remotePendingAtArm == false` 且执行时无活跃 pending，随行才进入重复体守卫和传送。

业务顺序固定为：

1. `EntityTravelToDimensionEvent` 阶段只建立随行快照；对候选执行 pending 只读查询并写入 `remotePendingAtArm`，不取消或改写 pending。
2. 玩家到达目标维度后的 `ServerTickEvent.Phase.END` 中，先运行随行服务，再运行远召服务。
3. 随行逐只完成来源实体、owner、档案和 canonical 校验后，同时检查 `remotePendingAtArm` 并只读查询同玩家同 companion 是否仍有活跃 pending。
4. `remotePendingAtArm == true` 或当前有 pending：该只记为 `yielded` 并跳过；不改 pending、不移动实体、不跑随行落点逻辑。前一个标志使 pending 在两次检查之间结束也不会触发补做。
5. 无 pending：执行重复体守卫，通过后才走共享传送核心；成功后继续下一只。
6. 远召服务随后正常处理仍存在的 pending；若 future 回调已经排队，`isCurrent(request)` 继续丢弃失去当前身份的旧回调。
7. 不需要为两条路径增加锁。服务端线程、canonical UUID、待决查询和远召原有 `isCurrent(...)` 足以保证串行语义。

| 交织场景 | 确定性结果 | 原因 |
|---|---|---|
| 随行 tick 时 pending 仍活跃 | 随行让路，远召继续 | 只读查询先于传送；远召状态机完全不动 |
| 远召在随行 tick 前已成功 | 随行跳过 | 档案维度和实体运行时位置已在目标维度；出发维度 `getEntity(UUID)` 不再命中 |
| 快照时已有 pending，随后失败并移除 | 随行仍跳过，不补做 | `remotePendingAtArm` 保留“这次旅行由显式远召接管”的事实，避免失败提示与宠物随后出现相互矛盾 |
| future 回调已排入服务端队列但尚未执行 | 随行看到 pending 后让路，回调稍后按 `isCurrent(...)` 处理 | pending 与回调都在服务端线程按序访问，不存在中间并发窗口 |
| 随行在无 pending 时完成 | 不产生远召终态，不碰远召冷却 | 没有显式请求需要收口；实体只由共享核心移动一次 |
| 随行完成后玩家再发起远召 | 按普通已加载请求处理 | 实体已在玩家维度，现有 `request(...)` 走同维度传送分支；不会创建第二只实体 |
| 同一 companion 存在异常重复体 | 有 pending 时由远召执行 D-12；无 pending 时随行自行跳过 | 两条路径都不在热路径删除重复体，也不绕过已有守卫 |
| 旅行事件被取消或快照过期 | 随行不执行，pending 保持原状 | 快照只在确认到达目标维度后释放为传送批次 |

不采用“随行成功后取消 pending”的原因：

1. pending 可能正处于 `WAIT_CHUNK` / `WAIT_ENTITY_LOAD`。随行若提前取消，会绕过远召在实体 section 载入后才执行的 D-12 重复体检查，可能把仍未入世的重复体留在存档里。
2. 取消待决请求会引入非失败终态，必须重新定义 `RemoteSummonResult`、绒亲录在途态、命令反馈、成功/失败语义和冷却规则，扩大了对已收口远召包的覆盖面。
3. D-10 已冻结“pending 期间玩家换维度不取消，终态使用玩家当前 Level”。远召成功本身就能把宠物带到玩家新维度，与随行的用户目标一致。
4. 显式操作优先于自动机制。若快照时已有 pending，即使它随后失败或取消，随行也不反向接管该快照；这是为了避免“失败提示之后宠物又出现”的矛盾反馈，损失由既有失败反馈和玩家重试承担。

仍需持续避免：

- 在 `EntityTravelToDimensionEvent` 阶段取消 pending。
- 随行与远召各自复制一套 `changeDimension`、档案写回和同步逻辑。
- 让随行读取、删除或重置远召 ticket。
- 为了减少 pending，直接跳过远召的实体 section 载入门槛或 `FurkinDuplicateRegistry` 检查。

### 3.10 玩家侧感知

随行本身不设计新的聊天提示或状态界面。玩家主要看到的是“跨维度完成后，身边符合条件的绒亲出现在新位置”这一实体结果；远召 pending 的既有加载态、成功/失败消息和绒亲录刷新继续沿用。

| 玩家场景 | 玩家侧感知 | 设计口径 |
|---|---|---|
| 无远召 pending，宠物在半径内且已加载 | 维度切换完成后，宠物在下一服务端 tick 左右出现在身边；坐下宠物起身跟随 | 静默成功，不发送“已随行”刷屏消息 |
| 多只宠物同时随行 | 多只宠物分布在玩家周围，不会全部堆在同一点 | 落点环形展开；失败或跳过只记服务端日志 |
| 宠物超出 16 格、未加载，或功能关闭 | 宠物留在原处，没有额外提示 | 与需求边界一致；玩家可通过配置和文档理解 |
| 旅行事件被取消、玩家进入第三维度或快照超时 | 玩家仍在预期维度；宠物不移动，也没有随行反馈 | 以实际玩家维度为准，避免跟随到错误世界 |
| 出发时已有远召 pending，随后远召成功 | 玩家先看到远召加载态；过门后宠物可能在远召完成后才到达，随后看到原有“远召完成”反馈 | 远召按 D-10 使用玩家当前 Level；随行不重复移动、不重复提示 |
| 出发时已有远召 pending，随后远召失败/超时/取消 | 玩家看到原有失败/取消反馈，宠物留在出发维度 | `remotePendingAtArm` 阻止随行补做，避免“提示失败后宠物又出现”的矛盾 |
| 出发时没有 pending，执行时也没有 pending | 正常静默随行 | 不触碰远召冷却、结果枚举或绒亲录状态 |
| 多只候选中部分身份/档案校验失败 | 成功者随行，失败者留下；聊天栏不刷错误 | 异常只记诊断日志，避免自动机制制造提示噪声；重复体按既有安全策略跳过 |

因此，玩家侧最重要的预期是：**没有显式远召时，过门后身边宠物正常随行；有显式远召时，由远召的既有 UI 和消息负责完整反馈，随行不会混合出第二套提示。** pending 已接管但最终失败时，宠物不会补跟随，这是已确认口径下唯一需要玩家通过重试或下次换维度处理的体验代价。

### 3.11 实体唯一性保证链

绒亲的唯一性不是依赖 Minecraft 原版实体 UUID，而是按“身份主键、档案 canonical、已加载重复体哨兵、执行前守卫”四层保证。跨维度随行只复用这条链，不新增另一套身份规则。

| 层级 | 载体 | 保证内容 | 现有代码依据 |
|---|---|---|---|
| 逻辑身份 | `FurkinData.companionId` | 契约时为每只绒亲生成一个 `UUID.randomUUID()`；它代表宠物身份，收回、重建、换维度都不变 | `FurkinContractHandler.executeContract(...)` 生成并写入 `companionId`；`FurkinData.isCompanion()` 判断已契约状态 |
| 档案主键 | `FurkinArchiveData.entries: Map<UUID, FurkinArchiveEntry>` | 一个 `companionId` 对应一条全局档案；档案保存 `owner_uuid`、`summoned`、`alive` 和最近 canonical 定位 | `FurkinArchiveData.putEntry/getEntry(...)` 按 `companionId` 读写；`FurkinArchiveEntry` 用 `companion_id` 序列化 |
| canonical 实体 | `FurkinArchiveEntry.entityUuid`、`entityDimension`、`entityPos` | 记录当前代表该身份的实体 UUID、维度和位置；它是“当前 canonical”，重建后可换新实体 UUID；定位时必须用该 UUID 查运行时实体并复核 capability 中的 `companionId` | `FurkinArchiveEntry.setEntityLocation(...)`；`FurkinEntityLocator.locate(...)` 的 UUID + 维度 + 身份核验 |
| 已加载重复体哨兵 | `FurkinDuplicateRegistry` 的 `Map<companionId, Set<entityUuid>>` | 服务端实体入世时登记，离世/死亡/清理时移除；查询时排除当前 canonical UUID。发现同 `companionId` 的其它已加载实体即返回冲突 | `CommonEvents.onEntityJoinLevel(...)` 登记；`onEntityLeaveLevel(...)` 移除；`hasLoadedDuplicate(...)` 同时校验运行时实体和 capability |

执行路径上的守卫顺序是：

1. 契约只给未契约实体分配新的 `companionId`，并立即建立一条 `summoned=true` 的档案。
2. 档案若已有 canonical UUID，定位只接受该 UUID 的实体；实体 capability 的 `companionId` 不一致时不接受。
3. `summonOrTeleport(...)` 在重建前先查 `findLoadedByRecordedUuid(...)` 和 `hasLoadedDuplicate(...)`；`rebuildCompanion(...)` 创建前再做一次同类守卫。
4. `RemoteSummonService` 在请求起点、异步区块加载前和实体 section 就绪后分别保留重复体检查；出现重复体返回 `DUPLICATE_CONFLICT`，不自动删除。
5. 跨维度随行的快照候选必须是“档案 `entityUuid` == 当前实体 UUID”的 canonical；逐只执行时再查 `getEntity(entityUuid)`、owner、`companionId`、`summoned/alive` 和 `hasLoadedDuplicate(...)`。
6. 随行只对找到的同一个 canonical 实体执行 `changeDimension(...)` 或同维度 `teleportTo`，不创建实体、不改 `companionId`、不重写 `entityUuid`；成功后才由既有 `setEntityLocation(...)` 刷新维度和位置。
7. 同一 companion 有活跃远召 pending 时随行让路，因此两条路径不会并行创建/迁移同一逻辑身份。

边界必须明确：

- 这套机制不是 Minecraft 引擎级的“一个 `companionId` 全局最多一个实体”数据库约束；它是 Furkin 服务端的档案 + 运行时索引 + 操作前复核约定。
- `companionId` 由 `UUID.randomUUID()` 生成，是持久逻辑主键；`entityUuid` 只是当前 canonical 实体的世界 UUID。重建/复活会创建新的实体 UUID，随后由 `setEntityLocation(...)` 更新档案；跨维度传送则保留原实体 UUID。
- 存档中已经存在的重复体、同 `companionId` 的孤儿实体、无法解析 canonical 的异常状态，默认只安全失败并记录诊断，不自动删除；清理仍走既有 P1 修复流程。
- 逻辑身份依赖随机 UUID 和档案 Map 主键，没有额外的全局碰撞注册表；理论碰撞概率极低，但相同 `companionId` 会被视为同一条档案，不能作为两个独立身份处理。
- `FurkinDuplicateRegistry` 只覆盖“已加载”实体；未加载区块中的异常重复体要等实体入世登记后才会被发现。因此本功能坚持“只处理已加载实体”，不为了唯一性扫描或强加载冷区。
- 本功能不新增实体 UUID 分配、全局 UUID 冲突修复或跨维度实体复制；它只把同一个已加载 canonical 实体迁移到玩家当前维度。

### 3.12 编码级执行契约

本节是 WP1-WP3 的落码入口。未在本节列出的类不应因为本功能而改动；如果实现时确需新增改动，先回到本文档补充理由和验证项。

#### 3.12.1 文件变更清单

| 文件 | 操作 | 需要的符号 / 位置 | 所属 WP |
|---|---|---|---|
| `src/main/java/com/wanancat/furkin/internal/config/FurkinServerConfig.java` | 修改 | 新增 `OWNER_DIMENSION_FOLLOW_ENABLED`、`OWNER_DIMENSION_FOLLOW_RADIUS`，放在 `SPEC = BUILDER.build()` 前 | WP1 |
| `src/main/java/com/wanancat/furkin/internal/contract/OwnerDimensionFollowService.java` | 新增 | 服务实例、快照记录、`arm(...)`、`tickIfPresent(...)`、`stop(...)`、批处理和落点方法 | WP1-WP3 |
| `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java` | 修改 | 保留现有 `teleportLoadedEntity(...)`，新增同包可见的显式落点重载 | WP1 |
| `src/main/java/com/wanancat/furkin/internal/contract/RemoteSummonService.java` | 修改 | 新增 `isPendingFor(...)` 只读查询；不修改已有请求、取消、ticket、终态和冷却路径 | WP1 |
| `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java` | 修改 | 新增旅行事件处理器；在 `onServerTick(...)` 中先调用随行 tick，再调用远召 tick；停机时清理随行服务 | WP1-WP3 |
| `README.md`、`README.zh-CN.md`、`CHANGELOG.md`、`CHANGELOG.en.md` | 修改 | 玩家说明、配置项、边界和版本记录 | WP4 |

明确不修改：`api/**`、`FurkinNetwork.PROTOCOL_VERSION`、`FurkinArchiveEntry`、`FurkinArchiveData`、`FurkinEntityLocator`、`FurkinDuplicateRegistry`、`RemoteSummonResult`、`RemoteSummonFeedback`、`RequestSummonPacket`、`FurkinCommand` 的远召反馈分支。

#### 3.12.2 `OwnerDimensionFollowService` 状态与 API

建议的包内可见类型和常量如下；实现时优先使用 `record`，不要引入新的公开 API 或第三方依赖。

```java
public final class OwnerDimensionFollowService {
    private static final int MAX_WAIT_TICKS = 20;
    private static final double SINGLE_FORWARD_DISTANCE = 1.5D;
    private static final double MULTI_RING_DISTANCE = 1.75D;

    private static final Map<MinecraftServer, OwnerDimensionFollowService> SERVICES =
            new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, ArmedRequest> armedByPlayer = new HashMap<>();

    private record Candidate(UUID entityUuid, UUID companionId,
                             double distanceSquared, boolean remotePendingAtArm) {}

    private record ArmedRequest(UUID playerUuid,
                                ResourceKey<Level> fromDimension,
                                ResourceKey<Level> toDimension,
                                Vec3 sourceAnchor,
                                long armedTick,
                                List<Candidate> candidates) {}

    public static void arm(ServerPlayer player, ResourceKey<Level> toDimension);
    public static void tickIfPresent(MinecraftServer server);
    public static void stop(MinecraftServer server);

    private static OwnerDimensionFollowService forServer(MinecraftServer server);
    private void tick(MinecraftServer server);
    private void process(ArmedRequest request, ServerPlayer player);
}
```

约束：

- `SERVICES` 与 `armedByPlayer` 只允许服务端线程访问，参照 `RemoteSummonService` 的生命周期。
- `Candidate` 不持有 `Entity` 引用；只保存 UUID、key 和值。
- `armedByPlayer` 一个玩家最多一个 `ArmedRequest`；再次 `arm(...)` 覆盖旧快照并记录 `WARN`。
- `stop(...)` 必须清空 `armedByPlayer` 并从 `SERVICES` 移除实例。
- `tick(...)` 在遍历前复制 `armedByPlayer.values()`，避免处理过程中移除当前项造成 `ConcurrentModificationException`。

#### 3.12.3 `arm(...)` 的精确步骤

`CommonEvents.onEntityTravelToDimension(...)` 只调用以下入口，所有空间查询和快照逻辑放在 service 内：

```java
import com.wanancat.furkin.internal.contract.OwnerDimensionFollowService;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;

@SubscribeEvent
public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
    if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) {
        return;
    }
    OwnerDimensionFollowService.arm(player, event.getDimension());
}
```

`arm(...)` 内部按以下顺序执行，任何一步失败都只放弃本次快照，不影响原版旅行：

1. 校验 `FurkinServerConfig.OWNER_DIMENSION_FOLLOW_ENABLED.get()`；关闭时直接返回。
2. `ServerLevel fromLevel = player.getLevel()`；若 `toDimension.equals(fromLevel.dimension())` 直接返回。
3. 若 `armedByPlayer` 已有该玩家快照，记录 `WARN` 后覆盖。
4. 读取 `radius = FurkinServerConfig.OWNER_DIMENSION_FOLLOW_RADIUS.get()`，以 `Vec3 anchor = player.position()` 构造精确中心盒：

   ```java
   AABB searchBox = new AABB(
           anchor.subtract(radius, radius, radius),
           anchor.add(radius, radius, radius));
   ```

5. 调用 `fromLevel.getEntitiesOfClass(LivingEntity.class, searchBox, predicate)`；谓词按“廉价到昂贵”顺序过滤：
   - `living != player`；
   - 未移除、`living.isAlive()`；
   - `living.distanceToSqr(anchor) <= radius * radius`；
   - `FurkinData data = living.getCapability(FurkinCapability.FURKIN_DATA).orElse(null)` 非空；
   - `data.isCompanion()`、`data.getCompanionId() != null`；
   - `player.getUUID().equals(data.getOwnerUuid())`；
   - 从 `FurkinArchiveData.get(player.getServer())` 取得同 `companionId` 的 entry；
   - `entry != null && entry.isAlive() && entry.isSummoned()`；
   - `living.getUUID().equals(entry.getEntityUuid())`。
6. 对每个合法实体计算 `distanceSquared = living.distanceToSqr(anchor)`；用 `RemoteSummonService.isPendingFor(player.getServer(), player.getUUID(), companionId)` 生成 `remotePendingAtArm`。
7. 将候选按 `distanceSquared` 升序、再按 `entityUuid.toString()` 字典序排序；超过 `FurkinServerConfig.ACTIVE_LIMIT.get()` 时只保留前 N 个，并记 `DEBUG` 数量。
8. 候选为空时返回，不创建空快照。候选非空时放入 `armedByPlayer`，记录一条 `DEBUG`：

   ```text
   owner dimension follow armed player=<uuid> from=<dimension> to=<dimension> candidates=<count> pending=<count>
   ```

注意：`arm(...)` 是“快照 + 只读 pending 标记”，不移动实体、不查 chunk、不改档案、不改远召状态。即使同一事件后续被其它监听器取消，快照也会在 tick 阶段因维度不匹配或超时丢弃。

#### 3.12.4 `tick(...)` 与批量处理

`CommonEvents.onServerTick(...)` 的顺序固定为：

```java
SkillPassiveDispatcher.onServerTick(event.getServer());
SkillRuntimeCalibrator.onServerTick(event.getServer());
OwnerDimensionFollowService.tickIfPresent(event.getServer());
RemoteSummonService.tickIfPresent(event.getServer());
```

`CommonEvents.onServerStopped(ServerStoppedEvent event)` 在现有远召 stop 前调用 `OwnerDimensionFollowService.stop(event.getServer())`；停止顺序不影响结果，但必须保证随行快照被清空。

`tick(...)` 对每个快照按以下顺序处理：

1. 取 `ServerPlayer player = server.getPlayerList().getPlayer(request.playerUuid())`。
2. 若 `player == null || !player.isAlive() || player.isRemoved()`：移除快照，记 `DEBUG`，不移动宠物。
3. `ResourceKey<Level> current = player.getLevel().dimension()`。
4. 若 `current.equals(request.toDimension())`：先移除快照，再调用 `process(...)`；到达目标维度后不再受 20 tick 上限阻止。
5. 若 `current` 既不等于 `fromDimension` 也不等于 `toDimension`：移除快照并记 `WARN`。
6. 若仍在 `fromDimension` 且 `server.getTickCount() - armedTick > MAX_WAIT_TICKS`：移除快照，记 `DEBUG`，不移动宠物。
7. 其余情况保留到下一 tick，直到到达、偏离或超时。

`process(...)` 必须使用逐只 `try/catch`，一只异常不能中断其余候选。每只顺序如下：

```text
for each candidate in request.candidates:
    fromLevel = server.getLevel(request.fromDimension)
    if fromLevel == null: skipped++; continue

    found = fromLevel.getEntity(candidate.entityUuid)
    if found == null: skipped++; continue
    if not LivingEntity: failed++; continue
    if not found.isAlive() or found.isRemoved(): failed++; continue

    data = found capability
    if data == null or !data.isCompanion(): failed++; continue
    if companionId mismatch or owner mismatch: failed++; continue

    entry = FurkinArchiveData.get(server).getEntry(candidate.companionId)
    if entry == null or !entry.isAlive() or !entry.isSummoned(): failed++; continue
    if entry.getEntityUuid != candidate.entityUuid: failed++; continue

    if candidate.remotePendingAtArm or RemoteSummonService.isPendingFor(...):
        yielded++; continue

    if FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry):
        failed++; log diagnostic; continue

    landing = chooseLanding(player, found, index, total)
    result = FurkinCompanionManager.teleportLoadedEntity(
        player, entry, found, landing.x, landing.y, landing.z,
        player.getYRot(), player.getXRot())

    if result == TELEPORTED: moved++
    else: failed++; log result and companionId
```

批处理结束后只输出一条服务端日志，不发送玩家聊天、actionbar 或 GUI 包：

```text
owner dimension follow processed player=<uuid> from=<dimension> to=<dimension>
moved=<n> yielded=<n> failed=<n> skipped=<n> total=<n>
```

#### 3.12.5 多只落点算法

落点必须确定、可复现、无随机远距传送：

1. `total == 1`：保持现有语义，使用玩家朝向正前方 `1.5` 格；`y` 使用 `player.getY()`。
2. `total > 1`：以 `angle = Math.toRadians(player.getYRot()) + 2π * index / total`、半径固定为 `1.75` 生成环形点：

   ```text
   x = player.getX() - sin(angle) * 1.75
   y = player.getY()
   z = player.getZ() + cos(angle) * 1.75
   ```

3. 对每个水平候选点，从 `player.getY()` 向下最多 `2.0` 格寻找非空碰撞支撑面；候选点体积用 `targetLevel.noCollision(target, target.getBoundingBox().move(delta))` 与危险体积检查做 best-effort 安全校验；拒绝 `NETHER_PORTAL`、`END_PORTAL`、`END_GATEWAY`、`LAVA`、`FIRE`、`SOUL_FIRE`。玩家悬空超过 2 格或落在深坑/熔岩上方时直接失败，不把宠物投入危险区。
4. 主环形点在支撑搜索或体积安全检查失败时依次尝试镜像点和 45° 间隔的替代环形点；全部失败则返回 `null`，调用方跳过该只并记录 `NO_SAFE_LANDING`，不回退到 `player.position()`，不调用 `randomTeleport(...)`，不为落点加载区块。
5. 传送成功后由共享核心执行 `setOrderedToSit(false)` / `setInSittingPose(false)`、刷新档案位置和 `SyncFurkinDataPacket`。

#### 3.12.6 `FurkinCompanionManager` 传送核心重载

保留现有公开入口，把计算前向落点的逻辑留在该入口，新增同包可见重载：

```java
static TeleportResult teleportLoadedEntity(
        ServerPlayer player,
        FurkinArchiveEntry entry,
        LivingEntity target,
        double targetX,
        double targetY,
        double targetZ,
        float yRot,
        float xRot)
```

实现顺序必须与现有方法一致：

1. `ServerLevel serverLevel = player.getLevel()`。
2. 若 `target.getLevel() != serverLevel`，调用 `target.changeDimension(serverLevel, new FixedTeleporter(targetX, targetY, targetZ, yRot, xRot))`；返回值不是 `LivingEntity` 时返回 `DIMENSION_CHANGE_FAILED`。
3. 若已在同一维度，调用 `target.teleportTo(targetX, targetY, targetZ)`。
4. 成功后执行 `entry.setEntityLocation(relocated)`、`FurkinArchiveData.get(serverLevel).putEntry(entry)`、清坐姿和同步。
5. 原有 `public static teleportLoadedEntity(player, entry, target)` 继续计算玩家朝向正前方 `1.5` 格，再委托给新重载；不得改变现有单只传送、远召和命令的行为。
6. 不修改实体 UUID、不创建实体、不重建档案；跨维度时由 `changeDimension(...)` 保留 canonical 实体 UUID。

#### 3.12.7 `RemoteSummonService` 只读 pending 查询

只新增以下静态入口，不修改 `request(...)`、`tick(...)`、`cancel(...)`、`finishPending(...)` 的现有逻辑：

```java
public static boolean isPendingFor(MinecraftServer server, UUID playerUuid, UUID companionId)
```

语义与实现约束：

1. `server == null || playerUuid == null || companionId == null` 返回 `false`。
2. `SERVICES.get(server)` 为空返回 `false`，不得为了查询创建 service。
3. 命中 `byCompanion.get(companionId)` 后，只返回 `request.playerUuid.equals(playerUuid) && request.state != TERMINAL`。
4. 只读，不遍历全 pending、不加载区块、不添加/释放 ticket、不记录冷却、不触发反馈。
5. 该方法是服务端线程 API；实现时应复用类内 `assertServerThread()` 或在静态入口做同等断言。

可直接实现为：

```java
public static boolean isPendingFor(MinecraftServer server, UUID playerUuid, UUID companionId) {
    RemoteSummonService service = SERVICES.get(server);
    if (service == null) {
        return false;
    }
    return service.hasPendingFor(playerUuid, companionId);
}

private boolean hasPendingFor(UUID playerUuid, UUID companionId) {
    assertServerThread();
    RemoteSummonRequest request = byCompanion.get(companionId);
    return request != null
            && request.playerUuid.equals(playerUuid)
            && request.state != RemoteSummonRequest.State.TERMINAL;
}
```

#### 3.12.8 日志、异常与诊断字段

允许的服务端日志字段固定为：

| 事件 | 级别 | 必须包含 |
|---|---|---|
| 快照建立 | `DEBUG` | `player`、`from`、`to`、`candidates`、`pending` |
| 快照覆盖 | `WARN` | `player`、旧目标、新目标 |
| 玩家不在预期维度 | `WARN` | `player`、`expected`、`actual` |
| pending 让路 | `DEBUG` | `player`、`companionId`、`remotePendingAtArm` 或 `active` |
| 重复体阻止 | `WARN` | `companionId`、canonical UUID、重复原因 |
| 单只传送失败 | `WARN` | `companionId`、`result`、源/目标维度 |
| 批量结束 | `INFO` | `moved/yielded/failed/skipped/total` |
| 服务停止清理 | `DEBUG` | 清理快照数量 |

禁止：逐 tick 刷屏、对玩家聊天栏报成功、为普通跳过发送失败消息、在日志中输出实体完整 NBT 或行囊内容。

#### 3.12.9 不应出现的实现方式

- 在 `EntityTravelToDimensionEvent` 内调用 `changeDimension(...)`、`teleportTo(...)` 或 `FurkinCompanionManager`。
- 使用 `server.getAllLevels()` 全维度扫描来寻找附近宠物；阶段 A 只用出发维度，阶段 B 只用出发维度 `getEntity(UUID)`。
- 在随行路径调用 `getChunkFuture(...)`、`addTicket(...)`、`setChunkForced(...)` 或 `LivingTickEvent`。
- 在 `process(...)` 中按 `owner` 全表扫描档案；按候选的 canonical `companionId` 定向读 entry。
- 用 `setPos(...)` 或 `moveTo(...)` 替代跨维度 `changeDimension(...)`。
- 随行成功后调用 `RemoteSummonService.cancelIfPresent(...)`、修改 `RemoteSummonResult` 或触发新的完成/取消反馈。
- 在 `arm(...)` 或 `process(...)` 中把 `Entity` 放进快照跨 tick 保存。

## 4. 负荷影响评估

> 本节保留基于当前架构和 1.19.2 API 的静态评估，并补充 2026-09-28 一次性服务端夹具的微基准。夹具不模拟真实多人网络、客户端渲染和 TPS，因此不能替代 §7.3-C/D 的真机验收；没有真机测量时，不得宣称客户端负荷或生产 TPS 已通过。

### 4.1 结论

- **服务器稳态**：接近零。功能没有常驻实体扫描、没有 `LivingTickEvent`、没有 chunk ticket；无待决请求时，每个 server tick 只多一次 `WeakHashMap` 服务存在性查询，现有远召服务查询不变。
- **服务器瞬时开销**：发生在玩家换维度的少数 tick。默认半径 16、单人、最多 3 只宠物时，为一次局部 AABB 实体查询、最多 6 次 pending `HashMap` 查询加最多 3 次实体维度迁移，预计远低于区块加载类远召路径。
- **客户端稳态**：零新增开销。没有客户端 tick、每帧渲染钩子、GUI 轮询或常驻网络包。
- **客户端瞬时开销**：随行宠物进入目标维度时产生原版实体跟踪/生成包，并复用现有 `SyncFurkinDataPacket`；默认最多 3 只宠物，量级与普通 3 只实体换维度相同。
- **主要风险点**：管理员把半径提高到 64 且查询范围落在密集实体农场；或大量玩家在同一 tick 换维度。默认 16 格不在这个高危区间，但配置上限不能被当作无成本开关。
- **成本边界**：候选传送数 `K <= ACTIVE_LIMIT`，默认 3；不会因为周围有 1000 个普通生物而传送 1000 个实体。额外成本主要是筛选这些生物时的实体查询。

### 4.2 服务端负荷

| 阶段 | 复杂度 | 默认/上限 | 评估 |
|---|---|---|---|
| `EntityTravelToDimensionEvent` 入口过滤 | `O(1)` | 每个跨维度实体一次 | 非玩家立即返回，成本可忽略 |
| 出发位置 AABB 实体查询 | 与半径内实体分区/实体数相关 | 半径 16：约 3x3x3 个实体 section；半径 64：约 9x9x9 | 只查已加载实体，不读区块、不生成区块、不触发磁盘 I/O |
| 精确距离与身份过滤 | `O(E)`，`E` 为查询盒内实体数 | 同半径 | 距离平方先于能力/档案查询，普通生物只付廉价过滤成本 |
| 待决 tick 检查 | `O(P)`，`P` 为同 tick 待完成玩家数 | 稳态 0；通常 1 | 只比较维度、tick age 和 UUID，不做实体扫描 |
| canonical 与档案复核 | `O(K)` | 默认 `K <= 3` | 每只一次实体索引查询和一次档案读取 |
| 活跃远召 pending 只读查询 | `O(K)` | 默认 `K <= 3`，最多 2K 次 | 快照时记录 `remotePendingAtArm`，执行时再查一次；均为 `HashMap` 查询，不遍历 pending，不改状态，不释放 ticket |
| `changeDimension(...)` | `O(K + 原版实体迁移/tracking)` | 默认 `K <= 3` | 目标区块已有玩家加载；源实体已在运行时索引；不新增 chunk ticket |
| 档案位置更新 | `O(K)` | 默认 `K <= 3` | 每次成功传送一次 `setEntityLocation + putEntry`，随后走正常 SavedData 保存周期 |
| 自定义同步包 | `O(K * T)`，`T` 为目标实体 tracking 玩家数 | 默认 3 只 | 包只含 `syncNBT()` 核心字段，不含行囊物品；fanout 与原版实体跟踪同一量级 |

半径与实体 section 的粗略关系：

- `radius=16`：包围盒边长大致 33 格，通常涉及约 27 个实体 section。
- `radius=64`：包围盒边长大致 129 格，通常涉及约 729 个实体 section。
- 这只是“最多检查哪些已加载 section”的几何估算，不代表必然扫描 729 个 section；实体存储会按实际已加载区间处理。但它说明半径从 16 提到 64 时，最坏查询面会放大一个数量级以上。

实现必须遵守以下低负荷顺序：

1. 先判断实体是否为目标玩家、是否为 `LivingEntity`、是否在精确半径内。
2. 距离通过后再读取 `FurkinData` 和 owner。
3. owner/companion 通过后再读取全局档案和 canonical UUID。
4. 不以 `FurkinArchiveData.allEntries()` 的全服遍历作为普通随行热路径。
5. 不按 UUID 扫描所有维度；出发实体索引只在出发 `ServerLevel` 查询。
6. 不为了补全候选而加载任何 chunk，也不要进入 `RemoteSummonService` 的异步加载队列。

内存方面，每个待决请求只在内存中保存一个玩家 UUID、两个维度键、一个 `Vec3` 和最多 `ACTIVE_LIMIT` 个小型候选记录（UUID + key + 距离 + pending 标记），不保存 `Entity` 强引用。默认 3 只时只有数百字节量级；服务停止或玩家登出后清理，不进入存档。

### 4.3 客户端负荷

- **逻辑端**：客户端不监听维度随行事件，不参与候选选择，不做服务端权威判断。
- **每 tick / 每帧**：零新增调用。没有新增渲染、粒子、HUD、GUI 或寻路逻辑。
- **网络**：每次随行只产生原版实体维度迁移相关的跟踪/生成包，以及现有 `SyncFurkinDataPacket`。该同步包只带 companion/owner、等级/经验、状态、战斗模式、技能等级和冷却等核心数据，不包含行囊物品。
- **渲染**：客户端看到的是最多 `ACTIVE_LIMIT` 只正常实体进入目标区块；默认 3 只，和正常三只猫/狗换维度没有结构性差异。
- **最坏客户端场景**：大量玩家同时把多只宠物带到同一区域，目标附近客户端会同时接收多只实体的普通生成/tracking 包。这个成本由原版实体跟踪和可见性裁剪承担，不是 Furkin 新增的每帧逻辑。

结论：客户端没有稳态负荷增加；瞬时网络和渲染开销与“若干普通实体同时换维度”同阶，默认口径下可忽略，需要在多人/高 `activeLimit` 压测中记录边界。

### 4.4 最坏情况与保护

| 最坏情况 | 结果 | 保护 |
|---|---|---|
| 半径 64 + 密集实体农场 | AABB 查询可能覆盖数百个实体 section，产生单次 tick 尖峰 | 默认使用 16；64 只作为显式管理配置；压测时必须单独记录 |
| 多玩家同 tick 换维度 | 总传送量约为各玩家随行宠物之和 | 每玩家只允许一个待决请求；每请求受 `ACTIVE_LIMIT` 限制；实现后做并发压力夹具 |
| `activeLimit` 被调高 | 单次 `changeDimension`、档案写入和同步包数量线性增加 | 不修改该现有配置的默认值 3；性能验收覆盖低、默认、高配置三档 |
| 目标区域大量 tracking 玩家 | 同步包 fanout 增大 | 复用原版 tracking；包体不含行囊；必要时后续单独评估带宽，但首版不新增额外广播范围 |
| 服务器持续卡顿 | 1 tick 内无法完成所有待决请求 | 当前设计最多跨 1 tick；若压力测试证明多人并发会形成尖峰，再评估内部“每 tick 最多迁移 N 只”的预算器，不把复杂队列预置进首版 |

建议性能验收门槛：

- 默认半径 16、单人、普通世界：快照查询与 3 只宠物迁移合计不得造成连续 `Can't keep up!` 或可复现的 TPS 下降。
- 半径 64、密集实体压力场景：记录 P50/P95/最大值；若单次 Furkin 额外处理经常超过 20 ms，发布前必须优化或降低推荐上限。
- 3 只宠物批量随行：记录每 tick 最大耗时、传送成功数和发送包数。
- 10 个及更多玩家同 tick 换维度：记录全局尖峰、总迁移数和是否存在部分请求跨 tick。
- 客户端：使用相同世界/实体数量对比开关前后帧率，必须排除原版实体进入视野本身的成本后不得出现 Furkin 专属持续下降。

上述数值是实施后的评审门槛，不是客户端帧率或生产网络结论。2026-09-28 已完成以下服务端证据：

1. **30 分钟无夹具空闲长跑**：临时启用 RCON，`debug start` 后每 10 分钟采样；全程 `Mean TPS: 20.000`，overall mean tick time 在 `0.859-0.910 ms` 之间，`debug stop` 记录 `36463 ticks (20.00 ticks per second)`。日志：`D:\frukin_dev\_research\odf_perf_30m_20260928.txt`。
2. **临时服务端压力夹具**：同一 JVM 内使用 FakePlayer 与受控实体，覆盖半径 16/64、`activeLimit=3/20`、80/150 只附近野生生物、12 个 FakePlayer 同 tick 换维度。夹具运行前执行 3 次热身；下表 `cold` 为首次场景，`hot` 为同场景再次运行。

| 场景 | 样本/条件 | 结果 |
|---|---|---|
| 30 分钟空闲基线 | 无夹具、无玩家、`debug start` 长跑 | 全程 20 TPS；overall mean tick time `0.859-0.910 ms` |
| `radius=16`、`activeLimit=20`、80 野生、cold | 8 次测量 + 3 次热身、20 只全部随行 | `arm P50/P95=0.320/0.344 ms`；`tick P50/P95/max=9.135/19.979/19.979 ms`；`moved=160` |
| `radius=16`、`activeLimit=20`、80 野生、hot | 20 次测量 + 3 次热身、20 只全部随行 | `arm P50/P95=0.232/0.262 ms`；`tick P50/P95/max=4.016/4.854/6.538 ms`；`moved=400` |
| `radius=64`、`activeLimit=20`、150 野生 | 5 次测量 + 3 次热身、20 只全部随行 | `arm P50/P95=0.316/0.365 ms`；`tick P50/P95/max=5.615/5.883/5.883 ms`；`moved=100` |
| `radius=16`、`activeLimit=3`、80 野生 | 20 次测量 + 3 次热身、3 只全部随行 | `arm P50/P95=0.178/0.340 ms`；`tick P50/P95/max=0.760/1.410/1.592 ms`；`moved=60` |
| 12 个 FakePlayer 同 tick、每人 3 只 | 4 次测量 + 2 次热身、共 36 只全部随行 | `tick P50/P95/max=6.835/7.027/7.027 ms`；`moved=144` |

结果解读：

- 默认 3 只的稳定态 P95 为 `1.410 ms`，P95 对应单个 tick 约 `7.05%` 的 20 ms 预算；多名玩家同 tick 的 12x3 场景 P95 为 `7.027 ms`。
- `activeLimit=20` 的 cold 场景首次出现过 `19.979 ms` 尖峰，但同场景 hot P95 降到 `4.854 ms`，说明该尖峰主要受 JVM/JIT 与首次路径初始化影响；不能把它写成生产常态。即便如此，当前仍不提高默认 `ACTIVE_LIMIT=3`。
- `radius=64` 在 150 只野生生物的受控场景 P95 为 `5.883 ms`，未重现静态评估中的数量级失控，但该夹具不能替代真实密集农场和磁盘/网络条件。
- 压力夹具日志：`D:\frukin_dev\_research\odf_perf_stress_20260928.log`。夹具源码和临时世界已在测量后删除，最终构建会再次确认 JAR 不含 fixture 类。
- 已完成补测：真实客户端帧率（vsync 60 上限；空场/三只宠物、Overworld/Nether）与真实双客户端 fanout（3 只 × 2 tracker，显式同步 + `StartTracking` 双路径）均无异常；详见 §11.4。不同硬件/视距下的渲染成本仍未被本次单一开发客户端环境覆盖，属于环境外推边界。

## 5. 配置与文档边界

建议新增两个 `FurkinServerConfig` 项：

| 键 | 默认值 | 范围 | 语义 |
|---|---:|---:|---|
| `ownerDimensionFollowEnabled` | `true` | boolean | 是否允许主人跨维度时让身边已加载绒亲随行 |
| `ownerDimensionFollowRadius` | `16` | 1-64 | 出发位置到绒亲中心的 3D 欧氏半径 |

配置是服务端权威。客户端不接受任何“请求随行”包，因此不需要网络协议或客户端分包改动。

### 5.1 `FurkinServerConfig` 落码模板

在 `src/main/java/com/wanancat/furkin/internal/config/FurkinServerConfig.java` 的远召配置段之后、`public static final ForgeConfigSpec SPEC = BUILDER.build();` 之前加入：

```java
// ===== Owner dimension follow =====

public static final ForgeConfigSpec.BooleanValue OWNER_DIMENSION_FOLLOW_ENABLED = BUILDER
        .comment("Enable nearby loaded companions to follow their owner through a dimension change.",
                "Design intent: true.")
        .define("ownerDimensionFollowEnabled", true);

public static final ForgeConfigSpec.IntValue OWNER_DIMENSION_FOLLOW_RADIUS = BUILDER
        .comment("3D Euclidean radius in blocks around the owner's departure position.",
                "Only loaded companions inside this radius are considered.",
                "Design intent: 16.")
        .defineInRange("ownerDimensionFollowRadius", 16, 1, 64);
```

要求：配置注释保持纯 ASCII；不在运行中缓存配置值，`arm(...)` 时读取开关和半径，`tick(...)` 时读取当前 `ACTIVE_LIMIT`。

发布时需要同步：

- `gradle.properties`：`1.19.2-0.0.4.0`。
- `README.md`、`README.zh-CN.md`：说明跨维度随行的边界和“不加载冷区宠物”。
- `CHANGELOG.md`、`CHANGELOG.en.md`：记录新机制和两个服务端配置键。
- 若保留 `AGENTS.md` 的长期规则，可追加“跨维度随只使用旅行前快照 + tick 完成校验，不在旅行事件内提前移动实体”。

不修改：

- `com.wanancat.furkin.api/**`
- `FurkinNetwork.PROTOCOL_VERSION`
- `FurkinArchiveEntry` / `FurkinArchiveData` 的 NBT schema
- `RemoteSummonResult`、`RemoteSummonFeedback`、`RequestSummonPacket` 和命令远召反馈分支
- `RemoteSummonService` 的 pending/ticket/超时/冷却终态流程；本次只新增只读 pending 查询
- `mods.toml` 或资源包格式

## 6. 工作包拆分

| WP | 目标 | 主要改动 | 完成条件 |
|---|---|---|---|
| WP0 | 冻结口径与实现契约 | 本工作文档、决策记录 | 半径、触发、范围外、失败语义及远召 pending 优先级均经乌狸确认 |
| WP1 | 配置与服务骨架 | `FurkinServerConfig`、`OwnerDimensionFollowService`、`CommonEvents` 接线 | 空服务可随 server start/stop 创建和清理，无状态泄漏 |
| WP2 | 旅行前快照 | `EntityTravelToDimensionEvent`、空间查询、canonical 过滤、`remotePendingAtArm` 只读标记 | 只快照本维度已加载的本人合法绒亲，并记录哪些由已知远召 pending 接管 |
| WP3 | tick 完成与批量传送 | 待决状态机、UUID 复核、pending 让路、目标落点、复用传送路径 | 单只、多只、取消、超时、pending 成功/失败、部分失败均可控 |
| WP4 | 交互收口 | 远召 pending 让路查询、日志、配置、README、CHANGELOG | 无新增协议/API/存档 schema，远召终态/冷却/UI 不受影响，文档与代码一致 |

建议执行顺序严格为 WP0 -> WP1 -> WP2 -> WP3 -> WP4。WP2 和 WP3 不能合并成“事件中直接传”，否则会破坏取消安全和末地分支兼容。

### 6.1 编码顺序与逐步验证

每个小步完成后都运行一次 `.\gradlew.bat compileJava --console=plain`，不要把多个结构性改动堆到最后一起编译：

1. **WP1a**：只加 `FurkinServerConfig` 两个字段；确认旧配置无迁移错误、`SPEC` 正常构建。
2. **WP1b**：加 `RemoteSummonService.isPendingFor(...)`；先用现有远召服务入口确认查询不会创建 service、不会改 pending。
3. **WP1c**：给 `FurkinCompanionManager` 抽取显式落点重载，原公开入口改为委托；确认现有单只远召/命令行为不变。
4. **WP1d**：新增 `OwnerDimensionFollowService` 的服务表、快照类型、`stop(...)` 空骨架，并接入 `CommonEvents.onServerStopped(...)`；验证启停无残留。
5. **WP2**：实现 `arm(...)` 和 `EntityTravelToDimensionEvent` 接线；只验证候选快照数量、排序、`remotePendingAtArm`，不实现传送。
6. **WP3a**：实现 `tick(...)` 的状态淘汰、逐只复核和 pending 让路；先验证 0 次传送时不会改远召状态。
7. **WP3b**：接入共享传送重载和落点算法；验证单只/多只/坐姿/部分失败。
8. **WP3c**：增加临时夹具覆盖 F-04 至 F-29，记录固定前缀日志并删除夹具源码。
9. **WP4**：更新 README、双语言 changelog、配置说明和版本号；运行 `build`、`runServer`、`runClient` 并保存日志证据。

## 7. 验证矩阵

### 7.1 功能夹具 / 实机

| ID | 场景 | 预期 |
|---|---|---|
| F-01 | 主世界->地狱，身边 2 只、半径外 1 只 | 2 只迁移到地狱；半径外保留原维度 |
| F-02 | 地狱->主世界 | 同上，方向反转不依赖硬编码维度 |
| F-03 | 原版末地->主世界返回路径（终章/重生） | 不随行：宠物留在末地；快照在玩家不可用或维度不符后清理，不加载末地区块（§10 R-01 选项 1） |
| F-04 | 半径边界 `distance == N` | 传送 |
| F-05 | 半径边界 `distance > N` | 不传送 |
| F-06 | 同维度正常传送 | 不触发跨维度随行 |
| F-07 | 旅行事件被另一监听器取消 | 宠物不移动；20 tick 后快照清理 |
| F-08 | 玩家最终进入第三个维度 | 原快照丢弃，不向错误维度传送 |
| F-09 | 旅行后 21 tick 仍未到达目标维度 | 快照过期；宠物不移动 |
| F-10 | 出发维度宠物区块已卸载 | 不加载、不重建、不传送 |
| F-11 | 其他玩家绒亲、野生物、未契约宠物在半径内 | 全部忽略 |
| F-12 | 本玩家已收回/已死亡档案在半径内出现异常实体 | 通过 canonical + 档案状态校验跳过 |
| F-13 | 重复实体同 companionId 在半径内 | 只处理 canonical；重复体跳过并记诊断 |
| F-14 | 坐下/坐姿宠物在半径内 | 随行，到达后坐姿清除并跟随 |
| F-15 | 快照后、执行前宠物死亡/解绑/被收回 | 跳过该只；不改档案；其他只继续 |
| F-16 | 快照时或执行时同一宠物已有同玩家活跃远召 pending | 随行让路，不取消/不改写 pending；该 tick 只由远召路径继续 |
| F-17 | 玩家登出或服务端停机时存在快照 | 内存快照清理，不持久化、不加载区块 |
| F-18 | `ownerDimensionFollowEnabled=false` | 不建立快照，不传送 |
| F-19 | 多只宠物目标落点碰撞/危险方块/脚下无支撑 | 优先环形空位，再尝试镜像/替代环形点；向下最多 2 格搜索可站立支撑面，并拒绝危险体积；无安全落点时该只跳过并记录 `NO_SAFE_LANDING`，不回退玩家位置；无随机远距传送 |
| F-20 | 传送失败/返回非 LivingEntity | 该只失败且档案位置不被误写 |
| F-21 | 默认半径 16 + 密集实体场景 | 记录 AABB 查询耗时；不得加载区块或明显拖慢 tick |
| F-22 | 默认 3 只宠物批量随行 | 记录一次 tick 的迁移耗时、档案写入数和同步包数 |
| F-23 | 10 个以上玩家同 tick 换维度 | 记录全局尖峰、总迁移数和跨 tick 行为 |
| F-24 | 半径 64 + 密集实体压力基准 | 仅作边界性能记录；验证是否需要限制推荐上限或增加内部预算 |
| F-25 | 快照时已有 pending，远召在随行 tick 前已完成 | `remotePendingAtArm` 或出发维度复核使随行跳过；无第二次传送、无第二次档案写入 |
| F-26 | 快照时已有 pending，随后失败并移除，来源实体仍在 | `remotePendingAtArm` 仍使随行跳过；不补做、不恢复旧 pending，玩家只看到远召失败反馈 |
| F-27 | 远召 pending 在实体 section 载入后才发现重复体 | 随行此前已让路；D-12 冲突检查、失败反馈和 ticket 释放保持原路径 |
| F-28 | 随行完成后玩家再次显式远召同一只 | 不产生第二只实体，不重建；按已加载 canonical 的普通同维度传送处理 |
| F-29 | 出发后、随行 tick 前新出现 pending 并保持活跃 | 执行时只读查询命中，随行让路；后续由远召反馈闭环 |

### 7.2 工程门槛

- `compileJava` 通过。
- `build` 通过，jar 不含临时 fixture / debug 类。
- `runServer` 正常启动到 `Done`，日志无新增 `ERROR`、`FATAL`、异常栈或资源缺失。
- `runClient` 完成主世界、地狱、末地三种实际切换，检查宠物位置、坐姿、客户端同步和档案落点。
- 静态检索确认没有新增 `LivingTickEvent`、`getChunkFuture`、`addRegionTicket`、`setChunkForced`、`FORCED` 或全实体逐 tick 扫描。
- 检索确认没有新增网络包、协议版本变化、公开 API 变化或 archive NBT 字段。
- 性能日志保存到 `D:\frukin_dev\_research\owner_dimension_follow_perf_<date>.log`，记录半径、实体数、随行数、P50/P95/最大值、TPS 和同步包数。
- 检查 `git status`，不夹带 `build/`、`run/`、日志、临时世界或研究产物。

### 7.3 可直接执行的验证顺序

#### A. 编译与静态审计

先执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
```

再执行静态检索，结果必须满足右侧条件：

| 检索 | 通过条件 |
|---|---|
| `rg -n "LivingTickEvent|getChunkFuture|addRegionTicket|removeRegionTicket|setChunkForced|FORCED|managedBlock" src/main/java` | 不新增随行路径命中；`RemoteSummonService` 的既有命中不增加 |
| `rg -n "EntityTravelToDimensionEvent|OwnerDimensionFollowService|ownerDimensionFollow" src/main/java` | 只命中预期的服务、配置和 `CommonEvents` 接线 |
| `rg -n "ownerDimensionFollow|RemoteSummonResult|PROTOCOL_VERSION" src/main/java` | `RemoteSummonResult` 和协议版本没有因本功能修改 |
| `git diff --stat` | 只出现 WP1-WP4 声明的文件，没有构建产物、日志、临时 fixture |

#### B. 服务端夹具（验证 F-04 至 F-29）

仓库当前没有自动化 GameTest，允许按既有远程召唤夹具做法增加一个临时 `internal.debug.OwnerDimensionFollowFixture`，但验收后必须删除，最终 jar 不得包含它。夹具至少完成：

1. 建立主世界、净地狱和末地测试玩家上下文。
2. 创建 3 只 canonical 绒亲：2 只在半径内、1 只在半径外；把半径内 1 只置为坐姿。
3. 调用 `OwnerDimensionFollowService.arm(...)`，断言快照候选数为 2；切断服务端 tick 前移动玩家到目标维度，调用 `tickIfPresent(...)`。
4. 断言输出 `owner dimension follow processed ... moved=2`，两只实体 UUID 不变、档案维度和位置更新、坐姿清除、客户端同步包已发出。
5. 为同一 companion 建立真实远召 pending；再次 `arm(...)` 并触发随行 tick，断言该只 `yielded=1`、pending 数量不变、ticket 数量不变、没有新的取消/冷却/成功消息。
6. 构造重复体在半径内、档案 `summoned=false`、实体已死亡、目标维度为第三个维度、快照超过 20 tick 等分支，分别断言 `failed`/`skipped` 和档案不变。
7. 记录每次 `arm`、`tick` 的墙钟耗时、候选数、moved/yielded/failed/skipped、tickets before/after、pending before/after。

夹具输出必须使用固定前缀，便于日志检索，例如：

```text
FURKIN_FIXTURE_ODF_BASIC_OK moved=2 radiusOut=1 sittingCleared=true canonicalPreserved=true
FURKIN_FIXTURE_ODF_PENDING_OK yielded=1 pendingBefore=1 pendingAfter=1 ticketBefore=1 ticketAfter=1
FURKIN_FIXTURE_ODF_FAILURE_OK duplicateSkipped=true deadSkipped=true thirdDimensionDropped=true archiveUnchanged=true
```

#### C. 客户端实机（验证 F-01/F-02/F-03/F-14/F-16/F-25/F-26）

执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat runClient --console=plain
```

至少走完：

1. 主世界 -> 地狱：2 只半径内宠物随行，1 只半径外不跟随；坐下宠物到达后起身。
2. 地狱 -> 主世界：反向动作、落点、维度定位和客户端同步一致。
3. 末地 -> 主世界返回：按 §10 R-01 选项 1 验证“不随行、快照清理、不加载末地区块”；这是负向用例，不要求宠物随行。
4. 先建立真实远召 pending，再过门；确认 player 只看到远召原有加载/完成/失败反馈，不出现“取消”或第二次传送。
5. 检查 `run/logs/latest.log`：无新增 `ERROR`、`FATAL`、异常栈或资源缺失；没有 `Can't keep up!` 的持续回归。

#### D. 最终门槛

```powershell
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

保存性能证据到 `D:\frukin_dev\_research\owner_dimension_follow_perf_<date>.log`；没有实测日志时，第 4 节只能保持“静态评估”状态。

## 8. 主要风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 在旅行事件中提前传送后事件被取消 | 宠物和玩家分离 | 只快照，不移动；完成后校验真实当前维度 |
| 只依赖 `PlayerChangedDimensionEvent` | 末地返回路径漏处理 | 不依赖该事件，统一在 ServerTick END 完成其它换维度路径；原版末地终章返回已按 §10 R-01 选项 1 列为范围外，不作为漏处理缺陷 |
| 使用档案 `entity_pos` 选人 | 位置可能落后于实体当前真实位置 | 出发瞬间用已加载实体空间查询 |
| 大批量 AABB 查询造成 tick 峰值 | 服务器卡顿 | 只玩家换维度时查询一次，半径默认 16、上限 64，候选受 active limit 约束 |
| 多只宠物落点重叠/卡墙 | 窒息、卡实体 | 环形落点 + `noCollision` best-effort + 玩家位置回退；不做随机远距传送 |
| 与远距召唤 pending 冲突 | 同一只实体双传送或状态竞争 | 快照时保存 `remotePendingAtArm`，执行时再只读查活跃 pending；任一命中即让路，不改写远召状态机 |
| 远召 pending 最终失败 | 快照已标记远召接管，宠物可能仍留在出发维度 | 这是显式请求优先的已记录代价；不补做随行，避免失败提示后宠物又出现，由玩家重试或下次换维度处理 |
| 第三方模组重定向维度 | 随行到错误世界 | `toDimension` 精确比对；第三个维度直接丢弃快照 |
| 自动处理坐下宠物引发玩家预期差 | 宠物离位 | 本功能冻结为“跨维度随行也带坐下宠物”；在有界范围内这是避免永久遗留的设计选择 |

## 9. 完成定义

- `owner-dimension-follow` 使用旅行前快照 + tick 完成校验，不在 `EntityTravelToDimensionEvent` 内直接移动宠物。
- 只处理出发维度内、半径内、已加载、本人、canonical、存活的已召唤绒亲。
- 同一 companion 在快照时已有 pending，或执行时发现活跃 pending 时，随行只读识别并让路，不取消、不完成、不改写 pending；`remotePendingAtArm` 保证 pending 随后失败/取消也不产生矛盾的补跟随；远召结果、冷却和反馈保持原样。
- 不新增区块加载、不解冻/重建实体、不修改数据 schema、不新增协议或公开 API。
- 单只、多只、取消、超时、重复体、远召 pending、死亡/解绑和部分失败均有验证证据；末地终章返回按 §10 R-01 选项 1 作为范围外负向用例验证（不随行、快照清理、不加载末地区块）。
- 默认半径 16 和坐姿随行的服务器/客户端负荷已按第 4 节记录实际性能证据，不把静态评估当作最终结论。
- 两份 README、两份 CHANGELOG、配置说明和本工作文档同步完成。
- 完成后版本按新机制推进到 `1.19.2-0.0.4.0`，但只有用户明确要求时才提交或推送。
## 10. 独立复审记录（2026-09-28）

- 复审范围：本文件全文、其引用的 1.19.2 运行时符号、以及仓库现有实现；只复审，不改 Java、资源、配置或版本号。
- 复审方式：用 JDK 17 的 `javap -p -c` 反汇编 `forge-1.19.2-43.2.0_mapped_official_1.19.2.jar` 取证（事件触发点、维度分支、API 签名），并与 `FurkinCompanionManager`、`RemoteSummonService`、`RemoteSummonRequest`、`CommonEvents`、`FurkinArchiveEntry`、`FurkinArchiveData`、`FurkinDuplicateRegistry`、`FurkinEntityLocator`、`FurkinServerConfig` 逐条对照。
- 复审结论：**除 R-01 外，本文档描述的架构、事件顺序、pending 让路、传送重载、唯一性链、配置与版本变更均取证通过，可直接落码。** R-01 是口径问题、不是实现难度问题，需先确认。

### 10.1 已取证成立

| # | 结论 | 证据 |
|---|---|---|
| C-01 | 阶段 A 可安全快照 | `ServerPlayer#changeDimension(ServerLevel, ITeleporter)` 的第一个动作就是 `ForgeHooks.onTravelToDimension(...)`（字节码偏移 5），早于 `isChangingDimension = true` 与任何 `setLevel`；事件触发时玩家仍在出发维度、坐标有效 |
| C-02 | 普通换维度在本次调用内完成 | 普通分支在同一方法内完成重发 `ClientboundRespawnPacket`、切换维度等，末尾才 `firePlayerChangedDimensionEvent(...)`（偏移 469）；故 ServerTick END 已能看到新维度，最多迟 1 tick |
| C-03 | 事件覆盖地狱门与命令传送 | `Entity#handleNetherPortal()` 调用 `changeDimension(ServerLevel)`；`ServerPlayer#teleportTo(ServerLevel, ...)` 在目标 `Level != 当前 Level` 时同样先调 `ForgeHooks.onTravelToDimension(...)`（偏移 41），同维度则直接普通传送、不触发 |
| C-04 | 取消即阻止玩家移动 | `changeDimension` 在 `onTravelToDimension` 返回 false 时立即 `return null`（偏移 8-12），与“不可在事件里提前移动宠物”的前提一致 |
| C-05 | 死亡重生不触发本功能 | `PlayerList#respawn(...)` 只做 `restoreFrom` + `moveTo`/`setPos`，不调用 `changeDimension`，因此不发布 `EntityTravelToDimensionEvent` |
| C-06 | 文档所用 API 在 1.19.2 全部存在 | `AABB(Vec3,Vec3)`、`AABB#inflate(double)`/`move`、`Vec3#add/subtract(double,double,double)`、`EntityGetter#getEntitiesOfClass(Class,AABB,Predicate)`、`Level#getEntity(UUID)`、`Entity#distanceToSqr(Vec3)`、`CollisionGetter#noCollision(Entity,AABB)`、`ServerPlayer#getLevel()` 直接返回 `ServerLevel`、`Entity#changeDimension(ServerLevel,ITeleporter)` 返回 `Entity` |
| C-07 | 事件接线可复用现有入口 | `CommonEvents` 带 `@Mod.EventBusSubscriber(bus = FORGE)`，`onServerTick` 已在 `ServerTickEvent.Phase.END`，插入随行 tick 无需第二套入口 |
| C-08 | 传送重载不破坏既有行为 | 现有 `teleportLoadedEntity(player, entry, target)` 语义为“玩家朝向前方 1.5 格 + `changeDimension(FixedTeleporter)`/`teleportTo` + `setEntityLocation` + `putEntry` + 清坐姿 + `SyncFurkinDataPacket`”；抽出显式落点重载后原入口委托即可保持语义不变 |
| C-09 | pending 语义与 §3.9 一致 | `byCompanion` 以 `companionId` 为键；`State.TERMINAL` 存在且 `finishPending` 会把请求移出 `byCompanion`；pending 只在“已召唤但运行时索引未命中”时创建；`resolveWhenEntitiesLoaded` 用 `getPlayerList().getPlayer(uuid)` 取玩家、落点取 `player.getLevel()`，仅对原 `targetDimension` 做 `areEntitiesLoaded` 检查 —— 即 D-10“pending 期间换维度仍由远召送到新维度” |
| C-10 | 唯一性链描述准确 | `companionId = UUID.randomUUID()`（契约时生成）、档案按 `companionId` 存储、`FurkinDuplicateRegistry` 按 `companionId -> Set<entityUuid>` 且排除 canonical 并复核 capability、`FurkinEntityLocator.locate` = UUID + 维度 + 身份 |
| C-11 | 配置与版本口径正确 | `ACTIVE_LIMIT` 存在（1-64，默认 3）；`SPEC = BUILDER.build()` 在类尾，新配置项须加在其前；按 AGENTS.md，新增机制走 MINOR，`1.19.2-0.0.4.0` 正确；全程不涉 `api/**`、`PROTOCOL_VERSION`、存档 schema |

### 10.2 发现的问题

#### R-01（阻断级，需先定口径）：原版末地“终章返回”在现冻结口径下无法随行

反汇编证据（`ServerPlayer#changeDimension(ServerLevel, ITeleporter)`）：

1. 当 `from == Level.END && to == Level.OVERWORLD && teleporter.isVanilla()` 时进入特殊分支（偏移 29-118）：`unRide()` → `ServerLevel.removePlayerImmediately(this, CHANGED_DIMENSION)` → `wonGame = true`、发送 `WIN_GAME`，随后 **直接 `areturn`**。该分支既不发布 `PlayerChangedDimensionEvent`，也 **不改写玩家的 `level` 字段**。
2. 玩家真正回到主世界发生在客户端发送 `ServerboundClientCommandPacket(PERFORM_RESPAWN)` 之后：`ServerGamePacketListenerImpl#handleClientCommand`（偏移 60-94）判到 `player.wonGame` 后调用 `PlayerList.respawn(player, true)`，生成 **新的 `ServerPlayer`**（维度为重生点维度，缺省 `server.overworld()`）。

对本文档设计的三点影响：

1. §3.12.4 第 2 步会在终章期间立刻丢弃快照：旧 `ServerPlayer` 已被 `removePlayerImmediately` 标记，`player.isRemoved()` 为 **true**，而 `getPlayerList().getPlayer(uuid)` 此时仍返回该旧实例。
2. 即使放宽 `isRemoved()` 判断，终章时长不可预期（不跳过时约一分多钟），远超 `MAX_WAIT_TICKS = 20`，快照必然超时。
3. 即使把窗口拉长，玩家回到主世界时末地侧已失去玩家 ticket、相关区块与实体已卸载，`fromLevel.getEntity(entityUuid)` 不再命中；按“不加载冷区、不申请 ticket”的冻结口径仍只能记为 `skipped`。

因此 §2.1 第 3 点、§3.4 的“最多跨一个 server tick”、F-03 的预期、§8 风险表中“不依赖该事件，统一在 ServerTick END 完成”对这条路径均不成立：**在现口径下 F-03 不能通过。**

处理选项（需乌狸择一，选定后再冻结 F-03）：

- **选项 1（推荐，口径最小改动）**：把“原版末地终章返回”明确列为范围外。F-03 改为“预期：不随行；快照在发现玩家不可用或维度不符后清理”，并在 §1.3 与 §3.10 说明玩家侧表现：从末地走终章返回时宠物留在末地，需重新远召或下次换维度才回到身边。
- **选项 2（要覆盖该路径）**：该分支不再走“已加载随行”，而是转交既有 `RemoteSummonService` 的 pending 机制（临时 ticket + 异步加载）。代价是重新引入区块加载，并需对齐 pending 让路、冷却、反馈，以及 `REMOTE_SUMMON_ENABLED = false` 时的行为口径。

#### R-02（低，文档一致性，已修订）

原 §3.3 第 2 步写 `player.getBoundingBox().inflate(radius)`，§3.12.3 第 4 步写以 `anchor` 为中心构造 `AABB`，两者盒尺寸不同（前者比半径大约 0.3 格，仍由精确距离过滤兜底）。已把 §3.3 统一为与 §3.12.3 相同的中心盒写法，落码口径不变。

#### R-03（低，诊断可读性，可选）

§3.12.4 的 `process(...)` 对“出发维度已卸载 / 实体已从出发维度索引消失”只记 `skipped`，与真正的“校验失败”混在一起。建议日志把 `skipped` 再分为 `source-level-missing` 与 `source-entity-missing` 两个计数，便于 F-10/F-13/F-15 判读；不影响行为。

### 10.3 复审结论

- 可行：WP0-WP4 的组件边界、两阶段时序、pending 让路、传送重载、落点算法、唯一性复核、配置/版本/文档变更清单均取证通过。
- 待决：R-01 的口径选择（选项 1 或选项 2）。确认后本文件即可作为直接执行依据，再进入编码。

### 10.4 口径落定（2026-09-28，乌狸确认）

- 采用 **选项 1**：原版末地“终章返回”不随行，列为范围外。
- 已按此修订：§1.1 例外说明、§1.3 范围外、§2 取证表、§2.1 第 3 点、§3.4 20 tick 说明、§3.10 玩家侧感知、§7.1 F-03、§7.3-C 第 3 项、§8 风险表、§9 完成定义。
- 随之冻结的实现口径：`tick(...)` 第 2 步保留 `player.isRemoved()` 淘汰 —— 终章期间旧 `ServerPlayer` 被标记 removed，正好让该路径自然落空，不需要额外特判；不新增任何为该路径服务的区块加载或 pending 转交逻辑。
- F-03 转为负向用例：验证不随行、快照清理、不加载末地区块。
- 其余 WP0-WP4 内容不变，可进入编码。

## 11. 实施与验证记录（2026-09-28）

### 11.1 实际改动文件

| 文件 | 操作 | 内容 |
|---|---|---|
| `src/main/java/com/wanancat/furkin/internal/config/FurkinServerConfig.java` | 修改 | 新增 `OWNER_DIMENSION_FOLLOW_ENABLED` / `OWNER_DIMENSION_FOLLOW_RADIUS`，置于 `SPEC = BUILDER.build()` 之前 |
| `src/main/java/com/wanancat/furkin/internal/contract/OwnerDimensionFollowService.java` | 新增 | 内存服务：`arm(...)` 旅行前快照、`tickIfPresent(...)` 到达后完成校验、`process(...)` 逐只复核与批量传送、`chooseLanding(...)` / `findSafeLanding(...)` / `ringPoint(...)` 落点、`stop(...)` 停机清理 |
| `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java` | 修改 | 抽出同包可见的显式落点重载；原公开入口改为计算“朝向正前方 1.5 格”后委托，语义不变 |
| `src/main/java/com/wanancat/furkin/internal/contract/RemoteSummonService.java` | 修改 | 新增只读 `isPendingFor(...)` 与私有 `hasPendingFor(...)`；request/tick/cancel/finishPending 未改 |
| `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java` | 修改 | 新增 `onEntityTravelToDimension(...)`；`onServerTick` 先随行、后远召；`onServerStopped` 增加随行清理 |
| `gradle.properties` | 修改 | `mod_version` -> `1.19.2-0.0.4.0` |
| `README.md`、`README.zh-CN.md` | 修改 | 特性与用法补充跨维度随行、半径配置与“不加载冷区”边界 |
| `CHANGELOG.md`、`CHANGELOG.en.md` | 修改 | 新增 `1.19.2-0.0.4.0` 条目（Added / API Changes / Validation） |

未修改：`api/**`、`FurkinNetwork.PROTOCOL_VERSION`、`FurkinArchiveEntry`、`FurkinArchiveData`、`FurkinEntityLocator`、`FurkinDuplicateRegistry`、`RemoteSummonResult`、`RemoteSummonFeedback`、`RequestSummonPacket`、`FurkinCommand` 的远召反馈分支、`mods.toml` 与资源包格式。

### 11.2 与 §3.12 契约的对照

- 两阶段时序、逐只复核顺序、pending 让路（`remotePendingAtArm` + 执行时只读复查）、重复体守卫、显式落点重载、落点支撑/危险体积筛选与日志级别/字段均按 §3.12 实现。
- 阶段 A 用 `getEntitiesOfClass(LivingEntity.class, 中心盒, predicate)`，谓词内按“存活/距离 -> capability/owner -> 档案/canonical”顺序过滤；快照只存 UUID 与值，不持有 `Entity`。
- `MAX_WAIT_TICKS = 20`、`SINGLE_FORWARD_DISTANCE = 1.5`、`MULTI_RING_DISTANCE = 1.75` 与 §3.12.2/§3.12.5 一致。
- §10.4 选项 1 已落地：`tick(...)` 第 2 步保留 `player.isRemoved()` 淘汰，末地终章返回因此自然落空，未新增任何为其服务的区块加载或 pending 转交逻辑。

### 11.3 已执行验证（2026-09-28）

| 检查 | 方式 | 结果 |
|---|---|---|
| 编译 | `gradlew.bat compileJava --console=plain`（JDK 17.0.2，多次） | `BUILD SUCCESSFUL` |
| 打包 | `gradlew.bat build --console=plain` | `BUILD SUCCESSFUL`；产物 `build/libs/furkin-1.19.2-0.0.4.0.jar` |
| 元数据 | 展开后 `mods.toml` | `version="1.19.2-0.0.4.0"`；非 ASCII 字节数 0；jar 内无 `internal.debug` / fixture 类 |
| 服务端启动/停止 | `gradlew.bat runServer --console=plain`（无夹具，JDK 17.0.2） | 启动到 `Done (2.266s)!`；RCON `stop` 后 `Stopping server` -> `Saving players` -> `Saving worlds` -> overworld/end/nether `All chunks are saved` -> `RCON Listener stopped`；Gradle `BUILD SUCCESSFUL in 1m 9s`、退出码 0；`FATAL` 0，未出现新异常栈；`ERROR` 仅为既有 vanilla/Forge `TagLoader` 标签噪声 |
| 客户端 | `gradlew.bat runClient --console=plain` | 启动到资源加载 / 标题界面；客户端日志 `ERROR=0`、`FATAL=0` |
| 静态审计 | `rg` 检索 + `git diff` | 随行路径无 `LivingTickEvent` / `getChunkFuture` / `addRegionTicket` / `setChunkForced`；`PROTOCOL_VERSION` 仍为 `"2"`；无公开 API / 存档 schema / 网络包变更 |
| 功能夹具 | 临时 `internal.debug.OwnerDimensionFollowFixture` + `runServer`（JDK 17.0.2） | `FURKIN_FIXTURE_ODF_OK checks=52 failures=0`；F-04 至 F-29 已执行，覆盖半径边界、事件取消、第三维度、超时、冷/卸载实体、身份/档案过滤、重复体、坐姿、部分失败、旧版落点回退（历史夹具，已被 F-19 重新打开）、远召 pending 让路、pending 后失败、随行后显式远召复用 canonical、停机清理；固定日志 `D:\frukin_dev\_research\owner_dimension_follow_fixture_20260928.log`；夹具源码与临时运行世界已删除 |
| 服务端空闲长跑 | 无夹具 `runServer` + RCON `debug start`，每 10 分钟采样 | 30 分钟全程 20 TPS；overall mean tick time `0.859-0.910 ms`；日志 `D:\frukin_dev\_research\odf_perf_30m_20260928.txt` |
| 服务端压力夹具 | `FURKIN_FIXTURE_ODF_PERF=1`，FakePlayer + 受控实体；覆盖半径 16/64、`activeLimit=3/20`、80/150 野生、12 玩家同 tick | cold 半径16/20只 P95 `19.979 ms`；hot 同场景 P95 `4.854 ms`、max `6.538 ms`；默认3只 P95 `1.410 ms`；半径64 P95 `5.883 ms`；12x3 P95 `7.027 ms`；日志 `D:\frukin_dev\_research\odf_perf_stress_20260928.log`；夹具源码与临时世界已删除 |
| 落点安全聚焦夹具 | 临时 `OdfLandingFixture` + `runServer`（JDK 17.0.2） | `FURKIN_FIXTURE_ODF_LANDING_OK checks=5 failures=0`；覆盖正常地面、向下超过 2 格无支撑、身体浸入熔岩、半砖支撑、身体浸入火；日志 `D:\frukin_dev\_research\odf_landing_fixture_20260928.log`；夹具源码已删除 |
| 最终构建 | `gradlew.bat clean build --console=plain`（JDK 17.0.2，2026-09-28 20:38） | `BUILD SUCCESSFUL in 16s`；产物 `build/libs/furkin-1.19.2-0.0.4.0.jar`（401598 bytes，SHA-256 `D71FB6FD05848CC589CDEB4F510D9980A701B007F03C3CF31EF8E0F3EB75D03B`）；`jar tf` 未发现 `internal/debug`、`OdfPerfProbe`、`OdfServerProbe` 或 `OdfClientProbe`；`git diff --check` 无空白错误 |

### 11.4 真实客户端补测记录（2026-09-28）

已实际完成：

| 用例 | 结果 | 证据 |
|---|---|---|
| F-01 主世界 -> 地狱 | 通过。`ODF1/ODF2` 在半径内随行；`ODF3` 在约 22.5 格外留在 Overworld；到达 Nether 后 `ODF2` 的 `Sitting` 由 1 变 0。 | `odf_client_matrix_fresh_20260928.log`：`armed ... candidates=2`、`moved=2`、`ODF1/ODF2 Nether`、`ODF3 Overworld` |
| F-02 地狱 -> 主世界 | 通过。`ODF1/ODF2` 返回 Overworld，`ODF3` 保持原位置/坐姿。 | `owner dimension follow processed ... from=the_nether to=overworld moved=2` |
| F-03 原版末地终章返回负向路径 | 通过负向口径。进入 End 时正常随行 `ODF1/ODF2`；终章返回仅建立快照，随后 `reason=player-unavailable` 丢弃，没有 `processed/moved`，宠物留在 End。 | 日志中 `from=the_end to=overworld` 的快照覆盖与 `player-unavailable` 记录；无返回随行传送 |
| F-14 坐姿随行 | 通过。`ODF2` 在出发维度 `Sitting=1`，到达 Nether 后 `Sitting=0`。 | 两次 `report_round` 的位置/坐姿输出 |
| F-16/F-25 客户端远召联动 | 部分通过。真实客户端观察到 `ODF1` 发起远召后进入 pending；同 tick 玩家切到 Nether，`ODF3` 由随行移动到 Nether；随后 `ODF1` 由 `RemoteSummonService` 完成 `COMPLETED_TELEPORT`，没有取消或第二次传送/重复实体。 | 日志：`remote summon request=1 ... pending=1`、`owner dimension follow processed ... moved=1`、`remote summon completed ... COMPLETED_TELEPORT` |
| F-01/F-02 三只全员往返 | 通过。最新构建中 `ODF1/ODF2/ODF3` 全部位于 16 格内，Overworld -> Nether 与 Nether -> Overworld 两段均为 `moved=3`；到达后三者位置不同，`Sitting=0`。 | `run/logs/latest.log`：`16:29:41`、`16:29:53` 两条 `owner dimension follow processed ... moved=3 failed=0 skipped=0 total=3` |
| F-19 传送门/危险落点回归 | 通过（修复后真实客户端复验）。2026-09-28 17:37 旧构建在主人从地狱传送门高处坠落时出现 `moved=3` 后约 3 秒熔岩死亡；当前实现增加最多 2 格支撑搜索并拒绝无支撑/危险体积。17:55 正常安全门测试 `moved=3` 且无死亡；17:57 跨维度 `/tp` 到地狱 `y=250` 的确定性无支撑场景记录三只 `NO_SAFE_LANDING`，最终 `moved=0 failed=3`，没有新的 `Furkin teleported`。 | `odf_landing_client_20260928.log`：17:57:49 三条 `landing blocked` / `NO_SAFE_LANDING`、17:57:49 `processed ... moved=0 failed=3`；聚焦服务端夹具 `FURKIN_FIXTURE_ODF_LANDING_OK checks=5 failures=0` |
| F-19 修复后最终代码压力复测 | 通过（服务端夹具）。最终代码、3 次热身、clean 日志口径：默认 3 只 P95 `1.861 ms`；12×3 同 tick P95 `8.558 ms`；radius16/activeLimit20 cold P95 `23.352 ms`。cold 尖峰属于首次路径/JIT 特征，未提高默认 `activeLimit=3`。 | `D:\frukin_dev\_research\odf_perf_final_clean_20260928.log` |
| F-26 真实客户端失败反馈 | 通过（独立复验）。真实客户端 `OdfAlpha` 收到 `远距召唤已取消，档案状态未改变。`，服务端远召结果为 `CANCELLED reason=STATE_CHANGED`，玩家侧没有错误成功提示。 | `run/logs/latest.log` 中 `[CHAT] 远距召唤已取消，档案状态未改变。`；共享证据副本 `D:\frukin_dev\_research\odf_client_probe_shared_20260928.log` |
| 最终代码真实客户端 FPS | 通过（有限口径）。稳定窗口 p50/p95 均为 60 FPS：空场 Overworld、三只 Nether、三只 Overworld、三只 Nether；只有启动/换维度瞬间出现低帧样本。 | `D:\frukin_dev\_research\odf_client_fps_final_20260928.txt` |
| 真实双客户端网络 fanout | 通过（有限口径）。3 只宠物、2 个 tracking 客户端：服务端显式同步 3×207 bytes、`chunkTrackers=2`；每客户端各收到 6 次处理记录（显式同步 + `PlayerEvent.StartTracking` 各一次）。 | `D:\frukin_dev\_research\odf_fanout_final_20260928.txt` |

旧构建曾把环形落点判为碰撞后回退到玩家脚下，随后被原版门弹回；当前实现取消玩家脚点回退。2026-09-28 17:37 的实机熔岩死亡进一步证明，仅拒绝落点所在方块的危险类型不够：主人从高空坠落时，宠物会被放到无支撑空中并随后坠入熔岩。现改为向下最多 2 格搜索非空碰撞支撑面，并要求落点体积不含 `NETHER_PORTAL`、`END_PORTAL`、`END_GATEWAY`、`LAVA`、`FIRE`、`SOUL_FIRE`；找不到时跳过该只并记录 `NO_SAFE_LANDING`。17:57 的跨维度 `/tp` 复验在无支撑场景中得到 `moved=0 failed=3`，证明危险分支不再把宠物投入空落点。这仍是 best-effort 安全筛选，不承诺在虚空、未加载区块或所有复杂地形中保证安全落点；相关边界仍按 §1.3 和 §8 记录。

F-26 记录口径：服务端状态机与真实客户端失败反馈均已通过；服务端 30 分钟 TPS、高 `activeLimit`、半径 16/64、最终代码压力复测和 12 玩家同 tick 均已有记录；真实客户端帧率与双客户端 fanout 已补测，详见 §11.4。

### 11.5 残余边界

- F-16/F-25：真实客户端 pending 让路联动已执行，当前记录为“部分通过”；剩余边界是另一时序分支的独立复验，未在本轮补测中重新打开。该口径不能扩展为所有远召竞态均已完整覆盖。
- §7.3-C 的真实客户端核心矩阵已执行 F-01/F-02、F-03、F-14、F-16/F-25 与三只全员往返；F-19 旧真实客户端结论已被 17:37 熔岩死亡推翻，修复后安全支撑与无支撑危险分支均已通过真实客户端复验。
- §4 的真实客户端帧率与真实多客户端网络 fanout 已补测；不同硬件、不同视距和真实生产网络的长期流量仍未做矩阵化覆盖，不能把本机开发客户端数据外推为所有生产环境保证。

### 11.6 状态

- WP0-WP4 的代码与文档改动已完成；无夹具 `clean build` 已通过，产物为 `furkin-1.19.2-0.0.4.0.jar`（401598 bytes，SHA-256 `D71FB6FD05848CC589CDEB4F510D9980A701B007F03C3CF31EF8E0F3EB75D03B`），且不含临时夹具；feature 提交 `215a70e` 与本次补测文档已提交并推送到 `origin/mc1.19.2`。
- 服务端性能已完成：30 分钟空闲 TPS、`activeLimit=20` 冷/热态、半径 64 密集野生、默认 3 只、12 玩家同 tick 均有测量记录；F-19 支撑搜索修复后又完成最终代码复测，默认 3 只 P95 `1.861 ms`、12×3 P95 `8.558 ms`、radius16/activeLimit20 cold P95 `23.352 ms`。真实客户端帧率与双客户端 fanout 已按 §11.4 补测。
- F-19 验证状态：修复后的正常安全支撑与无支撑危险分支均已通过真实客户端复验。
- F-26 验证状态：服务端状态机已通过；真实客户端失败反馈已在真实客户端独立复验。
- 版本号已推进到 `1.19.2-0.0.4.0`；已提交并推送。
