# Furkin 1.20.1 主人跨维度随行工作文档

- 日期：2026-09-28
- 分支：`mc1.20.1`
- 当前基线：`79335b3`（`docs: 记录 0.0.3.0 GitHub Release`）
- 目标运行时：Minecraft 1.20.1 / Forge 47.2.0 / Java 17
- 输入依据：`D:\frukin_dev\frukin_1_19_2\docs\owner-dimension-follow-1.19.2\README.md`
- 状态：**口径已确认；1.20.1 代码、配置、文档与版本已完成。真实客户端主世界/下界往返、坐姿、pending 让路和末地终章负向路径，以及服务端边界/压力矩阵均已通过；真实客户端帧率与多客户端网络 fanout 未独立测量。**

## 0. 结论摘要

1. 1.19.2 已落地的“旅行前快照 + 到达后服务端 tick 完成校验”方案可以迁移到 1.20.1，不需要重新设计事件链或网络协议。
2. 推荐实现与 1.19.2 同构：`EntityTravelToDimensionEvent` 只做无副作用候选快照；玩家到达目标维度后，在现有 `ServerTickEvent.Phase.END` 中逐只复核并复用已加载实体传送路径。
3. 1.20.1 不能机械复制 1.19.2 源码。需要逐项改写：
   - `ServerPlayer#getLevel()` -> `ServerPlayer#serverLevel()`；共享实体判断使用 `Entity#level()`。
   - 当前远召服务在 `ServerStoppingEvent` 停止并释放 ticket；随行服务应在同一停机时序清理，不照搬 1.19.2 的 `ServerStoppedEvent` 位置。
   - 当前 `FurkinCompanionManager.teleportLoadedEntity(...)` 尚无“显式落点”重载，需要先抽取，再让原有单只入口委托。
   - 当前 `RemoteSummonService` 尚无只读 pending 查询，需要新增，但不能改 pending、ticket、超时、冷却和终态流程。
4. 本功能不需要新增网络包、公开 API、存档字段或数据版本；`FurkinNetwork.PROTOCOL_VERSION` 应保持 `"2"`。
5. 本功能属于“新增机制”，按仓库版本规则，已推进为 `1.20.1-0.0.4.0`；公开 API、网络包、协议和存档 schema 均不变。
6. 1.20.1 的原版末地“终章返回”（末地 -> 终章 -> 重生）在同一特殊分支中直接返回，不能按普通 `changeDimension(...)` 完成路径处理。已确认延续 1.19.2 口径：**范围外、不随行、快照自然淘汰**。
7. 预计改动规模：新增 1 个约 500 行的内部服务类，修改 4 个现有 Java 文件，另同步 README、双 changelog、配置说明和版本号；不涉及 `api/**`。

## 1. 需求口径

### 1.1 触发条件

只有同时满足以下条件才建立随行快照：

- 事件实体是 `ServerPlayer`。
- 玩家确实开始一次跨维度旅行，目标维度与当前维度不同。
- `EntityTravelToDimensionEvent` 在本次处理时未被取消。
- `ownerDimensionFollowEnabled` 为 `true`。
- 出发维度存在至少一只通过下方候选条件的已加载绒亲。

原版地狱门、末地门（主世界 -> 末地）、跨维度 `/tp`、`/execute in`，以及其它最终走 `Entity#changeDimension(...)` 的普通路径应自然覆盖。

明确例外：

- 玩家死亡重生不走本功能的跨维度旅行事件路径。
- 原版末地出口门的“终章返回”不是普通的同调用栈维度切换。1.20.1 反汇编仍显示 `ServerPlayer#changeDimension(...)` 在末地 -> 主世界且 `ITeleporter#isVanilla()` 时执行特殊分支后直接返回，不在此次调用内把玩家字段切换到主世界。
- 本功能不承诺在该终章返回路径上跟随；该项应作为负向验收用例，而不是缺陷。

### 1.2 候选实体定义

候选实体必须同时满足：

- 是 `LivingEntity`，不是玩家。
- 位于出发 `ServerLevel` 的运行时实体索引中，且当前已加载。
- `FurkinData.isCompanion() == true`。
- `FurkinData.getOwnerUuid()` 等于玩家 UUID。
- 实体存活、未移除。
- 档案 `FurkinArchiveEntry.isSummoned() == true` 且 `isAlive() == true`。
- 档案 `entityUuid` 等于当前实体 UUID，保证处理的是 canonical 实体，而不是重复体。
- 到玩家出发位置的 3D 距离平方不大于半径平方。

不把 `Cat`、`Wolf` 或 `TamableAnimal` 写成新的物种白名单。随行身份以 `FurkinData` 和 `FurkinArchiveEntry` 为准；只有清坐姿、清除坐下意图时才在 `TamableAnimal` 上执行动作。

### 1.3 范围外

- 半径外、未加载、已卸载或位于其它维度的宠物自动强加载后随行。
- 用随行替代远距召唤、收回、死亡、复活或解绑。
- 自动迁移物品、坐骑、乘客、拴绳结或其它非绒亲实体。
- 同维度跟随寻路、原版 `FollowOwnerGoal` 距离调整。
- 新增公开 API、客户端 UI、网络包或存档 schema。
- 与原版末地终章返回争抢一套额外状态机；首版保持范围外。

### 1.4 默认口径

