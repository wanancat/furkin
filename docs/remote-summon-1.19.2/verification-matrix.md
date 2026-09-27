# 远距召唤功能包统一验证矩阵

- 日期：2026-09-27
- 适用阶段：P0、P1、P2
- 目标：把每个阶段的静态、运行期、异常和回归证据固定为可复现记录

## 1. 环境

每次验证记录以下信息：

- Minecraft：`1.19.2`
- Forge：`43.2.0`
- Java：17
- 分支：`mc1.19.2`
- 基线 commit
- 是否使用临时夹具
- 世界坐标、维度、实体 UUID、companionId
- 具体测试命令和观察结果

Gradle 命令必须显式设置 JDK 17：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 2. 通用静态门槛

每个工作包结束至少执行：

```powershell
.\gradlew.bat compileJava --console=plain
```

涉及 Java/资源元数据、配置或数据迁移：

```powershell
.\gradlew.bat build --console=plain
```

检索危险路径：

```powershell
rg -n "setSummoned\\(false\\)|clearEntityLocation\\(\\)|setEntityLocation|entity_uuid|remoteSummon|addRegionTicket|removeRegionTicket|getChunkFuture|LivingTickEvent|FurkinDuplicateRegistry" src/main/java
```

运行日志门槛：

- `run/logs/latest.log` 无新增 `ERROR`。
- 无新增 `FATAL`。
- 无未捕获异常栈。
- 无缺失语言键、资源或注册表名称。
- 允许计划内的 `INFO` / 明确 `WARN`，但每个 WARN 必须能解释原因。

## 3. P0 验证矩阵

| 编号 | 场景 | 操作 | 预期 |
|---|---|---|---|
| P0-01 | 未加载实体，首次召唤 | 档案 `summoned=true`，目标区块卸载后点击召唤 | 返回未解析；不改档案；不生成实体 |
| P0-02 | 未加载实体，重复召唤 | 连续点击两次 | 两次均失败；没有第二个实体 |
| P0-03 | 装备保持 | 活动实体穿盔甲后卸载区块，执行失败召唤，再加载原区块 | 原实体盔甲仍在 |
| P0-04 | 行囊保持 | 活动实体行囊放物品后卸载区块，执行失败召唤，再加载原区块 | 原实体行囊物品仍在 |
| P0-05 | UUID 保持 | 失败后检查档案 | `entity_uuid` 未清空 |
| P0-06 | 维度保持 | 失败后检查档案 | `entity_dimension` 未清空 |
| P0-07 | 正常已加载传送 | 实体已加载时点击召唤 | 正常传送，行为与旧版本一致 |
| P0-08 | 跨维度已加载传送 | 实体在另一已加载维度 | 正常 `changeDimension` |
| P0-09 | 合法收回重建 | 正常 `dismiss` 后再召唤 | 从档案恢复，不进入远召加载路径 |
| P0-10 | 命令与 GUI 一致性 | 分别执行 `/furkin summon` 和录屏按钮 | 结果和状态变化一致 |
| P0-11 | 离线档案同 UUID 已加载 | `summoned=false` 且同 UUID 实体仍在已加载维度 | 拒绝重建，第二只实体为 0；提示走 P1 修复 |

P0 阶段不得启用 P2 的临时区块加载；P0-01 的失败是最终预期。

## 4. P1 验证矩阵

| 编号 | 场景 | 操作 | 预期 |
|---|---|---|---|
| P1-01 | canonical 正常入世 | 单只实体卸载再加载 | 档案 UUID、维度、位置保持同一实体 |
| P1-02 | 重复实体入世 | 制造同 companionId 的第二实体并加载 | 档案不被抢绑；记录重复诊断日志 |
| P1-03 | 只读诊断 | 执行 `repair list <id>` | 只输出候选，不修改实体或档案 |
| P1-04 | 选择旧实体 | 将旧实体 UUID 指定为保留 | 新实体被清理，旧实体装备和行囊保留或正确掉落 |
| P1-05 | 选择新实体 | 将新实体 UUID 指定为保留 | 旧实体物品转移到新实体或掉落，不凭空复制；canonical 等级 / 经验 / 技能 / 战斗模式 / 冷却复制到新实体，不回退旧快照 |
| P1-06 | 空物品重复体 | 清空重复体后执行 `choose` | 重复体安全移除 |
| P1-07 | 搬运失败 | 注入物品转移异常 | 不更新 canonical、不删除实体；已成功转移的源槽不重复生成物品 |
| P1-08 | 重复执行 | 连续执行两次相同修复 | 第二次不会重复搬运或误删 canonical |
| P1-09 | 重启恢复 | 修复后重启服务端并加载区块 | 档案仍指向唯一实体，不重新抢绑 |
| P1-10 | 未加载原实体 | canonical 仍在未加载区块 | 返回 `CANONICAL_NOT_LOADED`；不改档案、不删除现有实体，提示先加载原区块 |
| P1-11 | 两阶段顺序 | keeper 是当前非 canonical 实体 | 核心数据与所有物品处理成功后才改 canonical，再删除旧 canonical |
| P1-12 | 部分搬运重试 | 第一次搬运中途失败，第二次重试 | 已搬运物品不重复，未搬运物品继续处理，实体数量正确收敛 |
| P1-13 | 核心数据保留 | 保留新实体，当前 canonical 有更高等级 / 技能 / 冷却 | keeper 复制 canonical 核心数据，删除后等级 / 经验 / 技能 / 战斗模式 / 冷却不回退 |
| P1-14 | 核心数据复制失败 | 注入 `FurkinData` 复制或技能重建异常 | 返回 `CLEANUP_FAILED`；canonical 不变；不删除任何实体；重试安全 |

P1 的 `choose` 不允许自动选择“最近实体”“第一个实体”或“档案当前 UUID”作为隐式策略；必须由调用者明确指定保留 UUID。

### 4.1 P1 夹具验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端夹具。
- 结果：第二次运行通过，服务端启动至 `Done (2.144s)`，日志输出 `FURKIN_FIXTURE_REPAIR_OK`。
- 覆盖：P1-05、P1-11、P1-13 的 happy path；同时观察到 P1-02 的重复入世诊断日志。
- 验证内容：显式 keeper 为当前非 canonical 实体；核心数据、战斗模式与冷却先复制；装备和行囊转移或掉落；canonical 切换到 keeper；旧实体删除；最终只剩一个已加载候选。
- 物品结果：keeper 行囊骨头 96；10 泥土因缩容掉落；keeper 原有钻石头盔未覆盖，冲突铁头盔掉落；旧 canonical 铁胸甲转移成功。
- 失败记录：首次运行仅是夹具断言把泥土物品数量误计为堆数，修正夹具后重跑通过；不是产品失败。
- P1-09：双阶段夹具通过。第一阶段修复并保存后正常停服；第二阶段等待 40 tick 完成实体加载，档案件 owner / keeper UUID / overworld 维度 / `summoned` / `alive` 保持，索引仅找到 keeper，重复注册表无重复，等级 17 / 经验 42 / 技能点 5 / `travel_pouch=1` / `AGGRESSIVE` / cooldown `12345` 均保留；日志输出 `FURKIN_FIXTURE_P1_09_RESTART_OK`。
- P1-09 时序修正：首查在 `ServerStartedEvent` 中过早执行，实体索引尚未加载完成；改为等待 40 tick 后通过。该调整只修正夹具观察时机。
- 干净回归：移除夹具环境变量后启动至 `Done (2.309s)`；`latest.log` 无夹具日志或夹具类，无 Furkin 专属 `ERROR` / `FATAL`；基线 Minecraft tag ERROR 仍存在。
- 后续补测：P1-03 / P1-04 / P1-08 / P1-10 已由 §4.3 在真实命令 / `choose` 路径上补齐；P1-06 / P1-07 / P1-12 / P1-14 已由 §4.2 补测。

