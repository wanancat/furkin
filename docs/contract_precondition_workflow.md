# 契约绒亲前置条件工作流（Enemy / NeutralMob / 两者都没有）

> 状态：设计工作流，尚未实现。
> 适用版本：Furkin 1.20.1 / Forge 47.2.0。
> 目标：按官方接口判定契约前置条件，优先级固定为 `Enemy > NeutralMob > 两者都没有`。
>
> 范围说明：本文要求服务端在命名请求前和命名确认后都重算血量门槛。`executeContract` 的“权威”首先指血量判定的权威点；现有确认包还可被改造客户端直接构造，若要让整个契约动作达到完整服务端权威，必须按 §4.3 补齐其他复检，不能把本节误写成已经具备完整防伪能力。
> 范围边界：本文的血量门槛只作用于新建契约的两个阶段；不回溯已有契约，也不借此重做契约后的 AI 或战斗系统。但解除 Enemy 的资格限制后，现有“仅对 `TamableAnimal` 应用跟随与战斗模式”的行为接入边界必须按 §4.4 明确，不能把“能契约”误写成“所有 Enemy 都已成为完整可操控绒亲”。

## 1. 判定口径

契约资格按以下顺序判定，分支互斥：

| 优先级 | 运行时条件 | 分类 | 使用的配置 | 默认要求 |
|---|---|---|---|---|
| 1 | `target instanceof net.minecraft.world.entity.monster.Enemy` | Enemy | `enemyContractHealthThreshold` | 生命值 ≤ 30% |
| 2 | 不是 Enemy，且 `target instanceof net.minecraft.world.entity.NeutralMob` | NeutralMob | `neutralContractHealthThreshold` | 生命值 ≤ 30% |
| 3 | 以上均不满足 | 两者都没有 | `otherContractHealthThreshold` | 生命值 ≤ 100% |

等价伪代码：

```java
if (target instanceof Enemy) {
    return healthPercent(target) <= ENEMY_CONTRACT_HEALTH_THRESHOLD.get();
}
if (target instanceof NeutralMob) {
    return healthPercent(target) <= NEUTRAL_CONTRACT_HEALTH_THRESHOLD.get();
}
return healthPercent(target) <= OTHER_CONTRACT_HEALTH_THRESHOLD.get();
```

必须遵守：

- 先判 `Enemy`，再判 `NeutralMob`，最后走无接口兜底。
- 一个实体同时实现 `Enemy` 和 `NeutralMob` 时，只按 `Enemy` 处理。末影人是典型例子，绝不落入 NeutralMob 门槛。
- `Enemy` 和 `NeutralMob` 是彼此独立的官方接口，不要求二选一，也不是互斥枚举。
- “两者都没有”不是官方标记类型，而是两个 `instanceof` 都为 false 的逻辑补集。
- 不使用 `MobCategory`。`MONSTER`、`CREATURE`、`WATER_CREATURE` 等是生成分类，不是敌意分类。
- 不使用 `Monster` 类作为唯一判据。第三方实体可以像官方标记接口一样直接 `implements Enemy`，不必继承 `Monster`。
- 不根据怒气、当前攻击目标、是否攻击过玩家等动态行为推断分类。
- 不把 `TamableAnimal`、`OwnableEntity` 等行为/归属类型当作第三分类接口；它们不是敌意或中立分类。
- 血量只取 `LivingEntity#getHealth() / LivingEntity#getMaxHealth()`，不把吸收量、护甲或伤害减免折算成血量。
- 本规则只影响新建契约的资格判定，不回溯已有绒亲，也不影响召唤、收回、喂养、成长、技能或已契约后的战斗状态。
- 血量门槛是“契约确认时的瞬时资格”，不是持续状态；契约成功后再回血、受伤或怒气变化，不会自动解约或重新检定。

## 2. 官方接口的组成

### 2.1 `Enemy`

`net.minecraft.world.entity.monster.Enemy` 是无抽象行为的标记接口（1.20.1 中仅含 XP 奖励常量），没有物种白名单，也不要求实体属于某个注册表。模组作者可以在自己的实体类上直接实现它。