| 项目 | 默认值 | 语义 |
|---|---:|---|
| `ownerDimensionFollowEnabled` | `true` | 是否启用主人跨维度随行总开关 |
| `ownerDimensionFollowRadius` | `16` | 出发玩家中心到宠物中心的 3D 欧氏半径，范围 1-64 |
| 候选上限 | `ACTIVE_LIMIT` | 取最近的合法 canonical，默认 3 |
| 坐姿宠物 | 随行 | 到达后清坐姿并恢复跟随 |
| 显式远召 pending | 优先 | 随行只读识别并让路，不取消、不完成、不改写 |
| 冷区宠物 | 不处理 | 不申请 ticket，不调用区块 future，不加载区块 |

## 2. 1.20.1 基线与 API 取证

以下结论已用本机 Forge 47.2.0 mapped official jar 的 `javap -p` / `javap -c` 和当前仓库源码核对。

| 项目 | 1.20.1 结论 | 对本功能的影响 |
|---|---|---|
| `EntityTravelToDimensionEvent` | 类存在；构造参数为 `(Entity, ResourceKey<Level>)`；公开 `getDimension()`；类仍为可取消事件 | 可直接沿用 1.19.2 的事件入口语义 |
| `ForgeHooks.onTravelToDimension(...)` | 发布 `EntityTravelToDimensionEvent`；事件取消时返回 `false`，`Entity#changeDimension(...)` 返回 `null` | 证明不能在事件阶段提前移动宠物，必须先快照 |
| `ServerPlayer#changeDimension(...)` | 重写公开方法；普通分支同一调用栈完成维度切换，末地 -> 主世界 vanilla 分支提前返回 | tick 完成校验仍适用普通路径；终章返回保持范围外 |
| `EntityTravelToDimensionEvent` 与 `PlayerChangedDimensionEvent` | 前者在切换前、可取消；后者在普通切换完成后发布，但不提供旧位置 | 不能只依赖 `PlayerChangedDimensionEvent` 实现 |
| `ServerPlayer#serverLevel()` | 公开返回 `ServerLevel` | 1.20.1 获取玩家当前服务端 Level 应使用它 |
| `Entity#level()` | 公开返回 `Level` | 判断宠物当前所在 Level 使用它；不写 1.19.2 的 `getLevel()` |
| `Entity#changeDimension(ServerLevel, ITeleporter)` | 公开，返回迁移后的 `Entity` | 复用现有跨维度传送方式 |
| `ServerLevel#getEntitiesOfClass(...)` | 公开 | 阶段 A 在出发维度做一次有界空间查询 |
| `ServerLevel#getEntity(UUID)` | 公开，只查运行时索引，不加载区块 | 阶段 B 按快照 UUID 复核实体 |
| `Level#isLoaded(BlockPos)` | 公开 | 落点安全检查只读已加载方块 |
| `Level#noCollision(Entity, AABB)` | 公开 | 落点体积做 best-effort 碰撞检查 |
| `BlockState#getCollisionShape(BlockGetter, BlockPos)` | 公开 | 向下搜索非空支撑面 |
| `TickEvent.ServerTickEvent` | 当前 `CommonEvents.onServerTick(...)` 已在 `Phase.END` 驱动远召和技能 tick | 随行接入现有 tick，不新增第二套 tick 入口 |
| `MinecraftServer#getTickCount()` | 公开 | 用于 20 tick 快照超时判断 |
| 服务器级档案 | 当前 `FurkinArchiveData` 已统一使用主世界服务器级实例 | 随行可以从 `FurkinArchiveData.get(server)` 定向读取 canonical 条目 |
| 远召停机时序 | 当前 `CommonEvents.onServerStopping(...)` 调用 `RemoteSummonService.stop(...)`，在区块调度器关闭前释放 ticket | 随行停止应接入同一 stopping 阶段，不能误抄 1.19.2 的 stopped 位置 |
| 远召 pending 查询 | 当前没有只读 pending 查询入口 | 本功能需要补一个不改状态的查询 |
| 显式落点传送 | 当前 `FurkinCompanionManager.teleportLoadedEntity(...)` 只计算玩家前方 1.5 格 | 多只随行需要先抽出一个显式坐标重载 |

### 2.1 为什么不能简化成“过门后直接传送”

`EntityTravelToDimensionEvent` 阶段仍有以下不确定性：

1. 事件可能被后续监听器取消。
2. 玩家目标落点和正式 `setLevel` 尚未完成。
3. 第三方模组可能改写最终维度或传送路径。
4. 如果在事件阶段调用宠物的 `changeDimension(...)`，还会再次触发维度旅行事件链，扩大重入和监听器顺序耦合。

因此两阶段结构不是可替换的实现细节，而是本功能的安全基础。

### 2.2 1.20.1 与原 1.19.2 的差异结论

1. 业务架构不需要改变。
2. Java API 替换点主要是 `getLevel()` 到 `level()` / `serverLevel()`。
3. 集成点必须按当前 1.20.1 仓库重写，特别是停机时序、远召 pending 查询和共享传送重载。
4. 任何对 `RemoteSummonService` 现有状态机的“顺手改造”都不合适；本功能只增加只读查询。

## 3. 1.19.2 -> 1.20.1 迁移映射

