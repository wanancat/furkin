# 远距召唤 P0-P2 收口审计

- 日期：2026-09-27
- 审计范围：`remote-summon` 功能包的 P0、P1、P2 验收项与发布门槛
- 排除项：`owner-dimension-follow` 独立案
- 审计原则：验收项只有在有源码事实、夹具日志或明确历史记录支撑时才视为通过；文档计划、实现描述和推断不能替代实际测试证据

## 1. 结论摘要

除独立案 `owner-dimension-follow` 外，`remote-summon` 功能包的 P0-P2 收口已完成。

- P0：安全失败主路径已实现；模块 C 已补齐“卸载 -> 失败召唤 -> 重新加载 -> 装备 / 行囊保持”的直接闭环，P0 定向阻断项已清空。
- P1：常规修复、canonical 切换、物品搬运、重启后唯一 canonical、失败注入、命令 / 反向修复以及 P1-01 单只实体卸载 / 回载均有直接证据，P1 定向阻断项已清空。
- P2：功能、边界、生命周期、重复冲突、异步成功、客户端结构、GUI 重复点击、性能与并发已有大量直接证据；模块 D 补齐 v0/v1 旧档迁移，模块 E 完成静态审计、统一四项 Gradle 门槛与最终日志检查，P2 完成定义已全部闭环。
- 发布门槛：通过（除独立案 `owner-dimension-follow`）。


## 1.1 模块 A 状态

- 2026-09-27：P1-06 / P1-07 / P1-12 / P1-14 已由一次性服务端夹具补测并通过。
- 证据：`p1-duplicate-recovery.md` §14、`p1-execution-contract.md` §10、`verification-matrix.md` §4.2。
- 原始日志：`D:\frukin_dev\_research\p1_failure_20260927.log`；干净回归：`D:\frukin_dev\_research\p1_failure_clean_20260927.log`。
- 夹具源码、临时世界与 `server.properties` 已清理；`clean build` 通过，jar 无 fixture / `internal.debug`。

## 1.2 模块 B 状态

- 2026-09-27：P1-03 / P1-04 / P1-08 / P1-10 已由一次性服务端夹具补测并通过。
- 证据：`p1-duplicate-recovery.md` §15、`p1-execution-contract.md` §11、`verification-matrix.md` §4.3。
- 原始日志：`D:\frukin_dev\_research\p1_command_20260927.log`；干净回归日志：`D:\frukin_dev\_research\p1_command_clean_20260927.log`。
- 夹具源码、临时世界与 `server.properties` 已清理；`clean build` 通过，jar 无 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (24.177s)`，该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

## 1.3 模块 C 状态

- 2026-09-27：两阶段服务端夹具补完 P0 卸载 / 回载闭环、P0-11 同 UUID 已加载守卫、正常 `dismiss -> summon`，并直接核销 P1-01。
- P0-01 / P0-05 / P0-06：未加载时返回 `ENTITY_UNRESOLVED`，档案 UUID / 维度 / 位置 / `summoned` / `alive` 不变且无第二只实体。
- P0-02：模块 C 只证明单次失败不新增实体；真实 GUI 第二次点击不产生第二请求 / 第二实体沿用 P2.6 §5.7 的既有证据。
- P0-03 / P0-04 / P1-01：原区块回载后命中同一实体 UUID，钻石头盔与 7 个骨头、维度和位置保持。
- P0-07：已加载传送复用同一实体，canonical UUID 不变，装备 / 行囊不丢且靠近主人。
- P0-09：`dismiss -> summon` 从档案快照恢复等级、经验、技能点、战斗模式、装备和名字；未把实体 UUID 变化加入本功能包契约。
- P0-11：`summoned=false` 且记录的 `entity_uuid` 实体已加载时，真实远召返回 `ENTITY_UNRESOLVED`，档案 UUID 不变，同身份已加载候选为 1。
- 证据：`p0-safe-failure.md` §8、`p1-duplicate-recovery.md` §16、`verification-matrix.md` §4.4。
- 原始日志：`D:\frukin_dev\_research\p1p0p2_closure_prepare_20260927.log`、`D:\frukin_dev\_research\p1p0p2_closure_20260927.log`；干净回归：`D:\frukin_dev\_research\p1p0p2_closure_clean_20260927.log`。
- 收尾：夹具源码、临时世界与 `server.properties` 已清理；`clean build` 通过，jar 无 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (20.112s)`，日志无 `ERROR` / `FATAL`。该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