已观察到的第三方用法包括：

- Alex's Mobs：`EntityEnderiophage extends Animal implements Enemy`，说明 Enemy 不等于 Monster。
- Ice and Fire：Stymphalian Bird 显式实现 `Enemy`；三系龙属于可驯服动物，不是 Enemy。
- Twilight Forest：Hydra、Knight Phantom、Wraith 等显式实现 `Enemy`。

### 2.2 `NeutralMob`

`net.minecraft.world.entity.NeutralMob` 是官方中立行为接口，与 `Enemy` 没有继承关系。它表达的是中立机制相关行为，不等价于“有攻击性”，也不等价于“非 Enemy”。

常见分类示例：

| 生物 | Enemy | NeutralMob | 本工作流分类 |
|---|---:|---:|---|
| 僵尸 | 是 | 否 | Enemy |
| 末影人 | 是 | 是 | Enemy，因优先级最高 |
| 狼 | 否 | 是 | NeutralMob |
| 猫 | 否 | 否 | 两者都没有 |

第三方实体只要实现 `NeutralMob`，就会进入第二分支；若它同时实现 `Enemy`，仍由第一分支接管。

### 2.3 两者都没有

原版没有 `FriendlyMob`、`PassiveMob`、`OtherMob` 或类似的第三个敌意分类接口。“两者都没有”只是按官方接口穷举后的剩余集合，包括被动生物、友好生物、可驯服动物以及其他未实现这两个接口的实体。

`TamableAnimal` 和 `OwnableEntity` 也存在，但它们描述驯服/归属行为，不是敌对分类，不能拿来替代第三分支。某个可驯服实体是否进入 NeutralMob 或 Enemy，仍只按它实际实现的接口和本节优先级决定。

这一分支使用第三项配置，默认 `100`，在正常合法生命值下等价于不设置血量限制；如果整合包需要，也可以单独调整它。

## 3. 配置项

在 `internal.config.FurkinServerConfig` 的“契约 / 拥有”区段、`ACTIVE_LIMIT` 附近增加三项。

| 配置键 | 类型与范围 | 默认值 | 语义 |
|---|---|---|---|
| `enemyContractHealthThreshold` | double，0–100 | `30.0` | Enemy 分支可契约的最高生命值百分比 |
| `neutralContractHealthThreshold` | double，0–100 | `30.0` | NeutralMob 分支可契约的最高生命值百分比 |
| `otherContractHealthThreshold` | double，0–100 | `100.0` | 两者都没有的生物可契约的最高生命值百分比 |

建议写法：

```java
public static final ForgeConfigSpec.DoubleValue ENEMY_CONTRACT_HEALTH_THRESHOLD = BUILDER
        .comment("Maximum health percentage for contracting a target that implements Enemy.",
                "Percentage of current health to maximum health, in the range 0-100.",
                "100 allows contracting at full health; 0 effectively disables contracting this category.",
                "Enemy takes precedence over NeutralMob.",
                "Design intent: 30.")
        .defineInRange("enemyContractHealthThreshold", 30.0D, 0.0D, 100.0D);

public static final ForgeConfigSpec.DoubleValue NEUTRAL_CONTRACT_HEALTH_THRESHOLD = BUILDER
        .comment("Maximum health percentage for contracting a target that implements NeutralMob but not Enemy.",
                "Percentage of current health to maximum health, in the range 0-100.",
                "100 allows contracting at full health; 0 effectively disables contracting this category.",
                "Design intent: 30.")
        .defineInRange("neutralContractHealthThreshold", 30.0D, 0.0D, 100.0D);

public static final ForgeConfigSpec.DoubleValue OTHER_CONTRACT_HEALTH_THRESHOLD = BUILDER
        .comment("Maximum health percentage for contracting a target that implements neither Enemy nor NeutralMob.",
                "Percentage of current health to maximum health, in the range 0-100.",
                "100 allows contracting at full health; 0 effectively disables contracting this category.",
                "Design intent: 100 (no health prerequisite).")
        .defineInRange("otherContractHealthThreshold", 100.0D, 0.0D, 100.0D);
```

生命值统一按百分比判定：