| 1.19.2 产物 | 1.20.1 处理 | 风险/注意 |
|---|---|---|
| `OwnerDimensionFollowService.java` | 同路径新增 | 逐处替换 `getLevel()`；不要把 `Entity` 强引用放进跨 tick 快照 |
| `FurkinServerConfig` 两个配置项 | 在远召配置后、`SPEC` 前新增 | 注释保持纯 ASCII；重新生成/检查 TOML |
| `FurkinCompanionManager` 显式落点重载 | 从当前单只方法抽取同包重载 | 原公开入口行为保持，单只远召/命令不能回归 |
| `RemoteSummonService.isPendingFor(...)` | 新增只读静态查询 | 服务不存在时不得创建实例，不碰 ticket、冷却、反馈 |
| `CommonEvents` 旅行事件处理器 | 新增 `onEntityTravelToDimension(...)` | 只调用 `arm(...)`，不直接传送 |
| `CommonEvents.onServerTick(...)` | 在远召前调用随行 tick | 顺序必须保证随行先看到 pending 并让路 |
| 停机清理 | 接入当前 `onServerStopping(...)` | 1.20.1 远召已经在 stopping 阶段释放 ticket |
| README、双 changelog、版本 | 已同步 | 版本号推进到 `1.20.1-0.0.4.0`；讨论稿中的“暂不动”仅为当时的阶段说明 |

### 3.1 明确不修改的文件边界

- `src/main/java/com/wanancat/furkin/api/**`
- `FurkinNetwork.PROTOCOL_VERSION`
- `FurkinArchiveEntry` / `FurkinArchiveData` 的 NBT schema
- `RemoteSummonResult`、`RemoteSummonFeedback`、`RequestSummonPacket`
- `RemoteSummonService` 的 pending/ticket/超时/冷却/终态流程；本功能只新增只读查询
- `mods.toml`、资源包格式和玩家 UI 包结构

## 4. 推荐架构

### 4.1 组件边界

新增内部服务：

- `com.wanancat.furkin.internal.contract.OwnerDimensionFollowService`

事件接入：

- `com.wanancat.furkin.internal.event.CommonEvents`

共享传送核心：

- `com.wanancat.furkin.internal.contract.FurkinCompanionManager`

只读 pending 查询：

- `com.wanancat.furkin.internal.contract.RemoteSummonService`

不新增公开 API 类型，不新增网络包，不把服务端状态放入 client 包。

### 4.2 内存状态

服务按 `MinecraftServer` 维护，参照当前 `RemoteSummonService` 的 `WeakHashMap` 生命周期。每个玩家最多保留一个待执行快照：

```text
ArmedRequest
  playerUuid
  fromDimension
  toDimension
  sourceAnchor(Vec3)
  armedTick
  candidates[
    entityUuid
    companionId
    distanceSquared
    remotePendingAtArm
  ]
```

约束：

- 只保存 UUID 和值，不跨 tick 保存 `Entity` 强引用。
- 候选按距离升序，再按 `entityUuid` 字符串排序，保证结果稳定。
- 候选数量以当前 `ACTIVE_LIMIT` 为上限。
- 同一玩家再次旅行时覆盖旧快照并记录 `WARN`。
- 待执行快照只存在内存；服务端重启不恢复。

### 4.3 阶段 A：旅行前快照

事件入口只做以下动作：

```java
if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) {
    return;
}
OwnerDimensionFollowService.arm(player, event.getDimension());
```

`arm(...)` 的内部顺序：

1. 读取 `ownerDimensionFollowEnabled`；关闭时返回。
2. 用玩家当前 `ServerLevel`、目标 `ResourceKey<Level>` 和玩家位置建立快照参数。
3. 以玩家 3D 位置为中心，构造边长 `2 * radius` 的空间查询盒。
4. 只查询出发 `ServerLevel` 的已加载实体。
5. 按“位置/存活 -> 精确距离 -> capability/owner -> 档案/canonical”的廉价到昂贵顺序筛选。
6. 对每个合法候选只读查询 `RemoteSummonService.isPendingFor(...)`，记录 `remotePendingAtArm`。
7. 无候选时不创建快照；有候选时写入内存快照。
8. 不移动宠物、不改档案、不改远召状态、不加载区块。

### 4.4 阶段 B：到达后 tick 完成

在 `CommonEvents.onServerTick(...)` 的 `Phase.END` 中固定顺序：

1. `OwnerDimensionFollowService.tickIfPresent(server)`
2. `RemoteSummonService.tickIfPresent(server)`

快照状态机：

| 当前状态 | 动作 |
|---|---|
| 玩家仍处于 `fromDimension`，且 age 不超过 20 tick | 保留 |
| 玩家当前维度等于 `toDimension` | 先移除快照，再执行一次批量随行 |
| 玩家进入第三个维度 | 丢弃快照并记录 `WARN`，不移动宠物 |
| 玩家仍在出发维度但超过 20 tick | 丢弃快照，不移动宠物 |
| 玩家离线、死亡或移除 | 丢弃快照，不移动宠物 |
| 服务端停机 | 清空全部快照 |

20 tick 是普通路径的内存安全上限。普通换维度在同一逻辑调用栈完成，最多跨一个服务端 tick；末地终章返回是已声明例外。

### 4.5 逐只执行契约

每只候选按以下顺序处理：

1. 从出发 `ServerLevel#getEntity(entityUuid)` 取实体；未命中则跳过，不加载区块。
2. 复核实体仍是 `LivingEntity`、存活、未移除。
3. 复核 capability 是绒亲，`companionId` 与快照一致，owner 是玩家本人。
4. 从 `FurkinArchiveData.get(server)` 定向读取 entry，复核 `isAlive`、`isSummoned`、canonical UUID。
5. 若 `remotePendingAtArm == true` 或当前仍查得同玩家同 companion 的活跃 pending，则 `yielded++` 并跳过。
6. 复核 `FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry)`；命中则跳过并记录诊断。
7. 计算安全落点并调用共享传送重载。
8. 单只失败不阻断其它候选；批量结束后记录 `moved/yielded/failed/skipped/total`。
9. 不向聊天栏发送成功提示，不伪造远召反馈。

