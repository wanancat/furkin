# Furkin 1.19.2 代码审查报告

- 审查日期：2026-09-25
- 工作目录：`D:\frukin_dev\frukin_1_19_2`
- 分支：`mc1.19.2`
- HEAD：`ba3132e` / `docs: remove resolved bleeding icon known issue`
- 参考审查：`D:\frukin_dev\frukin_1_20_1\docs\code_review_2026-09-24\code_review_2026-09-24.md` 及其工作包 `wp-01`～`wp-07`
- 审查范围：`src/main/java`、`src/main/resources`、构建配置、资源加载、网络协议、客户端/服务端边界
- 审查类型：只读静态审查 + 构建验证 + 专用服务端启动验证
- 代码修改：无
- 提交：无
- 结论：1.20.1 审查确认的 9 项问题在 1.19.2 当前 HEAD 上**全部复现**（3 高 / 4 中 / 2 低），另新增 2 项低严重度候选。建议按第 6 节的批次修复。

> 后续状态：本文是 `ba3132e` 时点的只读审查快照，不代表当前 HEAD。整改实施、最终验收结论和残余边界见 `code_review_2026-09-25_workflow.md` 及 `wp-01`～`wp-09`。

> 说明 1：本文记录的是当前 HEAD 的审查结论。绝对路径、行号和行为都应在后续代码变更后重新核对。
> 说明 2：1.20.1 报告在整改后已把各项工作项标注为「已修复」；**本文不继承那些结论**。本文每一项都是在本仓库 1.19.2 代码上重新逐行核对后的当前状态。
> 说明 3：1.19.2 移植提交 `6396af9` 的基线早于 1.20.1 的 9 个修复提交（`c783547`…`f4db61f`），因此 1.20.1 的修复没有随移植进入 1.19.2。此项仅作为背景；本文所有结论均以 1.19.2 源码本身为证据，不依赖提交谱系推断。

---

## 1. 审查摘要

### 1.1 高严重度

1. **H-01** 契约确认包缺少服务端权威校验，改造客户端可对任意活体强行落契约。
2. **H-02** 解绑未清理行囊、装备掉落率和战斗 AI，可能永久丢失物品并留下行为残留。
3. **H-03** 绒亲档案按当前维度读取，跨维度会把同一只宠物拆成多份。

### 1.2 中严重度

4. **M-01** 网络协议版本未随包结构变化递增。
5. **M-02** 洗点退款未乘技能 `cost`，技能 schema 缺少加载期校验。
6. **M-03** `FurkinCombatMode` 按基类删除 goal，会误删原版或第三方 AI。
7. **M-04** 技能热重载后已有属性效果可能残留，流血效果语义会漂移。

### 1.3 低严重度 / 待运行确认

8. **L-01** 共享网络包直接引用客户端类，端位纪律问题（本轮 `runServer` 已启动成功，未证实崩溃）。
9. **L-02** 调试命令回执大量硬编码英文，违反项目「玩家可见文本使用翻译键」规则。
10. **L-03**（1.19.2 新增候选）存档 NBT 反序列化对枚举使用 `Enum.valueOf`，无容错。
11. **L-04**（1.19.2 新增候选）技能树同 ID 静默覆盖，缺少重复定义校验。

### 1.4 建议修复顺序

1. 先修 H-01 的服务端权威校验（安全边界，外部可触发）。
2. 再修 H-02 解绑清理与 H-03 跨维度档案分裂（持久数据安全）。
3. 然后处理 M-01 协议版本、M-02 退款/schema、M-04 热重载一致性。
4. 再处理 M-03 AI 所有权。
5. 最后处理 L-01 端位纪律、L-02 本地化与 L-03/L-04 候选加固。

---

## 2. 验证范围与验证结果

### 2.1 项目规模

- Java 文件：87
- Java 行数：12,285
- 语言文件：
  - `src/main/resources/assets/furkin/lang/en_us.json`
  - `src/main/resources/assets/furkin/lang/zh_cn.json`
  - 两者均为 117 个键，键集合差异为 0（已用 `Compare-Object` 核对）。

### 2.2 已执行验证（运行已确认）

构建：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build --console=plain
```

结果：`BUILD SUCCESSFUL in 9s`；产物 `build/libs/furkin-1.19.2-0.0.1.0.jar`（291,383 字节，SHA-256 `85E4688181A1664C5D775CFC4E1F6237649FA23B17014AB2CED6AE3D24DF0665`）。

专用服务端启动（本轮前序会话执行，日志留存）：

```powershell
.\gradlew.bat runServer --console=plain
```

结果（证据 `run/logs/latest.log`）：

- 启动到达 `Done (12.354s)! For help, type "help"`。
- `Furkin loaded 13 skills.`
- 未出现项目自身的 `ERROR` / `FATAL` / 异常栈 / 类加载错误。
- 日志中的警告均为环境/上游：Forge 内部语言 jar 缺 `mods.toml`、OSHI/WMI 计数器失败、`mcassetsroot` schema、Forge `43.2.0` 被标记 `OUTDATED`（目标 `43.5.0`）。
- Ctrl+C 正常停止；Gradle 进程退出码 1 由终止批处理产生，不是运行失败。

资源与语言检查：

- `src/main/resources/**/*.json` 全部通过 JSON 解析检查。
- `en_us.json` 与 `zh_cn.json` 键集合一致。
- 代码中的静态 `Component.translatable("...")` 字面量（53 个）在两语言文件中均存在。

### 2.3 已执行的针对性取证

- 用 1.19.2 mapped official jar 的 `javap` 核对：
  - `ServerLevel#getDataStorage()` 与 `ServerChunkCache` 的 `DimensionDataStorage` 构造 —— 确认档案按维度各自存盘（H-03 的核心证据）。
  - `GoalSelector#getAvailableGoals()` 返回可修改的活 Set —— 确认 `removeIf` 确实生效（M-03 的语义前提）。
  - `ForgeHooksClient.popGuiLayer` 栈空时回落 `setScreen(null)` —— 用于排除 C-01（见第 4 节）。
  - `CreativeModeTab(String)` 委托 `addGroupSafe(-1, this)` 自动注册 —— 用于排除「创世标签注册方式」候选。
  - `fmlloader-1.19.2-43.2.0` 的 `ModInfo` 含 `logoFile` / `logoBlur` 读取 —— 用于排除 `mods.toml` 徽标键候选。
