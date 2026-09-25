# Chest Cavity / 脆骨症开胸血量门槛调研

- 调研日期：2026-09-25
- 调研对象：No Flesh Within Chest（脆骨症）1.19.2 Forge
- 实际开胸模组：`chestcavity-forge-1.19.2-2.16.6.1-fea.jar`
- 上游模组：Tigereye504/chestcavity；Forge 移植：ThePlasticPotato/ChestCavityForge
- 目的：判断其“低血量才能开胸”的实现是否值得 Furkin 契约前置条件借鉴

## 1. 结论

脆骨症确实通过血量控制能否开胸，但实现不是单一 10%，而是：

```text
当前血量 <= 绝对阈值
或者
当前血量 <= 最大血量 * 比例阈值
```

两者任一成立即可，是 `OR`，不是 `AND`。另外还要求胸甲槽为空；如果目标的 `EASE_OF_ACCESS` 器官分大于 0，则可以绕过血量限制。

脆骨症整合包把模组默认值从 `20 / 0.5` 改成了 `10 / 0.1`，所以“10%”这个记忆来自配置里的比例阈值，但实际生效门槛经常由绝对值 10 主导。

## 2. 配置来源

脆骨症仓库的 `config/chestcavity.json`：

```json
{
  "CHEST_OPENER_ABSOLUTE_HEALTH_THRESHOLD": 10,
  "CHEST_OPENER_FRACTIONAL_HEALTH_THRESHOLD": 0.1,
  "CAN_OPEN_OTHER_PLAYERS": false
}
```

Forge 移植模组的默认值则是：

```java
public int CHEST_OPENER_ABSOLUTE_HEALTH_THRESHOLD = 20;
public float CHEST_OPENER_FRACTIONAL_HEALTH_THRESHOLD = .5f;
```

## 3. 实现方法

1.19.2 Forge 的 `DefaultChestCavityType#isOpenable` 与 1.20.1 上游的 `GeneratedChestCavityType#isOpenable` 使用同一逻辑：

```java
public boolean isOpenable(ChestCavityInstance instance) {
    boolean weakEnough = instance.owner.getHealth()
            <= ChestCavity.config.CHEST_OPENER_ABSOLUTE_HEALTH_THRESHOLD
            || instance.owner.getHealth()
            <= instance.owner.getMaxHealth()
                    * ChestCavity.config.CHEST_OPENER_FRACTIONAL_HEALTH_THRESHOLD;

    boolean chestVulnerable =
            instance.owner.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
    boolean easeOfAccess =
            instance.getOrganScore(CCOrganScores.EASE_OF_ACCESS) > 0;

    return chestVulnerable && (easeOfAccess || weakEnough);
}
```

关键点：

- 使用当前实际血量，不做缓存。
- 使用 `<=`，恰好等于阈值时允许。
- 绝对值与比例值取“更容易满足的一边”。
- 胸甲槽非空时直接禁止，即使血量已经很低。
- `EASE_OF_ACCESS > 0` 是明确的绕过通道。

实际运行 jar 的字节码已核对，判定逻辑与源码一致，见 `chestcavity-forge-1.19.2-2.16.6.1-fea.jar` 中的 `GeneratedChestCavityType.isOpenable`。

## 4. 判定与副作用分层

开胸入口是 `ChestOpener#openChestCavity`：

1. 取目标的 `ChestCavityInstance`。
2. 调用 `cc.getChestCavityType().isOpenable(cc)` 做纯资格判断。
3. 不满足时只在客户端提示原因：
   - 胸甲槽非空：`Target's chest is obstructed`
   - 血量过高：`Target is too healthy to open`
4. 满足时，如果没有 `EASE_OF_ACCESS`，先对目标造成 4 点伤害。
5. 目标仍然存活才打开胸腔 GUI；若这 4 点伤害导致死亡，则不再打开 GUI。

这体现了“判定”和“执行副作用”分离：`isOpenable` 不知道提示、伤害或 GUI，调用方负责这些结果。

## 5. 10% 的实际效果

配置为绝对值 10、比例值 0.1 时，实际门槛是两者中更宽松的一个：

| 最大血量 | 绝对值门槛 | 比例门槛 | 实际生效门槛 | 实际占比 |
|---:|---:|---:|---:|---:|
| 8 | 10 | 0.8 | 8（血量上限） | 100% |
| 10 | 10 | 1 | 10（血量上限） | 100% |
| 20 | 10 | 2 | 10 | 50% |
| 30 | 10 | 3 | 10 | 33% |
| 60 | 10 | 6 | 10 | 17% |
| 100 | 10 | 10 | 10 | 10% |
| 200 | 10 | 20 | 20 | 10% |

结论：

- 最大血量不超过 10 的生物等于没有血量门槛。
- 原版常见的 20 血生物实际是半血门槛，而不是 10%。
- 只有最大血量至少 100 时，比例阈值才成为主导门槛。

这也是照抄它的绝对值时必须警惕的地方：Furkin 内置猫和狼都是 8 点血，如果直接采用“绝对值 10”，血量条件会完全失去作用。