### 4.6 远召 pending 的业务优先级

冻结优先级：**显式远召 pending 优先，自动随行让路。**

- 快照时已有 pending：记录 `remotePendingAtArm = true`。
- 执行时已无 pending：仍然跳过，不补做随行，避免“远召先提示失败，宠物随后又出现”的矛盾反馈。
- 执行时新出现 pending：只读识别，跳过，由远召继续处理。
- 随行不调用 `cancel`，不修改 `RemoteSummonResult`，不触发远召冷却或反馈。
- 随行完成后玩家再发起远召，应自然走当前“已加载实体传送”分支，不复制实体。

### 4.7 落点规则

目标是“可复现、无随机远距传送、失败宁可留下”。

- 单只：玩家朝向正前方 1.5 格，与现有单只传送观感一致。
- 多只：以玩家 yaw 为起始角，半径 1.75 格，按 `index / total` 环形分布。
- 每个水平候选点从玩家脚点向下最多 2 格寻找非空碰撞支撑面。
- 拒绝危险体积：`NETHER_PORTAL`、`END_PORTAL`、`END_GATEWAY`、`LAVA`、`FIRE`、`SOUL_FIRE`。
- 支撑搜索或体积检查失败时尝试镜像点及替代环形点。
- 全部失败则跳过该只，记录 `NO_SAFE_LANDING`，不回退到玩家脚下，不调用随机传送，不为落点加载区块。
- 这只是 best-effort 安全检查，不承诺覆盖虚空、复杂地形或所有模组方块。

## 5. 1.20.1 落码契约

### 5.1 文件变更清单

| 文件 | 操作 | 已落码内容 |
|---|---|---|
| `src/main/java/com/wanancat/furkin/internal/config/FurkinServerConfig.java` | 修改 | 在远召配置之后、`SPEC` 之前加入两个配置 |
| `src/main/java/com/wanancat/furkin/internal/contract/OwnerDimensionFollowService.java` | 新增 | 快照、状态机、筛选、pending 让路、落点、批量处理 |
| `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java` | 修改 | 抽取显式落点的包内传送重载，原入口委托 |
| `src/main/java/com/wanancat/furkin/internal/contract/RemoteSummonService.java` | 修改 | 新增只读 pending 查询，不改现有状态机 |
| `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java` | 修改 | 旅行事件入口、tick 顺序、停机清理 |
| `README.md`、`README.zh-CN.md` | 修改 | 功能边界、配置、冷区不加载和末地例外 |
| `CHANGELOG.md`、`changelog.en.md` | 修改 | 用户可见机制和配置项 |
| `gradle.properties` | 修改 | 已推进到 `1.20.1-0.0.4.0` |

### 5.2 `OwnerDimensionFollowService` 公开形态

建议包内服务结构：

```java
public final class OwnerDimensionFollowService {
    private static final int MAX_WAIT_TICKS = 20;
    private static final double SINGLE_FORWARD_DISTANCE = 1.5D;
    private static final double MULTI_RING_DISTANCE = 1.75D;
    private static final double MAX_LANDING_DROP = 2.0D;

    private static final Map<MinecraftServer, OwnerDimensionFollowService> SERVICES =
            new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, ArmedRequest> armedByPlayer = new HashMap<>();

    public static void arm(ServerPlayer player, ResourceKey<Level> toDimension);
    public static void tickIfPresent(MinecraftServer server);
    public static void stop(MinecraftServer server);
}
```

`Candidate` 和 `ArmedRequest` 保持私有，不跨 tick 持有 `Entity`。

### 5.3 `FurkinCompanionManager` 重载边界

保留当前公开入口：

```java
public static TeleportResult teleportLoadedEntity(
        ServerPlayer player,
        FurkinArchiveEntry entry,
        LivingEntity target)
```

它继续计算玩家朝向正前方 1.5 格，然后委托给包内重载：

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

1.20.1 中重载内部使用：

```java
ServerLevel serverLevel = player.serverLevel();
if (target.level() != serverLevel) {
    Entity changed = target.changeDimension(
            serverLevel,
            new FixedTeleporter(targetX, targetY, targetZ, yRot, xRot));
    if (!(changed instanceof LivingEntity living)) {
        return TeleportResult.DIMENSION_CHANGE_FAILED;
    }
    // 后续档案位置、清坐姿、同步均使用 living
} else {
    target.teleportTo(targetX, targetY, targetZ);
}
```

成功后仍执行现有的位置刷新、清坐姿和 `SyncFurkinDataPacket`。不创建实体、不改 `companionId`、不改 canonical UUID。

### 5.4 `RemoteSummonService` 只读查询

新增一个只读入口，不创建 service、不遍历 pending、不读写 ticket：

```java
static boolean isPendingFor(MinecraftServer server, UUID playerUuid, UUID companionId)
```

语义：

- `server == null`、`playerUuid == null` 或 `companionId == null` -> `false`。
- `SERVICES.get(server)` 不存在 -> `false`。
- `byCompanion.get(companionId)` 不存在 -> `false`。
- 只有 `request.playerUuid.equals(playerUuid) && request.state != TERMINAL` 才返回 `true`。
- 服务端线程调用，复用当前类的线程断言。

