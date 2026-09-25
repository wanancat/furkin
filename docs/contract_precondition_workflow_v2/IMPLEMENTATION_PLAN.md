# Furkin 契约血量前置条件完整工作计划

> 状态：实现完成；`50% / 8` 默认值已通过 `compileJava`、`build`、`runServer`，客户端满血狼 `8/8` 实际契约复测通过。
> 完成日期：2026-09-25。
> 源码基线：分支 `mc1.20.1`，提交 `35d931c`；当前工作区含 NeutralMob 默认值 `50% / 8` 调整（未提交）。
> 发布版本：`1.20.1-0.0.3.0`。
> 适用版本：Minecraft 1.20.1 / Forge 47.2.0；Gradle 8.1.1，JDK 17。
> 设计现状：`docs/contract_precondition_workflow_v2/README.md`。
> 外部参考：`docs/contract_precondition_workflow_v2/RESEARCH_CHEST_CAVITY.md`。

## 1. 目标

本次工作包的目标是给已经通过物种注册表的契约目标增加生命值资格门槛，并保证：

- 分类规则稳定、可解释、可配置。
- 默认配置不会让 8 点血的原版猫狗出现明显异常。
- 发起阶段与确认阶段使用同一套权威校验。
- 血量不符时不建档、不扣物品、不修改能力、名字或 AI。
- 只有生命值确实是唯一失败原因时才显示生命值提示。
- 支持真实第三方模组实体，尤其是多部件实体和非 `Monster` Enemy。

## 2. 非目标

- 不扩大物种注册白名单。
- 不为非 `TamableAnimal` Enemy 实现完整跟随、停战、护主和主动索敌。
- 不提供按物种覆盖的门槛。
- 不把吸收量、护甲或减伤折算入生命值。
- 不提供 UI 配置界面；只提供服务端 TOML。
- 不修改网络协议。

## 3. 冻结决策

1. 分类优先级固定为 `Enemy > NeutralMob > 其他`。
2. 每个分类使用“百分比 OR 绝对值”双阈值。
3. 默认 Enemy 为 `30%` 或 `4` 点，NeutralMob 为 `50%` 或 `8` 点；其他为 `100%`、绝对值禁用。
4. 判定使用 `<=`，恰好等于门槛时通过。
5. `currentHealth` 与 `maxHealth` 必须为有限正数，否则 `INVALID_HEALTH`。
6. 共享校验返回 `ContractCheckResult`，不再只返回 `boolean`。
7. 生命值检查位于主手检查之后、最终提交之前。
8. 只有 `HEALTH_TOO_HIGH` 显示生命值提示并取消实体交互。
9. `tryContract` 与 `confirmContract` 都执行共享校验。
10. `executeContract` 不复制第二套生命值判定。
11. 非 `TamableAnimal` Enemy 明确按弱支持记录。
12. Forge `PartEntity` 交互解析到父实体，作为真实模组兼容修复。
13. 版本按新机制递增 `MINOR`；协议保持 `2`。

## 4. 技术设计

### 4.1 配置

`internal.config.FurkinServerConfig` 新增：

| 配置键 | 默认值 | 范围 |
|---|---:|---:|
| `contractEnemyHealthPercent` | 30.0 | 0–100 |
| `contractEnemyHealthAbsolute` | 4.0 | 0–1024 |
| `contractNeutralHealthPercent` | 50.0 | 0–100 |
| `contractNeutralHealthAbsolute` | 8.0 | 0–1024 |
| `contractOtherHealthPercent` | 100.0 | 0–100 |
| `contractOtherHealthAbsolute` | 0.0 | 0–1024 |

计算模型：

```text
healthPercent = currentHealth * 100.0 / maxHealth
percentPass   = percentThreshold >= 100 || healthPercent <= percentThreshold
absolutePass  = absoluteThreshold > 0 && currentHealth <= absoluteThreshold
healthPass    = percentPass || absolutePass
```

### 4.2 共享结果模型

`internal.contract.FurkinContractHandler.ContractCheckResult` 包含：

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

枚举位于 `internal` 包，不进入公开 API。

### 4.3 共享检查顺序

1. 目标不能是玩家。
2. 同维度、存活、未移除。
3. 物种已注册。
4. capability 存在且尚未契约。
5. `TamableAnimal` 既有 owner 必须是当前玩家。
6. 触达距离通过。
7. 活跃上限未满。
8. 主手是契约物品。
9. 分类和生命值门槛通过。