## 1.4 模块 D 状态

- 2026-09-27：v0 / v1 旧档案迁移夹具补测并通过，直接核销 P2 完成定义中“位置字段旧档兼容，数据版本迁移不修改实体状态”。
- v0：无 `data_version` 主世界条目 + Nether legacy / 同 ID 冲突条目；迁移 `0 -> 2`，`imported=1, conflicts=1`，owner / entity UUID / 维度 / `summoned` / `alive` / level 保持，冲突保留主世界条目，落盘 `data_version=2`。
- v1：`data_version=1`，一条 `summoned=false`（`AGGRESSIVE`）+ 一条 `summoned=true`（`entity_uuid` + overworld 维度）条目；迁移 `1 -> 2`，逐项字段（含非默认战斗模式）保持。
- 两条分支均断言迁移后 `entity_pos` 仍为 `null`，迁移不补位置、不扫描实体、不改实体状态。
- 证据：`p2-execution-contract.md` §11.15、`verification-matrix.md` §5.16、`decision-log.md` D-26。
- 原始日志：`D:\frukin_dev\_research\p1_migration_v0_20260927.log`、`D:\frukin_dev\_research\p1_migration_v1_20260927.log`（DEBUG 明细见同名 `.debug.log`）。
- 口径：v0 首轮失败为夹具断言主键与顶层 NBT wrapper 写错，属夹具缺陷；修正后重跑通过，产品代码未改。
- 收尾：夹具源码、临时世界与 `server.properties` 已清理；模块 E 的 `clean build` / 四 Gradle 门槛与最终日志检查见 §1.5。

## 1.5 模块 E 状态

- 2026-09-27：静态审计与统一四项 Gradle 门槛完成。
- 静态检索：`rg -n "\bFORCED\b|setChunkForced|LivingTickEvent|managedBlock|addRegionTicket|removeRegionTicket|getChunkFuture" src/main/java` 仅命中 `RemoteSummonService` 的 `chunkSource.getChunkFuture(...)`（第 335 行）与注释；无 `FORCED`、`setChunkForced`、`LivingTickEvent`、主线程 `managedBlock`。
- ticket 复核：唯一 ticket 为临时 `TicketType.create("furkin:remote_summon", ...)`，`addTicket` / `removeTicket` 在终态、超时、取消路径成对释放，无永久强加载。
- `clean build` 通过；最终 jar `furkin-1.19.2-0.0.2.0.jar`（383829 bytes，SHA-256 `6F9B42BAE9C684DF6A890D22E7C9DC3BCD01398F3091EC919119E4B5DA157CCC`）不含 `internal/debug` / fixture 类。
- 无夹具 `runServer` 达到 `Done (2.282s)`，服务端会话无 Furkin 专属 `ERROR` / `FATAL`、无 `Can't keep up!`（仅 23 条基线 `TagLoader`）；无夹具 `runClient` 启动到主菜单，无崩溃。
- 终止口径：服务端 / 客户端在达到 `Done` / 主菜单后由批处理终止，Gradle `runServer` / `runClient` 因此报 `non-zero exit value -1`，非产品失败。
- 证据：`verification-matrix.md` §5.17；日志 `D:\frukin_dev\_research\p1_e_clean_build_20260927.log`、`p1_e_clean_server_20260927.log`、`p1_e_clean_client_20260927.log`。

## 2. P0 证据映射

