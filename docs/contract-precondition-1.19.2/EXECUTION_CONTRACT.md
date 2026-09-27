# 契约血量前置条件执行契约

- 日期：2026-09-27
- 实施基线：`206a92732c843a7f0aeea72fa7a3a6287b01356d`
- 状态：已实施并通过核心验证及人工客户端 A～F；等待乌狸确认提交/推送
- 目的：把 `IMPLEMENTATION_PLAN.md` 收敛成可以直接照着修改、复查和验收的代码级契约
- 设计摘要：[README.md](README.md)
- 可行性复查：[FEASIBILITY_AUDIT.md](FEASIBILITY_AUDIT.md)
- 验收矩阵：[VERIFICATION_MATRIX.md](VERIFICATION_MATRIX.md)

> 本文件是实施冲突时的优先口径。若本文件与 1.20.1 参考实现或旧计划冲突，以本文件和 1.19.2 实际公开 API 为准。

## 1. 实施边界

### 1.1 必须修改

- `src/main/java/com/wanancat/furkin/internal/config/FurkinServerConfig.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java`
- `src/main/resources/assets/furkin/lang/en_us.json`
- `src/main/resources/assets/furkin/lang/zh_cn.json`
- 发布收口时：`README.md`、`README.zh-CN.md`、`CHANGELOG.md`、`CHANGELOG.en.md`、`gradle.properties`

### 1.2 明确不改

- `src/main/java/com/wanancat/furkin/internal/network/**`
- `ConfirmContractPacket` 的字段、方向和处理器调用方式
- `FurkinNetwork` 包 ID 与 `PROTOCOL_VERSION`
- `src/main/java/com/wanancat/furkin/api/**`
- 远召服务、重复实体修复和 AI 所有权实现

## 2. 冻结结果模型

在 `FurkinContractHandler` 中增加：

```java
public enum ContractCheckResult {
    PASSED,
    INVALID_TARGET,
    UNREGISTERED,
    ALREADY_COMPANION,
    OWNED_BY_OTHER,
    OUT_OF_REACH,
    ACTIVE_LIMIT,
    INVALID_HAND,
    INVALID_HEALTH,
    HEALTH_TOO_HIGH
}
```

唯一定义：

- `PASSED`：前置检查全部通过；不表示已经落契约。
- `INVALID_TARGET`：玩家目标、异维度、死亡、已移除等不合法目标。
- `UNREGISTERED`：物种未注册。
- `ALREADY_COMPANION`：capability 缺失或已经是绒亲。
- `OWNED_BY_OTHER`：`TamableAnimal` 已属于其他玩家。
- `OUT_OF_REACH`：超过 4 格距离。
- `ACTIVE_LIMIT`：活跃绒亲已满。
- `INVALID_HAND`：主手不是非空契约物品。
- `INVALID_HEALTH`：生命值字段不是有限正数；静默拒绝。
- `HEALTH_TOO_HIGH`：生命值超过该分类双阈值；显示健康提示并取消交互。

## 3. 配置契约

在 `FurkinServerConfig` 的 `ACTIVE_LIMIT` 之后、成长配置之前增加六项：

