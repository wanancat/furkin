# Furkin 1.19.2 契约血量前置条件完整工作计划

- 日期：2026-09-27
- 分支：`mc1.19.2`
- 基线：`206a92732c843a7f0aeea72fa7a3a6287b01356d`；当前 `remote-summon-1.19.2` 已收口于 `206a9273`；本包与远召按默认同批收口为 `1.19.2-0.0.3.0`
- 状态：已实施；核心服务端夹具 33/33 通过；人工客户端 A～F 通过，G/H NOT RUN；TF 为环境债务；待乌狸确认提交/推送
- 设计现状：[契约血量前置条件工作流](README.md)
- 可行性复查：[FEASIBILITY_AUDIT.md](FEASIBILITY_AUDIT.md)
- 代码级执行契约：[EXECUTION_CONTRACT.md](EXECUTION_CONTRACT.md)
- 逐项验收模板：[VERIFICATION_MATRIX.md](VERIFICATION_MATRIX.md)
- 1.20.1 参考：`D:\frukin_dev\frukin_1_20_1\docs\contract_precondition_workflow_v2\`
- 适用版本：Minecraft 1.19.2 / Forge 43.2.0 / Java 17

> 本计划中的代码勾选项只有真正实现并取得对应验证证据后才能勾选。当前实现、构建、服务端/客户端启动和原版多部件验证已完成；TF/Hydra 增强项仍标为环境债务。

## 1. 目标

给已经通过物种注册表的契约目标增加生命值资格门槛，并保证：

- 分类规则稳定、可解释、可配置。
- 默认配置不会让 8 点血的原版狼出现明显异常；满血 Wolf 8/8 可通过 NeutralMob 的绝对门槛。
- 发起阶段与确认阶段使用同一套权威校验。
- 血量不符时不建档、不扣物品、不修改能力、名字或 AI。
- 只有生命值确实是唯一失败原因时才显示生命值提示。
- 支持 Forge 多部件实体，以及分类中的非 `Monster` Enemy。
- 不改变 1.19.2 既有待确认会话、服务端权威和客户端隔离设计。

## 2. 非目标

- 不扩大物种注册白名单。
- 不为非 `TamableAnimal` Enemy 实现完整跟随、停战、护主和主动索敌。
- 不提供按物种覆盖的门槛。
- 不把吸收量、护甲或减伤折算入生命值。
- 不提供 UI 配置界面；只提供服务端 TOML。
- 不修改网络协议。
- 不整体合并 1.20.1 分支或直接复制 1.20.1 的访问器写法。
- 不把远程召唤、重复实体修复或 AI 所有权问题混入本包顺手重构。

## 3. 冻结决策

1. 分类优先级固定为 `Enemy > NeutralMob > 其他`。
2. 每个分类使用“百分比 OR 绝对值”双阈值。
3. 最终默认 Enemy 为 `30%` 或 `4` 点，NeutralMob 为 `50%` 或 `8` 点；其他为 `100%`、绝对值禁用。
4. 判定使用 `<=`，恰好等于门槛时通过。
5. `currentHealth` 与 `maxHealth` 必须为有限正数，否则 `INVALID_HEALTH`。
6. 共享校验返回 `ContractCheckResult`，不再只返回 `boolean`。
7. 生命值检查位于主手检查之后、最终提交之前。
8. 只有 `HEALTH_TOO_HIGH` 显示生命值提示并取消实体交互。
9. `tryContract` 与 `confirmContract` 都执行共享校验。
10. `executeContract` 不复制第二套生命值判定。
11. 非 `TamableAnimal` Enemy 明确按弱支持记录。
12. Forge `PartEntity` 交互解析到父实体，作为真实模组兼容修复。
13. 本机制按 `MINOR` 发布；网络协议保持 `"2"`。
14. 以 `206a9273` 为实施基线；远召代码已收口，本包不得回退或重写其逻辑。

## 4. 1.19.2 技术设计

### 4.1 配置

`internal.config.FurkinServerConfig` 新增六项 `ForgeConfigSpec.DoubleValue`：

| 配置键 | 范围 | 默认值 | 语义 |
|---|---:|---:|---|
| `contractEnemyHealthPercent` | 0–100 | 30.0 | Enemy 百分比门槛 |
| `contractEnemyHealthAbsolute` | 0–1024 | 4.0 | Enemy 绝对门槛；0 禁用 |
| `contractNeutralHealthPercent` | 0–100 | 50.0 | NeutralMob 百分比门槛 |
| `contractNeutralHealthAbsolute` | 0–1024 | 8.0 | NeutralMob 绝对门槛；0 禁用 |
| `contractOtherHealthPercent` | 0–100 | 100.0 | 其他分类百分比门槛；100 不限制 |
| `contractOtherHealthAbsolute` | 0–1024 | 0.0 | 其他分类绝对门槛；0 禁用 |

计算模型：

```text
healthPercent = currentHealth * 100.0 / maxHealth
percentPass   = percentThreshold >= 100 || healthPercent <= percentThreshold
absolutePass  = absoluteThreshold > 0 && currentHealth <= absoluteThreshold
healthPass    = percentPass || absolutePass
```

### 4.2 共享结果模型

在 `internal.contract.FurkinContractHandler` 内新增：

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

枚举位于 `internal` 包，不进入公开 API。`PASSED` 只表示前置校验通过，不表示契约已写入。

### 4.3 共享检查顺序

`checkContract(...)` 按以下顺序返回结果：

1. 目标不能是玩家。
2. 目标与玩家同一 `ServerLevel`、存活、未移除。
3. 物种已注册。
4. capability 存在且尚未契约。
5. `TamableAnimal` 既有 owner 必须是当前玩家。
6. 触达距离通过。
7. 活跃上限未满。
8. 主手是契约物品。
9. 分类和生命值门槛通过。

1.19.2 实现要点：

```java
ServerLevel level = player.getLevel();
if (target.getLevel() != level || !target.isAlive() || target.isRemoved()) {
    return ContractCheckResult.INVALID_TARGET;
}
if (player.distanceToSqr(target) > CONTRACT_MAX_DISTANCE * CONTRACT_MAX_DISTANCE) {
    return ContractCheckResult.OUT_OF_REACH;
}
```

不要使用 1.20.1 的 `player.serverLevel()`、`target.level()` 或 `player.canReach(...)`。

### 4.4 健康判定

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

分类只检查接口：

```java
if (target instanceof Enemy) {
    return enemyGate;
}
if (target instanceof NeutralMob) {
    return neutralGate;
}
return otherGate;
```

### 4.5 发起与确认顺序

`tryContract(...)`：

1. 先移除该玩家的旧 pending 会话。
2. 调用 `checkContract(...)`。
3. 若 `HEALTH_TOO_HIGH`，发送生命值提示并返回该结果。
4. 若结果不是 `PASSED`，直接返回。
5. 创建 `PendingContract`。
6. 发送命名请求。
7. 返回 `PASSED`。

`confirmContract(...)`：

1. 消费 pending；无会话直接返回。
2. 校验过期、维度、实体 ID。
3. 按目标 UUID 重新取得实体并校验身份。
4. 校验快捷栏、主手堆叠和名字。
5. 再次调用 `checkContract(...)`。
6. 若 `HEALTH_TOO_HIGH`，发送生命值提示并返回。
7. 只有 `PASSED` 才调用 `executeContract`。

第 4 步必须位于共享检查之前，目的是避免无关的伪造确认包或非法名字触发生命值提示。当前 1.19.2 顺序与该目标不同，必须明确调整。

### 4.6 提示与格式化

只在 `HEALTH_TOO_HIGH` 时发送：

```java
HealthGate gate = healthGateFor(target);
String percent = formatThreshold(gate.percentThreshold()) + "%";
Component message = gate.absoluteThreshold() > 0.0D
        ? Component.translatable("furkin.msg.contract_health_with_absolute",
        percent, formatThreshold(gate.absoluteThreshold()))
        : Component.translatable("furkin.msg.contract_health", percent);
