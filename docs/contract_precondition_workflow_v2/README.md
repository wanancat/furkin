# 契约绒亲前置条件工作流 v2

> 状态：已实现；`compileJava`、`build`、`runServer` 与客户端满血狼 `8/8` 实际契约复测通过；Twilight Forest 压力矩阵沿用 `30% / 4` 基线结果。
> 复核日期：2026-09-25。
> 源码基线：分支 `mc1.20.1`，提交 `35d931c`；当前工作区含 NeutralMob 默认值 `50% / 8` 调整（未提交）。
> 发布版本：`1.20.1-0.0.3.0`。
> 适用版本：Minecraft 1.20.1 / Forge 47.2.0。
> 取代关系：本文取代 `docs/code_review_1.20.1-0.0.2.0/contract_precondition_workflow.md`；旧文仅保留历史设计过程。
> 验证依据：`compileJava`、`build`、`runServer`；`50% / 8` 客户端复测满血狼并成功契约；`30% / 4` 基线的 `runClient` 与 Twilight Forest 压力验证用于共享路径。

## 0. 结论摘要

本工作流解决的是“哪些已注册生物有资格被契约”，不是“契约后是否能完整操控该生物”。

已落地内容：

- 按 `Enemy > NeutralMob > 其他` 的固定优先级分类。
- 每个分类各有“最大生命百分比”和“绝对生命值”两个门槛，使用 OR：任一分支通过即可契约。
- 默认值：
  - Enemy：`30%` 或 `4` 点生命值。
  - NeutralMob：`50%` 或 `8` 点生命值；满血 8 点原版狼可直接契约。
  - 其他：`100%`，绝对分支禁用；正常满血生物不被额外限制。
- 发起命名请求和确认命名两个阶段共用同一套权威校验；命名期间回血会被二次拒绝。
- 只有 `HEALTH_TOO_HIGH` 会显示生命值提示并取消实体交互；`INVALID_HEALTH` 静默拒绝。
- 支持 Forge 多部件生物：事件目标是 `PartEntity` 时解析到其父实体，Twilight Forest Hydra 等主实体不可直接拾取的生物可以正常契约。
- 未注册物种仍不可契约；生命值门槛不会扩大物种白名单。

协议版本仍为 `2`。本次没有修改网络包字段、字段顺序、方向或处理器语义。

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
- `absoluteThreshold = 0` 禁用绝对分支。
- `percentThreshold = 100` 使正常满血目标不会被该分类的百分比限制。
- 不计算吸收量、护甲或减伤。
- 分类不看怒气、当前目标或是否攻击过玩家。
- 不用 `MobCategory`，也不把 `Monster` 当作唯一的敌意判据。

## 2. 官方接口复核

### 2.1 Enemy

`net.minecraft.world.entity.monster.Enemy` 是无抽象行为的标记接口。1.20.1 中 `Monster` 实现该接口，因此所有 `Monster` 都是 `Enemy`，但第三方实体可以不继承 `Monster` 而直接实现 `Enemy`。

Twilight Forest 的 `Hydra extends Mob implements Enemy` 就是真实非 `Monster` Enemy 样本。

### 2.2 NeutralMob

`net.minecraft.world.entity.NeutralMob` 是独立行为接口。若实体同时实现 `Enemy` 与 `NeutralMob`，仍由 Enemy 分支优先处理。

### 2.3 已核实示例

| 生物 | 类型关系 | Enemy | NeutralMob | 本规则分类 |
|---|---|---:|---:|---|
| 原版僵尸 | `Zombie extends Monster` | 是 | 否 | Enemy |
| 原版末影人 | `EnderMan extends Monster implements NeutralMob` | 是 | 是 | Enemy |
| 原版狼 | `Wolf extends TamableAnimal implements NeutralMob` | 否 | 是 | NeutralMob |
| 原版猫 | `Cat extends TamableAnimal` | 否 | 否 | 其他 |
| TF Hydra | `Hydra extends Mob implements Enemy` | 是 | 否 | Enemy |
| TF Alpha Yeti | `AlphaYeti extends Monster` | 是 | 否 | Enemy |
| TF Boar | `Boar extends Animal` | 否 | 否 | 其他 |

## 3. 实现落点

### 3.1 配置