```java
public static final ForgeConfigSpec.DoubleValue CONTRACT_ENEMY_HEALTH_PERCENT = BUILDER
        .comment("Contract health gate for Enemy targets: maximum health percentage.",
                "Passes when current health <= percent, or current health <= absolute (when absolute > 0).",
                "Design intent: 30.")
        .defineInRange("contractEnemyHealthPercent", 30.0D, 0.0D, 100.0D);

public static final ForgeConfigSpec.DoubleValue CONTRACT_ENEMY_HEALTH_ABSOLUTE = BUILDER
        .comment("Contract health gate for Enemy targets: absolute health threshold.",
                "Set to 0 to disable this branch.",
                "Design intent: 4.0 (two hearts).")
        .defineInRange("contractEnemyHealthAbsolute", 4.0D, 0.0D, 1024.0D);

public static final ForgeConfigSpec.DoubleValue CONTRACT_NEUTRAL_HEALTH_PERCENT = BUILDER
        .comment("Contract health gate for NeutralMob targets: maximum health percentage.",
                "Passes when current health <= percent, or current health <= absolute (when absolute > 0).",
                "Design intent: 50.")
        .defineInRange("contractNeutralHealthPercent", 50.0D, 0.0D, 100.0D);

public static final ForgeConfigSpec.DoubleValue CONTRACT_NEUTRAL_HEALTH_ABSOLUTE = BUILDER
        .comment("Contract health gate for NeutralMob targets: absolute health threshold.",
                "Set to 0 to disable this branch.",
                "Design intent: 8.0 (four hearts; lets a full-health vanilla wolf pass).")
        .defineInRange("contractNeutralHealthAbsolute", 8.0D, 0.0D, 1024.0D);

public static final ForgeConfigSpec.DoubleValue CONTRACT_OTHER_HEALTH_PERCENT = BUILDER
        .comment("Contract health gate for other targets: maximum health percentage.",
                "100 disables the percentage limit for this category.",
                "Design intent: 100.")
        .defineInRange("contractOtherHealthPercent", 100.0D, 0.0D, 100.0D);

public static final ForgeConfigSpec.DoubleValue CONTRACT_OTHER_HEALTH_ABSOLUTE = BUILDER
        .comment("Contract health gate for other targets: absolute health threshold.",
                "Set to 0 to disable this branch.",
                "Design intent: 0 (disabled).")
        .defineInRange("contractOtherHealthAbsolute", 0.0D, 0.0D, 1024.0D);
```

配置注释保持英文 ASCII；数值语义不得放进客户端偏好配置。

## 4. 处理器契约

### 4.1 新增导入

`FurkinContractHandler` 增加：

```java
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
```

不要增加 1.20.1 专有访问器导入。

### 4.2 `tryContract(...)`

签名改为：

```java
public static ContractCheckResult tryContract(
        ServerPlayer player, LivingEntity target, ItemStack hand)
```

执行顺序：

```java
PENDING_CONTRACTS.remove(player.getUUID());

ServerLevel level = player.getLevel();
ContractCheckResult result = checkContract(player, target, hand, level);
if (result == ContractCheckResult.HEALTH_TOO_HIGH) {
    sendHealthBlockedMessage(player, target);
    return result;
}
if (result != ContractCheckResult.PASSED) {
    return result;
}

PENDING_CONTRACTS.put(player.getUUID(), new PendingContract(
        target.getUUID(),
        target.getId(),
        level.dimension(),
        level.getGameTime(),
        player.getInventory().selected,
        hand));

FurkinNetwork.channel().send(
        PacketDistributor.PLAYER.with(() -> player),
        new com.wanancat.furkin.internal.network.RequestContractNamePacket(target.getId()));
return ContractCheckResult.PASSED;
```

不得在健康失败时保留旧 pending，也不得发送命名请求。

### 4.3 `confirmContract(...)`

会话消费和身份校验后，执行顺序必须是：

```text
1. 校验 pending 未过期、维度一致、entityId 一致
2. 按 targetUuid 取得 LivingEntity target
3. 取得当前 main hand
4. 校验快捷栏槽位、ItemStack.matches(hand, expectedHand)、名字合法
5. 调用 checkContract(...)
6. 若 HEALTH_TOO_HIGH，发送健康提示并返回
7. 仅 PASSED 调用 executeContract(...)
```

目标代码：

```java
ItemStack hand = player.getMainHandItem();
if (player.getInventory().selected != pending.selectedSlot
        || !ItemStack.matches(hand, pending.expectedHand)
        || !isValidContractName(name)) {
    return;
}

ContractCheckResult result = checkContract(player, target, hand, level);
if (result == ContractCheckResult.HEALTH_TOO_HIGH) {
    sendHealthBlockedMessage(player, target);
    return;
}
if (result != ContractCheckResult.PASSED) {
    return;
}

if (!executeContract(player, target, hand, name.trim())) {
    FurkinMod.LOGGER.warn("Furkin contract aborted before commit: entity={} player={}",
            target.getUUID(), player.getName().getString());
}
```

原因：非法确认包、换手、换槽位或非法名字不能触发生命值提示。

`pending` 在进入确认函数时已经消费，因此任何确认失败后都必须重新右键发起；不得把旧 pending 恢复后允许旧命名窗口再次确认。

### 4.4 `checkContract(...)`

把 `passesContractChecks(...)` 改成：

```java
private static ContractCheckResult checkContract(
        ServerPlayer player, LivingEntity target, ItemStack hand, ServerLevel level)
```