- `rg` 全仓检索网络包注册、档案读取点、契约/解绑/技能/战斗路径，逐一核对。
- Git 历史确认 `PROTOCOL_VERSION` 的取值（见 M-01）。

### 2.4 未执行验证（运行未确认）

本轮**没有**执行：

```powershell
.\gradlew.bat runClient --console=plain
```

因此以下属于**运行未确认（残余风险）**，只能给静态结论：

- 客户端界面/渲染/输入（绒亲录、技能面板、命名框）的实机回归。
- H-01 的伪造包注入、H-02 的解绑装备丢失、H-03 的跨维度档案分裂、M-04 的 `/reload` 效果残留等**异常路径**均未做真实游戏内注入。
- L-01 的客户端隔离只在**专用服务端启动路径**上被观察到未报错，未覆盖所有网络路径（见 L-01 的影响说明）。

### 2.5 工作区状态

- 审查期间未修改任何 Java 代码，未提交。
- 存在**预先存在**的未跟踪改动：`.gitignore`（新增忽略 `AGENTS.md`）。本报告不涉及也不回退该改动。
- 本报告新增文件均在 `docs/code_review_1.19.2-0.0.2.0/` 下（按版本归档）。

---

## 3. 详细问题记录

## 高严重度

### H-01：契约确认包缺少服务端权威校验，可绕过契约前置

- 状态：静态已确认
- 严重度：高
- 公开影响：改造客户端可以伪造确认包，把不可契约 / 未持有的契约物品 / 超距的实体强行纳入绒亲体系。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/network/ConfirmContractPacket.java:42-54`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:55-91`（`tryContract`，正规前置）
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:104-183`（`executeContract`，真正落契约）
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java:132-152`（唯一做「手持契约物品」判定的地方）

#### 证据

`ConfirmContractPacket.handle` 只做两件事：按实体 ID 从玩家所在维度取实体，然后直接调用 `executeContract`：

```java
Entity entity = player.getLevel().getEntity(packet.entityId);
if (entity instanceof net.minecraft.world.entity.LivingEntity target) {
    FurkinContractHandler.executeContract(player, target, player.getMainHandItem(), packet.name);
}
```

`executeContract` 自称「防御性复检」，但实际只复检三项（`FurkinContractHandler.java:104-119`）：

1. 目标 capability 存在且 `!data.isCompanion()`；
2. `target.getLevel() instanceof ServerLevel` 时的活跃上限。

`tryContract`（正规路径）与 `executeContract` **都没有**复检下列边界，而这些边界本应由服务端权威持有：

- **物种未在注册表内**：`tryContract` 第 57 行有 `FurkinSpeciesRegistry.isRegisteredEntity(target)`，`executeContract` 完全没有这道判定。`FurkinAttachHandler.java:25-28` 给**所有 `LivingEntity`** 挂 capability，所以「capability 存在」不等于「可契约」。
- **手持物品确实是绒亲契约**：该判定只存在于 `CommonEvents.java:133`（交互事件里）。伪造包路径下 `hand` 直接取 `player.getMainHandItem()`，可以是任意物品甚至空手；`hand.shrink(1)` 对空手是空操作。=> 可**免费**契约。
- **距离**：`tryContract` 靠交互距离间接保证；`executeContract` 无任何距离检查。=> 可契约同维度任意远处的实体。
- **存在有效的待命名会话**：`tryContract` 发出 `RequestContractNamePacket` 后，服务端不记录任何「待确认」状态；`executeContract` 不校验该实体是否曾走过第一步。=> 直接跳过第一步。
- **目标可契约性**：`executeContract` 未排除 `ServerPlayer` 等不该被契约的 `LivingEntity`。

#### 影响

- 可对同维度任意 `LivingEntity`（含未注册物种、其他玩家）落契约并建档，绕过物种注册、物品消耗、距离、会话与活跃配额之外的全部前置。
- 契约物品可被绕过（空手 / 非契约物品），破坏经济与进度前置。

#### 修复建议