### 4.2 P1 失败注入与重试夹具验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具，环境变量 `FURKIN_FIXTURE_P1_FAILURE=1`，临时世界 `furkin_p1_failure_20260927`。
- 夹具通过真实 `FurkinDuplicateRepair.choose(...)` 执行，不使用反射访问产品私有实现；故障点为临时实体子类在真实装备写入与技能属性重建路径上抛出异常。
- P1-06：空物品重复体 `EMPTY_OK`，旧 canonical 已移除，keeper 与档案一致。
- P1-07：装备写入异常 `EQUIPMENT_OK`，返回 `CLEANUP_FAILED`，两个实体均保留，canonical 档案不变，铁头盔总数 1。
- P1-12：部分搬运后失败、第二次重试 `PARTIAL_OK`；第一次返回 `CLEANUP_FAILED`，第二次返回 `OK`，`totalBefore=2 totalAfter=2`，无重复、无丢失。
- P1-14：核心数据复制后的技能重建异常 `CORE_OK`；第一次返回 `CLEANUP_FAILED`，canonical 与 keeper 均保留、档案不变；第二次返回 `OK`，keeper 保留等级 17 / 经验 42 / 技能点 5 / `AGGRESSIVE` / cooldown `12345`。
- 原始日志：`D:\frukin_dev\_research\p1_failure_20260927.log`。三条计划内 `Furkin duplicate repair failed` ERROR 栈分别来自装备、部分搬运和核心数据故障注入，最终 `FURKIN_FIXTURE_P1_FAILURE_OK` 出现。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，最终 jar 不含 `internal.debug` / fixture 类；无夹具 `runServer` 达到 `Done (21.399s)`，干净日志 `D:\frukin_dev\_research\p1_failure_clean_20260927.log` 无夹具记录和 Furkin 专属 ERROR / FATAL。该干净 `runServer` 在 `Done` 后通过 Ctrl+C 终止，未记录为正常停服；本次没有 pending 请求。

### 4.3 P1 命令、反向修复与幂等夹具验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具，环境变量 `FURKIN_FIXTURE_P1_COMMAND=1`，临时世界 `furkin_p1_command_20260927`。
- P1-03：通过真实 Brigadier dispatcher 执行 `repair list`；权限 2 得到 3 条候选消息，诊断前后档案与实体快照一致；权限 1 被拒绝；缺少 keeper UUID 与非法 UUID 均被拒绝。日志：`FURKIN_FIXTURE_P1_COMMAND_LIST_OK ... result=1 messages=3 permissionDenied=true missingKeeperRejected=true`。
- P1-04：keeper 为当前 canonical，重复体被清理；铁头盔与 7 个骨头先转移，canonical UUID 不变，最终同身份已加载候选为 1。日志：`FURKIN_FIXTURE_P1_COMMAND_REVERSE_OK ... duplicateRemoved=true bones=7`。
- P1-08：同一 keeper 连续两次 `choose` 均返回 `OK`；第二次日志为 `discards=none`，状态快照稳定，未重复搬运或误删 canonical。日志：`FURKIN_FIXTURE_P1_COMMAND_REPEAT_OK ... first=OK second=OK stable=true`。
- P1-10：canonical 未加载、重复体已加载时返回 `CANONICAL_NOT_LOADED`；重复体保留，档案 canonical UUID 不变。日志：`FURKIN_FIXTURE_P1_COMMAND_CANONICAL_NOT_LOADED_OK ... result=CANONICAL_NOT_LOADED duplicateLoaded=true archiveUuid=6e818859-f809-46d1-91d1-6c8c02c3302a`。
- 第一次夹具运行在 P1-08 读取第一次 `choose` 后已合法删除的旧 canonical，修正夹具观察对象后全组通过；产品代码未因该失败修改。
- 原始日志：`D:\frukin_dev\_research\p1_command_20260927.log`。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，最终 jar 不含 `internal.debug` / fixture 类；无夹具 `runServer` 达到 `Done (24.177s)`，干净日志 `D:\frukin_dev\_research\p1_command_clean_20260927.log` 无夹具记录和 Furkin 专属 ERROR / FATAL。该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