player.displayClientMessage(message, true);
```

`formatThreshold(...)` 使用 `DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT))`，仅影响显示。

### 4.7 多部件实体解析

`CommonEvents.onEntityInteract` 在进入 capability、进食、面板、收回和契约路径前解析父实体：

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

原因：Hydra 等主实体不可拾取，事件目标可能是 `PartEntity`。解析后契约、喂食、面板和收回路径统一使用父实体；原始事件取消仍对部件交互生效。

### 4.8 原版交互语义

- `PASSED`：已发出命名请求，取消实体交互，防止同一右键继续触发原版行为。
- `HEALTH_TOO_HIGH`：显示生命值提示，并取消实体交互；例如避免狼因右键切换坐姿。
- `INVALID_HEALTH`：静默拒绝，不显示错误提示。
- 未注册、错误 owner、超距、已契约、活跃上限等既有失败：保持原行为，不因本次改动扩大取消范围。
- 多部件实体解析为父实体后，取消结果作用于原始交互事件，部件转发链不继续执行。

## 5. 工作包

### WP-0：文档、基线与 API 取证

- [x] 新增 `docs/contract-precondition-1.19.2/`。
- [x] 记录 1.20.1 v2 的冻结语义与最终默认值。
- [x] 用 1.19.2 mapped official jar 核实 `Enemy`、`Monster`、`NeutralMob`、`PartEntity`、`ServerPlayer#getLevel()`。
- [x] 核实 1.19.2 没有 `Player#canReach(Entity, double)`。
- [x] 确认 `remote-summon-1.19.2` 已收口于 `206a9273`，可作为本包基线。
- [x] 发布口径按默认执行：与远召同批发布使用 `1.19.2-0.0.3.0`；工作区已更新 `mod_version`。
- [x] 默认发布口径已冻结；仅在乌狸明确要求远召先单独发布时重开版本切片决定。