这里采用包内可见入口即可；不要为了“方便”将查询改成扫描全 pending 或触发远召清理。

### 5.5 `CommonEvents` 接线

新增事件监听：

```java
@SubscribeEvent
public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
    if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) {
        return;
    }
    OwnerDimensionFollowService.arm(player, event.getDimension());
}
```

调整 tick：

```java
SkillPassiveDispatcher.onServerTick(event.getServer());
SkillRuntimeCalibrator.onServerTick(event.getServer());
OwnerDimensionFollowService.tickIfPresent(event.getServer());
RemoteSummonService.tickIfPresent(event.getServer());
```

停机时在当前 `onServerStopping(...)` 中清理随行快照，再继续现有 `RemoteSummonService.stop(...)`。不要把随行停止挪到当前的 `onServerStopped(...)` 作为主生命周期点。

## 6. 工作包拆分

| WP | 目标 | 主要改动 | 完成条件 |
|---|---|---|---|
| WP0 | 冻结口径 | 本文档；确认末地终章范围外、pending 优先、配置默认值 | 乌狸明确确认后才进入代码 |
| WP1 | 配置与共享骨架 | 两个 config、显式落点重载、只读 pending 查询 | `compileJava` 通过；现有单只远召行为不变 |
| WP2 | 阶段 A | 新服务、旅行前事件、有界空间筛选、快照 | 只快照合法已加载 canonical，不移动实体 |
| WP3 | 阶段 B | tick 状态机、逐只复核、pending 让路、落点、批量传送 | 单只/多只/坐姿/失败/取消/超时/pending 均可控 |
| WP4 | 收口与验证 | 日志、README、双 changelog、版本、runServer/runClient 证据 | 无协议/API/schema 变更，文档与实现一致 |

建议顺序严格为 `WP0 -> WP1 -> WP2 -> WP3 -> WP4`。WP2 和 WP3 不能合并成“旅行事件内直接传送”。

### 6.1 逐步验证

