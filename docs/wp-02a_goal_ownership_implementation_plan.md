# WP-02A：goal 所有权与生命周期实施计划

- 计划状态：A0-A8 已完成；`compileJava`、`build`、`runServer`、`runClient` 均通过，最终 JAR 不含临时夹具；未提交、未推送
- 制定日期：2026-09-24
- 工作分支：`mc1.20.1/dev`
- 代码基线：`c783547a2ccb9484223c6a6d094eeeabbce0fef2`
- 设计依据：`docs/wp-02_ai_ownership_unbind_design.md`
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0
- 遗留清理：A0 临时 patches/ 取证目录已删除。

---

## 1. 范围

WP-02A 只实现：

- Furkin 战斗 goal 的私有所有权。
- 冲突原版/第三方 goal 的运行时快照与恢复。
- `ai_state_version` 的持久化与旧档版本判断。
- 区块卸载重载、跨维度移动后的 AI 重建入口。
- 为 WP-02B 提供解绑时可调用的清理由与恢复入口。

WP-02A 不实现：

- 行囊、装备、冷却、进食状态的解绑清理。
- `entity_uuid` / `entity_dimension` 档案字段。
- `FurkinRevocationData` 注销墓碑。
- 强制解绑 UI、命令和网络包。
- H-03 完整档案合并。

这些仍归 WP-02B 或 WP-03。

---

## 2. 现有基线

### 2.1 当前问题

`FurkinCombatMode#applyTo(TamableAnimal)` 当前：

1. 按基类删除 `HurtByTargetGoal`、护主 goal、`NearestAttackableTargetGoal`、`MeleeAttackGoal`。
2. 重新添加 Furkin 自己的近战与目标 goal。

因此会误删原版狼猫或第三方 `TamableAnimal` 原本拥有的同类 goal。

当前还没有 `EntityJoinLevelEvent`。实体经过区块卸载再加载后：

- 原版构造函数重新注册原版 goal。
- Furkin 运行时 goal 不会随 NBT 持久化。
- 战斗模式不会自动恢复，直到玩家再次切档或收回/召唤。

### 2.2 已核对 API

- `GoalSelector#getAvailableGoals()`：返回 `Set<WrappedGoal>`。
- `WrappedGoal#getGoal()` / `getPriority()`：读取 goal 与优先级。
- `GoalSelector#removeGoal(Goal)`：按 goal 实例身份删除，不是按类型删除。
- `GoalSelector#addGoal(int, Goal)`：重新创建 wrapper 并加入选择器。
- `Mob#goalSelector` / `Mob#targetSelector`：公开字段。
- `EntityJoinLevelEvent`：Forge 服务端实体入世事件。

---

## 3. 实施阶段

### A0：事件与能力加载顺序取证

目的：确认 `EntityJoinLevelEvent` 触发时，实体 capability NBT 是否已经反序列化完成。

#### 结论（2026-09-24，静态取证）

采用 Forge 47.2.0 的公开调用顺序取证，结论如下：

1. 区块实体读盘时，`EntityStorage.loadEntities(...)` 经
   `EntityType.loadEntitiesRecursive(...)`、`Entity.load(...)` 完成 NBT
   与 `ForgeCaps` 反序列化。
2. `PersistentEntitySectionManager` 在
   `processPendingLoads()` 阶段才以 `addEntity(entity, true)` 触发
   `EntityJoinLevelEvent`。
3. 因此区块加载路径下，事件触发时 Furkin capability 已经完成反序列化，
   可以读取 `FurkinData`。
4. 新建实体路径下，capability 在 `AttachCapabilitiesEvent` 阶段挂载；
   `addFreshEntity(...)` 最终进入 `PersistentEntitySectionManager.addEntity(...)`，
   入世事件在该流程中触发。事件处理器必须把 capability 不存在视为
   “非绒亲/不可处理”，不能假设任意实体一定带 Furkin capability。
5. 采用 `EntityJoinLevelEvent` 作为入世恢复钩子；显式 `applyTo(...)`
   与事件的重复调用统一由 A4/A6 的幂等规则兜底。

#### 取证边界