验收：

- 文档中的每个 1.19.2 API 事实可追溯到当前依赖，而非 1.20.1 推断。
- 工作区没有为了本包误改远程召唤代码。

### WP-1：配置与分类

- [x] `FurkinServerConfig` 新增六项 `DoubleValue`。
- [x] 默认值和范围与 4.1 一致。
- [x] 实现 `Enemy > NeutralMob > 其他` 分类。
- [x] 使用双阈值 OR 判定。
- [x] 处理非有限、非正生命值。
- [x] 处理绝对值为 0 的禁用语义。
- [x] 处理百分比 100 的不限制语义。
- [x] 编译通过。

验收：

- 配置生成文件包含六项键。
- 已有 `activeLimit` 和远程召唤键不被覆盖或删除。
- 分类函数不依赖 `MobCategory` 或 `Monster` 单一判据。

### WP-2：共享校验与确认权威

- [x] 增加 `ContractCheckResult`。
- [x] `tryContract` 返回值改为 `ContractCheckResult`。
- [x] `passesContractChecks` 改为 `checkContract`，返回结果枚举。
- [x] 生命值检查位于主手检查之后。
- [x] `tryContract` 只对 `HEALTH_TOO_HIGH` 发送提示。
- [x] 只有 `PASSED` 创建 pending 并发送命名包。
- [x] `confirmContract` 调整为先校验会话、快捷栏、主手和名字，再共享校验。
- [x] 确认阶段 `HEALTH_TOO_HIGH` 不执行 `executeContract`。
- [x] `executeContract` 不新增第二套健康判定。
- [x] 保留 pending TTL、单次消费和主手快照。
- [x] 编译通过。

验收：

- 命名期间回血后确认会被拒绝，物品、档案、capability、名字和 AI 不变。
- 非法确认包不会触发健康提示。
- 健康失败不会进入 `executeContract`。
- `INVALID_HEALTH` 静默拒绝。
- `currentHealth <= 0` 或 NaN 先返回 `INVALID_TARGET`，不得误报 `INVALID_HEALTH`。

### WP-3：事件与多部件

