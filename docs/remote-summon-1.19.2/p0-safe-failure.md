# P0：未解析实体安全失败

- 状态：已实施；2026-09-27 已完成“卸载 -> 失败召唤 -> 原区块回载 -> 装备 / 行囊保持”定向闭环验收；P1/P2 不得回退该安全失败语义
- 依赖：无，必须先于 P1/P2 完成
- 目标版本：下一次 1.19.2 MINOR/PATCH 均可，建议先作为独立修复提交
- 影响面：服务端召唤分流、命令回执、界面提示、回归测试

## 1. 目标

当档案为 `summoned=true`，但目标实体因区块未加载、维度不可用或运行时索引缺失而无法解析时：

- 不改变档案状态。
- 不清空实体 UUID / 维度 / 未来位置字段。
- 不创建新实体。
- 不调用重建、复活或解绑清理。
- 返回明确的 `ENTITY_UNRESOLVED`，让玩家知道本次操作没有生效。

本阶段不是远距召唤本身，而是先切断“未加载 -> 误判丢失 -> 自愈 -> 第二次重建”的危险路径。

## 2. 当前根因

`FurkinCompanionManager.summonOrTeleport(...)`：

- 档案为 `summoned=true` 时调用 `teleportToOwner(...)`。
- `teleportToOwner(...)` 调用 `FurkinEntityLocator.locate(...)`。
- 定位器只查询 `ServerLevel#getEntity(UUID)`，不加载区块。
- 实体所在区块未加载时返回 `null`。
- 当前 `null` 分支执行：

```java
entry.setSummoned(false);
entry.clearEntityLocation();
archive.putEntry(entry);
return false;
```

这与“实体真的丢失/已收回”的语义混淆。下一次点击因此进入 `summon()` 的“未召唤”分支，从旧档案 `rebuildCompanion(...)`。活动实体的实时装备和行囊从未同步到档案，所以新实体通常丢失当前装备和行囊，而旧实体仍在区块存档中。

## 3. 设计决策

### 3.1 结果类型

在 `FurkinCompanionManager` 内新增明确的传送结果，避免 boolean 混淆全部失败原因：

```text
TeleportResult
  TELEPORTED
  ENTITY_UNRESOLVED
  DIMENSION_CHANGE_FAILED
```

`summonOrTeleport` 映射规则：

- `TELEPORTED -> SummonResult.TELEPORTED`
- `ENTITY_UNRESOLVED -> SummonResult.ENTITY_UNRESOLVED`
- `DIMENSION_CHANGE_FAILED -> SummonResult.DIMENSION_CHANGE_FAILED`
- 只有请求开始时 `entry.isSummoned() == false`，才允许进入 `rebuildCompanion(...)`

结果枚举只用于服务端内部和消息选择；不改变 `RequestSummonPacket` 字段。

### 3.2 失败只读

`teleportToOwner` 的 `target == null` 路径必须改成：

- 记录 `WARN` 或 `INFO` 诊断日志：
  - `companionId`
  - `entityUuid`
  - `entityDimension`
  - 请求玩家 UUID
  - 找不到的原因分类：无定位字段 / 维度不可用 / 已加载索引未命中
- 返回 `ENTITY_UNRESOLVED`
- 不调用 `setSummoned(false)`
- 不调用 `clearEntityLocation()`
- 不调用 `rebuildCompanion(...)`

建议日志固定前缀：

```text
Furkin remote resolve failed: id=<companionId>, entity=<entityUuid>, dimension=<dimension>, reason=<reason>
```

### 3.3 档案状态语义

完成后应满足：

| 档案状态 | 实体加载状态 | 操作 |
|---|---|---|
| `summoned=false` | 不在场 | 允许重建 / 复活，但必须先按 `entity_uuid` 查已加载索引；命中则拒绝并转 P1 修复 |
| `summoned=true` | 已加载且 UUID 命中 | 允许传送 |
| `summoned=true` | 未加载但位置可查 | P2 临时加载后传送 |
| `summoned=true` | 未加载且位置不可查 | 返回 `ENTITY_UNRESOLVED`，状态不变 |
| `summoned=true` | 记录 UUID 无命中 | 返回 `ENTITY_UNRESOLVED`，状态不变 |

P0 只实现最后两项的安全失败，不实现 P2 的临时加载。

## 4. 代码改动清单

### 4.1 `FurkinCompanionManager.java`