### 4.4 多部件实体解析

`CommonEvents.onEntityInteract` 在进入业务路径前执行：

```java
if (interactionTarget instanceof PartEntity<?> part
        && part.getParent() instanceof LivingEntity parent) {
    interactionTarget = parent;
}
```

原因：Twilight Forest Hydra 主实体 `isPickable() = false`，所有右键都会命中 `HydraPart`；旧逻辑因部件不是 `LivingEntity` 而静默退出。解析到父实体后，契约、喂食、面板和收回路径对多部件生物保持一致。

## 5. 工作包执行结果

### WP-0：复核与文档

- [x] 独立复核 1.20.1 `Enemy` / `NeutralMob` / `Monster` 类型关系。
- [x] 新增 `docs/contract_precondition_workflow_v2/`。
- [x] 旧文档标记废弃并指向 v2。
- [x] 调研 Chest Cavity / ChestCavityForge 的开胸双阈值实现。
- [x] 记录其“绝对值 OR 比例值、`<=`、当前血量、不做缓存”的可借鉴结论。

### WP-1：配置与分类

- [x] 新增六项服务端配置。
- [x] 实现 `Enemy > NeutralMob > 其他` 分类。
- [x] 使用双阈值 OR 判定。
- [x] 处理非有限、非正生命值。
- [x] 处理绝对值为 0 的禁用语义。
- [x] 处理百分比 100 的不限制语义。
- [x] 不扩大物种注册表。

### WP-2：共享校验与两阶段权威

- [x] 新增 `ContractCheckResult`。
- [x] `tryContract` 返回结果并只在 `PASSED` 时创建 pending。
- [x] `confirmContract` 消费 pending 后重新执行共享校验。
- [x] 主手、快捷栏、名字、UUID、维度、实体 ID 复检保持。
- [x] `executeContract` 不复制第二套生命值分类。
- [x] 生命值失败发生在能力、档案、物品、名字和 AI 写入之前。

### WP-3：反馈与事件

- [x] 中英文新增生命值提示键。
- [x] 服务端格式化阈值，避免浮点显示失控。
- [x] 只有 `HEALTH_TOO_HIGH` 显示提示。
- [x] `HEALTH_TOO_HIGH` 取消实体交互。
- [x] 其他失败不扩大取消范围。
- [x] 健康失败时取消原版右键副作用（早期 30% / 4 配置下以满血 Wolf 验证）。

### WP-4：第三方和多部件边界

- [x] 明确非 `TamableAnimal` Enemy 为弱支持。
- [x] 增加 Forge `PartEntity -> parent` 解析。
- [x] 使用 Twilight Forest Hydra 验证非 `Monster` Enemy、多部件和 360 最大生命。
- [x] 验证 Hydra 收回和重新召唤后档案一致。
- [x] 记录完整 AI、停战和主动索敌仍不在本次范围。

### WP-5：验证矩阵

- [x] `compileJava`（`50% / 8` 新默认值）。
- [x] `build`（`50% / 8` 新默认值）。
- [x] `runServer`（加载 `50% / 8` 新默认值，无配置不匹配警告）。
- [x] 主仓库 `runClient`（`30% / 4` 基线）。
- [x] Twilight Forest 桥接 `runClient`。
- [x] 检查 `latest.log`，无项目相关 `ERROR` / `FATAL` / 异常栈（`30% / 4` 基线及新默认值 `runServer`）。
- [x] 主仓库 `runClient` 复测满血 Wolf 8/8（`50% / 8` 新默认值）——通过，进入命名窗口并实际契约成功。
- [x] 二次校验路径由既有 `30% / 4` 基线覆盖；本次默认阈值变更未改确认逻辑。

### WP-6：版本与发布准备

- [x] `gradle.properties` 从 `1.20.1-0.0.2.0` 升到 `1.20.1-0.0.3.0`。
- [x] 更新 `CHANGELOG.md`。
- [x] 更新 `changelog.en.md`。
- [x] 更新 `README.md`。
- [x] 更新 `README.zh-CN.md`。
- [x] 确认公开 API 未变化，`MAJORAPI` 保持 `0`。
- [x] 确认网络包无变化，`PROTOCOL_VERSION` 保持 `2`。
- [x] 压力测试夹具仅存在于 `D:\frukin_dev\_research`，不进入生产构建。