- [x] `CommonEvents.onEntityInteract` 解析 `PartEntity#getParent()`。
- [x] 父实体解析发生在 capability 查询和所有分支之前。
- [x] 契约分支改用 `ContractCheckResult`。
- [x] `PASSED` 与 `HEALTH_TOO_HIGH` 都取消原版交互。
- [x] 其他结果不扩大取消范围。
- [x] 检查部件路径没有把 `PartEntity` 强转为 `LivingEntity`。
- [x] 编译通过。

验收：

- 多部件目标能进入契约路径。
- 健康拒绝时原版右键副作用被取消。
- 未注册和错误归属失败保持原行为。

### WP-4：文案与服务端夹具

- [x] `en_us.json` 新增 `furkin.msg.contract_health`。
- [x] `en_us.json` 新增 `furkin.msg.contract_health_with_absolute`。
- [x] `zh_cn.json` 同步两条键。
- [x] 中英文键集合保持一致。
- [x] 使用一次性服务端夹具覆盖正常两步、百分比边界、绝对边界、禁用分支、非法确认和回血确认。
- [x] 用一次性 Wolf 子类覆写 `getAttributeValue(Attribute)` 构造仍存活实体的非法 `maxHealth`；不得把 `setBaseValue(NaN/0)` 当作可达夹具。
- [x] 固定 `currentHealth <= 0/NaN -> INVALID_TARGET` 与非法 `maxHealth -> INVALID_HEALTH` 两条顺序敏感用例。
- [x] 夹具验证完成后删除源码、临时世界和测试配置，最终 JAR 不含夹具类。

建议日志标记：

```text
FURKIN_FIXTURE_CONTRACT_HEALTH_CLASS_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_PERCENT_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_ABSOLUTE_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_INVALID_TARGET_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_INVALID_SILENT_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_SECOND_CHECK_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_FAILURE_READ_ONLY_OK
```

夹具不得使用反射访问私有方法。优先通过公开 `tryContract(...)`、`confirmContract(...)`、真实实体和真实 `PlayerInteractEvent` 路径验证。

### WP-5：主仓库验证

- [x] `compileJava`。
- [x] `build`。
- [x] `runServer`。
- [x] `runClient`。
- [x] 检查 `run/logs/latest.log`，无新增 `ERROR`、`FATAL`、异常栈或资源缺失。

Windows PowerShell 命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

### WP-6：多部件验证（原版强制 + 第三方可选）

#### WP-6.1 原版 EnderDragon 强制验证

- [x] 建立一次性夹具，通过公开 `FurkinApi.registerSpecies(...)` 临时注册 EnderDragon。
- [x] 生成 EnderDragon，并将事件目标设为 `dragon.head` / `getSubEntities()` 中的部件。
- [x] 调用真实 `CommonEvents.onEntityInteract(...)`，验证解析到 EnderDragon 父实体。
- [x] 满血 EnderDragon 命中 Enemy 门槛，事件被取消且无 pending。
- [x] 将 EnderDragon 设为 4 点生命，验证解析成功、建立 pending、命名请求路径可达。
- [x] 通过 `confirmContract(...)` 完成契约，验证父实体建档、物品减少。
- [x] 回归验证喂食、潜行面板、潜行收回路径均使用父实体。
- [x] 验证结束后删除 EnderDragon 夹具、临时实体和临时世界。

#### WP-6.2 第三方 Twilight Forest 增强验证（ENV BLOCKED）

- [ ] 建立 1.19.2 兼容的一次性 Twilight Forest 桥接夹具。
- [ ] 夹具只通过公开 `FurkinApi.registerSpecies` 注册物种，不进入生产 JAR。
- [ ] 记录实际注册数量，不复制 1.20.1 的 `82 / 82` 结论。
- [ ] 同一次运行记录 Hydra 实际最大生命，再取满血、百分比阈值、绝对阈值三点。
- [ ] 验证 Hydra `PartEntity -> LivingEntity parent`。
- [ ] 验证实际契约成功、建档、物品减少。
- [ ] 验证收回后重新召唤，无重复档案或残留部件。
- [ ] 验证命名期间回血后确认拒绝。
- [ ] 检查客户端日志无项目相关错误。
- [ ] 验证结束后删除桥接夹具和临时世界。