返回顺序：

| 顺序 | 条件 | 返回 |
|---:|---|---|
| 1 | `target == player || target instanceof Player` | `INVALID_TARGET` |
| 2 | `target.getLevel() != level || !target.isAlive() || target.isRemoved()` | `INVALID_TARGET` |
| 3 | 未注册实体类型 | `UNREGISTERED` |
| 4 | capability 缺失或 `data.isCompanion()` | `ALREADY_COMPANION` |
| 5 | `TamableAnimal` owner 非当前玩家 | `OWNED_BY_OTHER` |
| 6 | 距离超过 4 格 | `OUT_OF_REACH` |
| 7 | 活跃上限已满 | `ACTIVE_LIMIT` |
| 8 | 主手为空或非 `FurkinContractItem` | `INVALID_HAND` |
| 9 | 分类健康判定失败 | `HEALTH_TOO_HIGH` 或 `INVALID_HEALTH` |
| 10 | 其余 | `PASSED` |

活跃上限提示保留在 `checkContract(...)` 内；健康提示只在 `tryContract(...)` 和 `confirmContract(...)` 中触发。

### 4.5 健康判定

```java
private static ContractCheckResult checkHealth(LivingEntity target) {
    HealthGate gate = healthGateFor(target);
    double currentHealth = target.getHealth();
    double maxHealth = target.getMaxHealth();
    if (!isFinitePositive(currentHealth) || !isFinitePositive(maxHealth)) {
        return ContractCheckResult.INVALID_HEALTH;
    }

    double healthPercent = currentHealth * 100.0D / maxHealth;
    boolean percentPass = gate.percentThreshold() >= 100.0D
            || healthPercent <= gate.percentThreshold();
    boolean absolutePass = gate.absoluteThreshold() > 0.0D
            && currentHealth <= gate.absoluteThreshold();
    return percentPass || absolutePass
            ? ContractCheckResult.PASSED
            : ContractCheckResult.HEALTH_TOO_HIGH;
}
```

有效可达性按以下口径记录：

- `currentHealth <= 0` 或 NaN 会在前面的 `isAlive()` 处成为 `INVALID_TARGET`。
- `currentHealth` 为 Infinity 且 `isAlive()` 仍为 true 时，进入 `checkHealth(...)` 并返回 `INVALID_HEALTH`。
- `maxHealth` 为 NaN、Infinity 或 `<= 0` 时，若实体仍存活，则进入 `checkHealth(...)` 并返回 `INVALID_HEALTH`。
- 1.19.2 的 `LivingEntity#getMaxHealth()` 是 `public final float`，最终读取经 `RangedAttribute#sanitizeValue(...)` 处理的 `MAX_HEALTH`；`setBaseValue(NaN)` 会读出最小值 `1.0`，`setBaseValue(0)` 会被钳到 `1.0`。因此不得用 `AttributeInstance#setBaseValue(double)` 构造这两个非法值。

### 4.6 分类与格式化

```java
private static HealthGate healthGateFor(LivingEntity target) {
    if (target instanceof Enemy) {
        return new HealthGate(
                FurkinServerConfig.CONTRACT_ENEMY_HEALTH_PERCENT.get(),
                FurkinServerConfig.CONTRACT_ENEMY_HEALTH_ABSOLUTE.get());
    }
    if (target instanceof NeutralMob) {
        return new HealthGate(
                FurkinServerConfig.CONTRACT_NEUTRAL_HEALTH_PERCENT.get(),
                FurkinServerConfig.CONTRACT_NEUTRAL_HEALTH_ABSOLUTE.get());
    }
    return new HealthGate(
            FurkinServerConfig.CONTRACT_OTHER_HEALTH_PERCENT.get(),
            FurkinServerConfig.CONTRACT_OTHER_HEALTH_ABSOLUTE.get());
}
```

```java
private static boolean isFinitePositive(double value) {
    return Double.isFinite(value) && value > 0.0D;
}

private static void sendHealthBlockedMessage(ServerPlayer player, LivingEntity target) {
    HealthGate gate = healthGateFor(target);
    String percent = formatThreshold(gate.percentThreshold()) + "%";
    Component message = gate.absoluteThreshold() > 0.0D
            ? Component.translatable("furkin.msg.contract_health_with_absolute",
            percent, formatThreshold(gate.absoluteThreshold()))
            : Component.translatable("furkin.msg.contract_health", percent);
    player.displayClientMessage(message, true);
}

private static String formatThreshold(double value) {
    return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT))
            .format(value);
}

private record HealthGate(double percentThreshold, double absoluteThreshold) {
}
```