## 6. 验证记录

### 6.1 主仓库客户端

| 用例 | 结果 |
|---|---|
| 满血 Wolf 8/8（新默认值） | 通过绝对值门槛，进入命名窗口并成功契约，恢复 0.0.2.0 的满血捕捉体验 |
| Wolf 4/8（新默认值） | 未单独复测；`4 <= 4` 且 `4 <= 8`，同一判定分支通过 |
| Neutral 绝对值改为 0（旧边界测试） | 既有 `30% / 4` 基线验证过绝对值禁用路径；新默认值下 8/8 Wolf 预期被 50% 百分比拒绝 |
| 满血 Cat | 其他分类默认通过 |
| 未注册 Cow | 无生命值提示，不接管原版交互 |
| 命名期间回血（新默认值） | 既有 `30% / 4` 基线已验证确认阶段拒绝；本次仅改阈值，未改确认路径 |

### 6.2 Twilight Forest 压力测试

夹具通过公开 `FurkinApi.registerSpecies` 注册 `82 / 82` 个 TF 实体；生产源码未加入 TF 专属逻辑。

| 用例 | 结果 |
|---|---|
| Hydra 360/360 | 拒绝 |
| Hydra 108/360 | 通过，验证 `<=` |
| Hydra 4/360 | 通过绝对分支 |
| Hydra 实际契约 | 成功建档、扣物品 |
| Hydra 收回与召唤 | 通过，无重复档案或残留部件 |
| Alpha Yeti 200/200 | 拒绝 |
| Alpha Yeti 60/200 | 通过 |
| Boar 10/10 | 其他分类通过 |
| Hydra 100/360 命名期间回血到 200/360 | 确认拒绝，无新档案、物品不变 |
| 客户端日志 | 无项目相关错误 |

### 6.3 已发现的真实兼容缺口

Hydra 初测右键无反应。原因是其主实体不可拾取，事件目标是 `HydraPart`，旧代码只接受 `LivingEntity`，所以静默返回。

修复为 Forge 公开 `PartEntity#getParent()` 解析后，Hydra 满血拒绝、阈值通过、实际契约、收回和重召唤均正常。该修复对任何沿用 Forge 多部件实体机制的第三方模组通用。

## 7. 完成定义

- [x] 六项配置实现。
- [x] 分类优先级实现。
- [x] 双阈值 OR 语义实现。
- [x] 发起与确认共用权威校验。
- [x] 只有 `HEALTH_TOO_HIGH` 显示提示。
- [x] 健康失败不写档案、不扣物品、不改能力或 AI。
- [x] 健康失败取消原版右键副作用。
- [x] pending、TTL、单次消费、身份复检保持。
- [x] 全局活跃上限保持。
- [x] 主仓库完成 `50% / 8` 满血 Wolf 实际契约复测；第三方压力客户端完成 `30% / 4` 基线验证。
- [x] 中英文语言、README、changelog、版本号同步。
- [x] 公开 API 与网络协议未变化。
- [x] 非 `TamableAnimal` 弱支持边界已记录。

## 8. 残余验证债务

以下项目不阻塞本次发布，但不得被写成已解决：

- 满血 Wolf `8/8` 复测已通过；`4/8` 和绝对分支禁用未单独重跑，但复用同一判定分支与既有边界证据。

- 非 `TamableAnimal` Enemy 契约后是否会立刻停战。
- 此类 Enemy 的跟随、护主、解绑 AI 恢复与 `AGGRESSIVE` 索敌。
- 目标卸载后重载。
- 服务器重启后的旧确认包。
- 实体 ID 复用与同位置重建。
- 真实 `PlayerLoggedOutEvent` 网络链。
- `ItemStack.matches` 是否允许同内容新堆叠；当前允许，并作为已知语义保留。

## 9. 后续工作建议

1. 若要把非 `TamableAnimal` Enemy 提升为完整支持，单独设计生命周期和 AI 工作包。
2. `AGGRESSIVE` 若需覆盖非 `Monster` Enemy，应使用 `LivingEntity.class` 加 `instanceof Enemy` 谓词，不能直接传 `Enemy.class`。
3. 若第三方模组数量继续增加，可把一次性桥接夹具升级为可选兼容测试模组，但不得进入生产发布 JAR。
4. 若需平衡不同 Boss，再评估按物种或 EntityType 覆盖门槛，而不是继续增加全局分类。