### 4.4 模块 C：P0 / P1 持久化闭环夹具验收（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17；两阶段服务端夹具，环境变量 `FURKIN_FIXTURE_C_REMOTE_PERSIST=prepare|verify`，临时世界 `furkin_p1p0p2_closure_20260927`。
- 夹具通过真实 `FurkinCompanionManager.summonOrTeleport(...)`、`dismiss(...)` 与 `RemoteSummonService.request(...)` 入口执行；用临时 force ticket 保证远区块实体落盘 / 回载，清除 force 后才进入停服或终态断言，产品代码未修改。
- P0-01 / P0-05 / P0-06：目标 canonical 未加载时返回 `ENTITY_UNRESOLVED`，档案 UUID / 维度 / 位置 / `summoned` / `alive` 不变，不创建第二只实体。日志：`FURKIN_FIXTURE_C_REMOTE_FAIL_OK ... result=ENTITY_UNRESOLVED archiveUnchanged=true`。
- P0-02：模块 C 只执行一次失败远召并确认未新增实体；真实 GUI 第二次点击不产生第二请求 / 第二实体由既有 P2.6 GUI 夹具覆盖（`verification-matrix.md` §5.7）。
- P0-03 / P0-04 / P1-01：原区块回载后命中同一实体 UUID，钻石头盔、7 个骨头、维度与位置保持。日志：`FURKIN_FIXTURE_C_P1_01_RELOAD_OK ... helmet=true bones=7`。
- P0-07：已加载传送返回 `TELEPORTED`，canonical UUID 不变，装备 / 行囊不丢且实体靠近主人。日志：`FURKIN_FIXTURE_C_P2_LOADED_TELEPORT_OK ... helmet=true bones=7 nearOwner=true`。
- P0-09：`dismiss -> summon` 从档案快照恢复等级 9 / 经验 11 / 技能点 2 / `FOLLOW` / 铁头盔 / 名字；日志 `FURKIN_FIXTURE_C_RECALL_OK ... level=9 xp=11 points=2 helmet=true name=true`。本项不新增实体 UUID 必须变化的契约；当前实现按快照恢复原实体 UUID。
- P0-11：`summoned=false` 且 `entity_uuid` 非空、该 UUID 实体已加载时，真实远召返回 `ENTITY_UNRESOLVED`，档案 UUID 不变，最终同身份已加载候选为 1。日志：`FURKIN_FIXTURE_C_SAME_UUID_GUARD_OK ... result=ENTITY_UNRESOLVED loadedCandidates=1`。
- 原始日志：`D:\frukin_dev\_research\p1p0p2_closure_prepare_20260927.log`、`D:\frukin_dev\_research\p1p0p2_closure_20260927.log`；干净回归：`D:\frukin_dev\_research\p1p0p2_closure_clean_20260927.log`。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，最终 jar 不含 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (20.112s)`，日志无 `ERROR` / `FATAL`。该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

## 5. P2 验证矩阵

| 编号 | 场景 | 操作 | 预期 |
|---|---|---|---|
| P2-01 | 同维度已加载 | 目标实体已加载 | 立即传送，不添加 ticket |
| P2-02 | 跨维度已加载 | 目标实体在另一已加载维度 | `changeDimension` 成功 |
| P2-03 | 同维度未加载 | 记录位置后卸载目标区块，执行远召 | 临时加载、按 UUID 找到、传送；ticket 释放 |
| P2-04 | 跨维度未加载 | 在另一维度卸载目标区块，执行远召 | 目标维度临时加载、传送；源维度 ticket 释放 |
| P2-05 | 无位置字段 | 模拟旧档 `entity_pos=null` | 返回 `NO_POSITION`，档案不变，不重建 |
| P2-06 | 加载失败 | 模拟 chunk future 失败 | 返回失败，ticket 释放，无新实体 |
| P2-07 | 加载超时 | 模拟区块长时间未完成 | 达到 timeout 后取消，ticket 释放，档案不变 |
| P2-08 | 重复点击 | 同玩家同一 companion 连续请求 | 只有一个 pending，一个实体 |
| P2-09 | 多宠物并发 | 同一玩家或不同玩家同时请求 | 同时受 `maxPendingPerPlayer` 和 `maxPendingGlobal` 限制 |
| P2-10 | 玩家登出 | 请求 pending 时登出 | 请求取消，ticket 释放 |
| P2-11 | 死亡/收回 | 请求期间实体死亡或被收回 | 请求取消，不复活、不传送、不重建 |
| P2-12 | 服务端停止 | pending 时停止服务器 | 请求清理，临时 ticket 不变成永久加载 |
| P2-13 | 服务器重启 | 重启后再次远召 | 无持久 pending；缺失位置时安全失败（见 5.14） |
| P2-14 | 重复实体冲突 | 加载后发现多个同 companionId 实体 | 拒绝自动选择，要求 P1 修复 |
| P2-15 | 成功后位置刷新 | 远召成功后再次卸载并远召 | 使用新位置成功，不重复加载旧区块 |
| P2-16 | 资源回归 | 串行连续远召 20 次 | 无 ticket 泄漏、无实体累积、无日志异常 |
| P2-17 | 远召关闭 | `remoteSummonEnabled=false` 时召唤未加载实体 | 返回 `DISABLED`，不添加 ticket；已加载传送和合法重建不受影响 |
| P2-18 | 冷却抑制 | 终态后在 `remoteSummonCooldownTicks` 内重复请求 | 返回 `COOLDOWN`，不创建第二个 pending |
| P2-19 | 玩家换维度 | pending 期间玩家换维度 | 请求不取消，终态按玩家当前 Level 落点（见 5.13） |
| P2-20 | 主线程非阻塞 | 远召期间启用 tick watchdog/记录主线程等待 | 没有 `managedBlock` 等待；区块 future 从后台执行器收集 |
| P2-21 | 离场位置 | 实体跨区块后卸载区块 | `EntityLeaveLevelEvent` 写入最后位置，远端不再加载旧位置 |
| P2-22 | 重复检测 | 加载目标邻域时出现同 companionId 第二实体 | 通过 `FurkinDuplicateRegistry` 返回 `DUPLICATE_CONFLICT`，不触发全实体扫描 |
| P2-23 | 维度缺失 | 模拟 `summoned=true` 且 `entity_dimension=null` | 返回 `DIMENSION_MISSING`，档案不变，不添加 ticket |
| P2-24 | 成功终态刷新 | 异步远召成功后打开绒亲录 | 服务端调用现有刷新入口，列表状态与实际一致，不新增协议包 |
| P2-25 | owner 换维度 | 玩家跨维度但宠物留在原维度；另一路径让宠物实际随行传送 | 未移动的宠物档案保持原维度 / 位置；实际随行由入世 / 离场或本项目传送路径刷新，不能把玩家新位置误写成宠物位置 |

### 5.1 P2.1 位置字段与数据版本验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端双阶段夹具。
- 实现覆盖：`FurkinArchiveEntry.entityPos` 的 NBT 读写与 null/非法字段防御；`FurkinArchiveData` 数据版本 2 与 v0→v1→v2 分步迁移；契约、重建、已加载传送、canonical 入世和离场路径刷新最后位置。
- 第一阶段：构造带位置和不带位置的两条档案，保存并正常停服。
- 第二阶段：重启后等待 40 tick，确认 owner、实体 UUID、overworld 维度、位置、`summoned` / `alive` 均保持；缺失位置仍为 `null`，没有回退为 `BlockPos.ZERO`。日志输出 `FURKIN_FIXTURE_P2_01_RESTART_OK`。
- 干净回归：移除一次性夹具后执行 `clean build` 通过；`runServer` 达到 `Done (2.457s)`；`latest.log` 无夹具日志和 Furkin 专属 `ERROR` / `FATAL`，基线 Minecraft tag `ERROR` 仍存在；最终 jar 不含 `internal.debug` / fixture 类。
- 尚未覆盖（本阶段）：单独构造 v0/v1 旧档案文件验证版本迁移分支——已由 §5.16 补测；P2-03 之后的区块加载、异步远召、pending、ticket 与传送路径。

### 5.2 P2.2 已加载传送公共路径验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端夹具。
- 同维度：已加载实体经 `teleportToOwner(...)` 调用公共路径，返回 `TELEPORTED`，实体 UUID、档案 `summoned` / `alive` 和位置刷新正常。
- 跨维度：已解析实体经 `teleportLoadedEntity(...)` 执行 `changeDimension(...)`，返回 `TELEPORTED`，实体 UUID 保持，维度与档案位置刷新到主人当前 Level。
- 夹具时序说明：跨维度实体在同一 tick 新建后尚未进入运行时实体索引，因此夹具直接传递已解析实体测试公共方法；完整“未加载→加载→定位→传送”覆盖留在 P2.3/P2.6。
- 回归：移除夹具后 `clean build` 通过；`runServer` 达到 `Done (2.216s)`；`latest.log` 无夹具记录，最终 jar 不含 fixture / `internal.debug` 类。

### 5.3 P2.3 异步远召服务验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_03=1`。
- 已加载路径：已加载绒亲立即返回 `COMPLETED_TELEPORT`，无 pending/ticket；紧接着再次请求返回 `COOLDOWN`。
- 未加载路径：在 Nether 远距位置创建目标实体，落盘并卸载其区块；请求返回 `PENDING`，重复请求返回 `ALREADY_PENDING`，ticket 计数为 1。
- 异步完成：目标区块通过 `ChunkStatus.FULL` future 加载后，服务端回调按 canonical UUID 定位原实体并传送回 Overworld；日志为 `result=COMPLETED_TELEPORT reason=COMPLETED ticketReleased=true`，实体 UUID 不变，档案 `summoned` / `alive` 保持不变，位置刷新到主人身边。
- 取消路径：第二个未加载目标进入 pending 后显式取消，日志为 `result=CANCELLED reason=STATE_CHANGED ticketReleased=true`；pending/ticket 归零，档案状态不变。
- 夹具断言：`FURKIN_FIXTURE_P2_03_PREPARED`、`FURKIN_FIXTURE_P2_03_REMOTE_OK`、`FURKIN_FIXTURE_P2_03_CANCEL_OK`、`FURKIN_FIXTURE_P2_03_OK` 全部出现，无夹具失败栈。
- 干净回归：移除一次性夹具后 `clean build` 通过；`runServer` 启动至 `Done (2.211s)`；`run/logs/latest.log` 无夹具 / `internal.debug` 记录，无 Furkin 专属 `ERROR` / `FATAL`，基线 Minecraft tag `ERROR` 仍存在；最终 jar 不含 `internal.debug` / fixture 类。
- 本阶段尚未覆盖：timeout、chunk future 失败注入、重复实体冲突、玩家登出/死亡/收回/canonical 变化与 service 的联调，以及 GUI/命令/配置接入。上述场景保留到 P2.4/P2.6；其中重复实体冲突已由 5.11 补测，不得据此宣称 P2 全量完成。