```text
healthPercent = currentHealth / maxHealth * 100
```

只有在 `LivingEntity#isAlive()` 为 true、`!target.isRemoved()` 且 `maxHealth` 为有限正数时才计算；死亡、已移除或最大生命值非法（0、负数、NaN、Infinity）的目标不能通过。使用 `<=` 比较：

- 阈值 `100`：正常满血也通过。两者都没有的生物默认因此仍可直接契约；它不是绕过存活和目标合法性检查的开关。
- 阈值 `0`：活体生物通常仍有一点以上生命值，所以实际效果是禁用该类生物的契约。
- 不使用绝对血量，避免不同模组生物的最大生命值差异导致默认值失真。
- 精确比较使用 `double`，不要先四舍五入或转成整数；否则恰好位于边界附近的生物可能被错误放行。

## 4. 运行时流程

契约分两步，血量是**瞬时状态**。命名窗口打开期间目标可能回血、受伤或状态发生变化，因此两个阶段都必须检查。

### 4.1 `tryContract`：发命名请求前

现有边界顺序：

1. 已注册物种。
2. 能力对象存在。
3. 尚未契约。
4. 未超过活跃上限。
5. **新增：按 `Enemy > NeutralMob > 两者都没有` 判定并检查血量门槛。**
6. 发送 `RequestContractNamePacket`。

血量门槛放在活跃上限之后，保留现有反馈优先级：活跃上限已满时仍先提示上限。

### 4.2 `executeContract`：命名确认后、落契约前

现有防御性复检顺序：

1. 能力对象存在且尚未契约。
2. 未超过活跃上限。
3. **新增：重新按同一优先级检查契约血量门槛。**
4. 写入 `FurkinData`、建档、设置名字、消耗契约物品。

`executeContract` 是血量门禁在确认阶段的权威判定点，不是对现有确认包完整安全性的证明。客户端只负责提交名字，不提交“已经过血量校验”的信任声明；服务端必须按确认时的实体状态重新判定。血量检查本身不能替代注册表、存活、手持物品、距离和维度等复检。

这些复检必须全部在第一个状态变更（能力写入、UUID 生成、建档、设置名字、消耗物品、同步）之前完成；失败路径必须保持目标、物品和档案完全不变。

推荐的公共判定骨架：

```java
private static double contractHealthThreshold(LivingEntity target) {
    if (target instanceof Enemy) {
        return FurkinServerConfig.ENEMY_CONTRACT_HEALTH_THRESHOLD.get();
    }
    if (target instanceof NeutralMob) {
        return FurkinServerConfig.NEUTRAL_CONTRACT_HEALTH_THRESHOLD.get();
    }
    return FurkinServerConfig.OTHER_CONTRACT_HEALTH_THRESHOLD.get();
}

private static boolean passesContractHealthGate(LivingEntity target) {
    if (!target.isAlive() || target.isRemoved()) {
        return false;
    }

    float maxHealth = target.getMaxHealth();
    if (!Float.isFinite(maxHealth) || maxHealth <= 0.0F) {
        return false;
    }

    double healthPercent = target.getHealth() * 100.0D / maxHealth;
    return healthPercent <= contractHealthThreshold(target);
}
```

失败时按阶段处理：

- `tryContract` 失败：不发送命名请求，返回 `false`。
- `executeContract` 失败：不写入能力数据、不建档、不消耗物品、不发送同步包。
- 只有血量门槛确实是当前唯一失败原因时，才使用 action bar 显示 `furkin.msg.contract_health`。目标已死亡、已移除、未注册、超出距离、主手物品失效等情况不能误报成“血量过高”。
- 常规门槛失败记录 `DEBUG`，只在配置异常或内部状态异常时记录 `INFO`/`WARN`。血量门槛会由高频右键触发，不能每次失败都写 `INFO`，否则日志会持续膨胀。

如果 `tryContract` 因血量门槛返回 `false`，当前调用方不会取消 `PlayerInteractEvent.EntityInteract`，因此原版或第三方实体的 `mobInteract` 仍可能继续执行。例如对已驯服的 `NeutralMob` 生物可能出现坐下/站起或其他右键副作用。这里必须明确二选一：

