# 契约血量前置条件：独立可行性复查

- 复查日期：2026-09-27
- 复查对象：本目录设计摘要、实施计划、执行契约、验证矩阵和调研记录
- 实施基线：`206a92732c843a7f0aeea72fa7a3a6287b01356d`
- 1.20.1 参考基线：`5ad0924`
- 复查结论：在 1.19.2 / Forge 43.2.0 上可完整实施；关键修正覆盖基线/发布口径、`INVALID_HEALTH` 可达路径、多部件强制验证与跨路径回归范围
- 说明：本文件保留实现前的独立复查快照，不在本文件中替代验收报告；实现后的命令、夹具和产物证据见 `VERIFICATION_MATRIX.md`

## 1. 复查方法

按以下顺序独立复核：

1. 读取当前 1.19.2 契约处理、事件处理、配置和网络入口。
2. 读取 1.20.1 当前已实现版本，而不是只依赖旧计划文档。
3. 用 1.19.2 mapped official jar 的 `javap` 核对接口、访问器、事件构造器和测试夹具所需公开 API。
4. 检查每个设计要求是否有实际可调用的公开路径。
5. 检查每个验证项是否能在不反射访问产品私有方法的前提下完成。
6. 检查版本、协议、文档和测试证据是否能形成闭环。

## 2. 结论

核心方案可行，原因是：

- 1.19.2 的 `Enemy`、`Monster`、`NeutralMob` 类型关系满足分类设计。
- 1.19.2 的 `ServerPlayer#getLevel()` 和 `target.getLevel()` 足以替代 1.20.1 的访问器。
- 当前 1.19.2 已有完整的两阶段服务端权威链，只需扩展结果模型和健康判定，不需要重做 pending 会话。
- 1.19.2 的 Forge `PartEntity#getParent()` 与 `PlayerInteractEvent.EntityInteract` 构造器都是公开 API，能做多部件真实事件测试。
- 原版 EnderDragon 是 `Mob implements Enemy`，其 `head` / `getSubEntities()` 是 `PartEntity<EnderDragon>`；可以先用原版多部件完成强制验证，再决定是否需要第三方 TF 桥接。
- 当前缺口的调用面很小：`FurkinContractHandler` 的结果返回类型只被 `CommonEvents` 调用；`ConfirmContractPacket`、网络包字段和协议无需修改。

## 3. 复查发现与修正

### F-00：1.20.1 参考文档有历史元数据滞后，代码口径已复核

1.20.1 当前分支 HEAD 为 `5ad0924`，`FurkinServerConfig` 已提交 NeutralMob `50.0 / 8.0`；但该分支 README 头部仍保留“基线 `35d931c`、50/8 尚未提交”的历史文字。本次 1.19.2 文档不照抄该头信息，而是以 1.20.1 当前代码和 HEAD 实际值为准：

- Enemy：`30 / 4`。
- NeutralMob：`50 / 8`。
- 其他：`100 / 0`。
- 协议：`2`。

### F-01：原稿基线已过期，已修正

原稿写的是 `642f29d` 加未提交的远召改动。实际当前分支已提交：

```text
206a9273 feat: 增加真正远距召唤功能包并收口 P0-P2
```

处理：

- 文档基线改为 `206a9273`。
- 删除“远召未提交”的阻断性描述。
- 保留约束：本功能不得回退或重写远召逻辑，只改本包列出的共享文件。

### F-02：`INVALID_HEALTH` 的测试口径已按 1.19.2 API 修正

当前检查顺序中，`!target.isAlive()` 位于健康检查之前。`currentHealth <= 0` 或 NaN 时：

- `isAlive()` 返回 false；
- `checkContract(...)` 先返回 `INVALID_TARGET`；
- 不会到达 `INVALID_HEALTH`。

`maxHealth` 的构造比原稿假设更严格。1.19.2 mapped official jar 的 `javap` 已核实：

- `LivingEntity#getMaxHealth()` 是 `public final float`，直接读取 `getAttributeValue(Attributes.MAX_HEALTH)`；
- `AttributeInstance#getValue()` 最终调用 `Attribute#sanitizeValue(...)`；
- `MAX_HEALTH` 是 `RangedAttribute`，范围 `1.0..1024.0`；
- `sanitizeValue(NaN)` 返回最小值 `1.0`，`0.0` 被 `Mth.clamp` 钳到 `1.0`。

因此，`AttributeInstance#setBaseValue(Double.NaN/0.0)` 不能让正常实体的 `getMaxHealth()` 返回非法值，原稿的夹具路线不可执行。

修正后的可达性口径：