### 5.4 P2.4 入口与反馈验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_04=1`。
- 夹具构造：捕获 `displayClientMessage` 的 `FakePlayer` 子类，以及捕获命令 `sendSuccess` / `sendFailure` 的 `CommandSource`；档案条目 `summoned=true`、维度 Nether、位置 `(1024, 64, 1024)`，对应实体不存在。
- 命令入口：即时未知 UUID 返回 `NOT_FOUND`，文案 `furkin.command.companion.not_found`；有效档案返回 `PENDING`，文案 `furkin.command.summon.remote_pending`；异步终态文案 `furkin.command.summon.entity_unresolved`。
- 绒亲录入口：即时未知 UUID 返回 `NOT_FOUND`，文案 `furkin.msg.summon_not_found`；有效档案返回 `PENDING`，文案 `furkin.msg.remote_summon_pending`；异步终态文案 `furkin.msg.remote_summon_unresolved`。
- 异步终态日志：`result=ENTITY_UNRESOLVED reason=STATE_CHANGED ticketReleased=true`；两次终态后 pending / ticket 归零。
- 夹具断言日志：`FURKIN_FIXTURE_P2_04_PREPARED`、`..._COMMAND_NOT_FOUND`、`..._COMMAND_PENDING`、`..._COMMAND_OK`、`..._PACKET_NOT_FOUND`、`..._PACKET_PENDING`、`..._PACKET_OK`、`..._OK` 全部出现，无夹具失败栈；夹具运行以 `halt(false)` 正常停服，Gradle `BUILD SUCCESSFUL`。
- 协议边界：未新增网络包，`PROTOCOL_VERSION` 未改动，仍为 `"2"`。
- 干净回归：移除一次性夹具后执行 `clean build` 通过；`runServer` 达到 `Done (2.364s)`；`latest.log` 无夹具记录和 `internal.debug` 类，无 Furkin 专属 `ERROR` / `FATAL`，基线 Minecraft tag `ERROR` 仍存在；最终 jar 不含 `internal.debug` / fixture 类。
- 日志说明：夹具运行与干净回归都出现运行世界历史实体的 `Legacy Furkin AI state ... original goals cannot be restored` WARN，与 P2.4 入口/反馈改动无关；两轮均无 Furkin 专属 `ERROR` / `FATAL`，基线 Minecraft tag `ERROR` 仍存在。
- 干净回归停服说明：本次干净回归的 `runServer` 在达到 `Done` 后经 Gradle wrapper 批处理终止（PTY `stop` 未传到内层 Java），未记录为正常停服；正常停服与 ticket / pending 清理已由夹具运行的 `halt(false)`（`Stopping server` → `Saving worlds`）覆盖。
- 尚未覆盖：异步成功文案 `furkin.msg.remote_summon_completed` 与 `refreshRecordList` 的实际客户端刷新、GUI 按钮加载态 / 重复点击、timeout / chunk future 失败注入、玩家登出 / 死亡 / 收回与 service 联调、实机 MSPT。配置接入已由 5.5 覆盖；其余保留到 P2.6。

### 5.5 P2.5 服务端配置验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_05=1`。
- 默认值：`remoteSummonEnabled=true`、`remoteSummonTicketRadius=1`、`remoteSummonTimeoutTicks=100`、`remoteSummonMaxPendingPerPlayer=1`、`remoteSummonMaxPendingGlobal=4`、`remoteSummonCooldownTicks=20`，与冻结契约一致；日志 `FURKIN_FIXTURE_P2_05_DEFAULTS_OK`。
- disabled 语义：`remoteSummonEnabled=false` 时，`summoned=false` 的合法重建返回 `COMPLETED_REBUILD`，随后已加载传送返回 `COMPLETED_TELEPORT`；证明总开关只作用于「已召唤但未加载」的远程加载路径；日志 `FURKIN_FIXTURE_P2_05_DISABLED_OK`。
- cooldown 配置：`remoteSummonCooldownTicks=0` 时同一 companion 连续两次 `DIMENSION_MISSING` 不被拦截；改回 20 后下一次重复请求返回 `COOLDOWN`；日志 `FURKIN_FIXTURE_P2_05_COOLDOWN_OK`。
- radius / pending：`remoteSummonTicketRadius=2` 产生 `remote summon request=... center=(66, 66) radius=2 pending=1`；pending 期间第二个 companion 请求返回 `TOO_MANY_PENDING`；终态 `ENTITY_UNRESOLVED ... ticketReleased=true`，pending / ticket 归零；日志 `FURKIN_FIXTURE_P2_05_RADIUS_PENDING`、`..._RADIUS_OK`、`..._OK`。
- 配置文件：生成的 `run/world/serverconfig/furkin-server.toml` 含 6 个键、范围注释与默认值；夹具结束时把运行中的临时值恢复默认。
- 干净回归：夹具删除后 `clean build` 通过；无夹具 `runServer` 达到 `Done (2.309s)`；`latest.log` 无夹具 / `internal.debug` 记录，无 Furkin 专属 `ERROR` / `FATAL`，基线 Minecraft tag `ERROR` 与运行世界历史实体的 `Legacy Furkin AI state ...` WARN 仍在；最终 jar 不含 `internal.debug` / fixture 类。
- 干净回归停服说明：本次干净回归 `runServer` 在达到 `Done` 后经 Gradle wrapper 批处理终止，未记录为正常停服；夹具运行以 `halt(false)` 正常停服，验证配置恢复与 ticket 释放。
- 截至 P2.5 尚未覆盖：`remoteSummonTimeoutTicks` 超时注入、`remoteSummonMaxPendingGlobal` 全服上限、实机 MSPT、客户端 `refreshRecordList` 刷新。后续性能与并发由 5.15 补测。