- 在服务端维护显式的「待确认会话」（player → entityId + 时间戳/距离快照），`executeContract` 先校验会话存在、未过期、目标与 `entityId` 一致，用后即清。
- `executeContract` 内**重新**执行 `tryContract` 的全套边界：`isRegisteredEntity`、非玩家/合法目标、主手是 `FurkinContractItem`、距离上限、目标存活且未移除。
- 名字做服务端长度/字符上限校验（不要把客户端的裁剪当作约束）。
- 契约物品的扣除只依据服务端复检通过的实体与手。

---

### H-02：解绑未清理行囊、装备掉落率和战斗 AI，可能永久丢物品

- 状态：静态已确认
- 严重度：高
- 公开影响：解绑后宠物携带的行囊内容失去访问入口；穿在身上的盔甲因掉落率已被归零而永久消失；AI/状态残留。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java:487-514`（`clearFurkinLayer`）
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java:107-139`（`unbind` 调用方）
- `src/main/java/com/wanancat/furkin/internal/equipment/EquipmentSlots.java:69-97`（`sealDrops`）
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:133-135`（契约即调 `sealDrops`）
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java:276-317`（死亡快照只对 companion 生效）

#### 证据

`clearFurkinLayer` 只清「身份 / 等级 / 经验 / 技能 / 技能点 / 战斗模式 / Tame / owner / sit / CustomName」：

```java
SkillEffectApplier.removeAll(target, SkillRegistry.tree(), data.getSkillLevels());
data.setCompanionId(null);
data.setOwnerUuid(null);
data.setLevel(1);
data.setXp(0);
data.setSkillPoints(0);
data.getSkillLevels().clear();
data.setCombatMode(FurkinCombatMode.FOLLOW);
data.setState(FurkinState.WILD);
```

**未处理**（对照 `dismiss` / 死亡路径应当做的收尾）：

- `data.getPouch()` 内容 —— 没有 `PouchDrop.dropAll`（死亡侧在 `CommonEvents.java:301`、收回侧在 `dismiss` 路径会倒空）。
- 四个盔甲槽与 `ArmorDropChances` —— 契约时 `EquipmentSlots.sealDrops(target)` 已把盔甲掉落概率归零（`FurkinContractHandler.java:135`），解绑**不还原**。
- `targetSelector` / `goalSelector` —— 注释（第 503-504 行）明确「有意不动」。但参见 M-03：此前已被 `FurkinCombatMode` 按基类删过的原版 AI 目标不会回来。
- 冷却、进食/周期被动状态等运行时字段。

后果链条（装备永久丢失）：

1. 契约 → 盔甲掉落率 = 0。
2. 解绑 → state 回 `WILD`，`isCompanion()` 为 false。
3. 该实体日后死亡 → `markFallenIfCompanion`（`CommonEvents.java:276-280`）在 `!data.isCompanion()` 处**直接 return**，不写装备快照、不倒行囊。
4. 掉落率仍是 0 → 盔甲既不掉出、也不进档案 → **永久消失**。

#### 影响

- 盔甲永久丢失（不可恢复）。
- 行囊物品滞留在已不属于任何宠物的 capability 里且无 UI 入口（等同丢失）。
- 解绑后动物仍带着被打乱的 AI（见 M-03）与残留状态。

#### 修复建议

- 解绑前按与 `dismiss`/死亡一致的口径收尾：`PouchDrop.dropAll`（或按设计交还玩家）、还原盔甲掉落率、`EquipmentSlots` 相关状态复位。
- 明确 AI 归属并在解绑时恢复/清理本模组挂上的 goal（与 M-03 一并处理）。
- 清冷却与周期被动计时（`SkillPassiveDispatcher.clearPeriodicTimers`）。
- 补一份「解绑后死亡不掉装备、不丢行囊」的回归检查项。

> 处置口径已于 2026-09-25 冻结（见工作流 §12）：行囊 / 盔甲 / 档案盔甲快照一律**掉落到脚下**、掉落率还原为 `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE`、档案最后删；并纳入 1.20.1 的强制解绑 + 墓碑（WP-09）。

---

### H-03：绒亲档案按当前维度读取，跨维度会拆成多份

- 状态：静态已确认
- 严重度：高
- 公开影响：宠物跨维度后从绒亲录消失、无法召唤/收回，活跃上限可被绕过，档案分叉。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/record/FurkinArchiveData.java:81-90`
- 玩法调用点（部分）：`FurkinRecordActionHandler.java:110,152,211,397`、`FurkinCompanionManager.java:87,157,211,259,454,480`、`SkillProgress.java:60,138,183,199`、`FurkinContractHandler.java:164`、`FurkinCombatModeHandler.java:56`、`CommonEvents.java:285`、`FurkinGrowth.java:148`、`FurkinRecordItem.java:80`、`FurkinCommand.java:205,358`

#### 证据

```java
public static FurkinArchiveData get(ServerLevel level) {
    return level.getDataStorage()
            .computeIfAbsent(FurkinArchiveData::load, FurkinArchiveData::new, NAME);
}

public static FurkinArchiveData get(MinecraftServer server) {
    return get(server.overworld());
}
```

`javap` 取证：`ServerLevel#getDataStorage()` → `ServerChunkCache#getDataStorage()` → 每个维度各自构造 `DimensionDataStorage`（按维度路径存盘）。因此 `get(ServerLevel)` 得到的**不是**全局单例。