- 新增 `TeleportResult`。
- `teleportToOwner(...)` 返回 `TeleportResult`，保留所有成功的维度切换、`teleportTo`、位置刷新和跟随唤醒逻辑。
- `teleportToOwner` 的 `null` 分支不退化为 `REBUILD_FAILED`，也不写档案。
- `summonOrTeleport` 增加新结果映射。
- 在 `summon(...)` / `rebuildCompanion(...)` 前增加同 UUID 已加载守卫：若 `entry.entityUuid` 非空且能通过 `ServerLevel#getEntity(UUID)` 在任一已加载维度命中，禁止重建，返回 `ENTITY_UNRESOLVED` 或内部 `INVALID_STATE`。该查询只查运行时索引，不加载区块。
- 复查 `summon(...)`：仍要求 `entry.isSummoned() == false` 才重建；不能把“传送失败”作为重建入口。
- 复查所有 `entry.setSummoned(false)` 调用，只允许：
  - `dismiss(...)` 成功收回。
  - `CommonEvents.markFallenIfCompanion(...)` 死亡侧写。
  - 明确的管理员修复流程（P1）。

### 4.2 `RequestSummonPacket.java`

- `ENTITY_UNRESOLVED` / `DIMENSION_CHANGE_FAILED` 使用独立文案，不要用笼统的 `furkin.msg.summon_failed`。
- 失败时不刷新为“未召唤”状态；如果界面需要保持列表一致性，可刷新现有列表，但服务端档案状态不能改变。
- 确认包字段和方向不变，不升 `PROTOCOL_VERSION`。

### 4.3 `FurkinCommand.java`

- `/furkin summon <id>` 与界面走同一个 `summonOrTeleport`。
- 对 `ENTITY_UNRESOLVED` 返回明确的 failure 回执。
- 命令不应因为一次不可解析而改变档案状态。

### 4.4 语言文件

同时更新：

- `src/main/resources/assets/furkin/lang/en_us.json`
- `src/main/resources/assets/furkin/lang/zh_cn.json`

建议键：

- `furkin.msg.summon_entity_unresolved`
- `furkin.msg.summon_dimension_change_failed`
- `furkin.command.summon.entity_unresolved`
- `furkin.command.summon.dimension_change_failed`

文案必须说明本次没有召唤、没有丢失状态，并提示目标所在区块未加载或实体不可解析。中英文键集合必须相同。

## 5. 验证步骤

### 5.1 静态验证

- `rg -n "setSummoned\\(false\\)|clearEntityLocation\\(\\)|REBUILD_FAILED|ENTITY_UNRESOLVED" src/main/java`
- 确认 `teleportToOwner` 的失败路径没有任何档案写入。
- 确认 `summonOrTeleport` 不会在一次请求内先传送失败再重建。
- `.\gradlew.bat compileJava --console=plain`
- Java/资源元数据改动后运行 `.\gradlew.bat build --console=plain`

### 5.2 运行期验证

建议使用真实世界或临时服务端夹具，夹具验证后移除，只保留日志证据：

1. 契约一只绒亲，穿上盔甲，并向行囊放入物品。
2. 记录档案中的 `summoned=true`、`entity_uuid`、`entity_dimension`。
3. 将玩家移到远处，确认目标区块已卸载。
4. 从绒亲录连续点击两次召唤。
5. 预期：
   - 两次都返回未解析/失败提示。
   - `summoned` 仍为 `true`。
   - `entity_uuid` 未清空。
   - 没有新增同 `companionId` 的实体。
   - 原实体在当前未加载区块存档中仍保留原装备和行囊。
6. 回到目标区域加载原区块。
7. 预期原实体仍可解析，装备和行囊没有丢失。
8. 对另一只已收回的绒亲正常执行“收回 -> 召唤”，确认合法重建路径不受影响。

### 5.3 失败与边界

- 档案缺少 `entity_uuid`，但 `summoned=true`：返回未解析，状态不变。
- 记录维度不存在：返回未解析，状态不变。
- 目标实体已加载但不在记录维度：
  - 定位器能跨已加载维度找到时正常传送。
  - 无法找到时返回未解析，状态不变。
- 跨维度已加载实体：`changeDimension` 失败只返回传送失败，不触碰档案。
- `summoned=false` 但同 UUID 实体仍在已加载索引：拒绝重建，不能把状态不一致静默变成第二只实体；提示管理员走 P1 修复。

## 6. 验收标准