### 5.6 P2.6 服务端边界夹具（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_06=1`。目标区块使用已加载的主世界出生区块，避免夹具通过远坐标触发额外区块生成。
- timeout：夹具把请求的 `deadlineGameTime` 置为当前 tick 之前，再调用 service tick，以覆盖真实 deadline 分支而非只断言配置值。结果为 `result=TIMEOUT reason=TIMEOUT ticketReleased=true`；`ticketBefore=1 ticketAfter=0`，pending / ticket 均为 0。
- global pending：`remoteSummonMaxPendingGlobal=2`、每玩家上限 4；三次请求依次返回 `PENDING`、`PENDING`、`TOO_MANY_PENDING`。随后夹具用 `cancelAll(SERVER_STOPPING)` 清理两条 pending，均记录 `ticketReleased=true`，pending / ticket 归零。
- 日志：`FURKIN_FIXTURE_P2_06_PREPARED`、`FURKIN_FIXTURE_P2_06_TIMEOUT_OK`、`FURKIN_FIXTURE_P2_06_GLOBAL_LIMIT_OK`、`FURKIN_FIXTURE_P2_06_OK`；验收运行证据位于 `run/logs/debug-1.log.gz`。
- 干净回归：夹具源码删除后 `clean build` 通过；无夹具 `runServer` 达到 `Done (2.174s)`；`latest.log` 无夹具 / `internal.debug` 记录；仅有基线 Minecraft tag `ERROR`、OSHI WARN 与历史 `Legacy Furkin AI state` WARN；最终 jar 不含 fixture / `internal.debug`。
- 停服说明：本次干净回归在 `Done` 后通过 Gradle wrapper 终止，未记录为正常停服；夹具运行本身以 `halt(false)` 正常停服，并完成 ticket 释放。
- 尚未覆盖（生命周期夹具后）：实机 MSPT / tick spike、GUI 加载态 / 重复点击的实际输入回归、连续 20 次远召资源回归；其中 `CHUNK_LOAD_FAILED` 注入已由 5.12 补测，服务器重启已由 5.14 补测，pending 换维度已由 5.13 补测。真实玩家点击后的异步成功 action bar 与列表刷新端到端已由 5.9 补测；重复实体冲突已由 5.11 补测。

### 5.7 P2.6 客户端定向夹具（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发客户端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_06_CLIENT=1`。
- 夹具路径：通过真实 `FurkinClientPacketHandler.handleRecordList(...)` 打开 `FurkinRecordScreen`，检查远召语言键、召唤按钮状态、`openScreen=false` 刷新后的就地状态与 `onClose()` 返回路径。
- 通过运行（第二次）：日志依次出现 `FURKIN_FIXTURE_P2_06_CLIENT_OPENED`、`FURKIN_FIXTURE_P2_06_CLIENT_STATE summonPresent=true summonActive=true pendingFeedback=server-action-bar`、`FURKIN_FIXTURE_P2_06_CLIENT_REFRESH_OK`、`FURKIN_FIXTURE_P2_06_CLIENT_OK`；当前简体中文下无缺失远召翻译键，`run/logs/latest.log` 无 Furkin 专属 `ERROR` / `FATAL`。
- 首次运行崩溃说明：夹具在断言后主动调用 `Minecraft.close()`，主循环继续访问已关闭的 GLFW，导致 `Blaze3D.getTime` NPE；该崩溃属于临时夹具生命周期处理错误，不是产品路径。移除主动关闭逻辑后第二次运行通过。
- 收尾：客户端夹具进程已正常终止；一次性夹具源码已删除；无夹具 `clean build` 通过；最终 jar 不含 `RemoteSummonClientP206Fixture` / `internal.debug` / fixture 类。
- 验证边界：该夹具覆盖“真实封包处理入口能打开真实屏幕、远召控件可见/可用、刷新不替换屏幕、关闭返回”的客户端结构路径；不替代真实玩家点击后的服务端异步成功文案、完整端到端刷新和实机性能网格。

### 5.8 P2.6 生命周期边界夹具（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_06_LIFECYCLE=1`。夹具在 `ServerStartedEvent` 创建一次 FakePlayer，并通过真实事件处理方法 / 业务入口执行场景；为验证异步回调，只在夹具生命周期内把该玩家临时放入 `PlayerList` 的 UUID 索引，验证后移除。
- `PLAYER_LOGOUT`：`CommonEvents.onPlayerLoggedOut(...)` 后日志为 `result=CANCELLED reason=PLAYER_LOGOUT ticketReleased=true`，pending / ticket 归零，回调 0 次。
- `ENTITY_DEATH`：`CommonEvents.onLivingDeath(...)` 后日志为 `result=CANCELLED reason=ENTITY_DEATH ticketReleased=true`，pending / ticket 归零，回调 1 次且结果为 `CANCELLED`；档案变为 `alive=false`、`summoned=false`。
- `DISMISSED`：`FurkinCompanionManager.dismiss(...)` 后日志为 `result=CANCELLED reason=DISMISSED ticketReleased=true`，pending / ticket 归零，回调 1 次且结果为 `CANCELLED`；档案保持 `alive=true`、`summoned=false`。
- `UNBOUND`：`FurkinRecordActionHandler.forceUnbind(...)` 后日志为 `result=CANCELLED reason=UNBOUND ticketReleased=true`，pending / ticket 归零，回调 1 次且结果为 `CANCELLED`；档案条目删除。
- `CANONICAL_CHANGED`：异步加载期间改写档案 canonical UUID，终态日志为 `result=CANCELLED reason=CANONICAL_CHANGED ticketReleased=true`，pending / ticket 归零，回调 1 次且结果为 `CANCELLED`。
- `SERVER_STOPPING`：`CommonEvents.onServerStopped(...)` 后日志为 `result=CANCELLED reason=SERVER_STOPPING ticketReleased=true`，pending / ticket 归零，回调 0 次。
- 夹具日志：`FURKIN_FIXTURE_P2_06_LIFECYCLE_LOGOUT_OK`、`..._DEATH_OK`、`..._DISMISS_OK`、`..._UNBIND_OK`、`..._SERVER_STOP_OK`、`FURKIN_FIXTURE_P2_06_LIFECYCLE_OK` 全部出现；第一次失败运行仅是把异步目标放在远坐标导致 `TIMEOUT`，改为已加载出生区块后通过，不是产品路径失败。
- 干净回归：夹具源码删除后 `clean build` 通过；无夹具 `runServer` 达到 `Done (2.357s)`；`latest.log` 无夹具 / `internal.debug` 记录，仅有基线 Minecraft tag `ERROR`、OSHI/资源包 WARN 与历史 `Legacy Furkin AI state` WARN；最终 jar 不含 fixture / `internal.debug`。
- 停服说明：夹具运行本身以 `halt(false)` 正常停服并保存世界；无夹具回归在 `Done` 后通过 Gradle wrapper / Ctrl+C 终止，未记录为正常停服。