1. 保持现状：只提示失败，不吞掉原版交互；在验证矩阵中接受并记录该行为。
2. 拦截失败：把 `tryContract` 改成语义明确的结果枚举，并在门槛失败时取消事件。

不能在文档中笼统写“阻止契约”而不说明原版交互是否继续。

### 4.3 确认包的安全边界与完整权威性

`ConfirmContractPacket` 是客户端可构造的网络请求，不能把“客户端显示过命名窗口”或“客户端曾通过一次血量检查”当作信任凭证。当前服务端确认路径只复检能力存在、尚未契约和活跃上限；它会从玩家当前 `ServerLevel` 按 ID 取实体，但没有复检注册表、存活、主手物品、距离和原始维度身份，因此：

- 血量门槛必须在确认阶段重新计算，这是本功能的硬要求。
- 若要让 `executeContract` 成为整个契约动作的完整权威入口，至少还要按确认时的服务端状态复检：目标来自玩家当前 `ServerLevel`、仍为已注册物种、`isAlive()`、能力存在且尚未契约、主手仍持有 `FurkinContractItem`、未超过活跃上限、玩家与目标处于原版可达范围，以及最终血量门槛。
- 距离检查应与原版服务端实体交互保持一致。1.20.1/Forge 47.2.0 的服务端实体交互使用 `ServerPlayer#canReach(target, 3.0D)`；确认包不应接受明显超距的目标。
- `entityId` 只在对应 `ServerLevel` 内有明确含义。玩家换维度后，不能拿一个仅含整数 ID 的包证明它仍指向原来的实体；应从玩家当前 `ServerLevel` 取实体并重新做全部条件校验。
- `entityId` 只是查找键，不是身份凭证。即使不换维度，原实体被移除/重建后也不能排除同 ID 复用；若产品要求“同一只实体”，应使用目标 UUID/会话令牌，而不是只比对整数 ID。
- 如果产品要求“确认时必须还是最初右键的那只实体”，仅靠 `entityId` 不能满足，需要服务端 pending 记录（例如玩家 UUID → 目标 UUID、过期时间）或协议中加入会话令牌/UUID。如果产品只要求“相当于对当前合法实体发起一次合法契约”，完整重检即可，不必扩协议，但必须写清这一语义。
- 使用 pending/会话令牌时还要定义生命周期：新请求覆盖旧请求、TTL、玩家登出、目标死亡/卸载、换维度和服务器停服时如何清理；客户端取消通常不会通知服务端，不能假设服务端能即时清空。
- 当前代码在确认路径中直接把 `player.getMainHandItem()` 交给 `executeContract`。如果确认时玩家已经换成空手或其他物品，现有逻辑可能免费契约，甚至消耗错误的物品堆叠。无论是否合并完整安全加固，确认阶段至少必须拒绝空手、非 `FurkinContractItem` 的主手物品。
- 还要定义“合法主手”是任意一叠契约物品，还是最初右键的那一叠。后者必须记录手/槽位或堆叠标识；前者只需确认当前主手仍为非空契约物品，并明确会消耗当前这一叠。
- 现有两阶段都没有检查目标是否已被其他玩家驯服或拥有。对 `TamableAnimal`，确认成功会直接执行 `setOwnerUUID(ownerUuid)`，可能把别人的宠物转移给发起者。这不是血量特有条件，但扩大注册物种范围后会放大影响；实现前必须定义允许还是拒绝，若拒绝则在两阶段都复检归属并纳入竞态矩阵。
- 可选但建议的完整加固还包括：客户端输入框当前限制为 32 字符并在发送前 `trim()`，服务端却使用 `readUtf()` 的协议默认上限（32767）；若纳入加固，应把服务端校验对齐到 32 字符并拒绝控制字符/换行，不能只依赖客户端。该加固与血量功能相互独立，不应悄悄省略。

实现范围必须明确选一种：

- 只做血量门槛：保留现有其他缺口，文档不得声称确认路径已经完整权威。
- 同时补最小防伪：至少加入注册表、存活、主手契约物品、距离/当前维度复检。
- 完整权威化：再按上面的完整清单和 pending/会话语义处理，并相应增加测试。