- [x] 未加载实体不会触发 `summoned=false`。
- [x] 未加载实体不会触发 `clearEntityLocation()`。
- [x] 第二次点击不会产生新实体。
- [x] 真实实体、装备、行囊在重回区块后保持。
- [x] 正常 `dismiss` -> `summon` 仍能按档案快照恢复。
- [x] `summoned=false` 但同 UUID 实体已加载时不会重建第二只实体。
- [x] `RequestSummonPacket` 线格式未改变。
- [x] `compileJava`、`build`、服务端运行验证通过，日志无新增 `ERROR` / `FATAL`。

## 7. 首轮执行记录

- 日期：2026-09-26。
- 已修改：
  - `FurkinCompanionManager`：新增 `TeleportResult`，移除未解析实体路径的自愈解绑，增加“已加载但档案未召唤”重建守卫和结构化诊断日志。
  - `FurkinEntityLocator`：新增只读的已加载 UUID 查询，供 P0 守卫复用。
  - `RequestSummonPacket`、`FurkinCommand`：接入 `ENTITY_UNRESOLVED` 与 `DIMENSION_CHANGE_FAILED` 回执。
  - 双语 `en_us.json` / `zh_cn.json`：补齐两条界面消息和两条命令消息。
- 已执行：
  - `.\gradlew.bat compileJava --console=plain`：通过。
  - `.\gradlew.bat build --console=plain`：通过。
  - `runServer`：启动至 `Done`，Furkin 加载成功；随后手动停止。日志未见 Furkin 相关异常栈。
- 待确认：
  - `runServer` 的 `latest.log` 中存在 Minecraft 数据包 tag 缺失 ERROR；当前未做改动前基线对比，不能宣称“全日志零 ERROR”。本轮 P0 的验收项仍需按世界夹具验证。

## 8. 卸载 / 回载闭环验收（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17；两阶段服务端夹具，临时世界 `furkin_p1p0p2_closure_20260927`。
- 第一阶段在 Overworld `(1024, 100, 1024)` 生成带钻石头盔、7 个骨头行囊的 `ARMOR_STAND` canonical，写入 `summoned=true`、实体 UUID / 维度 / 位置及等级 17 / 经验 42 / 技能点 5 / `AGGRESSIVE` 档案。夹具使用临时 force ticket 确保远区块真的把实体落盘；清除 force 后才停服，产品没有新增永久强加载。
- 第二阶段确认目标区块未加载后，调用真实 `FurkinCompanionManager.summonOrTeleport(...)` 得到 `ENTITY_UNRESOLVED`；档案 UUID / 维度 / 位置 / `summoned` / `alive` 不变，目标 `companionId` 的已加载候选仍为 0。日志：`FURKIN_FIXTURE_C_REMOTE_FAIL_OK ... result=ENTITY_UNRESOLVED archiveUnchanged=true`。
- 加载目标区块并等待实体 section 入世后，按档案 UUID 定位到同一实体；UUID、维度、位置、钻石头盔和 7 个骨头全部保持。日志：`FURKIN_FIXTURE_C_P1_01_RELOAD_OK ... helmet=true bones=7`。
- 已加载传送复用同一实体，canonical UUID 不变，实体靠近主人且装备 / 行囊未丢失。日志：`FURKIN_FIXTURE_C_P2_LOADED_TELEPORT_OK ... helmet=true bones=7 nearOwner=true`。
- 同一夹具补验正常 `dismiss -> summon`：档案快照恢复等级 9 / 经验 11 / 技能点 2 / `FOLLOW` / 铁头盔 / 自定义名字。日志：`FURKIN_FIXTURE_C_RECALL_OK ... level=9 xp=11 points=2 helmet=true name=true`。该路径沿用快照中的实体 UUID，本项未新增“必须换 UUID”的要求。
- 连续点击路径不在本夹具内重复触发；真实 GUI 第二次点击不发送第二请求、不产生第二实体的既有证据见 `verification-matrix.md` §5.7。
- `summoned=false` 且 `entity_uuid` 非空、该 UUID 实体已加载时，真实远召返回 `ENTITY_UNRESOLVED`；档案 UUID 不变、`summoned` 仍为 false、最终同身份已加载候选数为 1。日志：`FURKIN_FIXTURE_C_SAME_UUID_GUARD_OK ... result=ENTITY_UNRESOLVED loadedCandidates=1`。
- 原始日志：`D:\frukin_dev\_research\p1p0p2_closure_prepare_20260927.log`、`D:\frukin_dev\_research\p1p0p2_closure_20260927.log`；干净回归：`D:\frukin_dev\_research\p1p0p2_closure_clean_20260927.log`。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过；无夹具 `runServer` 达到 `Done (20.112s)`，日志无 `ERROR` / `FATAL`。该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。