### 5.9 P2.6 真实异步成功端到端（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发客户端一次性集成夹具；环境变量 `FURKIN_FIXTURE_P2_06_ASYNC=1`、`FURKIN_FIXTURE_P2_06_ASYNC_LEVEL=<临时世界>`。使用 `新的世界` 的世界复制品，不修改用户原存档。
- 现场制造：在 Nether `(512, 70, 512)` 创建带 `FurkinData` 的 `ARMOR_STAND`，写入 `summoned=true`、canonical UUID、维度与位置；通过公开 `ServerLevel#save(null, true, false)` 触发 `PersistentEntitySectionManager#saveAll(...)`，实体与 `ForgeCaps/furkin:furkin_data` 写入 `DIM-1/entities/r.1.1.mca`。移除临时 ticket 后，运行时实体索引与目标区块均卸载。
- 首次失败证据：区块 `ChunkStatus.FULL` future 完成后，service 立即 `locate(...)` 返回 `ENTITY_UNRESOLVED`；NBT 解析显示 `entities/*.mca` 中实体、UUID、位置、`ForgeCaps` 均存在，证明是实体 section 异步读盘未完成，而不是夹具未保存或产品实体丢失。
- 修复：`RemoteSummonRequest` 增加 `WAIT_ENTITY_LOAD`。future 成功后逐块等待 `ServerLevel#areEntitiesLoaded(long) == true`，全部 LOADED 才定位；未就绪保留 pending，由服务端 tick 在 deadline 内重试。主线程不反射、不读 NBT、不阻塞。
- 成功路径：真实 `FurkinRecordItem.sendRecordList(...)` 打开真实 `FurkinRecordScreen`，真实“召唤”按钮调用 `RequestSummonPacket`；日志为 `remote summon request=1 ... pending=1` 与 `remote summon completed request=1 ... result=COMPLETED_TELEPORT reason=COMPLETED ticketReleased=true`。
- 客户端断言：`FURKIN_FIXTURE_P2_06_ASYNC_OK pendingSeen=true successText=远距召唤完成 refreshed=true sameScreen=true oldEntries=ArrayList@68f9a4f4 newEntries=ArrayList@35852b6`；即 action bar 成功文案出现、屏幕实例不变、`entries` 列表对象被替换。
- 干净回归：夹具源码与全部临时世界复制品已删除；`clean build` 通过；无夹具 `runServer` 达到 `Done`；无夹具 `runClient` 正常进入标题界面；最终 jar 不含 fixture / `internal.debug`。
- 说明：夹具运行期间出现一次 `Can't keep up!`，来源为一次性世界复制、同步区块生成/保存和夹具调试；不能据此判断产品常态 MSPT。连续 20 次远召、实际 MSPT / tick spike 仍未覆盖；`CHUNK_LOAD_FAILED` 注入已由 5.12 补测，服务器重启已由 5.14 补测，pending 换维度已由 5.13 补测，重复实体冲突已由 5.11 补测。

