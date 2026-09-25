# WP-02A：战斗 AI goal 所有权

- 工作项：M-03（WP-02 的第一阶段，为 H-02 清理管线提供可安全解绑的 AI 基础）
- 实施日期：2026-09-25
- 基线：`3c330ab`（`mc1.19.2`）
- 状态：已完成
- 提交范围：仅 WP-02A；不推送
- 参考：1.20.1 `FurkinCombatMode` / `FurkinCombatAiState` 的所有权模型
- 冻结口径：D6；`AGENTS.md` 不改

---

## 1. 根因

旧 `FurkinCombatMode.applyTo(...)` 在每次切换战斗模式时按基类删除 goal：

- `TargetGoal`：连同原版护主、反击和第三方目标一起删除；
- `MeleeAttackGoal`：删除原版近战行动目标；
- `OcelotAttackGoal`：删除猫科攻击目标。

调用方没有保存原 goal 实例，也没有优先级快照。切换一次模式后，原版/第三方 AI
无法恢复；解绑时即使清掉本模组 goal，宠物也不再是完整原版动物。由此 M-03 与
H-02 形成硬依赖，必须先建立明确所有权。

---

## 2. 实施内容

### 2.1 运行时所有权状态

新增 `internal.contract.FurkinCombatAiState`，只在实体内存中存在，不进入 NBT、档案或
网络同步。状态包含：

- 原 `goalSelector` / `targetSelector` 的冲突 goal 快照（`WrappedGoal`，保留原实例与优先级）；
- 本模组创建 goal 的精确实例集合（identity set）；
- `originalCaptured` / `legacyPolluted` 标记。

`FurkinData` 新增 `ai_state_version`：

- 当前值 `CURRENT_AI_STATE_VERSION = 1`；
- 旧档缺失按 `0`；
- 运行时 `combatAiState` 标记为 `transient`；
- 反序列化前先清空旧运行时状态；
- `clearForUnbind()` 同步清空全部战斗 AI 归属。

### 2.2 只增删自有 goal

`FurkinCombatMode.applyTo(...)` 改为返回 `boolean`，并在失败时不提交模式变更：

1. 先只移除本模组记录过实例的 goal；私有 `FurkinOwnedGoal` 标记只作兜底，避免第三方
   同名/同基类被误删。
2. 第一次接管且 `ai_state_version >= 1` 时，保存并移除两个 selector 中与冻结范围冲突的
   `TargetGoal` / `MeleeAttackGoal` / `OcelotAttackGoal`。
3. 旧档 `ai_state_version = 0` 不猜测原 goal，只移除冲突项并记 `WARN`，不生成假快照。
4. 按当前模式重建本模组私有 goal；重复调用同一档位保持幂等。
5. 新增 `onUnbind(...)`：移除本模组 goal、清 target，并在有效快照存在时按原实例和优先级恢复；
   最后清运行时状态并把 `ai_state_version` 回零。

### 2.3 实体生命周期接入

- 契约执行前先建立 AI 版本并应用默认 `FOLLOW`；AI 应用失败则不提交契约状态、不消耗物品。
- 召唤/复活重建实体时写入 AI 版本并应用档案中的模式；失败时不把实体标记为 summoned。
- `EntityJoinLevelEvent` 在服务端对已契约 `TamableAnimal` 重新应用模式，覆盖区块重载、跨维度
  重新入世和存档读盘路径。
- 客户端不执行 AI 结算。

### 2.4 1.19.2 API 差异

1.19.2 没有谓词版 `GoalSelector#removeAllGoals(Predicate)`。本分支使用已核实的公开
`getAvailableGoals()` 活集合 `removeIf(...)`，避免 `removeGoal(...)` 额外触发 `stop()`。

世界访问使用 `Entity#getLevel()`，没有 1.20.1 的 `level()` 形式。

`FurkinCombatAiState` 的快照和 goal 类型均以 1.19.2 mapped official jar 编译验证为准，
未整体照搬 1.20.1。

---

## 3. 修改文件

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatAiState.java`（新增）
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatMode.java`
- `src/main/java/com/wanancat/furkin/internal/capability/FurkinData.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java`

---

## 4. 验证

### 4.1 编译与干净构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --console=plain
```

结果：`BUILD SUCCESSFUL in 14s`；夹具删除后的 `clean build` 通过，JAR 未包含
`Wp02aAiOwnershipFixture`。

### 4.2 专用服务端运行取证

按用户授权的临时服务端夹具在 `ServerStartedEvent` 创建狼实体，覆盖会话中的
12 个探针；日志时间 2026-09-25 16:23:06，证据位于 `run/logs/latest.log`：

```text
[WP02A-FIXTURE] P1 join applies FOLLOW and owns one melee goal: PASS
[WP02A-FIXTURE] P2 non-conflicting goals preserved: PASS
[WP02A-FIXTURE] P3 original conflicting goals snapshotted: PASS
[WP02A-FIXTURE] P4 PASSIVE owns two goals: PASS
[WP02A-FIXTURE] P5 repeated PASSIVE is idempotent: PASS
[WP02A-FIXTURE] P6 PROTECT owns four goals: PASS
[WP02A-FIXTURE] P7 AGGRESSIVE owns five goals: PASS
[WP02A-FIXTURE] P8 unbind removes only owned goals: PASS
[WP02A-FIXTURE] P9 unbind restores original goal instances and priorities: PASS
[WP02A-FIXTURE] P10 unbind clears AI state and version: PASS
[WP02A-FIXTURE] P11 legacy join removes conflicting goals without snapshot: PASS
[WP02A-FIXTURE] P12 legacy unbind does not guess-restore: PASS
[WP02A-FIXTURE] SUMMARY pass=12 fail=0 failed=none
```

同次运行到达 `Done (11.844s)`；除夹具主动制造的旧档兼容 `WARN` 外，没有项目自身
`ERROR` / `FATAL` / 异常栈。第一次夹具运行因探针把“应当被移除的原版目标”误纳入
非冲突检查而报 P2 FAIL；修正探针后复测全部 PASS，探针问题已记录，不归因代码。

### 4.3 验证边界

- 已验证：14 个目标检查（12 个探针）中的新建实体入世、四档重建、重复 apply、非冲突 goal
  保留、原 goal 实例/优先级恢复、旧档不恢复、解绑清理。
- 运行已确认：服务器级事件路径和日志。
- 未运行确认：真实客户端 UI 的操作时序、区块卸载后实体对象重建、第三方自定义 goal 的
  语义兼容；这些属于 WP-02B / WP-09 与最终集成验证的边界。
- 本项不修改公开 API，不修改 `AGENTS.md`，不修改 `.gitignore`。

---

## 5. 残余风险与后续

- 旧档只有 `ai_state_version=0` 的实体没有原 goal 快照，解绑后只能回到原版重建行为，
  不能精确恢复运行期第三方实例；这是冻结的“未证实即可恢复”的保守口径。
- WP-02B 必须把本 WP 的 `onUnbind(...)` 接入清理管线；H-02 在解绑清理落地前不能关闭。
- 本 WP 只处理 `TamableAnimal` 的两个 selector，不触碰原版 TAME 行为层、寻路、繁殖和非冲突 AI。