### 4.4 Enemy 物种的既有行为接入边界

血量门槛只决定“能不能契约”，不会自动把任意敌人变成完整可控的绒亲。放开 Enemy 物种前，必须先明确以下既有边界：

- `executeContract` 会把 `FurkinData` 的 `combatMode` 写成 `FOLLOW`，但只有在目标是 `TamableAnimal` 时才调用 `setTame`/`setOwnerUUID`、清坐定并 `applyTo(...)`。非 `TamableAnimal` 的 Enemy 即使成功建档，也不会由这段代码接管跟随、目标选择和战斗模式；它可能继续沿用原版或模组的敌对 AI，甚至在契约后继续攻击主人。
- 因而“第三方实体能实现 `Enemy`，且能进入血量分类”不等于“该实体已支持绒亲生命周期”。对非 `TamableAnimal` 物种，契约、召回、重召唤、解绑、死亡、AI 状态和持久化都需要独立的兼容层；若本轮不做，必须限制可注册范围，或在发布说明中明确为未支持。
- 现有 `FurkinCombatMode.AGGRESSIVE` 的目标筛选是 `Monster.class`，不是 `Enemy`。因此 `EntityEnderiophage` 这类 `implements Enemy` 但不继承 `Monster` 的生物不会被 AGGRESSIVE 主动攻击。这属于既有战斗模式语义，不是本次血量门槛可以顺带修复的部分。
- 不能只把 `Monster.class` 改成 `Enemy.class`：1.20.1 的 `Enemy` 不继承 `LivingEntity`，而 `NearestAttackableTargetGoal<T extends LivingEntity>` 的泛型不接受该类型。若要统一为 Enemy 语义，应使用 `LivingEntity.class` 加 `instanceof Enemy` 谓词，或编写专用目标逻辑，并分别验证原版 Monster 与第三方 Enemy。
- 实现前必须选择：只允许行为层已支持的 Enemy 物种；为所有注册 Enemy 补独立 AI 与生命周期；或明确接受“只建档，不保证跟随、停战或参战”的弱支持。无论选择哪一种，都要写入支持范围和验证矩阵，不能留下“契约成功后会自动变成普通宠物”的隐含假设。

## 5. 本地化与反馈

在 `zh_cn.json` 和 `en_us.json` 同步增加一个反馈键。两个文件的键集合必须一致。

建议文案：

```json
"furkin.msg.contract_health": "目标生命值需不高于 %s%% 才能契约"
```

```json
"furkin.msg.contract_health": "Target health must be at or below %s%% to contract"
```

参数为当前分类适用的阈值。分类本身由服务端判定；客户端不根据实体类做预测，也不缓存门槛状态。`Double` 直接以 `%s` 传入时可能显示成 `30.0%`；若要求整数显示，应在服务端先格式化，或使用双方都验证过的格式写法，并同时检查中英文语言文件。

## 6. 改动清单

| 文件 | 改动 | 说明 |
|---|---|---|
| `internal/config/FurkinServerConfig.java` | 新增三个 DoubleValue 配置 | Enemy、NeutralMob、两者都没有各一项，范围 0–100 |
| `internal/contract/FurkinContractHandler.java` | 引入 `Enemy`、`NeutralMob`；新增优先级判定和门槛检查；在 `tryContract` 与 `executeContract` 各调用一次 | 核心改动集中在此；同时按 4.3 决定确认阶段的最低防线 |
| `internal/event/CommonEvents.java` | 视 4.2 的交互失败决策决定是否修改 | 若要吞掉门槛失败的右键，需要结果枚举；若保持现状则不改分支结构 |
| `internal/network/ConfirmContractPacket.java` | 若选择完整权威化或同一目标语义，增加确认阶段校验/会话信息 | 只做血量门槛时可以不改网络包，但必须承认现有身份与距离缺口 |
| `internal/network/FurkinNetwork.java` | 若确认包字段或会话语义变化，提升 `PROTOCOL_VERSION` | 只做血量门槛不改协议；加入 UUID/会话令牌或改变编码字段时必须同步 |
| `src/main/resources/assets/furkin/lang/zh_cn.json` | 新增反馈键 | 与英文键同步，并验证阈值显示格式 |
| `src/main/resources/assets/furkin/lang/en_us.json` | 新增反馈键 | 与中文键同步，并验证阈值显示格式 |
| `gradle.properties` | 按项目版本规则递增版本 | 新增机制应递增 MINOR 并重置 PATCH；当前若从 `1.20.1-0.0.1.1` 发布，候选为 `1.20.1-0.0.2.0` |
| `CHANGELOG.md`、`changelog.en.md` | 在对应版本记录用户可见行为 | CurseForge 上传时只提取本次版本节，不粘贴全历史 |
| `README.md`、`README.zh-CN.md` | 视发布口径补充配置说明 | 若该行为面向整合包作者，建议明确写入 |