**全局入口 `get(MinecraftServer)` 存在，但玩法路径没有使用它**：上列 20+ 个调用点传入的都是玩家/实体所在维度的 `ServerLevel`（例如 `player.getLevel()`、`target.getLevel()` 处的 `serverLevel`）。同一只宠物在下界、末地与主世界会各自命中不同的 `furkin_archive`，于是：

- 玩家带宠物进下界 → 主世界档案查不到该 `companionId` → 绒亲录为空、无法收回。
- 活跃上限统计按维度各算一份 → 可绕过上限。

#### 影响

- 跨维度后宠物「消失」于绒亲录，功能不可用。
- 档案分叉：同一 `companionId` 在不同维度出现不同副本，状态互相覆盖。
- 活跃配额按维度重复计算。

#### 修复建议

- 档案统一到单一权威存储（例如始终 `get(server.overworld())`，即改用已存在的 `get(MinecraftServer)`），并让所有玩法路径改走全局入口。
- 召唤/收回/统计等按 `server.getLevel(ResourceKey)` 在**目标维度**操作实体，但读写同一份全局档案。
- 迁移：合并已分叉的旧档案（或至少在读取时回退合并）。

---

## 中严重度

### M-01：网络协议版本未随包结构变化递增

- 状态：静态已确认
- 严重度：中
- 公开影响：新旧版本客户端/服务端可能握手成功，但消息 ID / 字段不兼容，导致错包、解码失败或状态错乱。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/network/FurkinNetwork.java:25`
- 注册表：`FurkinNetwork.java:38-138`（10 个包）

#### 证据

```java
private static final String PROTOCOL_VERSION = "1";
```

注册在案的包**共 10 个**（`SYNC_FURKIN_DATA`、`RECORD_LIST`、`REQUEST_SUMMON`、`REQUEST_CONTRACT_NAME`、`CONFIRM_CONTRACT`、`RECORD_ACTION`、`UNLOCK_SKILL`、`RESET_SKILLS`、`SELECT_TAB`、`OPEN_FURKIN_SCREEN`）。`id` 从 0 顺序分配，**消息 ID 就是注册顺序**；契约命名、录操作、解锁/洗点、页签、开屏等包都是后来加入的。

Git 历史确认 `PROTOCOL_VERSION` 自加入起一直为 `"1"`，未随后续新增/改字段而递增。

#### 影响

- 旧客户端连新服务端（或反之）会因注册顺序不同导致同一 ID 指向不同包 → 反序列化错位。
- 字段增删后 `readUtf` / `readVarInt` 读取越界抛异常，踢玩家或造成同步错乱。
- `NetworkRegistry.newSimpleChannel` 的版本谓词对两端都要求相等；既然恒为 `"1"`，它实际上**不提供任何版本隔离**。

#### 修复建议

- 每次新增/修改包（含字段签名）递增 `PROTOCOL_VERSION`，并在 `docs` 记录协议变更。
- 建立「协议版本 = 与包结构绑定的整数字符串」的治理约定，禁止复用旧 ID 表示不同含义。

---

### M-02：洗点退款未乘技能 `cost`，技能 schema 也缺少校验

- 状态：静态已确认
- 严重度：中
- 公开影响：第三方技能若 `cost != 1`，退款金额错误；非法 `cost`（含负值）可制造技能点。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:102-107`（加点按 `cost` 扣点）
- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:148-165`（在场洗点退款）
- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:167-177`（未召唤洗点退款）
- `src/main/java/com/wanancat/furkin/internal/skill/SkillLoader.java:59-120`（解析，无校验）

#### 证据

加点时按 `skill.getCost()` 扣点：

```java
if (data.getSkillPoints() < skill.getCost()) {
    return Result.NOT_ENOUGH_POINTS;
}
data.setSkillPoints(data.getSkillPoints() - skill.getCost());
```

但两条洗点退款路径只累加**等级**，不乘 `cost`：

```java
// 在场：SkillProgress.java:149-152
int refund = 0;
for (int lv : data.getSkillLevels().values()) {
    refund += lv;                       // 少了 * cost
}
// 未召唤：SkillProgress.java:169-172
int refund = 0;
for (String key : entry.getSkillSnapshot().getAllKeys()) {
    refund += entry.getSkillSnapshot().getInt(key);   // 少了 * cost
}
```

`SkillLoader.parseSkill`（`SkillLoader.java:77-79`）直接 `getAsInt()` 读 `tier` / `maxLevel` / `cost`，**不拒绝**：

- `cost <= 0`（`cost = 0` → 免费加点；`cost < 0` → 加点反而加点数）；
- `maxLevel == 0` 或负 `maxLevel`；
- 非法 `tier` / `requiresLevel`；
- 依赖顶点是否存在（`requires` / `requiresLevel` / `levelGate` 指向不存在的技能）。

#### 影响

- `cost != 1` 的技能被洗点时少退（或按 cost 大于 1 时**少退大量点数**），破坏进度经济。
- `cost < 0` 的数据包技能可被加点刷点，再被洗点放大。
- 非法 `maxLevel` 会让等级语义越界（如负数等级进入效果计算）。

#### 修复建议

- 两条退款路径改为 `refund += lv * costOf(skillId, tree)`；对已从树中消失的技能需要一个回退口径（见 M-04）。
- `SkillLoader` 增加加载期校验：`cost >= 1`、`maxLevel >= 1`、`tier >= 1`、`requiresLevel >= 1`、引用完整性；不合格技能跳过并记日志。

