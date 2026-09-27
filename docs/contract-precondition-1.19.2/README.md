# Furkin 1.19.2 契约血量前置条件工作流

- 日期：2026-09-27
- 分支：`mc1.19.2`
- 基线：`206a92732c843a7f0aeea72fa7a3a6287b01356d`；当前 `remote-summon-1.19.2` 已收口于 `206a9273`；本包与远召按默认同批收口为 `1.19.2-0.0.3.0`
- 状态：已实施；核心服务端夹具 33/33 通过；人工客户端 A～F 通过；原版 EnderDragon 多部件已验证；TF/Hydra 为 `ENV BLOCKED`；待乌狸确认提交/推送
- 参考实现：`D:\frukin_dev\frukin_1_20_1\docs\contract_precondition_workflow_v2\`
- 参考基线：1.20.1 分支 `5ad0924`
- 适用运行时：Minecraft 1.19.2 / Forge 43.2.0 / Java 17

> 本功能包移植的是 1.20.1 已完成并验证的**设计语义**，不是整体合并 1.20.1 代码。所有接收者类型、世界访问、距离判断和多部件事件按 1.19.2 的真实公开 API 重新落点。

## 文档索引

- [设计摘要与运行口径](README.md)
- [完整实施计划](IMPLEMENTATION_PLAN.md)
- [独立可行性复查](FEASIBILITY_AUDIT.md)
- [代码级执行契约](EXECUTION_CONTRACT.md)
- [验证矩阵](VERIFICATION_MATRIX.md)
- [Chest Cavity 阈值调研](RESEARCH_CHEST_CAVITY.md)

## 0. 结论摘要

本工作流解决“哪些已注册生物有资格被契约”，不改变“契约后是否能完整操控该生物”。

目标行为与 1.20.1 v2 保持一致：

- 按 `Enemy > NeutralMob > 其他` 固定优先级分类。
- 每个分类各有“最大生命百分比”和“绝对生命值”两个门槛，使用 OR：任一分支通过即可契约。
- 最终默认值：
  - Enemy：`30%` 或 `4` 点生命值。
  - NeutralMob：`50%` 或 `8` 点生命值；满血 8 点原版狼可直接契约。
  - 其他：`100%`，绝对分支禁用；正常满血生物不被额外限制。
- 发起命名请求和确认命名共用同一套权威校验；命名期间回血会被二次拒绝。
- 只有 `HEALTH_TOO_HIGH` 显示生命值提示并取消实体交互；`INVALID_HEALTH` 静默拒绝。
- Forge `PartEntity` 交互解析到父 `LivingEntity`，兼容 Twilight Forest Hydra 一类主实体不可直接拾取的生物。
- 未注册物种仍不可契约；生命值门槛不会扩大物种白名单。
- 不修改网络包字段、字段顺序、方向或处理器语义，`PROTOCOL_VERSION` 保持 `"2"`。

## 1. 判定口径

| 优先级 | 分类条件 | 分类 | 百分比配置 | 绝对值配置 |
|---|---|---|---|---|
| 1 | `target instanceof Enemy` | Enemy | `contractEnemyHealthPercent` | `contractEnemyHealthAbsolute` |
| 2 | 非 Enemy，且 `target instanceof NeutralMob` | NeutralMob | `contractNeutralHealthPercent` | `contractNeutralHealthAbsolute` |
| 3 | 以上均不满足 | 其他 | `contractOtherHealthPercent` | `contractOtherHealthAbsolute` |

计算规则：

```text
healthPercent = currentHealth * 100.0 / maxHealth
percentPass   = percentThreshold >= 100 || healthPercent <= percentThreshold
absolutePass  = absoluteThreshold > 0 && currentHealth <= absoluteThreshold
healthPass    = percentPass || absolutePass
```

边界语义：

- 使用 `<=`，恰好等于门槛时通过。
- `currentHealth` 与 `maxHealth` 必须是有限正数；否则返回 `INVALID_HEALTH`，不显示“生命值过高”。
- 由于 `isAlive()` 先于健康检查，`currentHealth <= 0` 或 NaN 通常会先返回 `INVALID_TARGET`；`INVALID_HEALTH` 主要防御异常第三方实体返回的非法字段。1.19.2 的 `MAX_HEALTH` 会被 sanitize/clamp，验收夹具必须用一次性子类覆写 `getAttributeValue(Attribute)`，不能用 `setBaseValue(NaN/0)`。
- `absoluteThreshold = 0` 禁用绝对分支。
- `percentThreshold = 100` 使正常满血目标不会被该分类的百分比限制。
- 不计算吸收量、护甲或减伤。
- 分类不看怒气、当前目标或是否攻击过玩家。
- 不用 `MobCategory`，也不把 `Monster` 当作唯一的敌意判据。

## 2. 1.19.2 API 事实与迁移差异

以下符号已用 1.19.2 mapped official jar 的 `javap` 核实：

| 符号 | 1.19.2 结论 | 对本功能的影响 |
|---|---|---|
| `net.minecraft.world.entity.monster.Enemy` | 存在的标记接口 | 可作为第一优先级分类条件 |
| `net.minecraft.world.entity.monster.Monster` | `Monster implements Enemy` | 所有原版 Monster 归入 Enemy |
| `net.minecraft.world.entity.NeutralMob` | 独立行为接口 | Wolf 等中立生物可作为第二优先级 |
| `EnderMan` | `extends Monster implements NeutralMob` | 同时命中时由 Enemy 优先 |
| `net.minecraftforge.entity.PartEntity` | `PartEntity<T extends Entity>`，公开 `T getParent()` | 可安全解析多部件父实体 |
| `net.minecraft.world.entity.boss.enderdragon.EnderDragon` / `net.minecraft.world.entity.boss.EnderDragonPart` | Dragon 为 `Mob implements Enemy`；Part 为 `PartEntity<EnderDragon>` 且 `isPickable()` 返回 true | 提供原版强制多部件验证样本 |
| `ServerPlayer#getLevel()` | 直接返回 `ServerLevel` | 不再写 `instanceof ServerLevel` 判断 |
| `LivingEntity#getMaxHealth()` | `public final float`；读取经 sanitize/clamp 的 `MAX_HEALTH`；`setBaseValue(NaN/0)` 不能产出非法值 | `INVALID_HEALTH` 夹具必须用一次性子类覆写公开 `getAttributeValue(Attribute)` |
| `Entity#getLevel()` | 返回 `Level` | 目标世界一致性用 `target.getLevel()` |
| `Player#canReach(Entity, double)` | 1.19.2 不存在 | 沿用当前 4 格距离平方等价判断 |