| 验收项 | 现有证据 | 判定 | 缺口 / 下一步 |
|---|---|---|---|
| 未加载实体不会触发 `summoned=false` | 模块 C：`FURKIN_FIXTURE_C_REMOTE_FAIL_OK ... archiveUnchanged=true`，未加载召唤后 `summoned` / UUID / 维度 / 位置均不变；P2.6 故障注入继续覆盖加载失败路径 | 已验证 | 无 |
| 未加载实体不会触发 `clearEntityLocation()` | 模块 C 对未加载失败后的 UUID / 维度 / 位置做完整快照断言；无第二只实体 | 已验证 | 无 |
| 第二次点击不会产生新实体 | `p2-execution-contract.md` §11.9 真实 GUI 第一次/第二次点击夹具；§11.10 重复实体与孤儿重建冲突夹具；P2 重复请求返回 `ALREADY_PENDING` / `DUPLICATE_CONFLICT` | 已覆盖 | 无 |
| 真实实体、装备、行囊在重回区块后保持 | 模块 C：远区块实体落盘、卸载、失败远召、加载原区块后，原 UUID / 维度 / 位置、钻石头盔与 7 个骨头均保持；随后已加载传送也未丢失 | 已验证 | 无 |
| 正常 `dismiss` -> `summon` 仍能按档案快照恢复 | 模块 C：真实 `dismiss` 后 `summon` 恢复等级 9 / 经验 11 / 技能点 2 / `FOLLOW` / 铁头盔 / 自定义名字 | 已验证 | 无；实体 UUID 变化不属于当前契约 |
| `summoned=false` 且同 UUID 实体已加载时不重建 | 模块 C 精确覆盖 `summoned=false + entity_uuid` 非空 + 该 UUID 实体已加载：返回 `ENTITY_UNRESOLVED`，档案 UUID 不变且候选数为 1；P2.6 孤儿夹具继续覆盖 `entity_uuid=null` 分支 | 已验证 | 无 |
| `RequestSummonPacket` 线格式未改变 | 包仍只有单个 `UUID companionId`；`encode` / `decode` 仍读写一个 UUID；`PROTOCOL_VERSION` 仍为 `"2"`；P2.4 / P2.6 记录均确认未新增协议字段 | 已覆盖 | 无 |
| `compileJava`、`build`、服务端运行、日志检查通过 | P2.6 §11.14 与 `verification-matrix.md` §5.15：最终 `clean build` 通过；无夹具 `runServer` 到 `Done (20.190s)`；日志无 Furkin 专属 ERROR / FATAL | 已覆盖 | 最终收口时补记录一次统一 Gradle 四任务证据或明确引用本次记录 |

## 3. P1 证据映射

| 编号 | 验收项 | 现有证据 | 判定 | 缺口 / 下一步 |
|---|---|---|---|---|
| P1-01 | canonical 正常入世不抢绑、定位不漂移 | 模块 C：单只 canonical 在未加载区块落盘后回载，`FURKIN_FIXTURE_C_P1_01_RELOAD_OK` 证明档案 UUID / 维度 / 位置保持同一实体；装备与 7 个骨头同时保持 | 已覆盖 | 无 |
| P1-02 | 重复实体入世不抢绑并记录诊断 | `verification-matrix.md` §4.1 明确观察到重复入世诊断日志 | 已覆盖 | 无 |
| P1-03 | `repair list` 只读、可复现、不加载区块 | 模块 B：真实 Brigadier dispatcher 夹具；权限 2 返回 3 条候选消息，诊断前后档案 / 实体快照一致，权限 1、缺少 keeper UUID 与非法 UUID 均被拒绝 | 已覆盖 | 无 |
| P1-04 | 选择旧实体时新实体被清理，物品正确保留或掉落 | 模块 B：keeper = 当前 canonical 的反向路径通过；重复体铁头盔与 7 个骨头先转移，重复体删除，canonical UUID 不变且最终只剩 1 个已加载候选 | 已覆盖 | 无 |
| P1-05 | 选择新实体时不复制物品，核心数据不回退 | `p1-duplicate-recovery.md` §12 与 `verification-matrix.md` §4.1：`FURKIN_FIXTURE_REPAIR_OK` | 已覆盖 | 无 |
| P1-06 | 空物品重复体安全移除 | 模块 A：`FURKIN_FIXTURE_P1_FAILURE_EMPTY_OK`，真实 `choose` 返回 `OK`，旧 canonical 移除，档案指向 keeper | 已覆盖 | 无 |
| P1-07 | 物品搬运异常时不改 canonical、不删实体、已转移槽不重复生成 | 模块 A：`FURKIN_FIXTURE_P1_FAILURE_EQUIPMENT_OK`，注入 HEAD 装备写入异常后返回 `CLEANUP_FAILED`，两个实体保留、档案不变、铁头盔总数 1 | 已覆盖 | 无 |
| P1-08 | 连续执行两次相同修复不会重复搬运或误删 | 模块 B：同一 keeper 连续两次 `choose` 均为 `OK`；第二次 `discards=none`、状态快照稳定、最终只剩 1 个已加载候选 | 已覆盖 | 无 |
| P1-09 | 修复后重启仍指向正确 UUID | `FURKIN_FIXTURE_P1_09_RESTART_OK`；双阶段夹具覆盖 owner、UUID、维度、核心数据与唯一候选 | 已覆盖 | 无 |
| P1-10 | canonical 未加载时返回 `CANONICAL_NOT_LOADED` | 模块 B：直接调用 `FurkinDuplicateRepair.choose(...)`，canonical 未加载且重复体已加载时返回 `CANONICAL_NOT_LOADED`；重复体保留，档案 UUID 不变 | 已覆盖 | 无 |
| P1-11 | 核心数据与物品全部成功后才改 canonical、再删实体 | `FURKIN_FIXTURE_REPAIR_OK` 覆盖 happy path；模块 A 的 P1-07 / P1-12 / P1-14 覆盖失败分支，失败时 canonical 与两实体均保持 | 已覆盖 | 无 |
| P1-12 | 部分搬运中途失败后重试收敛 | 模块 A：`FURKIN_FIXTURE_P1_FAILURE_PARTIAL_OK`，第一次 `CLEANUP_FAILED`，第二次 `OK`，`totalBefore=2 totalAfter=2`，最终只剩 keeper | 已覆盖 | 无 |
| P1-13 | 保留新实体时 canonical 核心数据不回退 | `FURKIN_FIXTURE_REPAIR_OK` 断言等级 / 经验 / 技能点 / 战斗模式 / 冷却 | 已覆盖 | 无 |
| P1-14 | 核心数据复制或技能重建异常时安全失败、可重试 | 模块 A：`FURKIN_FIXTURE_P1_FAILURE_CORE_OK`，第一次 `CLEANUP_FAILED` 且 canonical / keeper 均保留，第二次 `OK`，核心数据保留 | 已覆盖 | 无 |