---

### M-03：`FurkinCombatMode` 按基类删除 goal，会误删原版或第三方 AI

- 状态：静态已确认
- 严重度：中
- 公开影响：契约 / 切档 / 重召唤会误删原版（或第三方模组）挂上的攻击目标 AI；解绑后不恢复。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatMode.java:93-106`

#### 证据

```java
animal.targetSelector.getAvailableGoals().removeIf(wrapped ->
        wrapped.getGoal() instanceof HurtByTargetGoal
                || wrapped.getGoal() instanceof OwnerHurtByTargetGoal
                || wrapped.getGoal() instanceof OwnerHurtTargetGoal
                || wrapped.getGoal() instanceof NearestAttackableTargetGoal);
animal.goalSelector.getAvailableGoals().removeIf(wrapped ->
        wrapped.getGoal() instanceof MeleeAttackGoal);
```

`javap` 确认 `getAvailableGoals()` 返回的是**可修改的活 Set**，所以 `removeIf` 确实生效 —— 问题不在「删不掉」，而在**匹配口径**：

- 按**基类**（`instanceof HurtByTargetGoal` / `MeleeAttackGoal` / `NearestAttackableTargetGoal` …）删除，会把**原版自带**与**第三方模组**挂上的同类型目标一起删掉（例如狼原版就有 `HurtByTargetGoal`；其它模组可能给动物挂 `NearestAttackableTargetGoal`）。
- `MeleeAttackGoal` 在 `goalSelector` 上无差别删除，影响普通行动目标。
- 解绑路径（`FurkinRecordActionHandler.java:503-504`）有意不动 selector，因此被删掉的原版 AI **不会恢复**。

#### 影响

- 契约/切档后宠物丢失原版攻击行为；解绑回落「普通动物」后仍是残缺 AI（且带 H-02 的其它残留）。
- 与第三方模组（宠物/驯服类）交互时互相破坏 goal 列表，行为不可预测。

#### 修复建议

- 只删**本模组自己创建并记录引用**的 goal 实例（保存引用于 capability 或实体侧映射），不按基类匹配。
- 或改用带归属标记的包装（自定义 Goal 子类），删除时只匹配自有类型。
- 解绑时恢复/重建原版 AI，或明确约定本模组接管这些目标的所有权。

---

### M-04：技能热重载后已有属性效果可能残留，流血语义会漂移

- 状态：静态已确认
- 严重度：中
- 公开影响：`/reload` 或数据包更新后，旧属性 modifier 残留/并存；流血 DPS 立刻按新配置改变，语义未定义。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/skill/SkillRegistry.java:33-43`（`reloadTree`）
- `src/main/java/com/wanancat/furkin/internal/skill/SkillEffectApplier.java:23-69`（`removeAll` / `removeSkill`）
- `src/main/java/com/wanancat/furkin/internal/skill/effect/AttributeEffect.java:42-76`（按 UUID 精确移除）
- `src/main/java/com/wanancat/furkin/internal/effect/BleedingEffect.java:49-59`（每 tick 读规格）
- `src/main/java/com/wanancat/furkin/internal/skill/BleedingSpec.java:102-109`（`of()`）

#### 证据

`reloadTree` 只做「换新树 + 清 `SkillParams` / `HarvestSpec` 缓存」，**不遍历在场宠物重建效果**：

```java
SkillTree tree = new SkillTree();
SkillLoader.load(manager, tree);
TREE.set(tree);
SkillParams.invalidateCache();
HarvestSpec.invalidateCache();
```

于是已挂的 `AttributeModifier` 仍按旧定义存在。而移除路径对**已从树中消失**的技能无能为力：

```java
public static void removeSkill(LivingEntity target, SkillTree tree, ResourceLocation skillId) {
    Skill skill = tree.get(skillId).orElse(null);
    if (skill == null) {
        return;                     // 树里没有 → 无法知道该技能改过哪些属性
    }
    ...
}
```

`AttributeEffect` 只用「技能 ID + 属性」派生的 UUID 精确移除（`AttributeEffect.java:54,75`）；树删除后连效果列表都取不到，modifier 无法定位 ⇒ 残留。若同一属性被新技能以不同 UUID 再加一次，则**多个 modifier 并存**。

流血侧：`BleedingEffect.applyEffectTick` 每 tick 调 `BleedingSpec.of()`，而 `BleedingSpec` 从（重载后已失效并重读的）`SkillParams` 缓存取规格 ⇒ `/reload` 后**已有流血效果**的 DPS 立即变化。

#### 影响

- 删除/改名技能后，旧属性加成永久残留，数值与技能树显示不符。
- 同一属性出现重复 modifier，翻滚/死亡/收回后可能继续累积。
- 流血 DPS 热漂移：已施加的效果在中途改变取值，语义不确定（也可能导致瞬间爆发）。

#### 修复建议

- 重载后遍历在场宠物，先按**旧树**移除全部效果，再按**新树**重建（需要保留旧树引用到重载完成）。
- 为「树中已不存在但实体上仍有」的技能提供按自有 UUID 前缀/注册表**兜底清除**（如维护技能 → 属性 modifier UUID 的注册表）。
- 流血伤害改为在**施加时快照**（编码进 amplifier/时长或独立实例数据），使 `/reload` 不影响已有实例。