- 本结论来自当前 Forge 47.2.0 映射 JAR 的公开调用顺序和现有 capability
  挂载入口，不保留临时诊断代码。
- 真实区块重载、跨维度和新建实体的运行日志验证放入 A7；若 A7 发现
  顺序或状态与静态结论不符，必须先暂停并回写本节。

#### 交付

- 事件顺序结论。
- 选择的入世钩子。
- 不保留临时诊断代码。

#### 确认门槛

- A0 静态取证完成并暂停；确认后才进入 A1。

### A1：新增 `FurkinCombatAiState`

状态：已完成（2026-09-24）；`compileJava` 通过，静态检查确认没有序列化 goal 或 wrapper。

新增：

```text
src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatAiState.java
```

建议字段：

```text
boolean originalCaptured
boolean legacyPolluted

List<WrappedGoal> savedGoalSelectorGoals
List<WrappedGoal> savedTargetSelectorGoals

Set<Goal> ownedGoals
```

约束：

- `ownedGoals` 使用 identity set：
  `Collections.newSetFromMap(new IdentityHashMap<>())`
- 快照只保存引用，不复制 goal，不写 NBT，不走网络。
- 提供创建、获取、清空和状态判断方法。
- 不把类型暴露到 `api` 包。

验收：

- `compileJava` 通过。
- 静态检查确认没有序列化 goal 或 wrapper。

### A2：新增私有 goal 类型

状态：已完成（2026-09-24）；私有 goal、所有权标记、精确实例登记与兜底清理已落地，`compileJava` 通过。

在 `FurkinCombatMode` 内部或同一 internal 包新增：

- `FurkinOwnedGoal` 标记接口。
- `FurkinMeleeAttackGoal`
- `FurkinHurtByTargetGoal`
- `FurkinOwnerHurtByTargetGoal`
- `FurkinOwnerHurtTargetGoal`
- `FurkinNearestAttackableTargetGoal<T extends LivingEntity>`

要求：

- 类型只供 Furkin 内部创建。
- 构造参数、目标类型、优先级和原版版本语义一致。
- 创建后同时登记到 `FurkinCombatAiState.ownedGoals`。
- 不修改第三方 goal，也不按公共基类全局删除。

验收：

- 狼、猫默认 goal 类型分类结果正确。
- `MeleeAttackGoal` 不再由 `removeAllGoals` 按类型删除。

### A3：扩展 `FurkinData`

状态：已完成（2026-09-24）；版本字段、持久化边界、反序列化重置与 `copy()` 规则已落地，`compileJava` 通过。静态检查确认 `ai_state_version` 只写入
`serializeNBT()`，`syncNBT()` 不包含该字段或运行时 goal 状态；运行时 NBT 往返与网络包验证仍放 A7。A2 为满足 goal 精确实例登记，已先落地
`combatAiState` 字段及 `getCombatAiState()`、`getOrCreateCombatAiState()`、
`clearCombatAiState()`；A3 在此基础上补齐版本管理。

修改：

```text
src/main/java/com/wanancat/furkin/internal/capability/FurkinData.java
```

新增：

```text
private int aiStateVersion;
```

规则：

- 当前版本常量建议为 `1`。
- 旧档缺失字段按 `0` 读取。
- `serializeNBT()` 写入 `ai_state_version`。
- `syncNBT()` 不写 `ai_state_version`，也不写运行时状态。
- `deserializeNBT()`：
  - 读取版本；
  - 清空并重置运行时 `combatAiState`；
  - 不允许 provider 复用时携带旧 goal 引用。
- `copy()` 只复制版本数字，不复制 goal 快照。

建议方法：

```text
getAiStateVersion()
setAiStateVersion(int)
```

`getCombatAiState()`、`getOrCreateCombatAiState()`、`clearCombatAiState()`
已在 A2 落地。

验收：

- 存档往返后版本值保留。
- 网络同步包不含新版本字段。
- 客户端不会获得 goal 引用。

### A4：改造 `FurkinCombatMode`

状态：已完成（2026-09-24）；冲突 goal 快照、精确实例清理、legacy 路径、原 goal 恢复与 `onUnbind` 已落地，`compileJava` 通过。`GoalSelector#addGoal` 会重新创建 wrapper，因此恢复承诺为原 goal 实例与优先级一致，不承诺 wrapper 身份相同；运行验证放 A7。