每个小步完成后运行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
```

顺序建议：

1. WP1a：先加两个配置项，确认旧 TOML 不迁移错误。
2. WP1b：加 pending 只读查询，确认未创建远召 service 时零副作用。
3. WP1c：抽取显式落点重载，回归现有单只传送。
4. WP2：接事件快照，只验证候选数和 `remotePendingAtArm`。
5. WP3a：实现 tick 状态淘汰与 pending 让路，验证零传送路径。
6. WP3b：接共享传送重载与落点算法，验证单只、多只、坐姿和部分失败。
7. WP4：更新文档、版本号，执行完整构建和实机矩阵。

## 7. 验证矩阵

### 7.1 功能用例

| ID | 场景 | 预期 |
|---|---|---|
| ODF-01 | 主世界 -> 地狱，2 只内圈、1 只外圈 | 2 只随行，外圈留原维度 |
| ODF-02 | 地狱 -> 主世界 | 方向反转，不依赖硬编码维度 |
| ODF-03 | 末地终章返回 | 范围外；宠物不随行，快照不泄漏 |
| ODF-04 | 半径边界等于 N | 通过 |
| ODF-05 | 半径边界大于 N | 不处理 |
| ODF-06 | 同维度普通传送 | 不触发跨维度随行 |
| ODF-07 | 旅行事件被取消 | 宠物不移动，快照超时清理 |
| ODF-08 | 玩家实际进入第三维度 | 原快照丢弃，不向错误维度传送 |
| ODF-09 | 旅行后 21 tick 未到目标维度 | 快照过期，不移动 |
| ODF-10 | 出发维度宠物区块已卸载 | 不加载、不重建、不传送 |
| ODF-11 | 其他玩家宠物、野生物、未契约实体在半径内 | 全部忽略 |
| ODF-12 | 已收回/已死亡档案异常存在实体 | canonical + 档案状态校验跳过 |
| ODF-13 | 同 companionId 重复体 | 只认 canonical；重复体跳过并诊断 |
| ODF-14 | 坐姿宠物随行 | 到达后清坐姿并跟随 |
| ODF-15 | 快照后、执行前宠物死亡/解绑/收回 | 跳过该只，其它继续，不改档案 |
| ODF-16 | 快照时或执行时已有同玩家 pending | 随行让路，不取消/改远召状态 |
| ODF-17 | 快照时 pending，随行前失败/取消 | 仍不补随行，避免矛盾反馈 |
| ODF-18 | `ownerDimensionFollowEnabled=false` | 不快照、不传送 |
| ODF-19 | 目标落点碰撞、无支撑、危险方块 | 尝试替代点；无安全点则 `NO_SAFE_LANDING`，不回退脚下 |
| ODF-20 | `changeDimension` 返回非 LivingEntity | 该只失败，档案位置不误写 |
| ODF-21 | 默认 16 格 + 密集实体 | 只做有界查询，不加载区块，记录 tick 峰值 |
| ODF-22 | 默认 3 只批量随行 | 记录迁移数、档案写入数、同步包数 |
| ODF-23 | 多名玩家同 tick 换维度 | 记录全局尖峰和跨 tick 行为 |
| ODF-24 | 半径 64 + 密集实体 | 边界性能记录，不默认放开 |
| ODF-25 | 随行后再次显式远召同一只 | 走已加载实体分支，不复制实体 |

### 7.2 工程门槛

- `compileJava` 通过。
- `build` 通过，JAR 不含临时 fixture 或 debug 类。
- `runServer` 正常到 `Done`；日志无新增 `ERROR`、`FATAL`、异常栈、注册失败或资源缺失。
- `runClient` 完成主世界、地狱、末地实际切换；检查位置、坐姿、客户端同步和档案落点。
- 静态审计确认没有新增 `LivingTickEvent`、`getChunkFuture`、`addRegionTicket`、`setChunkForced`、`FORCED` 或全实体逐 tick 扫描。
- 检索确认没有新增网络包、协议版本变化、公开 API 变化或 archive NBT 字段。
- 日志证据保存到 `D:\frukin_dev\_research\`，不提交运行日志。

### 7.3 最终命令

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 在旅行事件内提前传送 | 事件被取消后宠物与玩家分离 | 只快照；到达后 tick 复核 |
| 机械复制 1.19.2 的 `getLevel()` | 1.20.1 编译失败或上下文错误 | 使用 `serverLevel()` / `level()` 逐处核对 |
| 直接复制 1.19.2 的停机时序 | 在错误的生命周期清理，掩盖远召 ticket 问题 | 随行接入当前 `onServerStopping(...)`，远召停止顺序保持 |
| 未抽取显式落点重载 | 多只宠物只能堆在同一点 | 先重构共享传送入口，原公开入口委托 |
| pending 让路查询改动了远召状态 | 远召结果、冷却、ticket 或反馈回归 | 只读 `byCompanion`，不遍历、不 cancel、不 finish |
| 末地终章返回被当成普通路径 | 无法稳定定义行为或产生错误传送 | 保持范围外，做负向验证 |
| 落点安全检查过度承诺 | 复杂地形/模组方块仍可能危险 | 明确 best-effort，无安全点就留在原维度 |
| 半径 64 + 密集实体 | 换维度 tick 峰值 | 默认 16；候选受 `ACTIVE_LIMIT` 限制；做压力记录 |

## 9. 已确认口径

2026-09-28 乌狸确认：以下事项与 1.19.2 侧保持一致。

1. **末地终章返回范围外**：延续 1.19.2 口径，终章返回不随行，只做负向用例。
2. **默认开关和半径**：`true`、16 格、范围 1-64。
3. **坐姿宠物随行**：范围内坐姿宠物随行，到达后清坐姿并跟随。
4. **冷区不加载**：断线/卸载区块中的宠物不强行加载；可随后显式远召。
5. **显式远召 pending 优先**：快照时已有 pending，则本次旅行不补随行，即使 pending 后失败或取消。
6. **落点策略**：最多向下 2 格寻找安全支撑；找不到就跳过，不回退玩家脚下。
7. **版本推进**：落码发布时使用 `1.20.1-0.0.4.0`，不新增协议和 API。
8. **验证范围**：至少覆盖 `compileJava`、`build`、`runServer`、主/下界实机往返、末地负向路径和远召 pending 联动。

## 10. 收口标准（不等同于当前验证状态）

- `owner-dimension-follow` 在 1.20.1 使用旅行前快照 + tick 完成校验，不在 `EntityTravelToDimensionEvent` 中直接移动宠物。
- 只处理出发维度内、半径内、已加载、本人、canonical、存活的已召唤绒亲。
- 同一 companion 在快照时已有 pending，或执行时发现活跃 pending 时，只读识别并让路。
- 不新增区块加载、不解冻/重建实体、不修改数据 schema、不新增协议或公开 API。
- 单只、多只、坐姿、取消、超时、重复体、远端 pending、死亡/解绑和部分失败均有验证证据；缺少任一项时，不得把功能级矩阵标记为完成。
- 末地终章返回按范围外负向用例验证：不随行、不加载区块、快照清理。
- README、双 changelog、配置说明、版本和工作文档同步完成。
- 功能级矩阵全部具备证据后，才可把状态从“实现与启动烟测通过”改为“功能完成”；提交、推送仍由乌狸另行指示。

## 11. 实施与验证记录（2026-09-28）

### 11.1 实际改动

- 新增 `src/main/java/com/wanancat/furkin/internal/contract/OwnerDimensionFollowService.java`：旅行前快照、20 tick 完成校验、逐只 canonical 复核、远召 pending 让路、安全落点与批量传送。
- 修改 `FurkinServerConfig`：新增 `ownerDimensionFollowEnabled`（`true`）和 `ownerDimensionFollowRadius`（`16`，1-64）。
- 修改 `FurkinCompanionManager`：保留原有单只传送入口，抽取显式落点包内重载；1.20.1 使用 `player.serverLevel()` 与 `target.level()`。
- 修改 `RemoteSummonService`：新增只读 `isPendingFor(...)`；未改动 pending、ticket、超时、冷却或反馈状态机。
- 修改 `CommonEvents`：接入 `EntityTravelToDimensionEvent`，在远召 tick 前运行随行，并在 `ServerStoppingEvent` 清理随行快照。
- 同步 `README.md`、`README.zh-CN.md`、`CHANGELOG.md`、`changelog.en.md` 和 `gradle.properties` 到 `1.20.1-0.0.4.0`。
- `PROTOCOL_VERSION` 保持 `"2"`；公开 API、网络包和 archive schema 未变。

### 11.2 已执行验证

- `$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.2'` 下执行 `.\gradlew.bat compileJava --console=plain`：`BUILD SUCCESSFUL`。
- 执行 `.\gradlew.bat build --console=plain`：`BUILD SUCCESSFUL`，产物为 `build/libs/furkin-1.20.1-0.0.4.0.jar`（407915 bytes）。
- 展开产物 `META-INF/mods.toml`：展开版本为 `1.20.1-0.0.4.0`，`logoFile="logo.png"`、`logoBlur=false` 保持。
- `runServer` 启动到 `Done`，自动生成 `ownerDimensionFollowEnabled=true` 与 `ownerDimensionFollowRadius=16`；通过临时 RCON 发送 `stop`，完成 `Saving players`、三维度 `Saving chunks`、全部存储保存和 Gradle `BUILD SUCCESSFUL`。临时 RCON 配置已恢复为关闭。
- `runClient` 启动到主菜单，日志显示用户 `Dev`、Forge 47.2.0、Furkin 内置物种和资源加载；关闭窗口后输出 `Stopping!`，Gradle `BUILD SUCCESSFUL`。
- 服务端日志没有本功能引入的 `ERROR`、`FATAL` 或异常栈；出现的是原有 Forge 版本提示、离线模式警告和正常配置迁移提示。

