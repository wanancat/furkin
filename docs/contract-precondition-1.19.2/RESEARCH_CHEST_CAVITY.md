# Chest Cavity / 脆骨症开胸血量门槛调研（1.19.2 适用）

- 日期：2026-09-27
- 调研基线：沿用 1.20.1 `docs/contract_precondition_workflow_v2/RESEARCH_CHEST_CAVITY.md`
- 原始调研对象：No Flesh Within Chest（脆骨症）1.19.2 Forge
- 实际模组例：`chestcavity-forge-1.19.2-2.16.6.1-fea.jar`
- 目的：说明 Furkin 1.19.2 为什么要采用“百分比 OR 绝对值”的血量门槛，以及哪些实现不宜照搬

> 本次整理没有重新下载或反编译第三方 JAR；公式、配置和结论沿用 1.20.1 工作包中已记录的 1.19.2/上游源码调研。本文件只作为设计依据，不宣称第三方字节码已在本工作区重新核验；如需引用具体字节码证据，应重新对本地实际 JAR 做一次核验。

## 1. 结论

脆骨症通过血量控制能否开胸，核心逻辑为：

```text
当前血量 <= 绝对阈值
或者
当前血量 <= 最大血量 * 比例阈值
```

两者任一成立即可，是 OR，不是 AND。开胸还要求胸甲槽为空；存在 `EASE_OF_ACCESS` 器官分时可绕过血量限制。

整合包把默认值调成绝对值 `10`、比例值 `0.1`，所以“10%”通常只是记忆中的比例部分；低血量上限生物的实际门槛经常由绝对值主导，甚至等于满血。

## 2. 配置与实现

典型配置：

```json
{
  "CHEST_OPENER_ABSOLUTE_HEALTH_THRESHOLD": 10,
  "CHEST_OPENER_FRACTIONAL_HEALTH_THRESHOLD": 0.1,
  "CAN_OPEN_OTHER_PLAYERS": false
}
```

Forge 移植源码默认值曾为绝对值 `20`、比例值 `0.5`。核心判定可抽象为：

```java
boolean weakEnough = owner.getHealth() <= absoluteThreshold
        || owner.getHealth() <= owner.getMaxHealth() * fractionalThreshold;

boolean chestVulnerable = owner.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
boolean easeOfAccess = instance.getOrganScore(CCOrganScores.EASE_OF_ACCESS) > 0;

return chestVulnerable && (easeOfAccess || weakEnough);
```

关键点：

- 使用当前实际血量，不做缓存。
- 使用 `<=`，恰好等于门槛时允许。
- 绝对值与比例值取“更容易满足的一边”。
- 胸甲槽非空时直接禁止。
- `EASE_OF_ACCESS > 0` 是明确的绕过通道。
- 判定和执行副作用分层：`isOpenable` 只回答资格，调用方负责提示、伤害和 GUI。

## 3. 绝对值与比例值的实际效果

配置为绝对值 `10`、比例值 `0.1` 时：

| 最大血量 | 绝对值门槛 | 比例门槛 | 实际生效门槛 | 实际占比 |
|---:|---:|---:|---:|---:|
| 8 | 10 | 0.8 | 8（最大血量） | 100% |
| 10 | 10 | 1 | 10（最大血量） | 100% |
| 20 | 10 | 2 | 10 | 50% |
| 30 | 10 | 3 | 10 | 33% |
| 60 | 10 | 6 | 10 | 17% |
| 100 | 10 | 10 | 10 | 10% |
| 200 | 10 | 20 | 20 | 10% |

结论：

- 最大血量不超过 10 的生物等于没有血量门槛。
- 原版常见的 20 血生物实际是半血门槛，不是 10%。
- 只有最大血量至少 100 时，比例阈值才成为主导门槛。
- 直接照抄绝对值 `10` 会让 Furkin 的 8 点血狼、10 点血猫等低血量原版生物完全绕过血量限制。

## 4. 对 Furkin 1.19.2 的适用结论

### 4.1 采用双阈值 OR

单一百分比阈值很难同时适配小体型和高血量模组生物：

- 若所有目标都用 30%：8 点血狼与 10 点血猫只需降到约 2～3 点，默认体验不合理。
- 200 点血的大型生物，30% 是 60 点，要求打掉 70% 血量。
- 绝对值可以给低血量上限生物一个固定、可理解的底线。

Furkin 1.19.2 冻结值与 1.20.1 v2 一致：