---

## 低严重度 / 待运行确认

### L-01：共享网络包直接引用客户端类

- 状态：静态已确认（端位纪律）；运行风险**未证实**（`runServer` 启动到 `Done`，无 `NoClassDefFoundError` / `ClassNotFoundException`）
- 严重度：低
- 公开影响：专用服务端在加载这些共享包时有潜在类解析风险；当前启动路径已观察为无异常。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/network/SyncFurkinDataPacket.java:5`（`net.minecraft.client.Minecraft`，用于 `:54` 附近）
- `src/main/java/com/wanancat/furkin/internal/network/RecordListPacket.java:3`（`internal.client.FurkinRecordScreen`，`:210-212`）
- `src/main/java/com/wanancat/furkin/internal/network/OpenFurkinScreenPacket.java:3`（`internal.client.FurkinPanelScreen`，`:268`）
- `src/main/java/com/wanancat/furkin/internal/network/RequestContractNamePacket.java:3`（`internal.client.ContractNameScreen`，`:44`）

#### 证据

四个共享网络包在**类级 import** 中直接引用了客户端专用类型（`net.minecraft.client.Minecraft` 与 `internal.client.*` 界面类）。调用点都包在 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 里，因此客户端的**执行**被正确隔离，但**符号引用**仍在共享类上。

#### 影响与结论

- 理论上专用服务端加载这些类时，类验证可能触发对客户端类的解析；实践中是否触发取决于 JVM 解析时机与 Forge 的类加载策略。
- **本轮 `runServer` 已成功启动到 `Done (12.354s)`，日志无类加载错误、无项目 `ERROR`/`FATAL`** —— 因此该项在**启动路径**上未证实为崩溃原因，降级为低严重度端位纪律/残余风险，而不是「专用服务端必崩」。
- 未验证的残余：运行期走到这些包的 handle/encode 阶段（真实玩家操作）时的解析行为；以及某些混淆/打包/裁剪配置下的行为。

#### 修复建议

- 把客户端调用移进单独的、只在 `Dist.CLIENT` 加载的辅助类（如 `ClientPacketHandlers`），共享包只 `DistExecutor` 调用该辅助类的方法，杜绝共享类级客户端 import。
- 补一个专用服务端启动 + 简单交互的回归检查项，长期守住端位纪律。

---

### L-02：调试命令回执大量硬编码英文

- 状态：静态已确认
- 严重度：低
- 公开影响：命令输出不随语言文件本地化，违反项目「玩家可见文本使用翻译键」规则。命令仅 OP 可用，影响面有限。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java`（共 82 处 `Component.literal`）
- 主要区间：`:167,178-185,217-262,301-338,353-376,407-472,487-591,623-644`

#### 证据

命令回执大量使用 `Component.literal(...)` 直接构造英文字符串（如用法提示、成功/失败反馈、参数说明），未走 `Component.translatable`。项目规则要求所有玩家可见文本使用翻译键（`README` 与 `AGENTS.md` 均声明）。命令本身在 `require` 权限层面仅 OP 可见，故影响有限。

#### 影响

- 中文客户端看到英文回执；语言文件无法覆盖这部分文本。
- 未来改文案需改代码而非资源。

#### 修复建议

- 为每条回执建立翻译键（如 `furkin.command.reload.success`），同步补 `en_us.json` / `zh_cn.json`。
- 保留参数占位（数量、名字）用 `Component.translatable(key, args...)`。
- 加一个「Java 中不应出现面向玩家的裸英文 `literal`」的静态检查或人工检查项。

---

### L-03：（1.19.2 新增候选）存档 NBT 反序列化对枚举使用 `Enum.valueOf`，无容错

- 状态：静态已确认（触发条件为损坏/降级存档，故降级为低）
- 严重度：低（潜在读档失败）

#### 位置

- `src/main/java/com/wanancat/furkin/internal/capability/FurkinData.java:290`（`FurkinState.valueOf(tag.getString("state"))`）
- `src/main/java/com/wanancat/furkin/internal/capability/FurkinData.java:291-293`（`FurkinCombatMode.valueOf(...)`，旧档缺键时回退 `FOLLOW`，但键存在而值非法时仍抛）
- `src/main/java/com/wanancat/furkin/internal/record/FurkinArchiveEntry.java:245-247`（`FurkinCombatMode.valueOf(...)` 同型问题）

#### 证据

```java
this.state = FurkinState.valueOf(tag.getString("state"));           // 无 try/catch
...
entry.combatMode = tag.contains("combat_mode")
        ? FurkinCombatMode.valueOf(tag.getString("combat_mode"))    // 无 try/catch
        : FurkinCombatMode.FOLLOW;
```

枚举名非法（存档损坏、被其它工具改写、或未来版本新增枚举值后回退版本）时抛 `IllegalArgumentException`，在 capability / 档案反序列化阶段向上传播，可能导致实体或区块**读档失败**。

另注：`FurkinState` 这一处连「键缺失」都不安全 —— `tag.getString("state")` 在缺键时返回空串 `""`，`FurkinState.valueOf("")` 同样抛异常（序列化侧 `FurkinData.java:234` 总会写 `state`，故正常存档不触发，异常存档/外部数据会触发）。`combat_mode` 有 `tag.contains(...)` 守卫，缺键安全，但值非法仍抛。

