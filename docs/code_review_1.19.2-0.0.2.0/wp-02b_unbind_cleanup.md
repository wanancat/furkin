# WP-02B：解绑清理管线

- 工作项：H-02（WP-02 的第二阶段；与 WP-02A 的 goal 所有权共同关闭 M-03/H-02 的代码基础）
- 实施日期：2026-09-25
- 基线：`2d698db`（`mc1.19.2`）
- 状态：已完成
- 提交范围：仅 WP-02B；不推送
- 冻结口径：D1～D8；`AGENTS.md` 不改，D9 留给 WP-09

---

## 1. 根因

旧解绑路径只清身份 / 等级 / 技能等级 / 战斗模式，然后立刻删档案；没有：

- 把行囊内容倒到宠物脚下；
- 掉落并清空四件盔甲；
- 还原盔甲掉落概率；
- 移除被动分发器维护的 transient modifier；
- 对未召唤 / 已亡档案中的装备快照做归还；
- 在清理失败时保留档案供重试。

因此解绑可能永久丢失行囊 / 装备，或留下已不再是绒亲但仍存在的属性加成。目标已召唤但实体定位不到时，旧路径还会直接删档，制造幽灵记录。

---

## 2. 实施内容

### 2.1 共享清理管线

新增 `FurkinUnbindCleanup`，固定顺序与工作流 §12.2 对齐：

1. 校验服务端、能力、`companionId` 与期望 ID；
2. `FurkinCombatMode.onUnbind(...)`；
3. `PouchDrop.dropAll(...)`；
4. `EquipmentSlots.dropAndClear(...)`；
5. `EquipmentSlots.restoreDefaultDropChances(...)`；
6. `SkillEffectApplier.removeAll(...)`；
7. `SkillPassiveDispatcher.clearRuntimeEffects(...)`；
8. 清等级 / 经验 / 技能点 / 技能等级 / 冷却 / 进食计数 / 行囊容量；
9. `FurkinData.clearForUnbind()`；
10. 清 TAME / owner / sit / target / CustomName；
11. 后置条件检查。

失败返回 `Result`（阶段 + 原始异常），不会在管线内部删除档案；调用方仅在成功时删档。

### 2.2 装备与掉落率

`EquipmentSlots` 新增 1.19.2 版本的三项能力：

- `dropAndClear(LivingEntity)`：复用本分支已有的 `MobEquipmentContainer` 和官方 `Containers.dropContents`；
- `restoreDefaultDropChances(LivingEntity)`：写回 `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE`（D4）；
- `dropArchivedEquipment(ServerPlayer, CompoundTag)`：把未召唤 / 已亡档案的 `ArmorItems` 快照在发起者脚下掉落。

1.19.2 的原版没有 1.20.1 那个 `MobEquipmentContainer` 类型；本分支已有的同名自定义容器是官方 `Container` 视图，继续复用它，没有新增第二套装备序列化。

### 2.3 解绑入口

`FurkinRecordActionHandler.unbind(...)` 改为：

- 已召唤：先定位本维度实体；找不到返回 `ENTITY_UNRESOLVED`，不删档；
- 找到：执行 `FurkinUnbindCleanup.cleanup(...)`，失败返回 `CLEANUP_FAILED`；
- 未召唤 / 已亡：先 `dropArchivedEquipment(...)`，成功后再删档；
- 所有归还动作完成后才 `archive.removeEntry(...)`。

`RecordActionPacket` 为新增结果补充 `furkin.msg.unbind_entity_unresolved` 和
`furkin.msg.unbind_cleanup_failed` 文案；protocol 版本在本 WP 不变，WP-04 统一收口字段变更。

### 2.4 1.19.2 API 细节

- 世界访问使用 `Entity#getLevel()`，不是 1.20.1 的 `level()`。
- `Containers.dropContents(...)` 在 1.19.2 不负责清空实体槽，`dropAndClear` 在调用后显式清槽。
- `Mob#getEquipmentDropChance(...)` 是 `protected`，外部不可读；按 D4 只写默认值，不做猜测性还原。
- `SkillPassiveDispatcher.clearRuntimeEffects(...)` 是本分支新增入口，移除 `PACK_TACTICS_UUID` transient modifier 并清周期产出计时。

---

## 3. 修改文件

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinUnbindCleanup.java`（新增）
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java`
- `src/main/java/com/wanancat/furkin/internal/equipment/EquipmentSlots.java`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillPassiveDispatcher.java`
- `src/main/java/com/wanancat/furkin/internal/network/RecordActionPacket.java`
- `src/main/resources/assets/furkin/lang/en_us.json`
- `src/main/resources/assets/furkin/lang/zh_cn.json`

---

## 4. 验证

### 4.1 编译与干净构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --console=plain
```

结果：`BUILD SUCCESSFUL in 14s`；夹具删除后的 JAR 不包含 `Wp02bCleanupFixture`。

### 4.2 专用服务端运行取证

按用户授权加入一次性 `Wp02bCleanupFixture`，在 `ServerStartedEvent` 创建带四件盔甲和两格行囊物品的狼，设置技能效果 / 群猎 transient modifier / 进食与冷却状态，执行清理管线；同时用 `FakePlayer` 验证未召唤档案装备归还。日志时间 2026-09-25 16:33:11，证据位于 `run/logs/latest.log`：

```text
[WP02B-FIXTURE] P1 cleanup succeeds: PASS
[WP02B-FIXTURE] P2 identity/state/runtime data cleared: PASS
[WP02B-FIXTURE] P3 pouch and armor slots cleared: PASS
[WP02B-FIXTURE] P4 drops include pouch and armor: PASS
[WP02B-FIXTURE] P5 drop chance restored to vanilla default: PASS
[WP02B-FIXTURE] P6 skill modifiers removed: PASS
[WP02B-FIXTURE] P7 vanilla ownership/name cleared: PASS
Furkin unbound: id=... by WP02B
[WP02B-FIXTURE] P8 archived equipment unbind succeeds: PASS
[WP02B-FIXTURE] P9 archived entry removed after drop: PASS
[WP02B-FIXTURE] SUMMARY pass=9 fail=0 failed=none
```

同次运行到达 `Done (12.798s)`；没有项目自身 `ERROR` / `FATAL` / 异常栈。

### 4.3 验证边界

- 已验证：行囊 / 四盔甲掉落与清槽、掉率还原、技能 modifier 清理、身份与原版归属清理、
  未召唤档案装备归还、入口成功后才删档。
- 未运行确认：真实客户端录内按钮的完整点击链、跨维度实体定位、强制解绑、墓碑入世清理；
  这些属于 WP-09，本 WP 只提供清理管线并在当前维度入口接好。
- 残留边界：第三方自定义掉率无法读取原值，只能按 D4 写回原版默认值；主 / 副手不在绒亲装备管辖范围。
- 本项不修改公开 API，不修改 `AGENTS.md` 或 `.gitignore`。