如果只做血量门槛，不需要新增网络包、不需要改变协议版本，也不需要新增公开 API 或客户端实体分类代码。若按 §4.3 给确认包加入会话令牌/UUID 或改变编码字段，则必须同步提高 `FurkinNetwork.PROTOCOL_VERSION`，并把它列入改动和双端验证范围。

## 7. 验证计划

### 7.1 构建与启动

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

检查 `run/logs/latest.log`：不得出现新的 `ERROR`、`FATAL`、异常栈、注册失败或资源缺失。首次运行服务端若生成 `run/eula.txt`，必须由用户本人同意 Minecraft EULA 后再改为 `eula=true`；代理不能代为同意。

### 7.2 功能验证矩阵

| 场景 | 期望 |
|---|---|
| Enemy，生命值高于敌对阈值 | 阻止契约，不扣物品，显示 action bar |
| Enemy，生命值恰好等于阈值 | 允许契约，因为比较关系是 `<=` |
| Enemy，生命值低于阈值 | 允许契约 |
| 末影人，Enemy 20%、NeutralMob 80%，当前 50% | 因 Enemy 分支被阻止；不能错误落入 NeutralMob 而放行 |
| 末影人，当前 10%，两阈值如上 | 允许契约，证明仍使用 Enemy 分支 |
| NeutralMob，默认阈值 30，满血 | 阻止契约 |
| NeutralMob，生命值恰好等于 30% | 允许契约 |
| 狼，默认配置 | 按 NeutralMob 处理，满血时被阻止 |
| 猫等两者都没有的生物，默认阈值 100，满血 | 允许契约 |
| 两者都没有的生物，`otherContractHealthThreshold` 低于 100 且高于门槛 | 阻止契约 |
| 第三方实体显式 `implements Enemy`，高于阈值 | 阻止契约 |
| 实体同时实现 Enemy 和 NeutralMob，且两项阈值不同 | 始终使用 Enemy 阈值 |
| 命名窗口打开后目标回血到阈值以上 | 确认时被拒绝，不扣物品 |
| 命名窗口打开后目标降到阈值以下 | 确认时允许契约 |
| 命名窗口期间目标死亡或被移除后伪造确认 | 不落契约、不扣物品，也不能误报为单纯血量过高 |
| 活跃上限已满且目标血量不合格 | 优先提示活跃上限，保持现有顺序 |
| Enemy 阈值设为 0 | 对 Enemy 实际禁用 |
| NeutralMob 阈值设为 0 | 对 NeutralMob 实际禁用 |
| 两者都没有的生物阈值设为 0 | 对该分支实际禁用 |
| 阈值设为 100 | 对正常合法生命值的对应分类无额外血量限制 |
| 取消命名窗口 | 不落契约，不扣物品 |

若选择保持 §4.2 的现有交互语义，还要补充一条真实验证：血量不合格的 `NeutralMob` 右键后，原版 `mobInteract` 是否产生坐下/站起等副作用；该结果必须记录，不能把“未取消事件”写成“已完全拦截右键”。

### 7.3 行为兼容矩阵（按 §4.4 选定范围执行）