#### 影响

- 单条坏数据可能让整块区域加载异常（具体传播路径未做运行验证）。
- 降级版本（新枚举值 → 旧 `valueOf`）场景下尤其容易触发。

#### 修复建议

- 用容错解析（`parse(String)` 风格：遍历 `values()` + `equalsIgnoreCase`，非法回退默认值并记一次警告），与代码里已有的 `FurkinCombatMode.parse` 保持一致。
- 对每条档案条目做「单条失败不影响其余条目」的隔离（`FurkinArchiveData.load` 逐条 try/catch 跳过）。

---

### L-04：（1.19.2 新增候选）技能树同 ID 静默覆盖，缺少重复定义校验

- 状态：静态已确认
- 严重度：低（数据完整性；触发需要数据包/资源包冲突）

#### 位置

- `src/main/java/com/wanancat/furkin/internal/skill/SkillTree.java:25-27`（`register`）
- `src/main/java/com/wanancat/furkin/internal/skill/SkillLoader.java:47-50`（调用点）

#### 证据

```java
public void register(Skill skill) {
    byId.put(skill.getId(), skill);   // 同名（同 ResourceLocation）后者静默覆盖前者
}
```

`SkillLoader.load` 遍历 `manager.listResources` 的结果注册技能；若两个来源（本模组数据包 + 第三方数据包）声明同一 `id`，`put` 会**静默覆盖**，既不报错也不告警，加载数量统计里也看不出重复。`SkillLoader` 未检测重复 ID。

#### 影响

- 技能被意外覆盖，行为与作者预期不符，且难以排查。
- 已投点玩家的技能语义可能在重载后悄然改变。

#### 修复建议

- `register` 遇到已存在 ID 时记一条 `WARN`（含来源），或返回布尔让加载器决定跳过/覆盖策略。
- 在加载器里对覆盖来源做显式优先级约定并记录。

---

## 4. 1.19.2 迁移特有核查（已排除的候选）

本节记录**核查后未列为缺陷**的项，避免后续重复排查。

### 4.1 `ContractNameScreen` 的取消/ESC 关闭语义 —— 不是缺陷（C-01 排除）

- 1.19.2 的 `Screen.onClose()` 会走 `Minecraft.popGuiLayer()`；`ContractNameScreen` 由 `Minecraft.setScreen(...)` 打开、未压入 GUI 层。
- `javap` 确认 `ForgeHooksClient.popGuiLayer`：栈空时回落 `Minecraft.setScreen(null)` → 返回世界；这正是 `ContractNameScreen` 的预期出口。
- 实机证据：`docs/wp9d-gui-test-plan.md:443,681` 记录「契约命名取消/ESC 返回世界的实测结果正确」，并有 D2/D9 全部通过的回归记录。
- `RenameScreen` 语义不同（需返回绒亲录父屏），已局部实现显式父屏返回（`wp9d-gui-test-plan.md:662-681`）。两者不可混为一谈。
- **结论**：不列为 1.19.2 特有缺陷。

### 4.2 `ModCreativeTab` 注册方式 —— 正确

`javap` 确认 1.19.2 的 `CreativeModeTab(String)` 委托 `CreativeModeTab(-1, langId)` → `addGroupSafe(-1, this)` 自动注册；显示名 = `Component.translatable("itemGroup." + langId)`。语言文件含 `itemGroup.furkin`，`ModItems` 通过 `Item.Properties.tab(FURKIN_TAB)` 引用。**结论**：1.19.2 下的注册方式正确，不列为缺陷。

### 4.3 `mods.toml` 的 `logoFile` / `logoBlur` —— 受支持

`javap` 核对 `fmlloader-1.19.2-43.2.0` 的 `ModInfo`：存在 `logoFile` / `logoBlur` 字段、`getLogoFile()` / `getLogoBlur()`，Forge 43.2.0 会读取这两个键。**结论**：功能上受支持（仅注释里「Verified ... 1.20.1」的措辞属文案问题，不是缺陷）。

### 4.4 机械迁移差异核查 —— 未发现残留

在 `src/main/java` 全量检索，**未发现**下列 1.20.1 专有 API 的误用：

- `Entity#level()` / `serverLevel()`（1.20.1 形式）—— 本仓库统一使用 `getLevel()`。
- `Entity#damageSources()` —— 通用伤害使用 `DamageSource.GENERIC`（`BleedingEffect.java:59`）。
- `DamageTypeTags` —— 无敌穿透使用 `DamageSource#isBypassInvul()`（`SkillPassiveDispatcher.java:235`）。
- `GuiGraphics#blitNineSliced(...)` —— 仅在客户端注释里作为「1.19.2 无此 API」的历史说明出现（`FurkinPanelScreen.java:193,713`），未调用。
- `EditBox#setHint(...)`、`Slot#setByPlayer(...)`、谓词版 `removeAllGoals(...)` —— 均无调用（`FurkinCombatMode.java:95` 注释说明为何改用 `getAvailableGoals()`）。