| 分类 | 百分比阈值 | 绝对阈值 | 语义 |
|---|---:|---:|---|
| Enemy | 30 | 4 | 血量降到 30% 或 4 点以下 |
| NeutralMob | 50 | 8 | 满血 8 点原版狼 `8/8` 可直接契约 |
| 其他 | 100 | 0 | 不增加血量限制 |

`absoluteThreshold = 0` 表示禁用绝对分支，避免零血边界歧义。

### 4.2 判定与副作用分离

Furkin 应继续保持：

- `checkContract(...)` 只回答资格和失败原因。
- `sendHealthBlockedMessage(...)` 只在 `HEALTH_TOO_HIGH` 时处理展示。
- `CommonEvents` 根据结果决定是否取消原版交互。
- `executeContract(...)` 只执行已获授权的最终写入，不复制健康规则。

### 4.3 分类阈值放在可扩展路径中

Chest Cavity 的阈值挂在胸腔类型上，Furkin 当前对应的是 `FurkinSpecies` 注册表。本版不开放物种级阈值，但分类逻辑应保持独立函数，避免把配置读取散落在事件和处理路径。

### 4.4 失败原因要分开

Chest Cavity 区分“胸甲阻挡”和“血量过高”。Furkin 也必须区分：

- `HEALTH_TOO_HIGH`：显示生命值提示，并取消原版交互。
- `INVALID_HEALTH`：静默拒绝。
- `UNREGISTERED`、`OWNED_BY_OTHER`、`OUT_OF_REACH` 等：保持原有失败语义，不能吞并成生命值错误。

### 4.5 明确绕过通道

Chest Cavity 的 `EASE_OF_ACCESS` 说明硬门槛应保留单一、可审计的例外。Furkin 本版不实现绕过通道；未来若新增“特定物种或道具无视血量门槛”，应集中定义，不得分散写特判。

## 5. 不宜照搬的部分

- 不要把绝对值直接照抄为 `10`。Furkin 的 8 点血狼需要 `8` 才能覆盖满血 Wolf 边界；10 点血猫也会因 `10 <= 10` 被一并放行，扩大无门槛范围。
- 不要只返回 `boolean`。必须区分 `HEALTH_TOO_HIGH` 与 `INVALID_HEALTH`。
- 胸甲槽为空是开胸语义，不能映射成 Furkin 契约条件。
- Chest Cavity 没有防御 `NaN`、无穷大或非正最大血量；Furkin 应使用有限正数校验。
- Chest Cavity 可以在客户端做即时提示，但 Furkin 的契约写入必须以服务端为权威，不能为复刻提示方式破坏现有两步校验链。
- 不要照搬 1.20.1 的客户端/世界访问 API；Furkin 1.19.2 继续使用 `player.getLevel()`、`target.getLevel()` 和现有距离判断。

## 6. 对 1.19.2 实施计划的直接约束

1. 百分比与绝对值使用 OR。
2. 比较使用 `<=`。
3. 绝对值 `0` 禁用该分支。
4. 百分比 `100` 表示该分类不限制。
5. 使用当前 `getHealth()`，不缓存。
6. `currentHealth` 与 `maxHealth` 必须为有限正数；`maxHealth` 非法而实体仍存活时返回 `INVALID_HEALTH`。`currentHealth <= 0` 或 NaN 通常会先被 `isAlive()` 判为 `INVALID_TARGET`。1.19.2 的 `MAX_HEALTH` 会被 sanitize/clamp，夹具必须用一次性 Wolf 子类覆写 `getAttributeValue(Attribute)`，不能依赖 `setBaseValue(NaN/0)`。
7. 只有 `HEALTH_TOO_HIGH` 显示提示。
8. 判定与执行副作用分离，`executeContract` 不重复判定。
9. 分类函数优先读取 `Enemy`，再读取 `NeutralMob`，最后兜底。
10. 不新增物种级阈值入口，除非另开设计工作包。

## 7. 来源

- Furkin 1.20.1 工作流：
  - `D:\frukin_dev\frukin_1_20_1\docs\contract_precondition_workflow_v2\README.md`
  - `D:\frukin_dev\frukin_1_20_1\docs\contract_precondition_workflow_v2\IMPLEMENTATION_PLAN.md`
  - `D:\frukin_dev\frukin_1_20_1\docs\contract_precondition_workflow_v2\RESEARCH_CHEST_CAVITY.md`
- 脆骨症整合包仓库：`https://github.com/Yorunina/No-Flesh-Within-Chest`
- Forge 移植源码：`https://github.com/ThePlasticPotato/ChestCavityForge`
- 上游模组：`https://github.com/Tigereye504/chestcavity`