### 5.10 P2.6 GUI 加载态 / 重复点击实际输入（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，一次性客户端集成夹具；环境变量 `FURKIN_FIXTURE_P2_06_GUI=1`、`FURKIN_FIXTURE_P2_06_GUI_LEVEL=furkin_p2_06_gui_20260927_2`。复制 `新的世界` 到临时目录后由夹具自动加载，原存档未改动。
- 输入路径：服务端通过既有 `RecordListPacket` 打开真实 `FurkinRecordScreen`；夹具对该屏幕调用真实 `Screen#mouseClicked(...)`，坐标取自生产召唤按钮中心，不直接调用 `Button#onPress()`。
- 首次点击：`FURKIN_FIXTURE_P2_06_GUI_FIRST_CLICK consumed=true active=false label=召唤中……`；第二次同位置点击：`..._SECOND_CLICK consumed=false active=false label=召唤中……`。两次点击之间按钮保持禁用，未产生第二个 pending。
- 服务端观测：`remote summon request=1 ... pending=1`、`FURKIN_FIXTURE_P2_06_GUI_SERVER_PENDING pending=1`、`..._PENDING pending=1`；终态 `ENTITY_UNRESOLVED reason=STATE_CHANGED ticketReleased=true` 后，既有列表刷新包到达并恢复按钮，最终 `..._OK ... refreshed=true unlocked=true`。
- 实现口径：客户端只保留一个本地在途请求；服务端每个非 `PENDING` 结果都发一次 `RecordListPacket(openScreen=false)` 作为 UI 结束信号；`PENDING` 不刷新。客户端 620 tick 兜底解锁，服务端 `ALREADY_PENDING` 仍是权威防线。
- 协议边界：未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`；本次只改变既有 refresh 的发送时机和客户端展示状态。
- 收尾：夹具源码与两份临时世界已删除；`clean build` 通过；无夹具 `runServer` 达到 `Done (2.260s)`；`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`；最终 jar 不含 fixture 类。
- 边界：仍未覆盖实机 MSPT / tick spike、连续 20 次远召资源回归和真实成功实体传送后的装备 / 行囊保留实测；重复实体冲突已由 5.11 补测。

### 5.11 P2.6 重复实体冲突与合法重建（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；使用最终源码版本重跑。夹具运行在临时 `furkin_p2_06_dup_20260927` 世界，原存档未改动。
- 重复实体冲突：canonical 在远处未加载，同 `companionId` 的重复实体已在出生区块加载；真实请求结果为 `DUPLICATE_CONFLICT`。断言覆盖不传送 canonical、不删除重复体、档案 UUID / 维度 / 位置 / `summoned` / `alive` 不变、重复登记仍可见、pending / ticket 归零。
- 孤儿重建：档案 `summoned=false` 且 canonical 为空，同身份孤儿实体已加载；请求返回 `DUPLICATE_CONFLICT`，`candidateCount=1`，孤儿仍加载，档案保持未召唤且定位字段为空。
- 合法重建回归：档案 `summoned=false` 且没有已加载同身份实体；请求仍返回 `COMPLETED_REBUILD`，`candidateCount=1`，证明守卫没有误伤正常 rebuild。
- 证据日志：`FURKIN_FIXTURE_P2_06_DUP_OK result=DUPLICATE_CONFLICT ... pending=0 tickets=0`、`FURKIN_FIXTURE_P2_06_ORPHAN_OK result=DUPLICATE_CONFLICT candidateCount=1 orphanStillLoaded=true archiveStillUnsummoned=true`、`FURKIN_FIXTURE_P2_06_LEGAL_OK result=COMPLETED_REBUILD candidateCount=1`。
- 代码边界：`FurkinDuplicateRegistry` 只维护服务端内存索引，不持久化、不自动删实体；`summoned=false` 的合法 rebuild 在 `CommonEvents` 中的入世诊断改为 `DEBUG`。未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。
- 加载前置检查（D-21）：canonical 未加载、重复体已加载时，真实请求在添加远召 ticket 前返回 `DUPLICATE_CONFLICT`；夹具日志 `FURKIN_FIXTURE_P2_06_DUP_PRECHECK_OK result=DUPLICATE_CONFLICT registered=true canonicalLoaded=false duplicateLoaded=true pending=0 tickets=0 archiveUnchanged=true`。异步实体 section 阶段仍保留同检查，覆盖加载期间才出现的重复体。
- 收尾：夹具源码、临时世界和运行配置已恢复；`clean build` 通过，最终 jar 为 `build/libs/furkin-1.19.2-0.0.2.0.jar`（SHA-256 `A4BA86F1C815E80BCFF2E9065BB7ECBF1FEE219DC903FC67C58090A3CD6F38D2`），jar 内无 `internal.debug` / fixture 类。无夹具 `runServer` 达到 `Done (2.251s)`；`latest.log` 无 fixture / `internal.debug`，无 Furkin 专属 `ERROR` / `FATAL`，仅保留基线 Minecraft `TagLoader` `ERROR` 与历史 `Legacy Furkin AI state` WARN。PTY 未把 `stop` 传入内层 Java，随后终止批处理，未发现残留 Java 进程；正常停服清理已由夹具的 `halt(false)` 覆盖。

### 5.12 P2.6 CHUNK_LOAD_FAILED 故障注入（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_06_CHUNK_FAIL=1`，临时世界 `furkin_p2_06_chunk_fail_20260927`，不修改原存档。
- 夹具先通过真实 `RemoteSummonService.request(...)` 创建 `PENDING` 请求，确认 `pendingBefore=1`、`ticketBefore=1`；随后向真实 `onLoadFinished(...)` 注入 `Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED)`。
- 断言结果：`result=CHUNK_LOAD_FAILED`、`ticketReleased=true`、`pendingAfter=0`、`ticketAfter=0`、`archiveUnchanged=true`、`entityAbsent=true`。日志为 `FURKIN_FIXTURE_P2_06_CHUNK_FAIL_OK result=CHUNK_LOAD_FAILED pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 archiveUnchanged=true entityAbsent=true`。
- 边界：本项验证 1.19.2 `ChunkHolder.ChunkLoadingFailure` 的正常右值失败形态；异常 future 与正常右值失败最终都进入同一 `CHUNK_LOAD_FAILED` 清理路径。未修改生产代码，未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；`clean build` 通过，jar 内无 `internal.debug` / fixture 类；无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`，仅保留基线 Minecraft `TagLoader` `ERROR` 与历史 `Legacy Furkin AI state` WARN。

### 5.13 P2.6 pending 换维度（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_P2_06_DIMENSION=1`，临时世界 `furkin_p2_06_dimension_20260927`，不修改原存档。
- 夹具通过真实 `RemoteSummonService.request(...)` 进入 `PENDING`，确认 `pendingBefore=1`、`ticketBefore=1`；随后把 FakePlayer 从 Overworld 传到 Nether，并在 Overworld 目标位置构造 canonical 实体。
- 反射调用真实 `onLoadFinished(request, List.of(), null)`；夹具在终态后等待异步实体入世并断言结果、ticket、pending、档案维度、canonical UUID 与主人距离。
- 成功日志：`FURKIN_FIXTURE_P2_06_DIMENSION_OK result=COMPLETED_TELEPORT pendingBefore=1 ticketBefore=1 pendingAfter=0 ticketAfter=0 playerDimension=minecraft:the_nether targetDimension=minecraft:the_nether archiveInNether=true canonicalPreserved=true nearPlayer=true`。
- 结论：pending 期间玩家换维度不会误取消请求；终态按玩家当前 Nether Level 传送，实体与档案均为 Nether，canonical UUID 保持，pending / ticket 归零，目标实体位于主人身边。
- 边界：首次夹具在终态后立即查询 Nether 实体，因目标区块尚未完成实体入世查询而失败；夹具预加载目的地 chunk 并等待异步入世后通过，产品代码未修改。该项未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；`clean build` 通过，jar 无 `internal.debug` / fixture 类；无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`，仅保留基线 Minecraft `TagLoader` `ERROR` 与历史 `Legacy Furkin AI state` WARN。

### 5.14 P2.6 服务器重启（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端两阶段夹具；环境变量 `FURKIN_FIXTURE_P2_06_RESTART=1`，临时世界 `furkin_p2_06_restart_20260927`，不修改其他世界。
- 第一阶段：创建带位置的有效档案 A 与缺位置档案 B，通过真实 `RemoteSummonService.request(...)` 让 A 进入 pending，确认 `pending=1`、`tickets=1`；保存世界后正常停服，日志 `result=CANCELLED reason=SERVER_STOPPING ticketReleased=true`。
- 第二阶段：同一临时世界重启后，新 service 的 `stalePending=0`、`staleTickets=0`；A、B 的持久字段保持不变。
- 重启后 A 再次请求返回 `PENDING`，显式取消后 `cancelPending=0`、`cancelTickets=0`；B 请求返回 `NO_POSITION`，`missingPending=0`、`missingTickets=0`，档案不变。
- 成功日志：`FURKIN_FIXTURE_P2_06_RESTART_OK stalePending=0 staleTickets=0 reRequest=PENDING reRequestPending=1 reRequestTickets=1 cancelPending=0 cancelTickets=0 missingPosition=NO_POSITION missingPending=0 missingTickets=0 archiveUnchanged=true`。
- 边界：内存 pending / ticket 不持久化；重启后必须重新发请求。未修改生产代码，未新增网络包，`PROTOCOL_VERSION` 保持 `"2"`。
- 收尾：夹具源码、临时世界和 `server.properties` 已恢复；`clean build` 通过，jar 无 `internal.debug` / fixture 类；无夹具 `runServer` 达到 `Done (2.251s)`，`latest.log` 无 fixture / `internal.debug` 与 Furkin 专属 `ERROR` / `FATAL`，仅保留基线 Minecraft `TagLoader` `ERROR` 与历史 `Legacy Furkin AI state` WARN。

### 5.15 P2.6 性能收口与 4 并发（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；seed 阶段写入 5 个真实实体，measure 阶段执行冷区单请求、冷区 4 并发和同进程热区 4 并发。
- 连续 20 次资源回归：临时使用 timeout=900 tick，首轮冷区 `maxElapsedTicks=434`，后续回落；最终 pending / tickets=0，无额外实体与 ticket 泄漏。默认 100 tick 不据该夹具宣称为通过。
- 串行 MSPT：measure `avg=3.69ms,max=54.35ms,over50=16,over100/200/500=0`；loaded `2209 -> 3170 -> 2209`；无 `Can't keep up!`、ERROR、FATAL。
- 4 并发关键结果：冷区单请求 max=51.84ms；冷区 4 并发 26 ticks / 1675.2ms，max=378.22ms；热区 4 并发 22 ticks / 1529.0ms，max=431.41ms；三轮均为 0 个 >500ms，9/9 `COMPLETED_TELEPORT`。
- 玩家侧结论：单次冷区近于轻微一帧停顿；4 并发会出现一次约 0.38–0.43 秒的全服短暂卡顿，但不是持续掉 TPS。热区没有消除尖峰，说明永久强加载不是有效替代。
- 体感口径由服务端主线程 tick 推导，不是客户端 FPS 抓帧。
- 配置判定：`remoteSummonTimeoutTicks=600`、`remoteSummonTicketRadius=1`、`remoteSummonMaxPendingGlobal=4`；原因是前两项有实测支撑，四项并发语义已被 4 并发夹具验证，不以未实测的更低 pending 上限替换。
- 收尾：夹具、临时世界、`server.properties` 已恢复；`clean build` 通过且 jar 不含 fixture；无夹具 `runServer` 达到 `Done (20.190s)`，生成的配置已确认 `remoteSummonTimeoutTicks=600`，日志无 Furkin 专属 ERROR / FATAL 或 `Can't keep up!`。
- 详细命令、原始指标和 20 次回归口径见 `p2-execution-contract.md` 的 11.14。