以下为可选增强项；当前环境未建立 1.19.2 TF 依赖，整组按 `ENV BLOCKED` 保留：

- [x] 明确记录为残余验证债务。
- [x] 不宣称 Twilight Forest 或 Hydra 已通过。
- [x] 原版 EnderDragon 多部件强制验证和主仓库事件矩阵仍已完成。

### WP-6.3 实施结果（2026-09-27）

- 一次性服务端夹具在临时世界执行，最终日志 `FURKIN_FIXTURE_CONTRACT_HEALTH_SUMMARY pass=33 fail=0`。
- EnderDragon 多部件强制验证通过：满血拒绝、4 点通过、确认建档、喂食、面板、收回均经过真实 `PlayerInteractEvent.EntityInteract` 路径；原始事件目标保持为 `dragon.head`。
- 1.19.2 原版 Cat 实际最大生命为 10；早期文档中的 `8/8` 已修正为 `10/10`。
- Twilight Forest/Hydra 1.19.2 依赖未建立，WP-6.2 保留为 `ENV BLOCKED`，不计入通过声明。

### WP-7：文档与发布收口

- [x] 更新 `README.md` 和 `README.zh-CN.md` 的契约特性说明。
- [x] 更新 `CHANGELOG.md` 和 `CHANGELOG.en.md` 的 `Unreleased` / 发布条目。
- [x] 确认公开 API 未变化，`MAJORAPI` 保持 `0`。
- [x] 确认 `PROTOCOL_VERSION` 保持 `"2"`。
- [x] 默认统一升至 `1.19.2-0.0.3.0`；若发布切片被明确拆分，则改为下一 `MINOR`。
- [x] 确认远程召唤与契约健康门槛若同批发布不会重复占用两个版本号。
- [x] 确认最终 JAR 不含测试夹具。
- [x] 确认工作区没有 `build/`、`run/`、日志或临时诊断文件被误加入提交。

## 6. 验证矩阵

本节只列关闭维度；逐用例前置、操作和证据格式以 [VERIFICATION_MATRIX.md](VERIFICATION_MATRIX.md) 为准。

### 6.1 服务端权威链

| 用例 | 预期 |
|---|---|
| 无 pending 的伪造确认 | 拒绝，无档案、无物品变化 |
| 正常两步契约 | 命中门槛后发送命名包，确认后建档并扣 1 个物品 |
| 命名前命中门槛，命名后回血超过门槛 | 确认拒绝，无档案、无物品变化 |
| 主手被换掉 | 确认拒绝，不发送健康提示 |
| 名字含控制字符或 `§` | 确认拒绝，不发送健康提示 |
| 活跃上限已满 | 返回 `ACTIVE_LIMIT`，沿用现有提示 |
| 主手为空或非契约物品 | 返回 `INVALID_HAND`，不发送健康提示 |
| 实体仍存活但 `maxHealth` 为 NaN/Infinity/非正 | 返回 `INVALID_HEALTH`，静默拒绝 |

> `currentHealth <= 0` 或 NaN 会先被 `isAlive()` 判为 `INVALID_TARGET`。1.19.2 的 `MAX_HEALTH` 会 sanitize/clamp，`setBaseValue(NaN/0)` 不能构造非法最大值；`INVALID_HEALTH` 使用仍存活 Wolf 的一次性子类覆写公开 `getAttributeValue(Attribute)`，在 `setHealth(...)` 之后启用 NaN/0/Infinity。

## 6.2 最终默认值上的分类矩阵

| 场景 | 预期 |
|---|---|
| 满血 Wolf 8/8 | NeutralMob 绝对分支 `8 <= 8`，进入命名窗口 |
| Wolf 4/8 | 恰好 50% 且满足绝对分支，进入命名窗口 |
| 满血 Cat 10/10 | 其他分类 100%，进入命名窗口（1.19.2 实际最大生命为 10） |
| 满血 Zombie 20/20 | Enemy 拒绝，提示 `30% / 4` |
| Zombie 6/20 | 恰好 30%，进入命名窗口 |
| Zombie 4/20 | 绝对分支，进入命名窗口 |
| 未注册 Cow | 无生命值提示，不因健康门槛取消原版交互 |
| 其他玩家的已驯服 Wolf | `OWNED_BY_OTHER`，无生命值提示 |
| NeutralMob 绝对值临时设为 0，Wolf 8/8 | 被 50% 百分比拒绝，提示只显示百分比 |
| Enemy 百分比临时设为 100、绝对值 0 | 满血 Enemy 通过 |