`checkHealth(...)` 和 `sendHealthBlockedMessage(...)` 都不修改实体状态。

### 4.7 `executeContract(...)`

保持现有职责和提交顺序：

- 不新增健康判定。
- 不新增网络字段。
- 保持活跃上限的局部防御复检。
- 保持 AI 初始化、capability 写入、档案建立、物品最后扣除的顺序。
- `hand.shrink(1)` 仍然只能发生在最终提交成功之后。

## 5. 事件契约

`CommonEvents` 增加导入：

```java
import net.minecraftforge.entity.PartEntity;
```

`onEntityInteract(...)` 在玩家判定后、能力查询前执行：

```java
Entity interactionTarget = event.getTarget();
if (interactionTarget instanceof PartEntity<?> part
        && part.getParent() instanceof LivingEntity parent) {
    interactionTarget = parent;
}
if (!(interactionTarget instanceof LivingEntity target)) {
    return;
}
```

解析后所有业务和距离检查都使用父 `target`。本包不把原始 `PartEntity` 距离传入处理器；极大实体若出现父中心超距，按 `FEASIBILITY_AUDIT.md` 的 F-10 记为后续专项。

契约分支改成：

```java
FurkinContractHandler.ContractCheckResult result =
        FurkinContractHandler.tryContract(player, target, player.getMainHandItem());
if (result == FurkinContractHandler.ContractCheckResult.PASSED
        || result == FurkinContractHandler.ContractCheckResult.HEALTH_TOO_HIGH) {
    event.setCancellationResult(InteractionResult.SUCCESS);
    event.setCanceled(true);
}
```

结果动作表：

| 结果 | 健康提示 | 取消原版交互 | 建立 pending | 发送命名包 |
|---|---:|---:|---:|---:|
| `PASSED` | 否 | 是 | 是 | 是 |
| `HEALTH_TOO_HIGH` | 是 | 是 | 否 | 否 |
| `ACTIVE_LIMIT` | 否 | 否 | 否 | 否 |
| `INVALID_HEALTH` | 否 | 否 | 否 | 否 |
| 其他失败 | 否 | 否 | 否 | 否 |

## 6. 文案契约

`en_us.json`：

```json
"furkin.msg.contract_health": "Target is too healthy to contract (needs ≤ %s).",
"furkin.msg.contract_health_with_absolute": "Target is too healthy to contract (needs ≤ %s or ≤ %s health)."
```

`zh_cn.json`：

```json
"furkin.msg.contract_health": "目标生命值过高（需不高于 %s）",
"furkin.msg.contract_health_with_absolute": "目标生命值过高（需不高于 %s 或 %s 点生命值）"
```

两个语言的键集合必须一致；展示阈值保留 `%` 和格式化后的小数，不参与业务计算。

## 7. 实施顺序

按以下切片提交，每个切片独立编译或运行：

1. 执行契约、可行性和验证矩阵文档。
2. `FurkinServerConfig` 六项配置，只运行配置加载相关验证。
3. `FurkinContractHandler` 结果枚举、`checkContract(...)` 和健康判定；同步更新类/方法 Javadoc。
4. `CommonEvents` 事件结果与 `PartEntity` 解析。
5. 中英文语言键。
6. 服务端夹具与主仓库 `compileJava` / `build` / `runServer`。
7. 原版 EnderDragon 多部件强制验证。
8. 可选 TF 压力矩阵。
9. README / changelog / 版本号 / 最终日志收口。

若第 3 或第 4 切片编译失败，不得开始第 6 切片。

## 8. 服务端夹具设计

夹具名称建议：`ContractHealthFixture`。触发方式建议：

```text
FURKIN_FIXTURE_CONTRACT_HEALTH=core
```

夹具在 `ServerStartedEvent` 执行，复用当前项目已证明可行的 Forge `FakePlayer` 路线。多部件部分固定使用原版 `EnderDragon.head`（类型 `net.minecraft.world.entity.boss.EnderDragonPart`；也可取 `getSubEntities()`）构造 `PlayerInteractEvent.EntityInteract`，再调用真实 `CommonEvents.onEntityInteract(...)`：