必须遵守的迁移差异：

1. 不把 1.20.1 的 `player.serverLevel()`、`target.level()` 带入 1.19.2。
2. `ServerPlayer#getLevel()` 已经是 `ServerLevel`，不要写无效的 `instanceof` 模式判断。
3. 距离校验继续使用 `player.distanceToSqr(target) > CONTRACT_MAX_DISTANCE * CONTRACT_MAX_DISTANCE`，其中 `CONTRACT_MAX_DISTANCE = 4.0D`。
4. 多部件解析使用：

```java
Entity interactionTarget = event.getTarget();
if (interactionTarget instanceof PartEntity<?> part
        && part.getParent() instanceof LivingEntity parent) {
    interactionTarget = parent;
}
```

5. 事件取消仍作用于原始事件；解析出的父实体只作为业务目标，不改写事件目标对象。
6. `LivingEntity#getMaxHealth()` 是 final，但关联的 `getAttributeValue(Attribute)` 是公开且非 final；仅一次性夹具可用子类开关构造非法 `maxHealth`，生产代码不得改写访问路径。

### 2.1 已核实分类样本

| 生物 | 1.19.2 类型关系 | Enemy | NeutralMob | 本规则分类 |
|---|---|---:|---:|---|
| 原版僵尸 | `Zombie extends Monster` | 是 | 否 | Enemy |
| 原版末影人 | `EnderMan extends Monster implements NeutralMob` | 是 | 是 | Enemy |
| 原版狼 | `Wolf extends TamableAnimal implements NeutralMob` | 否 | 是 | NeutralMob |
| 原版猫 | `Cat extends TamableAnimal` | 否 | 否 | 其他 |
| TF Hydra | 需按 1.19.2 TF 实际类型复核；预期非 Monster 但实现 Enemy | 待实测 | 待实测 | Enemy |

第三方实体的真实类型关系必须以实际 1.19.2 依赖和 `javap` 结果为准，不能用 1.20.1 的存在性推断。