### 5.16 P2.1 旧档 v0 / v1 迁移夹具验收记录（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具；环境变量 `FURKIN_FIXTURE_D_MIGRATION=verify-v0|verify-v1`，临时世界 `furkin_d_migration_v0_20260927` / `furkin_d_migration_v1_20260927`，不修改其他世界。
- 夹具构造：`ServerAboutToStartEvent` 阶段直接写旧格式 NBT 档案（主世界 `data/furkin_archive.dat`，兼容分支再写 `DIM-1/data/furkin_archive.dat`），`ServerStartedEvent` 阶段调用真实 `FurkinArchiveData.get(server)` 触发迁移并断言字段。
- v0：无 `data_version` 主世界条目 + Nether legacy / 同 ID 冲突条目。迁移日志 `Furkin archive data version migrated: 0 -> 2`、`imported=1, conflicts=1`；`FURKIN_FIXTURE_D_V0_STATE_OK ... conflictKeptOverworld=true` 证明 owner / entity UUID / 维度 / `summoned` / `alive` / level 保持，legacy 被导入，同 ID 冲突保留主世界条目且不静默覆盖。
- v1：`data_version=1`，一条 `summoned=false`（战斗模式 `AGGRESSIVE`）与一条 `summoned=true`（`entity_uuid` + overworld 维度）条目，并删除 Nether 旧档案。迁移日志 `Furkin archive data version migrated: 1 -> 2`；`FURKIN_FIXTURE_D_V1_STATE_OK` 逐项字段（含非默认战斗模式）保持。
- 两条分支均断言迁移后 `entity_pos` 仍为 `null`：迁移不补位置、不扫描实体、不改 `summoned` / `alive` / `entity_uuid`。`FURKIN_FIXTURE_D_VERSION_OK dataVersion=2` 证明落盘 `data_version` 为 2。
- 成功日志：`FURKIN_FIXTURE_D_V0_MIGRATION_OK`、`FURKIN_FIXTURE_D_V1_MIGRATION_OK`；原始日志见 `p2-execution-contract.md` §11.15。
- 边界：v0 首轮失败源于夹具断言主键与顶层 NBT wrapper 写错，属夹具缺陷；修正后重跑通过，产品代码未改。旧 Nether 档案文件保留作回滚副本，不主动删除。
- 收尾：夹具源码、临时世界与 `server.properties` 已恢复；最终统一四项 Gradle 门槛与 jar 检查见 §8。
### 5.17 模块 E：静态审计与统一四项 Gradle 门槛（2026-09-27）

- 危险路径静态检索：`rg -n "\bFORCED\b|setChunkForced|LivingTickEvent|managedBlock|addRegionTicket|removeRegionTicket|getChunkFuture" src/main/java` 只命中 `RemoteSummonService` 的注释与 `chunkSource.getChunkFuture(...)`（第 335 行）；未发现 `FORCED`、`setChunkForced`、`LivingTickEvent` 或主线程 `managedBlock`。
- ticket 复核：唯一 ticket 为临时 `TicketType.create("furkin:remote_summon", ...)`，配套 `distanceManager.addTicket` / `removeTicket` 在请求终态与超时/取消路径成对释放；无永久强加载。
- `clean build`：通过（`8 actionable tasks: 8 executed`），最终 jar `build/libs/furkin-1.19.2-0.0.2.0.jar`（383829 bytes，SHA-256 `6F9B42BAE9C684DF6A890D22E7C9DC3BCD01398F3091EC919119E4B5DA157CCC`），224 个 entry 中不含 `internal/debug` 或 fixture 类。
- 无夹具 `runServer`：达到 `Done (2.282s)`；`run/logs` 服务端会话无 Furkin 专属 `ERROR` / `FATAL`，无 `Can't keep up!`；仅 23 条基线 `TagLoader` tag 合并 `ERROR`。
- 无夹具 `runClient`：开发客户端启动到主菜单（纹理图集与声音引擎初始化完成），无崩溃、无 Furkin 专属 `ERROR` / `FATAL`；日志中的 Netty 反射告警与 `libpng iCCP` 为开发环境基线噪声。
- 终止口径：服务端和客户端在达到 `Done` / 主菜单后由批处理终止，Gradle `runServer` / `runClient` 因此报告 `non-zero exit value -1`；这不是产品失败，两个任务在终止前均已完成既定启动验证，未记录为正常停服 / 正常退客户端。
- 原始日志：`D:\frukin_dev\_research\p1_e_clean_build_20260927.log`、`p1_e_clean_server_20260927.log`、`p1_e_clean_client_20260927.log`。
## 6. 性能记录

P2 需要记录但不设置虚假的统一阈值，至少采集：

- 单次 chunk future 完成时间。
- 每次请求实际添加的 ticket 数量和半径。
- pending 请求停留 tick 数。
- 请求成功、超时、失败数量。
- 请求期间服务器是否出现明显 tick spike。
- 连续请求后加载区块数量是否回落。
- 服务器停止后是否还有 `furkin:remote_summon` ticket 日志。

建议临时在调试日志中输出：

```text
remote summon request=<id> companion=<uuid> center=<chunk> radius=<n>
remote summon loaded request=<id> elapsed=<ticks>
remote summon completed request=<id> result=<result> elapsed=<ticks>
remote summon cleanup request=<id> reason=<reason> ticketReleased=<true|false>
```

调试日志不得包含完整物品 NBT、玩家聊天内容或大规模实体 NBT。

P2.6 实测结论（2026-09-27，详见 5.15）：

- 冷区单请求：`maxMs=51.84`，无 >100ms；主要玩家感受是接近一帧的轻微停顿。
- 冷区 4 并发：`maxMs=378.22`，`over200=1`，窗口等效 TPS 15.5；表现为一次约 0.4 秒的全服短暂卡顿。
- 热区 4 并发：`maxMs=431.41`，`over200=1`，窗口等效 TPS 14.4；热缓存不能消除尖峰。
- `loaded` 是 chunk-map 工作集，不是可玩区块或 ticking 区块；单请求 radius=1 的真实峰值仍需结合 5.15 的分解阅读。
- 默认 timeout 由历史 100 tick 调整为 600 tick；ticket 半径与全服 pending 上限保持 1 / 4。


## 7. 客户端回归

P2 接入界面后执行：

```powershell
.\gradlew.bat runClient --console=plain
```

检查：

- 召唤按钮加载态/重复点击行为正确。
- 远召 pending 时界面不误显示“已召唤”或“未召唤”新状态。
- 成功后列表刷新，状态和位置正确。
- 失败/超时后列表仍显示原来的 `summoned=true`。
- 中英文提示都来自语言键。
- 不出现 GUI 关闭、父屏返回、焦点或按钮状态回归。

## 8. 完成门槛

- [x] P0、P1、P2 的矩阵全部有记录（§3 / §4 / §5，模块 A-E）。
- [x] P0/P1 未通过前不宣称 P2 完成（P0/P1 定向阻断项已清空，P2 完成定义映射已闭环）。
- [x] `compileJava`、`build`、`runServer`、`runClient` 均执行并记录（§5.17 统一门槛）。
- [x] `run/logs/latest.log` 无新增错误（§5.17；服务端仅基线 `TagLoader`，客户端无 Furkin 专属 ERROR / FATAL）。
- [x] 无永久 `FORCED` ticket 默认行为（§5.17 静态审计，仅临时 `furkin:remote_summon` ticket）。
- [x] 未加载实体不会进入重建路径。
- [x] 失败不会清档案定位，不会造成物品丢失。
- [x] 重复实体修复前先搬运/掉落物品，再删除重复实体。
- [x] 文档、配置、README、CHANGELOG 和实际行为同步（CHANGELOG 中英均含远召条目；lang 键 197/197、`remote_summon` 13/13；README 按乌狸决策不新增服务器配置表，保持不产生误导）。