### 6.3 原版交互副作用

| 场景 | 预期 |
|---|---|
| 临时把 NeutralMob 改为 `30% / 4`，满血 Wolf 右键契约 | 拒绝、显示生命值提示、Wolf 坐姿不切换 |
| 命中门槛的 Wolf 右键契约 | 进入命名窗口，原版右键不继续执行 |
| 未注册 Cow 右键契约 | 无健康提示，既有原版交互语义不变 |
| EnderDragon `head` 部件右键，满血 | 解析到父实体，Enemy 门槛拒绝，部件转发链不继续执行 |
| EnderDragon `head` 部件右键，4 点生命 | 解析到父实体，进入命名窗口 |
| Hydra 部件右键且未命中门槛 | 提示生命值过高，部件转发链不继续执行 |
| Hydra 部件右键且命中门槛 | 进入命名窗口，后续契约目标为父实体 |

### 6.4 构建与日志

| 门槛 | 证据 |
|---|---|
| 编译 | `.\gradlew.bat compileJava --console=plain` 成功 |
| 构建 | `.\gradlew.bat build --console=plain` 成功 |
| 服务端 | `runServer` 到达正常启动；夹具结果全部通过 |
| 客户端 | `runClient` 加载资源并完成启动；交互与事件矩阵由一次性服务端夹具验证，客户端无崩溃 |
| 日志 | `run/logs/latest.log` 无新增项目相关 `ERROR`、`FATAL`、异常栈或资源缺失 |
| 产物 | 最终 JAR 不含一次性夹具类 |

## 7. 完成定义

只有同时满足以下条件，WP 才能关闭：

- [x] 六项配置实现并与冻结值一致。
- [x] 分类优先级和双阈值 OR 语义实现。
- [x] 发起与确认共用权威校验。
- [x] 只有 `HEALTH_TOO_HIGH` 显示提示并取消交互。
- [x] 健康失败不写档案、不扣物品、不改能力、名字或 AI。
- [x] pending、TTL、单次消费、身份复检保持。
- [x] 全局活跃上限保持。
- [x] 原版 EnderDragon 多部件父实体解析通过真实事件验证；TF 压力验证完成或列为环境债务。
- [x] 主仓库完成构建、服务端和真实客户端链路验证。
- [x] 原版 EnderDragon 多部件验证完成；第三方多部件验证完成或明确列为残余债务且不作通过声明。
- [x] 中英文语言、README、changelog 与版本号同步。
- [x] 公开 API 与网络协议未变化。
- [x] 非 `TamableAnimal` 弱支持边界已记录。

## 8. 残余验证债务

以下项目不阻塞代码实现，但不得写成已解决：

- 非 `TamableAnimal` Enemy 契约后是否会立刻停战。
- 此类 Enemy 的跟随、护主、解绑 AI 恢复与 `AGGRESSIVE` 索敌。
- 目标卸载后重载。
- 服务器重启后的旧确认包。
- 实体 ID 复用与同位置重建。
- 真实多人并发命名。
- 若无法建立 1.19.2 TF 桥接，Hydra 的实际契约、收回和重召唤链。

## 9. 后续建议

1. 若要把非 `TamableAnimal` Enemy 提升为完整支持，另开生命周期和 AI 工作包。
2. `AGGRESSIVE` 若需覆盖非 `Monster` Enemy，应使用 `LivingEntity.class` 加 `instanceof Enemy` 谓词，不能直接传 `Enemy.class`。
3. 若第三方兼容种类继续增加，可把一次性桥接夹具升级为可选兼容测试模组，但不得进入生产发布 JAR。
4. 若需平衡不同 Boss，再评估按物种或 `EntityType` 覆盖门槛，而不是继续增加全局分类。