修改：

```text
src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatMode.java
```

建议方法：

```text
boolean applyTo(TamableAnimal)
void clearOwnedGoals(TamableAnimal, FurkinCombatAiState)
void captureAndRemoveOriginalGoals(TamableAnimal, FurkinCombatAiState)
void restoreOriginalGoals(TamableAnimal, FurkinCombatAiState)
void onUnbind(TamableAnimal, FurkinCombatAiState)
```

`applyTo` 顺序：

1. 客户端侧直接返回失败或空操作。
2. 读取 capability；无能力时返回失败。
3. 获取 `FurkinCombatAiState`。
4. 清除本模组拥有的 goal。
5. 如果尚未捕获原状态：
   - `aiStateVersion >= CURRENT`：保存并移除冲突 goal。
   - `aiStateVersion < CURRENT`：标记 `legacyPolluted`，不再猜测性恢复。
6. 按当前模式添加一组 Furkin goal。
7. 保证重复调用不增加 goal 数量。

冲突快照谓词：

- 两个 selector 中的全部 `TargetGoal`。
- `MeleeAttackGoal`。
- `OcelotAttackGoal`。

必须保留：

- `FloatGoal`。
- `FollowOwnerGoal`。
- `SitWhenOrderedToGoal`。
- `PanicGoal`。
- 第三方非冲突 goal。

`onUnbind` 顺序：

1. 按 owned 实例移除全部 Furkin goal。
2. 用私有标记做兜底清理。
3. 清空当前目标。
4. 仅当存在有效快照时恢复原 goal。
5. 清空运行时 AI state。
6. `ai_state_version` 回 0。

验收：

- 新契约狼的原 goal 实例与优先级可恢复。
- 新契约猫不会误删非冲突 goal。
- 第三方非冲突 goal 保持原实例。
- 模式切换和重复 apply 不产生重复 goal。
- 旧档 `version = 0` 不会恢复猜测出的 goal。

### A5：接入三个现有调用点

状态：已完成（2026-09-24）；新契约、模式切换和实体重建三处已接入所有权版 `applyTo`，失败顺序已处理，`compileJava` 通过。运行验证放 A7。

修改：

1. `FurkinContractHandler`
   - 新契约在 `applyTo` 前设置 `ai_state_version = CURRENT`。
   - `applyTo` 失败时不得扣契约物品、不得写不可回滚状态。
2. `FurkinCombatModeHandler`
   - 先应用新模式；成功后再写能力、档案并同步客户端。
   - 应用失败时保持旧模式和旧 goal 状态。
3. `FurkinCompanionManager#rebuildCompanion`
   - 重建实体后在首次 apply 前设置 `ai_state_version = CURRENT`。
   - apply 失败时不要把 `summoned=true` 落档。

验收：

- 三个入口都只调用新的所有权版 `applyTo`。
- 不存在残留的 `removeAllGoals` 基类删除路径。
- 失败路径不扣物、不写错状态。

### A6：新增 `EntityJoinLevelEvent`

状态：已完成（2026-09-24）；服务端实体入世恢复已接入，事件重入保持幂等，`compileJava` 通过。运行验证放 A7。

修改：

```text
src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java
```

新增处理：

```text
onEntityJoinLevel(EntityJoinLevelEvent)
```

条件：

- 事件未取消。
- 服务端。
- 实体是 `TamableAnimal`。
- capability 存在且 `isCompanion()`。

行动：

- 调用档案模式对应的 `applyTo`。
- 不因为入世事件额外发送同步包。
- 不在客户端修改 goal。
- 事件重入保持幂等。

验收：

- 区块卸载再加载后 Furkin AI 自动恢复。
- 跨维度移动后 goal 不重复。
- `addFreshEntity` 显式 apply 与事件 apply 同时发生时仍只有一组 owned goal。

### A7：临时服务端夹具

状态：已完成（2026-09-24，专用服务端两轮夹具）；临时夹具和状态文件已删除，未进入待提交文件。

使用临时夹具覆盖：