## 4. P2 完成定义映射

| 验收项 | 现有证据 | 判定 | 缺口 |
|---|---|---|---|
| 已加载同维度和跨维度传送无回归 | `verification-matrix.md` §5.2、§5.3、§5.9、§5.12、§5.13、§5.15 | 已验证 | 无 |
| 未加载同维度和跨维度均可临时加载、按 UUID 定位并传送 | §5.3、§5.9、§5.12、§5.13、§5.15；`p2-execution-contract.md` §11.8、§11.12、§11.14 | 已验证 | 无 |
| 无位置、加载失败、超时均安全失败，不改档案 | §5.5、§5.6、§5.11、§5.14；`p2-execution-contract.md` §11.11、§11.13 | 已覆盖 | 无 |
| 重复点击不会创建第二个 ticket 或实体，全服 pending 上限生效 | §5.6、§5.8、§5.9、§5.15；`p2-execution-contract.md` §11.5、§11.9、§11.14 | 已覆盖 | 无 |
| pending 期间换维度不误取消，终态使用当前 Level | `p2-execution-contract.md` §11.12；`verification-matrix.md` §5.13 | 已覆盖 | 无 |
| 登出、死亡、收回、解绑、服务停止均释放 ticket | `p2-execution-contract.md` §11.7；`verification-matrix.md` §5.8 的六个生命周期场景 | 已验证 | 无 |
| 无永久 `FORCED` ticket，默认路径没有全实体逐 tick 记录 | 模块 E：`rg -n "\bFORCED\b|setChunkForced|LivingTickEvent|managedBlock|addRegionTicket|removeRegionTicket|getChunkFuture" src/main/java` 仅命中 `getChunkFuture` 与注释；唯一 ticket 为临时 `furkin:remote_summon`，终态释放 | 已验证 | 无 |
| 位置字段旧档兼容，数据版本迁移不修改实体状态 | 模块 D：v0 / v1 旧档案文件迁移夹具；`FURKIN_FIXTURE_D_V0_MIGRATION_OK` / `FURKIN_FIXTURE_D_V1_MIGRATION_OK` 证明 v0 -> v1 -> v2 迁移不补位置、不扫描实体、不改字段，落盘版本为 2；P2-01 双阶段夹具另验证空 / 非空位置重启保持 | 已验证 | 无 |
| `compileJava`、`build`、`runServer`、`runClient` 和日志检查通过 | 模块 E：`clean build` 通过、jar 无 `internal/debug`；无夹具 `runServer` 达到 `Done (2.282s)`、无夹具 `runClient` 到主菜单；`latest.log` 无 Furkin 专属 ERROR / FATAL（见 `verification-matrix.md` §5.17） | 已验证 | 无 |