## 6. 值得 Furkin 借鉴的部分

### 6.1 绝对阈值加比例阈值，使用 OR

这是最值得借鉴的一点。单一百分比阈值在小体型和高血量模组生物之间很难同时合理：

- 若沿用单一 30%：猫、狼 8 点血时门槛只有 2.4 点，玩家需要把宠物打到约 2 点血；这不是期望的默认体验。
- 第三方大型生物 200 点血，30% 是 60 点，要求打掉 70% 血量。
- 绝对值门槛可以给低血量上限生物一个固定、可理解的底线。

建议只借鉴结构，不照抄数值。Furkin 最终将 Enemy 设为 30% / 4，将 NeutralMob 设为 50% / 8；8 点绝对值让满血原版狼可以直接进入命名流程，恢复 0.0.2.0 的捕捉体验。

### 6.2 判定与副作用分离

`isOpenable` 只回答“能不能”，调用方决定提示、伤害和 GUI。Furkin v2 计划中的 `passesContractChecks` 与 `executeContract` 已经是同型结构，应继续保持：健康判定只进入共享前置校验，不在提交方法里再造第二套规则。

### 6.3 类型级覆盖

Chest Cavity 的阈值挂在 `ChestCavityType` 上，每种实体类型可以有自己的 `isOpenable`。Furkin 对应的是 `FurkinSpecies` 注册表。本版不一定要开放物种级阈值，但当前设计应避免把分类阈值写死在无法扩展的位置。

### 6.4 明确、可配置的绕过通道

`EASE_OF_ACCESS > 0` 表明：硬阈值之外应该保留一个显式例外，而不是到处写特判。Furkin 未来如果需要“某些物种或道具无视血量门槛”，应有单一、可审计的入口，不应散落在事件处理里。

### 6.5 失败原因区分

它把“胸甲阻挡”和“血量过高”分开提示。Furkin 当前的 `HEALTH_TOO_HIGH` 独立结果枚举符合这一原则；应继续避免让血量失败吞掉未注册、超距、已有主人等更具体的原因。

## 7. 不宜照搬的部分

- 不要把绝对值直接照抄为 10。Furkin 的猫和狼只有 8 点血；最终 NeutralMob 采用 8 点绝对值，是明确为了覆盖满血原版狼的 8/8 边界，而不是沿用 Chest Cavity 的 10。
- 不要只返回 `boolean`。Furkin 需要区分 `HEALTH_TOO_HIGH` 与其他失败原因，才能只发送正确提示。
- 胸甲槽为空是 Chest Cavity 的开胸语义，不能映射成 Furkin 契约条件。
- Chest Cavity 没有防御 `NaN`、无穷大或非正最大血量；Furkin v2 计划里的有限正数校验更严谨，应保留。
- 它的判定可以在客户端执行以提供即时提示，但 Furkin 的契约写入以服务端为权威，不应为复刻提示方式破坏现有服务端校验链路。

## 8. 对 v2 计划的建议

建议把 WP-1 的健康模型从“每类一个百分比阈值”扩展为“每类一个百分比阈值加一个可选绝对阈值，二者 OR”：

```text
percentPass  = healthPercent <= percentThreshold
absolutePass = absoluteThreshold > 0 && currentHealth <= absoluteThreshold
healthPass   = percentPass || absolutePass
```

最终采纳的默认值（2026-09-25 定案）：

| 分类 | 百分比阈值 | 绝对阈值（点） | 语义 |
|---|---:|---:|---|
| Enemy | 30 | 4 | 血量降到 30% 或 4 点以下 |
| NeutralMob | 50 | 8 | 满血 8 点原版狼可直接契约，兼容 0.0.2.0 捕捉体验；其他中立生物在 50% 或 8 点以下通过 |
| 其他 | 100 | 0 | 不增加血量限制 |

`absoluteThreshold = 0` 表示不启用绝对分支，避免零血边界歧义。

早期曾考虑所有分类统一使用 `30 / 30 / 100` 纯百分比方案，但该方案会让满血猫和狼需要被降到约 2 点血才能进入契约命名流程；最终未采纳。

## 9. 来源

- 脆骨症整合包仓库：`https://github.com/Yorunina/No-Flesh-Within-Chest`
- 脆骨症开胸配置：`config/chestcavity.json`
- 实际运行模组：`mods/chestcavity-forge-1.19.2-2.16.6.1-fea.jar`
- Forge 移植源码：`https://github.com/ThePlasticPotato/ChestCavityForge`
  - `src/main/java/net/tigereye/chestcavity/chestcavities/types/DefaultChestCavityType.java`
  - `src/main/java/net/tigereye/chestcavity/chestcavities/types/GeneratedChestCavityType.java`
  - `src/main/java/net/tigereye/chestcavity/items/ChestOpener.java`
  - `src/main/java/net/tigereye/chestcavity/config/CCConfig.java`
- 上游模组：`https://github.com/Tigereye504/chestcavity`