`internal.config.FurkinServerConfig` 新增六项服务端 `DoubleValue`：

| 配置键 | 范围 | 默认值 | 语义 |
|---|---:|---:|---|
| `contractEnemyHealthPercent` | 0–100 | 30.0 | Enemy 百分比门槛 |
| `contractEnemyHealthAbsolute` | 0–1024 | 4.0 | Enemy 绝对门槛；0 禁用 |
| `contractNeutralHealthPercent` | 0–100 | 50.0 | NeutralMob 百分比门槛 |
| `contractNeutralHealthAbsolute` | 0–1024 | 8.0 | NeutralMob 绝对门槛；0 禁用 |
| `contractOtherHealthPercent` | 0–100 | 100.0 | 其他分类百分比门槛；100 不限制 |
| `contractOtherHealthAbsolute` | 0–1024 | 0.0 | 其他分类绝对门槛；0 禁用 |

单人世界的配置路径为存档下的 `serverconfig/furkin-server.toml`。Forge 不会用新默认值覆盖已有配置文件；需要删除该文件让其重新生成，或手动把 NeutralMob 两项改为 `50.0` / `8.0`。

### 3.2 共享结果模型

`FurkinContractHandler.ContractCheckResult` 区分：

- `PASSED`
- `INVALID_TARGET`
- `UNREGISTERED`
- `ALREADY_COMPANION`
- `OWNED_BY_OTHER`
- `OUT_OF_REACH`
- `ACTIVE_LIMIT`
- `INVALID_HAND`
- `INVALID_HEALTH`
- `HEALTH_TOO_HIGH`

该枚举位于 `internal` 包，不属于公开 API。共享校验顺序为：

1. 目标不能是玩家。
2. 目标必须同维度、存活、未移除。
3. 物种必须已注册。
4. capability 必须存在，且目标尚未契约。
5. `TamableAnimal` 的既有主人必须是当前玩家。
6. 玩家必须能在 3 格内触达。
7. 活跃上限必须未满。
8. 主手必须是 `FurkinContractItem`。
9. 最后执行分类与生命值门槛。

生命值检查放在主手检查之后，保证空手或错误物品不会被误报为“生命值过高”；活跃上限已满时也不会显示生命值提示。

### 3.3 发起与确认

`tryContract`：

1. 清除该玩家旧 pending 会话。
2. 执行共享校验。
3. `HEALTH_TOO_HIGH` 时发送 action bar 提示并返回结果。
4. 其他失败直接返回。
5. 通过后记录目标 UUID、实体 ID、维度、时间戳、快捷栏槽位和主手副本。
6. 发送 `RequestContractNamePacket(entityId)`。

`confirmContract`：

1. 原子消费 pending 会话。
2. 校验 TTL、维度、实体 ID。
3. 按目标 UUID 重新取得实体。
4. 校验快捷栏、主手堆叠和名字。
5. 再次执行同一套共享校验。
6. 只有 `PASSED` 才调用 `executeContract`，因此发起后回血也会被拒绝。

`executeContract` 不再重复实现生命值分类；它只保留能力、未契约、活跃上限、AI 初始化和最终写入所需的局部防御。

### 3.4 多部件实体

Forge 的 `PlayerInteractEvent.EntityInteract` 可能以 `PartEntity` 为目标；部分模组的主实体又故意不可拾取，只能点击部件。

`CommonEvents` 现在执行：

```java
if (interactionTarget instanceof PartEntity<?> part
        && part.getParent() instanceof LivingEntity parent) {
    interactionTarget = parent;
}
```

随后契约、喂食、面板与收回路径统一使用父实体。该处理使用 Forge 公开的 `PartEntity#getParent()`，不反射、不依赖具体模组。

### 3.5 文案

中英文同步新增：

- `furkin.msg.contract_health`
- `furkin.msg.contract_health_with_absolute`

阈值使用服务端格式化后的可读字符串；判定始终使用原始 double，不把展示层舍入结果写回业务逻辑。

## 4. 原版交互语义