- 新建狼。
- 新建猫。
- 非冲突 goal 保留。
- 原 goal 实例与优先级恢复（wrapper 由 `GoalSelector#addGoal` 重建）。
- 四档模式切换。
- 重复 apply 幂等。
- 区块卸载/重载。
- 跨维度移动。
- 旧档 `ai_state_version = 0`。
- apply 失败不扣物、不落态。

夹具要求：

- 仅用于本次验证。
- 不提交。
- 最终 JAR 不包含夹具类。

#### A7 执行记录（2026-09-24）

- 第一轮 `runServer`：159 项检查、0 失败；完成新建狼、新建猫、非冲突 goal 保留、原 goal 实例与优先级恢复、四档切换、重复 apply 幂等、旧档 `version = 0`、跨维度移动和重载实体落盘。
- 第二轮 `runServer`：11 项检查、0 失败；从磁盘加载远端区块，确认 `EntityJoinLevelEvent` 恢复 `AGGRESSIVE` 模式、`ai_state_version = 1`、五个 owned goal 且两个 selector 计数正确。
- 夹具类 `Wp02aAiFixture` 和运行态 `wp02a_ai_fixture.state` 已删除，最终构建不会收集夹具。
- `apply 失败不扣物、不落态` 未采用运行时注入缺失 capability：按 `executeContract`、`setMode`、`rebuildCompanion` 的源码顺序复核，失败分支均在 `hand.shrink(1)`、档案写入、`setCombatMode`、`setSummoned(true)` 或成功状态落档之前返回。

### A8：构建与文档

状态：已完成（2026-09-24）；`compileJava`、`build`、`runServer`、`runClient` 均通过。客户端日志仅有离线 Realms 验证 INFO 和原版资源/声音警告，无 ERROR/FATAL、类加载异常或注册失败。

阶段 A1-A7 完成后执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

检查：

- `run/logs/latest.log` 无新增 `ERROR`、`FATAL`、异常栈、注册失败或资源缺失。
- 客户端侧没有 goal 修改。
- 专用服务端加载、卸载和重载正常。
- 最终 JAR 不含临时夹具。

文档更新：

- `docs/wp-02_ai_ownership_unbind_design.md` 记录实际签名和事件顺序结论。
- `docs/code_review_2026-09-24_workflow.md` 更新 WP-02A 执行状态。
- 若出现用户可见行为变化，再评估 changelog；WP-02A 预计以内部修复为主。

---

## 4. 不变量

- 只删除 Furkin 自己创建的 goal 实例。
- 原 goal 快照只存在于实体能力对象内存中。
- 快照不进入实体 NBT、档案、网络包或静态全局 Map。
- `ai_state_version` 是唯一持久化版本字段。
- 旧档不猜测性恢复原版/第三方 goal。
- 所有 apply 入口必须幂等。
- 客户端不修改 selector。
- 任何失败不得先写成功状态或扣物品。

---

## 5. 风险与停止条件

| 风险 | 处理 |
|---|---|
| `EntityJoinLevelEvent` 早于能力反序列化 | A0 不通过则停止，改用已核实的入世路径 |
| 第三方动态 goal 不在构造阶段注册 | 只保留当前可见的冲突 goal；不猜测不存在状态 |
| `WrappedGoal` 重新加入后身份变化 | 只要求原 goal 实例和优先级恢复，不要求 wrapper 身份相同 |
| 重复 apply 导致 goal 增长 | 必须在 A4 阻断，不允许带到 A5 |
| 旧档 `version = 0` 状态判断错误 | 使用 legacy 路径，不做自动回填 |
| 契约 apply 失败仍扣物 | A5 必须修复，否则停止 |
| 事件与显式 apply 重入 | 通过 owned identity set 和 captured 状态保持幂等 |

---

## 6. 确认门槛

执行时严格按以下顺序暂停：

1. A0 取证完成，报告结果并确认。
2. A1-A4 完成，`compileJava` 通过，报告并确认。
3. A5-A6 完成，专项夹具通过，报告并确认。
4. A7-A8 完成，运行验证与日志复核通过，报告并确认。

未获得当前阶段确认，不进入下一阶段；未获得明确授权，不提交、不推送。