`instanceof ServerLevel` 共 10 处，逐一核对：全部作用在 `LivingEntity` / `Entity` 的 `getLevel()`（返回 `Level`）上，属必要判断；**未发现**对 `ServerPlayer#getLevel()`（1.19.2 已返回 `ServerLevel`）的冗余 `instanceof`。（`FurkinCompanionManager.java:317` 有一处 `(ServerLevel) player.getLevel()` 强转，1.19.2 下冗余但无害，属风格项。）

### 4.5 不可解析物种（`species == null`）—— 已有守卫，不列为缺陷

1.20.1 的 `77c1012` 修复了「不可解析实体自救」。1.19.2 代码中**已存在**对应守卫：

- `FurkinCompanionManager.java:321-325`（重建前判空并记日志返回）。
- `FurkinCommand.java:282`、`FurkinRecordActionHandler.java:475`、`FurkinRecordItem.java:132`、`FurkinDisplayOrder.java:52`、`SkillProgress.java:87`。

**结论**：null species 路径已被多处守卫覆盖，不作为独立缺陷列出（其残留风险归入 L-03 的存档容错话题）。

### 4.6 语言与资源一致性 —— 通过

- `en_us.json` / `zh_cn.json` 各 117 键，键集合差异 0。
- 代码中 53 个静态 `Component.translatable("...")` 字面量在两语言文件中均存在。
- `src/main/resources/**/*.json` 全部可解析；未发现缺失的贴图/`gui/` 资源引用。

---

## 5. 与 1.20.1 审查的对应关系

| 1.20.1 编号 | 1.19.2 现状 | 1.19.2 位置（摘要） |
| --- | --- | --- |
| H-01 契约确认缺服务端校验 | **未修复（复现）** | `ConfirmContractPacket.java:42-54`；`FurkinContractHandler.java:104-183` |
| H-02 解绑未清行囊/装备/AI | **未修复（复现）** | `FurkinRecordActionHandler.java:487-514` |
| H-03 档案按维度分裂 | **未修复（复现）** | `FurkinArchiveData.java:81-90` |
| M-01 协议版本未递增 | **未修复（复现）** | `FurkinNetwork.java:25` |
| M-02 退款未乘 cost / 无 schema 校验 | **未修复（复现）** | `SkillProgress.java:148-177`；`SkillLoader.java:77-79` |
| M-03 按基类删 goal | **未修复（复现）** | `FurkinCombatMode.java:93-106` |
| M-04 热重载效果残留 / 流血漂移 | **未修复（复现）** | `SkillRegistry.java:33-43`；`SkillEffectApplier.java:64-69` |
| L-01 共享包引用客户端类 | **存在；风险未证实** | `SyncFurkinDataPacket.java:5` 等 4 处 |
| L-02 命令回执硬编码英文 | **存在** | `FurkinCommand.java`（82 处 `literal`） |
| — | **1.19.2 新增候选** | L-03 枚举 `valueOf` 无容错；L-04 技能 ID 静默覆盖 |

---

## 6. 建议的修复批次

### 批次 1：服务端权威与持久数据安全

- **H-01** 服务端待确认会话 + `executeContract` 全套边界复检。
- **H-02** 解绑收尾：行囊、盔甲掉落率、AI、冷却/周期状态（口径已冻结，见工作流 §12）。
- **D9 / WP-09**（随 H-02 冻结一并纳入）：强制解绑 + 墓碑 + 实体定位，对齐 1.20.1 WP-02B。
- **H-03** 档案统一为全局单例（改用 `FurkinArchiveData.get(MinecraftServer)`），并迁移旧的分叉档案。

### 批次 2：协议与数据一致性

- **M-01** 递增 `PROTOCOL_VERSION` 并建立治理约定。
- **M-02** 退款乘 `cost` + `SkillLoader` 加载期校验。
- **M-04** 重载时按旧树移除、按新树重建；流血施加时快照。

### 批次 3：AI 兼容与端位纪律

- **M-03** 技能/AI goal 所有权化，仅删本模组自有实例。
- **L-01** 客户端调用下沉到 `Dist.CLIENT` 专用辅助类。

### 批次 4：本地化与加固

- **L-02** 命令回执翻译键化，两语言文件同步。
- **L-03** 枚举容错解析 + 逐条档案隔离。
- **L-04** 技能 ID 重复检测与告警。

每批完成后按项目规则执行：`compileJava` → 受影响面 `build` → 界面/渲染/输入变更 `runClient` → 注册/命令/能力/网络/存档变更 `runServer`，并检查 `run/logs/latest.log` 无新 `ERROR`/`FATAL`。

---

## 7. 最终验证声明

- **静态已确认**：第 3 节 H-01～H-03、M-01～M-04、L-01～L-04 的代码位置与行为，均在本仓库 1.19.2 当前 HEAD 上逐行核对。
- **运行已确认**：`build` 成功；专用服务端 `runServer` 启动到 `Done`，项目无 `ERROR`/`FATAL`、无类加载错误；语言/资源一致性检查通过。
- **运行未确认（残余风险）**：`runClient` 未执行；H-01/H-02/H-03/M-04 的异常路径未做真实游戏内注入；L-01 的隔离只在服务端启动路径被观察，未覆盖运行期全部网络路径；L-03 的读档传播路径未实机复现。
- 本报告为**只读审查**，未修改任何 Java 代码，未提交，未推送。

> 备注：本文件随代码演进可能过期。重新核对时，请以「位置」列出的文件当前行号为准。