| 输入 | 先命中的结果 |
|---|---|
| `currentHealth <= 0` | `INVALID_TARGET`，因为 `isAlive()` 先失败 |
| `currentHealth` 为 NaN | `INVALID_TARGET`，因为 `isAlive()` 先失败 |
| `currentHealth` 为 Infinity 且实体仍被判定存活 | `INVALID_HEALTH` |
| `maxHealth` 为 NaN/Infinity/0，且实体仍被判定存活 | `INVALID_HEALTH` |
| 两者均有限正数 | 进入分类门槛判定 |

夹具必须用不反射的产品外路径构造非法值：

- 定义仅存在于一次性夹具中的 `LivingEntity` 子类，例如继承 `Wolf`；
- 构造和 `setHealth(8.0F)` 阶段保持正常属性；
- 随后通过开关覆写公开且非 final 的 `getAttributeValue(Attribute)`，仅对 `Attributes.MAX_HEALTH` 返回 `NaN`、`0` 或 `Infinity`；
- 这样实体仍存活，能进入 `checkHealth(...)` 并取得 `INVALID_HEALTH`；
- 若还要覆盖 Infinity 当前血量的防御分支，可在同一夹具子类用独立开关覆写 `public float getHealth()`；NaN 和非正当前血量仍由 `isAlive()` 先截为 `INVALID_TARGET`；
- 夹具源码、类文件引用和临时运行配置全部删除后重新构建。

### F-03：发布版本口径已按默认决策收口

复查时远召已经提交，但 `gradle.properties` 仍为 `1.19.2-0.0.2.0`，远召功能仍在两份 changelog 的 `[Unreleased]` 下。该观察现已按默认发布口径收口：工作区版本为 `1.19.2-0.0.3.0`，两份 changelog 已生成同批发布条目。

本包采用以下默认口径：

- 实现期间曾不改 `gradle.properties`；发布收口时已统一升到 `1.19.2-0.0.3.0`。
- 本功能与已提交远召按同批发布处理。
- 若乌狸后续明确要求远召先单独发布，本功能顺延到下一 `MINOR`，不改变代码工作包。
- 该决策是发布收口门槛，不是实现代码的门槛。

### F-04：多部件验证原先依赖第三方，验证闭环不足，已补强

原稿把 Hydra/TF 作为主要多部件证据，1.19.2 环境的第三方桥接尚未建立，可能导致核心 `PartEntity` 修复没有强制实机证据。

补强为两级：

1. **强制**：原版 EnderDragon 多部件验证。
   - `net.minecraft.world.entity.boss.enderdragon.EnderDragon extends Mob implements Enemy`。
   - `net.minecraft.world.entity.boss.EnderDragonPart extends PartEntity<EnderDragon>`，且 `isPickable()` 返回 true。
   - 通过公开 `FurkinApi.registerSpecies(...)` 临时注册 EnderDragon。
   - 构造 `PlayerInteractEvent.EntityInteract(FakePlayer, MAIN_HAND, dragon.head)`。
   - 调用真实 `CommonEvents.onEntityInteract(event)`，验证解析到 dragon 父实体并正确取消事件。
2. **可选压力**：1.19.2 TF 桥接。
   - 若环境建立，验证 Hydra 和更多第三方实体。
   - 若不能建立，作为残余验证债务，不阻塞原版多部件验证。

### F-05：多部件解析会影响不止契约路径，已补充回归范围

解析 `PartEntity` 到父实体发生在 capability 查询之前，因此不只契约路径会变化，以下已有路径也需回归：

- 喂食已契约的多部件绒亲。
- 潜行右键打开面板。
- 潜行 + 契约物品收回。
- 普通右键契约。
- 部件事件最终取消原版交互。

若只测契约而漏测喂食/面板/收回，不能证明多部件兼容完整。

### F-06：健康提示的触发边界已澄清

必须区分“健康提示”和“所有用户提示”：

- `HEALTH_TOO_HIGH`：发送 `furkin.msg.contract_health*`，事件层取消原版交互。
- `INVALID_HEALTH`：静默，事件层不取消，按既有非健康失败处理。
- `ACTIVE_LIMIT`：沿用既有活跃上限提示，不发送健康提示。
- 其他失败：不发送健康提示。

`checkContract(...)` 内部已有活跃上限提示；不能为了让“只有 HEALTH_TOO_HIGH 有提示”成立而移除既有活跃上限提示。

### F-07：返回类型改动的调用面已确认

`tryContract(...)` 从 `boolean` 改为 `ContractCheckResult` 后：