## 3. 当前 1.19.2 实现缺口

| 区域 | 当前状态 | 目标状态 |
|---|---|---|
| `FurkinServerConfig` | 只有 `activeLimit` 等既有配置 | 增加六项服务端 `DoubleValue` |
| `FurkinContractHandler#tryContract` | 返回 `boolean` | 返回 `ContractCheckResult` |
| `FurkinContractHandler#passesContractChecks` | 返回 `boolean`，无健康判定 | 改为 `checkContract(...)` 返回结果枚举，并在主手校验后执行健康判定 |
| `confirmContract` | 先跑共享边界，再检查快捷栏和名字 | 先校验会话目标、快捷栏、主手和名字，再跑共享边界，避免无效确认触发提示 |
| `CommonEvents#onEntityInteract` | 直接把 `event.getTarget()` 当 `LivingEntity` | 先解析 `PartEntity` 父实体 |
| 原版交互取消 | 只在契约成功时取消 | `PASSED` 或 `HEALTH_TOO_HIGH` 都取消 |
| 中英文语言 | 无生命值门槛文案 | 新增百分比和百分比 + 绝对值两条键 |
| README / CHANGELOG | 未记录本功能 | 实施和发布切片完成时同步更新 |

当前代码位置：

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java`
- `src/main/java/com/wanancat/furkin/internal/config/FurkinServerConfig.java`
- `src/main/resources/assets/furkin/lang/en_us.json`
- `src/main/resources/assets/furkin/lang/zh_cn.json`

## 4. 冻结设计

1. 共享结果模型使用 `internal.contract.FurkinContractHandler.ContractCheckResult`，不进入公开 API。
2. 结果至少包含：

```text
PASSED
INVALID_TARGET
UNREGISTERED
ALREADY_COMPANION
OWNED_BY_OTHER
OUT_OF_REACH
ACTIVE_LIMIT
INVALID_HAND
INVALID_HEALTH
HEALTH_TOO_HIGH
```

3. 逻辑校验顺序固定为：

```text
玩家目标 -> 同维度/存活/未移除 -> 已注册物种 -> capability/未契约
-> TamableAnimal 归属 -> 距离 -> 活跃上限 -> 主手 -> 生命值
```

4. `tryContract` 只有在结果为 `PASSED` 时才创建待确认会话并发送命名包。
5. 只有 `HEALTH_TOO_HIGH` 在 `tryContract` 中发送生命值提示；其他失败不发送健康文案。
6. `confirmContract` 先解析并校验会话、实体身份、快捷栏、主手内容和名字，再执行共享校验。
7. 确认阶段再次得到 `HEALTH_TOO_HIGH` 时发送同一提示，但不建档、不扣物品、不改 capability、名字或 AI。
8. `executeContract` 不复制第二套健康规则，只在权威校验通过后进行最终写入。
9. 健康失败时取消原版右键行为，避免狼切换坐姿等副作用。
10. 分类阈值只影响新契约，不追溯已有绒亲，也不在契约成功后持续检定。

## 5. 实施落点

### 5.1 配置

`FurkinServerConfig` 新增六项：

| 键 | 范围 | 最终默认值 |
|---|---:|---:|
| `contractEnemyHealthPercent` | 0–100 | 30.0 |
| `contractEnemyHealthAbsolute` | 0–1024 | 4.0 |
| `contractNeutralHealthPercent` | 0–100 | 50.0 |
| `contractNeutralHealthAbsolute` | 0–1024 | 8.0 |
| `contractOtherHealthPercent` | 0–100 | 100.0 |
| `contractOtherHealthAbsolute` | 0–1024 | 0.0 |

Forge 不会用新的默认值覆盖已有配置文件中的旧值。新增键会在加载配置时补入；若测试必须确认默认值，应先备份并移除本地 `furkin-server.toml`，或手动改为表中值。

### 5.2 契约处理器

- 增加 `Enemy`、`NeutralMob`、`DecimalFormat`、`DecimalFormatSymbols`、`Locale` 导入。
- 增加 `ContractCheckResult`。
- 把 `passesContractChecks(...)` 改为 `checkContract(...)`。
- 新增 `checkHealth(...)`、`healthGateFor(...)`、`sendHealthBlockedMessage(...)`、`formatThreshold(...)` 和内部 `HealthGate`。
- 保留现有 `PendingContract`、TTL、单次消费、主手快照和 `executeContract` 的最终写入顺序。

### 5.3 事件层

- 在 `onEntityInteract` 的 `LivingEntity` 判断前解析 `PartEntity` 父实体。
- 契约分支改用 `ContractCheckResult`。
- 仅对 `PASSED` 或 `HEALTH_TOO_HIGH` 执行：

```java
event.setCancellationResult(InteractionResult.SUCCESS);
event.setCanceled(true);
```

- 不扩大 `UNREGISTERED`、`OWNED_BY_OTHER`、`OUT_OF_REACH` 等既有失败的取消范围。

### 5.4 文案

中英文同步新增：

- `furkin.msg.contract_health`
- `furkin.msg.contract_health_with_absolute`

阈值使用服务端格式化后的可读字符串；判定始终使用原始 double，不把展示层舍入结果写回业务逻辑。

## 6. 协议、版本与发布边界

- `com.wanancat.furkin.api` 不变，`MAJORAPI` 保持 `0`。
- 不新增网络包、不改字段或处理器语义，`PROTOCOL_VERSION` 保持 `"2"`。
- 这是新增机制，发布切片应递增 `MINOR`。
- `remote-summon-1.19.2` 已在 `206a9273` 收口；本包与远召按默认同批收口，工作区版本为 `1.19.2-0.0.3.0`。
- 发布口径已按默认执行：本功能与已收口的远召同批发布，`gradle.properties` 已升到 `1.19.2-0.0.3.0`；提交/推送前仍需乌狸确认。
- 未采用远召单独发布方案；若后续要拆分发布，需先回退当前 `1.19.2-0.0.3.0` 收口并重新生成两份 changelog。
- 两份 changelog、两份 README、版本号和最终 JAR 已在工作区完成发布收口，但尚未提交。

## 7. 验证门槛

核心验证至少包含：

- `compileJava`
- `build`
- `runServer`
- `runClient`
- 检查 `run/logs/latest.log` 无新增项目相关 `ERROR`、`FATAL`、异常栈或资源缺失
- 服务端夹具或实机覆盖分类、双阈值、等于边界、禁用分支和确认阶段二次拒绝
- 客户端实机覆盖提示文案、原版交互取消和命名界面
- 原版 `EnderDragon.head` 多部件事件验证必须通过；它覆盖 `PartEntity -> LivingEntity parent` 的核心路径
- 1.19.2 兼容的第三方多部件桥接为增强项；若环境无法建立，必须明确记为残余验证债务，不能宣称 Hydra 已通过

完整步骤、代码级落地和逐项证据模板见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)、[EXECUTION_CONTRACT.md](EXECUTION_CONTRACT.md) 与 [VERIFICATION_MATRIX.md](VERIFICATION_MATRIX.md)。

## 8. 已知边界与残余风险

- 非 `TamableAnimal` Enemy 仍只按弱支持预期：可建档、收回和重新召唤，不保证跟随、停战、护主或主动索敌。
- `FurkinCombatMode.AGGRESSIVE` 若以 `Monster.class` 为目标类型，不会自动覆盖“实现 Enemy 但不继承 Monster”的实体；该问题不在本功能包内顺手修改。
- 不提供按物种覆盖门槛；当前只有三种分类级配置。
- `INVALID_HEALTH` 在正常原版属性路径上不可由 `MAX_HEALTH` base 值触发；它是对异常/第三方实体返回非法生命字段的防御分支，验收依赖一次性测试子类。
- 目标卸载后重载、重启后的旧确认包、实体 ID 复用等属于既有契约权威链的残余风险，本功能包不假装解决。
- 多部件事件解析到父实体后，距离检查也按父实体中心计算；极大尺寸实体存在“部件可点但父实体中心超过 4 格”的几何边界，本包记录为残余风险，不静默放宽距离。
- 远程召唤已在 `206a9273` 收口；本包已基于该提交实施，只改本包列出的共享文件，未回退或重写远召功能。
- 本包已在 1.19.2 / Forge 43.2.0 完成 `clean build`、`runServer`、`runClient` 和一次性服务端夹具验证；真实 GUI 截图与 TF/Hydra 压力验证仍是未闭边界，详见 `VERIFICATION_MATRIX.md`。