- `PASSED`：已发出命名请求，取消实体交互，防止同一右键继续触发原版行为。
- `HEALTH_TOO_HIGH`：显示生命值提示，并取消实体交互，例如避免狼因原版右键切换坐姿。
- `INVALID_HEALTH`：静默拒绝，不显示错误提示。
- 未注册、错误 owner、超距、已契约、活跃上限等既有失败：保持原行为，不因本次改动扩大取消范围。
- 多部件实体被解析为父实体后，取消结果作用于原始交互事件，部件转发链不会继续执行。

## 5. 验证结果

### 5.1 构建与服务端

- `compileJava`：通过。
- `build`：通过。
- `runServer`：启动成功，无项目相关 `ERROR` / `FATAL` / 异常栈。
- 主仓库 `runClient`：`50% / 8` 满血狼复测通过并成功契约。
- 协议保持 `2`；未修改任何网络包结构。

### 5.2 主仓库客户端矩阵

| 场景 | 结果 |
|---|---|
| 满血狼 8/8 右键契约 | 通过绝对门槛，进入命名窗口并成功契约；恢复 0.0.2.0 的满血捕捉体验 |
| 狼 4/8，恰好 50% | 未单独复测；`4 <= 4` 且 `4 <= 8`，同一判定分支通过 |
| 中性绝对分支临时改为 0 | 旧 `30% / 4` 基线验证过禁用分支；`50% / 8` 下 8/8 狼按百分比判定预期拒绝 |
| 满血猫 | 其他分类默认 100%，进入命名窗口 |
| 未注册牛 | 无生命值提示，不接管原版交互 |
| 命名期间回血后确认 | 既有 `30% / 4` 基线已验证二次校验拒绝；本次仅改阈值，未改确认路径 |

### 5.3 Twilight Forest 压力矩阵

一次性桥接模组成功注册 `82 / 82` 个 Twilight Forest `EntityType`，Furkin 生产源码未新增内置测试物种。

| 场景 | 结果 |
|---|---|
| Hydra 满血 360/360，非 `Monster` 的 Enemy | 拒绝并显示生命值提示 |
| Hydra 108/360，恰好 30% | 进入命名窗口 |
| Hydra 4/360，绝对分支 | 进入命名窗口 |
| Hydra 完成真实契约 | 建档成功，物品正确减少 |
| Hydra 收回后重新召唤 | 状态与档案正常，无重复或部件残留 |
| Alpha Yeti 满血 200/200，标准 Monster | 拒绝 |
| Alpha Yeti 60/200，恰好 30% | 进入命名窗口 |
| Boar 10/10，其他分类 | 默认 100%，进入命名窗口 |
| Hydra 100/360 进入命名后回血到 200/360 再确认 | 二次校验拒绝，不新增档案、不扣物品 |
| 压力客户端日志 | 无项目相关 `ERROR` / `FATAL` / 异常栈 |

Hydra 的实测同时证明了 `PartEntity -> LivingEntity parent` 兼容层有效，因为 Hydra 主实体 `isPickable()` 返回 `false`，只能通过部件交互。

## 6. 行为边界与残余风险

- 非 `TamableAnimal` 的 Enemy 当前是弱支持：可以建档、收回、重新召唤，但不保证跟随、停战、护主或主动索敌。
- 本次压力测试使用 `NoAI` 固定实体，验证的是分类、事件、契约和档案生命周期，不把“契约后立即停战”宣称为已实现。
- `FurkinCombatMode.AGGRESSIVE` 仍以 `Monster.class` 为目标类型，不会主动索敌“实现 Enemy 但不继承 Monster”的实体。
- 目标卸载后重载、跨重启旧确认包、实体 ID 复用、真实登出网络链等 WP-01 残余仍需专项故障注入测试。
- 不提供按物种覆盖门槛；当前只有三种分类级配置。
- 本门槛只影响新契约，不追溯已有绒亲，也不在契约成功后持续检定。

## 7. 版本与 API

- 新机制按 Forge 推荐版本格式递增 `MINOR`：`1.20.1-0.0.2.0` → `1.20.1-0.0.3.0`。
- `MAJORAPI` 保持 `0`；没有修改 `com.wanancat.furkin.api` 的任何公开类型或方法。
- 网络协议保持 `2`。
- `CHANGELOG.md` 与 `changelog.en.md` 已新增 `0.0.3.0` 条目。
- 完整实施与验收记录见 `IMPLEMENTATION_PLAN.md`。