### 11.3 真实客户端核心补测记录（2026-09-28）

| 用例 | 结果 | 证据 |
|---|---|---|
| 主世界 -> 地狱批量随行 | 通过。3 只受 `ACTIVE_LIMIT=3` 限制的已加载绒亲随行，`moved=3`。 | `latest-core.log`：`18:42:41` 的 `owner dimension follow processed ... from=minecraft:overworld to=minecraft:the_nether moved=3`。 |
| 地狱 -> 主世界批量随行 | 通过。反向路径同样 `moved=3`，不依赖硬编码维度。 | `latest-core.log`：同轮后续 `from=minecraft:the_nether to=minecraft:overworld moved=3`。 |
| 真实下界传送门 | 通过。玩家进入自建下界门后，主世界 -> 地狱 `moved=3`，其中一条日志出现落点调整。 | `latest-core.log`：`owner dimension follow landing adjusted` 与对应 `processed ... moved=3`。 |
| 坐姿随行 | 通过。测试绒亲出发时 `sitting=true`，到达目标维度后 `sitting=false`。 | `latest-core.log`：同一 companion 的目标维度 report 与 `Furkin teleported` 记录。 |
| 无安全落点负向路径 | 通过。目标点下方超过 2 格或危险体积时，三只均记录 `NO_SAFE_LANDING`，最终 `moved=0 failed=3`，宠物留在原维度。 | `debug-core.log`：`18:43:43` 三条 `landing blocked` 与随后的 `processed ... moved=0 failed=3`。 |
| 同维度普通传送 / 附近无合法候选 | 通过。未新增 `owner dimension follow armed/processed` 日志，宠物状态未变化。 | `latest-core.log`：测试段未出现新的随行日志。 |
| 显式远召 pending 优先 | 通过。用临时夹具建立已加载 canonical 的 synthetic pending；快照为 `candidates=1 pending=1`，随行记录 `yielded=1 moved=0`，随后只有 `RemoteSummonService` 完成 `COMPLETED_TELEPORT`。 | `D:\frukin_dev\_research\odf-1.20.1-evidence-20260928\pending-and-end-clean-20260928.debug.log`：`19:05:31.658`、`19:05:32.197`、`19:05:32.217`。 |
| 末地终章返回负向路径 | 通过。干净世界末地放置出口门进入终章后，快照只建立 `from=the_end to=overworld candidates=1 pending=0`，随后 `dropped: reason=player-unavailable`；没有 `processed/moved`，没有随行 `Furkin teleported`。 | `pending-and-end-clean-20260928.debug.log`：`19:07:29.295` 与 `19:07:29.296`。 |

本轮正式产品路径未发现新增 `ERROR`、`FATAL`、异常栈或注册失败。测试期间曾有一次临时夹具直接调用 `changeDimension` 的错误尝试，破坏了玩家区块跟踪；该失败仅存在于临时夹具，不计入产品结论，随后改为公开 `ServerPlayer#teleportTo(ServerLevel, ...)` 重新验证并通过。

### 11.4 服务端边界与性能补测记录（2026-09-28）

使用环境变量 `FURKIN_FIXTURE_ODF_BOUNDARY=1` 激活临时 `OdfBoundaryFixture`，在隔离世界 `odf-boundary-world` 中运行 `runServer`。夹具使用 FakePlayer、受控 canonical 绒亲和受控野生实体，不依赖真实客户端；验证源码副本保存在 `D:\frukin_dev\_research\odf-1.20.1-evidence-20260928\OdfBoundaryFixture.java`，正式源码在验证后已删除。