## 5. 发布门槛映射

| 门槛 | 状态 | 说明 |
|---|---|---|
| P0、P1、P2 矩阵全部有记录 | 通过 | §3 / §4 / §5 均有记录（模块 A-E） |
| P0/P1 未通过前不宣称 P2 完成 | 通过 | P0/P1 定向阻断项已清空，P2 完成定义映射已闭环 |
| 四个 Gradle 任务均执行并记录 | 通过 | `verification-matrix.md` §5.17 统一门槛 |
| `latest.log` 无新增错误 | 通过 | 服务端仅基线 `TagLoader`，客户端无 Furkin 专属 ERROR / FATAL |
| 无永久 `FORCED` ticket | 通过 | 模块 E 静态审计 + ticket 复核 |
| 未加载实体不会进入重建路径 | 通过 | P0 / P1 / P2 守卫夹具（§2、§4.4、§5.11） |
| 失败不会清档案定位、不会造成物品丢失 | 通过 | P2 档案失败路径；模块 C P0 卸载 / 回载闭环；P1-07 / P1-12 物品守恒 |
| 重复实体修复先搬物再删实体 | 通过 | happy path 与 P1-07 / P1-12 / P1-14 失败注入均已覆盖 |
| 文档、配置、README、CHANGELOG 与实际行为同步 | 通过 | CHANGELOG 中英均含远召条目；lang 197/197、`remote_summon` 13/13；README 按乌狸决策不加配置表 |

## 6. 缺口优先级

### P1 阻断项

- 模块 A / B / C 已核销 P0 持久化闭环与 P1-01；P0/P1 定向阻断项已清空。

### P0 / P2 边界项

1. 装备与行囊“卸载 -> 失败或成功远召 -> 重新加载”闭环。
2. `summoned=false + entity_uuid 非空 + 同 UUID 已加载` 的精确重建守卫。
3. `dismiss -> summon` 档案快照恢复专用夹具。
4. ~~v0 / v1 旧档案文件迁移夹具。~~ 已完成（模块 D，2026-09-27，见 §1.4 / D-26）。
5. ~~最终静态边界命令记录。~~ 已完成（模块 E，2026-09-27，见 §1.5 / §5）。
6. ~~最终统一 `compileJava`、`build`、`runServer`、`runClient` 与日志门槛。~~ 已完成（模块 E，2026-09-27，见 §1.5 / `verification-matrix.md` §5.17）。

## 7. 建议执行顺序

1. **模块 A（已完成，2026-09-27）：P1 失败注入与空物品修复夹具**，覆盖 P1-06、P1-07、P1-12、P1-14。
2. **模块 B（已完成，2026-09-27）：P1 命令与反向修复夹具**，覆盖 P1-03、P1-04、P1-08、P1-10。
3. **模块 C（已完成，2026-09-27）：P0/P1 实体持久化闭环**，覆盖装备 / 行囊卸载重载、同 UUID 重建守卫、`dismiss -> summon`、P1-01。
4. **模块 D（已完成，2026-09-27）：旧档迁移夹具**，构造 v0 / v1 档案并验证 v2 迁移不触碰实体状态。
5. **模块 E（已完成，2026-09-27）：静态审计与最终门槛**，统一执行四 Gradle 任务、日志检索、FORCED / LivingTickEvent 检索，并核销文档勾选项。

## 8. 声明口径

模块 A-E 已完成，可以声明：

- “除 `owner-dimension-follow` 外，`remote-summon` 功能包的 P0-P2 收口已完成。”
- “最终静态审计、四项 Gradle 门槛与最终日志检查已完成（见 §1.5、`verification-matrix.md` §5.17）。”

唯一排除项仍是独立案 `owner-dimension-follow`。