| 场景 | 期望 |
|---|---|
| 注册为可契约物种的非 `TamableAnimal` Enemy，契约成功后 | 按 §4.4 语义验证；当前不会自动应用跟随与战斗模式，不能把“建档成功”写成“已完整接管 AI” |
| 非 `TamableAnimal` Enemy 在契约前正在攻击玩家 | 若未实现 AI 兼容层，必须记录契约后是否停战；不能假定血量门槛会清除攻击目标 |
| `AGGRESSIVE` 绒亲面对 Enemy 但不继承 `Monster` 的第三方生物 | 当前不会被主动索敌；若产品口径是全 Enemy，必须实现谓词或专用逻辑后改为期望命中 |
| 解绑、收回或重召唤非 `TamableAnimal` Enemy | 若未接生命周期，应明确支持结果；不能出现状态已清除但 AI 或召唤行为半接管 |
| 已有能力数据的 `TamableAnimal` 解绑后 | 原有目标选择器是否完整复原；该回归属于既有战斗模式边界，不应被血量门槛掩盖 |

### 7.4 权威性与竞态矩阵（按 §4.3 选定范围执行）

| 场景 | 期望 |
|---|---|
| 不触发 `tryContract`，直接伪造 `ConfirmContractPacket` | 仍要经过完整服务端复检；不能免费或契约未注册实体 |
| 命名窗口期间把主手换成空手 | 拒绝契约，不写数据、不建档、不消耗其他物品 |
| 命名窗口期间把主手换成普通物品 | 拒绝契约，绝不能消耗普通物品 |
| 命名窗口期间主手换成另一叠契约物品 | 按产品语义处理：若要求同一堆叠则拒绝；若只要求合法主手则只消耗当前这一叠，测试必须固定预期 |
| 命名窗口期间玩家离开可达范围 | 拒绝契约 |
| 命名窗口期间玩家换维度，当前维度存在同 ID 实体 | 不得误契约当前维度同 ID 实体；应按会话语义拒绝或完整重检 |
| 命名窗口期间同一维度原目标被移除，另一个实体复用了相同 ID | 不得误契约新目标；若要求“同一只实体”，应因 UUID/会话不匹配而拒绝 |
| 命名窗口期间目标已被其他路径契约 | 再次确认不能覆盖或重复建档 |
| 两名玩家几乎同时确认同一目标 | 至多一名玩家成功，另一名被已契约/上限规则拦截 |
| 目标在命名窗口期间被其他玩家驯服或转交 | 按归属语义拒绝或明确允许，不能让所有权在未定义的情况下被静默转移 |
| 同一玩家重复发送同一确认包 | 只有第一次可成功，后续不能再次扣物品、重复建档或覆盖数据 |
| 命名窗口期间活跃上限被其他契约占满 | 确认阶段再次拦截并给出活跃上限反馈 |
| 目标包只携带非活体或不存在实体 ID | 安静拒绝，不抛异常 |
| 改造客户端提交超长或异常名字 | 若纳入完整加固，应按客户端同样上限拒绝或截断 |
| 确认时目标已不再属于注册物种 | 拒绝契约，不写数据、不扣物品 |
| 改造客户端提交超过 32 字符、控制字符或换行的名字 | 若纳入完整加固，应在服务端拒绝或按同一规则规范化，不能只依赖客户端 |

### 7.5 回归点

- 未注册物种仍不响应契约。
- 已契约实体仍不能重复契约。
- `ConfirmContractPacket` 仍由服务端按实体 ID 重新取实体。
- 契约成功后仍只消耗一张契约，命名、建档、能力同步流程不变。
- 活跃上限的现有提示和计数逻辑不变。

## 8. 工作量与服务器负荷