- 给 FakePlayer 主手放契约物品。
- 临时通过 `FurkinApi.registerSpecies(...)` 注册测试物种。
- 生成原版实体；EnderDragon 设置 `setNoAi(true)`、移动到 FakePlayer 附近，并设置可复现的生命值。
- 通过真实 `tryContract(...)`、`confirmContract(...)` 和 `CommonEvents.onEntityInteract(...)`。
- 非法生命值的夹具使用一次性 `LivingEntity` 子类，不反射调用私有健康方法。
- 记录结果、物品数、capability 的 `isCompanion()`、档案条目存在性和 pending 副作用。
- 完成后删除夹具源码、临时世界和测试配置。

`INVALID_HEALTH` 夹具必须按以下顺序规避 1.19.2 的属性钳制：

```java
private static final class InvalidMaxHealthWolf extends Wolf {
    private Double invalidMaxHealth;

    InvalidMaxHealthWolf(ServerLevel level) {
        super(EntityType.WOLF, level);
        setHealth(8.0F); // 此时 MAX_HEALTH 仍为正常值。
    }

    void enableInvalidMaxHealth(double value) {
        this.invalidMaxHealth = value;
    }

    @Override
    public double getAttributeValue(Attribute attribute) {
        if (this.invalidMaxHealth != null && attribute == Attributes.MAX_HEALTH) {
            return this.invalidMaxHealth;
        }
        return super.getAttributeValue(attribute);
    }
}
```

- 必须在 `setHealth(...)` 之后启用非法值；否则 `LivingEntity` 构造期和 `setHealth` 的钳制路径会受污染。
- `getAttributeValue(Attribute)` 在 1.19.2 是公开且非 final；只允许一次性夹具覆写，生产代码不得改此访问路径。
- Wolf 已由内置物种注册，夹具无需调用 `registerSpecies(...)` 即可覆盖 `INVALID_HEALTH`。
- 若同时要覆盖 Infinity `currentHealth` 防御分支，可在同一夹具子类用独立开关覆写 `public float getHealth()`；NaN 和非正当前血量仍由 `isAlive()` 先截为 `INVALID_TARGET`。

夹具不得：

- 反射调用 `checkHealth(...)`、`passesContractChecks(...)` 或其他 private 方法。
- 直接改档案来伪造测试结果。
- 将测试物种留在生产注册表。
- 把临时注册写入生产启动路径。`FurkinApi` 没有注销接口，注册只允许存在于一次性 `runServer` 进程；验证后必须停服、删除夹具并重新构建。
- 用 `System.setProperty` 或修改生产配置默认值作为通过条件。

## 9. 回滚与失败保护

按影响范围回滚：

- 只改配置值：恢复六项默认值，不改代码。
- 健康分类错误：保留结果枚举和 pending 改造，回滚 `healthGateFor(...)` / `checkHealth(...)`。
- 提示错误：只回滚 `sendHealthBlockedMessage(...)` / 语言键。
- 多部件解析错误：只回滚 `CommonEvents` 的 `PartEntity` 解析，保留处理器结果模型。
- 网络或协议异常：说明本包不应改动网络；若出现变化，停止并单独审查。

任何回滚都不得把 `tryContract(...)` 恢复为只返回 `boolean` 而保留 `CommonEvents` 的新调用方式；这两个改动必须原子回滚。

## 10. 完成判据

- [x] `ContractCheckResult` 唯一定义且只由 `internal` 使用。
- [x] 六个配置键存在，默认值和范围正确。
- [x] `tryContract(...)` 返回结果且只在 `PASSED` 建 pending。
- [x] `confirmContract(...)` 先校验会话、快捷栏、主手和名字，再做共享检查。
- [x] 健康检查与分类 OR 规则完全一致。
- [x] `INVALID_HEALTH` 与 `HEALTH_TOO_HIGH` 行为不同且均有测试口径。
- [x] `CommonEvents` 只对 `PASSED` 和 `HEALTH_TOO_HIGH` 取消事件。
- [x] 原版 EnderDragon 多部件路径通过强制验证。
- [x] 网络、公开 API、`PROTOCOL_VERSION` 未变化。
- [x] 发布文档、版本和最终 JAR 证据完整。