| 用例 | 结果 | 关键证据 |
|---|---|---|
| 半径精确边界 | 通过。距离 16.00 的绒亲随行，16.01 的不随行。 | `radius-16-follows=PASS`、`radius-16.01-stays=PASS`。 |
| 功能总开关关闭 | 通过。`ownerDimensionFollowEnabled=false` 时玩家换维度，绒亲留在出发维度且无快照。 | `disabled-no-follow=PASS`。 |
| 同维度传送 | 通过。同一维度不建立快照。 | `same-dimension-no-snapshot=PASS`。 |
| 事件在随行前取消 | 通过。HIGHEST 优先级取消后，原版旅行中止、无快照、绒亲不移动。 | `cancel-before-arm-no-snapshot=PASS`。 |
| 事件在快照后取消并超时 | 通过。先形成 `candidates=1` 快照，事件随后被取消；21 tick 后记录 `owner dimension follow expired`，快照清空，绒亲仍在出发维度。 | `cancel-after-arm-snapshot-present=PASS`、`cancel-after-arm-expired=PASS`。 |
| 玩家实际进入第三维度 | 通过。arm 到 Nether 后玩家实际进入 End，记录 `dropped expected=minecraft:the_nether actual=minecraft:the_end`，不向错误维度传送。 | `third-dimension-dropped=PASS`。 |
| 多只部分失败 | 通过。快照后一只档案状态失效，另一只正常随行；结果为 `moved=1 failed=1 total=2`，失败不阻断幸存者。 | `partial-failure-isolated=PASS`。 |
| pending 在 arm 后失败/取消 | 通过。arm 时记录 pending，随后 pending 以 `CANCELLED reason=STATE_CHANGED` 结束；随行仍 `moved=0 yielded=1`，不补做跟随。 | `pending-at-arm-failure-still-yields=PASS`、对应 `remote summon completed ... CANCELLED ... STATE_CHANGED`。 |
| pending 在执行时才出现 | 通过。执行时只读发现新的活跃 pending，随行 `moved=0 yielded=1`，不取消或改写 pending。 | `pending-at-execution-yields=PASS`。 |
| canonical 重复体冲突 | 通过。装入同 companionId 的第二实体后，处理记录 `blocked ... reason=loaded-duplicate`，canonical 与重复体均留在出发维度，避免误传送。 | `duplicate-not-followed=PASS`。 |
| 综合夹具门槛 | 通过。`ODF_FIXTURE_BOUNDARY_DONE checks=13 failures=0`。 | `odf-boundary-perf-final-20260928.log`。 |

服务端压力夹具结果如下。时间由夹具在服务端线程内用 `System.nanoTime()` 采样；`arm` 为旅行前快照，`follow` 为到达后的批量复核/传送 tick，`total` 为两者加 FakePlayer 维度切换。它不是客户端帧率或真实网络 fanout。

| 场景 | 样本 | `arm` P50/P95 | `follow` P50/P95/max | `total` P50/P95/max | 移动数 |
|---|---:|---:|---:|---:|---:|
| `cold-r16-a20`：半径 16、20 只、80 野生 | 2 | 0.419/0.527 ms | 7.043/7.709/7.709 ms | 9.043/9.133/9.133 ms | 40 |
| `hot-r16-a20`：半径 16、20 只、80 野生 | 8 | 0.329/8.867 ms | 8.778/14.073/14.073 ms | 12.446/23.657/23.657 ms | 160 |
| `radius64`：半径 64、20 只、150 野生 | 6 | 0.295/0.382 ms | 4.400/5.148/5.148 ms | 7.093/7.983/7.983 ms | 120 |
| `default3`：默认活跃上限 3 | 10 | 0.218/0.382 ms | 1.138/1.838/1.838 ms | 4.726/6.122/6.122 ms | 30 |
| `12x3`：12 个 FakePlayer 同 tick，各 3 只 | 4 | 0.650/0.715 ms | 7.980/8.173/8.173 ms | 21.761/23.981/23.981 ms | 144 |

测试日志没有 `ERROR`、`FATAL` 或异常栈。夹具初始化铺平台/生成区块时出现过一次 `Can't keep up`，发生在正式产品路径之外；压力数据按上表独立采样记录。

### 11.5 尚未执行的验证缺口

- 真实客户端帧率与真实多客户端网络 fanout 仍未独立测量；当前 `12x3` 数据只能证明服务端状态机与批量处理开销，不能替代客户端渲染或网络负载结论。
- 复杂地形、虚空、极端碰撞组合下的落点安全仍是 best-effort，范围按 §1.3 和 §8 保持不变；本轮没有扩大为“所有地形保证安全”的承诺。
- 以上两项与 1.19.2 工作文档保留的缺口一致，不阻止当前服务端功能矩阵和真实客户端核心矩阵判定为通过。

### 11.6 状态

- 删除全部临时夹具与测试钩子后执行 `compileJava` 与 `clean build`：均为 `BUILD SUCCESSFUL`；产物 `build/libs/furkin-1.20.1-0.0.4.0.jar`（407915 bytes，SHA-256 `3BDA3EC15AE495A86B2E97CAECB9C9017F1C15B5DF2FC9D10432FFEB02BF30E1`）。
- 展开 `META-INF/mods.toml` 确认版本为 `1.20.1-0.0.4.0`，`logoFile="logo.png"`、`logoBlur=false` 保持，文件非 ASCII 字节数为 0；JAR 中无 `OdfBoundaryFixture`、`OdfVerificationCommand` 或 `ODF_FIXTURE` residue。
- 最新无夹具构建未改变公开 API、网络协议 `2` 或 archive schema；`RemoteSummonService` 只保留正式需要的只读 pending 查询。
- `run\server.properties` 已从备份恢复为 `level-name=world`、`enable-rcon=false`、空 RCON 密码；临时世界 `run\odf-boundary-world` 已删除。
- 删除夹具后的正式源码在隔离世界 `odf-smoke-final-20260928` 执行无夹具 `runServer`，正常到达 `Done (13.099s)`；加载 `Furkin` 内置物种与 13 个技能，日志无 `ERROR`、`FATAL`、异常栈或注册失败。证据：`D:\frukin_dev\_research\odf-1.20.1-evidence-20260928\odf-final-smoke-20260928.log`。
- 烟测后已恢复 `run\server.properties` 并删除 `run\odf-smoke-final-20260928`；备份文件未残留。
- 未提交、未推送。提交与推送等待乌狸明确指示。