- 最小血量门槛改动集中在配置、一个处理器和两份语言文件；无持久化格式变更。若保持当前网络协议，也没有新增包。
- 若同时补 §4.3 的确认包防线，工作量会增加：至少涉及确认包/处理器的校验顺序和错误反馈；若要求证明“仍是同一只目标”，还要增加服务端 pending 状态或协议字段。
- 若加入 pending/会话令牌，额外成本主要在生命周期、TTL、登出/换维度清理和测试；内存与运行负荷仍很小，但状态复杂度上升。
- 血量判定、注册表检查、复检主手物品和 `canReach` 都是 O(1) 操作，只在右键和命名确认时触发。
- 现有 `countActive`/`countSummoned` 只扫描传入 `ServerLevel` 的已加载实体；如果活跃上限按玩家跨维度全局计算，确认阶段的复检不会自动修复这个既有缺口，需单独定义。
- 不增加每 tick 扫描；相对现有 `countActive` 的边界扫描，这些额外检查的负荷可忽略。
- 主要风险不是性能，而是命名窗口期间的瞬时状态、确认包伪造、目标归属语义和失败后的原版交互副作用。双阶段校验、权威性矩阵和安全回归必须按选定范围完成。
- 若要让非 `TamableAnimal` Enemy 真正具备跟随、停战、参战、解绑和重召唤能力，需要新增独立 AI 与生命周期适配，工作量远高于三项配置和双阶段血量门禁，不能与最小实现混算。

## 9. 实施检查表

- [ ] 明确本文的实现范围：只做血量门槛、同时补 §4.3 的确认包权威性，还是把 §4.4 的 Enemy 行为接入一并纳入。
- [ ] 明确本规则只作用于新契约，不回溯已有绒亲，也不改变召唤、成长和战斗流程。
- [ ] 增加 `enemyContractHealthThreshold`，默认 30，范围 0–100。
- [ ] 增加 `neutralContractHealthThreshold`，默认 30，范围 0–100。
- [ ] 增加 `otherContractHealthThreshold`，默认 100，范围 0–100。
- [ ] 实现 `Enemy > NeutralMob > 两者都没有` 的固定优先级。
- [ ] 确保同时实现 Enemy 和 NeutralMob 的实体使用 Enemy 阈值。
- [ ] 在 `tryContract` 的活跃上限检查之后加入血量门槛。
- [ ] 在 `executeContract` 的活跃上限复检之后再次检查血量门槛。
- [ ] 判定时排除已移除目标和非有限 `maxHealth`，并保持 `double` 比较。
- [ ] 明确门槛失败时 `PlayerInteractEvent.EntityInteract` 是否取消，并验证原版交互副作用。
- [ ] 若选择完整权威化，确认阶段复检注册表、存活、主手 `FurkinContractItem`、当前维度、`canReach(target, 3.0D)` 和活跃上限。
- [ ] 若选择完整权威化，定义 `entityId` 身份语义、pending/会话 token 的替换/TTL/登出清理，以及是否要求同一手/槽位。
- [ ] 若只做血量门槛，明确保留“确认包可伪造、可能免费契约/消耗错误物品”的已知缺口，不写成完整安全实现。
- [ ] 明确已驯服或已有归属的目标能否被其他玩家契约；若拒绝，在 `tryContract` 与 `executeContract` 两处复检归属。
- [ ] 明确非 `TamableAnimal` Enemy 的支持范围；若不做 AI 与生命周期接管，不能宣称它们已是完整可操控绒亲。
- [ ] 若要求 `AGGRESSIVE` 覆盖全部 Enemy，使用 `LivingEntity` 筛选或专用目标逻辑，并补第三方 Enemy 回归；不要直接替换成不满足泛型约束的 `Enemy.class`。
- [ ] 若确认包字段或协议语义变化，提升 `FurkinNetwork.PROTOCOL_VERSION` 并完成双端验证。
- [ ] 若做名字加固，服务端按 32 字符和字符集校验，不依赖客户端。
- [ ] 区分“血量过高”和“目标死亡/未注册/超距/主手失效”等失败原因，避免错误提示。
- [ ] 常规门槛失败使用 `DEBUG`，避免每次右键刷 `INFO` 日志。
- [ ] 同步添加中英文语言键，并验证阈值显示格式。
- [ ] 按新增机制递增 `gradle.properties` 版本并重置后续段。
- [ ] 更新中英文 changelog；按发布口径决定是否补充 README。
- [ ] 运行 `compileJava`、`build`、`runServer`、`runClient`。
- [ ] 检查 `run/logs/latest.log` 无新增错误，并完成功能矩阵、行为兼容矩阵、权威性/竞态矩阵和回归点。