- 产品代码只有一个调用点：`CommonEvents.onEntityInteract(...)`。
- `ConfirmContractPacket` 只调用 `confirmContract(...)`，返回值仍为 `void`，无需改包。
- 没有公开 API 类型引用 `ContractCheckResult`。

因此不需要修改 `FurkinNetwork`、包 ID、方向或 `PROTOCOL_VERSION`。

### F-08：服务端夹具可行性已确认

1.19.2 当前项目已有 `FakePlayer` 两阶段契约测试记录，证明以下路线可行：

- 在 `ServerStartedEvent` 使用 `ServerLevel` 和 Forge `FakePlayer`；
- 给 FakePlayer 放契约物品；
- 通过真实 `tryContract(...)` / `confirmContract(...)` 路径完成契约；
- 检查物品数量从 8 变 7。

本包夹具可以复用该模式。夹具必须：

- 不反射访问产品私有方法；
- 临时注册物种，且只允许存在于一次性服务器进程；`FurkinApi` 没有注销接口，验证后必须停服、删除夹具并重新构建；
- 在测试结束后删除夹具源码、临时世界和测试配置；
- 若测试包发送，按既有远召夹具经验处理 FakePlayer 与 `PlayerList` 的时序/索引问题。

### F-09：健康检查与最终提交之间无需插入异步逻辑

1.19.2 的服务端路径在同一服务器线程内完成：

```text
confirmContract -> checkContract -> executeContract
```

没有异步等待，因此 `executeContract(...)` 不需要重复健康判定，也不应复制第二套规则。若未来把契约确认改为异步，必须另开设计并重新评估 TOCTOU 风险。

### F-10：多部件距离口径有已知几何边界

`PartEntity` 解析为父实体后，距离检查使用 `player.distanceToSqr(parent)`。对于尺寸很大的多部件实体，玩家可能贴近某个部件，但父实体中心仍在 4 格外，从而返回 `OUT_OF_REACH`。

本包保留这一口径，与 1.20.1 参考实现和当前 1.19.2 的父实体业务目标一致。EnderDragon/TF 验证必须记录父实体距离；若真实游戏路径出现合法部件交互被拒，不能在本包内静默放宽规则，应另开专门工作包讨论“使用原始部件距离还是父实体距离”。

### F-11：`PartEntity` API 可行性已确认

1.19.2 mapped official jar 核实：

```java
public abstract class PartEntity<T extends Entity> extends Entity {
    public T getParent();
}
```

`PlayerInteractEvent.EntityInteract` 的公开构造器为：

```java
EntityInteract(Player player, InteractionHand hand, Entity target)
```

因此原版 EnderDragon 多部件事件夹具不需要桥接模组，也不需要反射。

### F-12：配置键默认值与旧配置行为需要单独记录

新增六项服务端配置不会自动改写已有旧键值。最终验证必须记录：

- 六项新键存在；
- 新键默认值正确；
- 旧 `activeLimit` 与远召六项键仍存在且值未被重置；
- 若测试要求默认值，先备份并移除本地 `furkin-server.toml`，或手动设置。

### F-13：文档证据必须保持“实现前 / 实现后”分离

复查阶段本目录是工作文档，不是验收报告。实现后已按以下规则分离证据：

- 保留本文件作为实现前的可行性审查记录；
- 在 `VERIFICATION_MATRIX.md` 逐项填写实际命令、时间、日志标记和结果；
- 不把计划中的预期结果写成已完成。

## 4. 关闭条件

在开始实现前，必须满足：

- [x] 基线更新为 `206a9273`。
- [x] 1.19.2 API 事实完成核实。
- [x] 发布默认口径已冻结；除非用户明确拆分发布，否则不再阻塞。
- [x] 原版多部件强制验证路线已确定。
- [x] `INVALID_HEALTH` 可达性已重新定义。
- [x] 把 `EXECUTION_CONTRACT.md` 作为代码修改的唯一执行契约。
- [x] 把 `VERIFICATION_MATRIX.md` 作为验收证据模板。
- [x] 2026-09-27 已确认实施前的文档改动范围；实施后已复核最终工作区与构建产物。

## 5. 发布选择与实现后的边界

复查阶段记录的未决项及当前处理：

- 远召与本功能已按默认同批发布处理，工作区版本为 `1.19.2-0.0.3.0`。
- 1.19.2 TF/Hydra 压力验证：当前依赖未建立，整组标为 `ENV BLOCKED`，不宣称通过；原版 EnderDragon 强制多部件验证已完成。
- 物种级绕过门槛：本版仍不提供；若需要，必须另开设计与验证工作包。