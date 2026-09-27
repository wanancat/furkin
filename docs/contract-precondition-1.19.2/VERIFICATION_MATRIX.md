# 契约血量前置条件验证矩阵

- 日期：2026-09-27
- 状态：实施后验证记录；一次性服务端夹具 33/33 通过；人工客户端 A～F 通过，G/H NOT RUN；第三方 TF 为环境债务
- 实施基线：`206a92732c843a7f0aeea72fa7a3a6287b01356d`
- 执行契约：[EXECUTION_CONTRACT.md](EXECUTION_CONTRACT.md)
- 设计摘要：[README.md](README.md)

> 本文件最初作为验收证据模板；当前已取得实际结果的 `P` 列已勾选，未执行的第三方项标为 `ENV BLOCKED`。不得用预期结果替代实测证据。

## 1. 证据记录格式

每个用例至少记录：

- 提交 / 工作区状态；
- 执行日期与时间；
- 命令或夹具入口；
- 实际操作与前置状态；
- 预期结果；
- 实际结果；
- `run/logs/latest.log` 中的日志标记或关键片段；
- 若为客户端场景，记录界面可见结果或截图路径；
- 若为失败用例，记录是否保持只读、是否没有第二实体或物品变化。

统一标记：

```text
PASS
FAIL
NOT RUN
ENV BLOCKED
```

`ENV BLOCKED` 只能用于第三方依赖或环境缺失，不能用于产品代码或项目内 API 失败。

## 2. 构建门槛

| ID | P | 前置 | 操作 | 预期 | 证据 |
|---|---:|---|---|---|---|
| B-01 | [x] | JDK 17 已设置 | `.\gradlew.bat compileJava --console=plain` | 编译成功，无新增编译错误 | Gradle 退出码、构建日志 |
| B-02 | [x] | B-01 通过 | `.\gradlew.bat build --console=plain` | 全部构建任务成功 | `BUILD SUCCESSFUL`、产物路径 |
| B-03 | [x] | B-02 通过 | `.\gradlew.bat runServer --console=plain` | 服务端启动到 `Done`，无项目专属异常 | `run/logs/latest.log` |
| B-04 | [x] | B-02 通过 | `.\gradlew.bat runClient --console=plain` | 客户端完成资源/声音初始化，无启动崩溃 | 客户端日志（`Sound engine started` / atlas created） |
| B-05 | [x] | B-03/B-04 完成 | 检查 `run/logs/latest.log` | 无新增 Furkin 专属 `ERROR`、`FATAL`、异常栈或资源缺失 | 日志片段、时间戳 |
| B-06 | [x] | 最终构建完成 | 检查最终 JAR 内容 | 不含 `internal.debug`、fixture、临时测试类 | JAR 文件列表、SHA-256 |

命令模板：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

仓库当前没有自动化 GameTest，不用 `runGameTestServer` 充当通过条件。

## 3. 配置与分类

| ID | P | 前置 | 操作 | 预期 | 证据 |
|---|---:|---|---|---|---|
| S-01 | [x] | 干净或已备份配置 | 启动一次服务端，读取 `furkin-server.toml` | 六项健康配置存在，默认值为 `30/4/50/8/100/0` | 配置片段 |
| S-02 | [x] | 已有旧配置 | 加载当前工作区配置 | `activeLimit` 与远召六项键存在且值未被重置 | 配置前后 diff |
| S-03 | [x] | `Enemy` 测试实体 | 满血 20/20 尝试契约 | 返回 `HEALTH_TOO_HIGH`，提示按 30% / 4 格式化 | 夹具日志、消息 |
| S-04 | [x] | 同上 | 设置 6/20 尝试契约 | `6 <= 20 * 30%`，进入命名请求路径 | pending/命名包证据 |
| S-05 | [x] | 同上 | 设置 4/20 尝试契约 | 命中绝对分支，进入命名请求路径 | pending/命名包证据 |
| S-06 | [x] | Wolf，最终默认 | 8/8 尝试契约 | `50% / 8` 的绝对分支通过，进入命名请求路径 | 夹具日志、命名包 |
| S-07 | [x] | Wolf，最终默认 | 4/8 尝试契约 | 恰好 50% 通过 | 夹具日志 |
| S-08 | [x] | Cat，最终默认 | 10/10 尝试契约 | 其他分类 100%，通过 | 夹具日志 |
| S-09 | [x] | Zombie | 临时设 Enemy 百分比 100、绝对值 0 | 满血 Enemy 通过，证明百分比 100 分支 | 配置快照、夹具日志 |
| S-10 | [x] | Wolf | 临时设 NeutralMob 绝对值为 0 | 8/8 被 50% 百分比拒绝，提示不含绝对项 | 配置快照、消息 |
| S-11 | [x] | 未注册 Cow | 持契约物品尝试契约 | 返回 `UNREGISTERED`，无健康提示 | 夹具日志、消息捕获 |
| S-12 | [x] | 其他玩家的已驯服 Wolf | 尝试契约 | 返回 `OWNED_BY_OTHER`，无健康提示 | 夹具日志 |
| S-13 | [x] | 活跃上限已满的玩家 | 尝试契约 | 返回 `ACTIVE_LIMIT`，沿用既有活跃上限提示，无健康提示 | 夹具日志、消息捕获 |
| S-14 | [x] | 主手为空或非契约物品 | 直接调用 `tryContract(...)` | 返回 `INVALID_HAND`，无健康提示、无物品变化 | 夹具日志、物品快照 |
| S-15 | [x] | 正常 Wolf | 分别把 `currentHealth` 设为 `0`、`NaN`，尝试契约 | 均返回 `INVALID_TARGET`，证明 `isAlive()` 先于健康检查，无物品变化 | 夹具日志、生命值快照 |
| S-16 | [x] | Wolf 仍存活，已先设置正生命值 | 用一次性 Wolf 子类覆写 `getAttributeValue(MAX_HEALTH)`，分别返回 `NaN`、`0`（并至少补测 `Infinity`），尝试契约 | 均返回 `INVALID_HEALTH`，静默、无物品变化 | 夹具日志、覆写值与生命值快照 |
| S-17 | [x] | 已有 pending，随后新请求健康失败 | 发新 `tryContract(...)` | 新请求返回失败，旧 pending 不应仍可确认 | pending 副作用、确认拒绝日志 |
| S-18 | [x] | 正常两步流程 | 命中门槛后发起命名，再把生命回高后确认 | 确认拒绝，不建档、不扣物品、不改 AI/capability/名字 | 前后快照、物品数 |

`INVALID_HEALTH` 记录说明：1.19.2 的 `MAX_HEALTH` 会经 `RangedAttribute#sanitizeValue(...)` 清洗，`setBaseValue(NaN)` 读出最小值 `1.0`、`setBaseValue(0)` 被钳到 `1.0`，不能据此构造非法最大值。S-16 使用一次性 Wolf 子类覆写公开且非 final 的 `getAttributeValue(Attribute)`，在 `setHealth(...)` 之后才启用 NaN/0/Infinity。S-15 单独固定 `isAlive()` 先于健康检查的顺序；若补测 Infinity `currentHealth`，继续用同一夹具的独立 `getHealth()` 开关。

## 4. 交互与原版副作用

| ID | P | 前置 | 操作 | 预期 | 证据 |
|---|---:|---|---|---|---|
| C-01 | [x] | 最终默认值，满血 Wolf | 普通右键契约 | 进入命名请求路径，原版右键不继续执行 | 真实事件路径、服务端日志 |
| C-02 | [x] | 临时把 NeutralMob 改为 30% / 4 | 满血 Wolf 普通右键契约 | 健康提示出现，Wolf 坐姿不切换 | 真实事件路径、实体状态 |
| C-03 | [x] | 健康拒绝场景 | 检查物品与实体 | 契约物品数量不变、死亡/存活状态不变、capability 不变 | before/after 快照 |
| C-04 | [x] | 最终默认值，满血 Cat | 10/10 普通右键契约 | 其他分类通过，进入命名请求路径 | 真实事件路径、日志 |
| C-05 | [x] | 未注册 Cow | 持契约物品右键 | 无健康提示，不因健康门槛接管原版交互 | 真实事件路径、日志 |
| C-06 | [x] | 错误归属 Wolf | 右键契约 | 无健康提示，既有失败语义不变 | 真实事件路径、消息捕获 |
| C-07 | [x] | 英文客户端 | 触发 `HEALTH_TOO_HIGH` | 英文键显示阈值且 `%`/小数格式正确 | 消息组件键/参数捕获 |
| C-08 | [x] | 中文客户端 | 触发 `HEALTH_TOO_HIGH` | 中文键显示阈值且 `%`/小数格式正确 | 消息组件键/参数捕获 |
| C-09 | [x] | 相对/绝对双分支配置 | 触发只有绝对分支的提示 | 使用 `contract_health_with_absolute`，参数顺序正确 | 消息组件键/参数捕获 |
| C-10 | [x] | 健康失败后重试 | 调高到命中门槛后再次右键 | 可重新发起命名，不残留旧 pending | 真实事件路径、pending 行为 |

## 5. 多部件实体

### 5.1 强制：原版 EnderDragon

测试夹具临时通过 `FurkinApi.registerSpecies(...)` 注册 EnderDragon，在临时世界中生成 dragon，设置 `setNoAi(true)` 并移动到 FakePlayer 附近。文件与实体在验证后清理，不进入生产代码。

| ID | P | 前置 | 操作 | 预期 | 证据 |
|---|---:|---|---|---|---|
| M-01 | [x] | EnderDragon 满血，事件 target 为 `dragon.head` | 调用真实 `CommonEvents.onEntityInteract(event)` | 解析到 EnderDragon 父实体；记录 part/parent 距离；命中 Enemy 门槛并取消事件 | `FURKIN_FIXTURE_CONTRACT_MULTIPART_REJECT_OK` |
| M-02 | [x] | EnderDragon 设为 4 点生命 | 同上 | 解析到父实体且进入命名请求路径 | `FURKIN_FIXTURE_CONTRACT_MULTIPART_PASS_OK` |
| M-03 | [x] | M-02 已建立 pending | 通过 `confirmContract(...)` 完成 | 父实体建档，物品从 8 → 7，`isCompanion()` 为真 | 实体/物品/档案快照 |
| M-04 | [x] | M-03 已完成 | 验证喂食、潜行面板、潜行收回路径 | 所有路径使用父实体，无 `PartEntity` 强转或静默退出 | 日志、实体状态 |
| M-05 | [x] | M-01 事件 | 检查事件对象 | 取消作用于原始部件事件，部件不再转发原版交互 | event canceled/result、日志 |

### 5.2 可选压力：1.19.2 Twilight Forest

| ID | P | 前置 | 操作 | 预期 | 证据 |
|---|---:|---|---|---|---|
| T-01 | ENV BLOCKED | 1.19.2 兼容桥接、临时世界 | 通过公开 API 注册 TF 实体 | 记录实际注册数量，不在生产源码硬编码 TF | 注册清单 |
| T-02 | ENV BLOCKED | Hydra 可生成 | 满血、百分比边界、绝对边界三点 | 分别拒绝/通过，基于运行时真实最大生命计算 | 日志、生命值快照 |
| T-03 | ENV BLOCKED | Hydra 命中门槛 | 完成真实契约、收回、重新召唤 | 档案一致，无双实体、无部件残留 | 日志、实体快照 |
| T-04 | ENV BLOCKED | 命名后回血 | 确认契约 | 二次校验拒绝，不建档、不扣物品 | 日志、物品/档案快照 |

若 T-01～T-04 因 1.19.2 依赖或桥接环境无法建立，全部标为 `ENV BLOCKED` 并保留为残余债务；M-01～M-05 仍必须通过。

## 6. 证据与收口

### 6.1 实际夹具日志

```text
FURKIN_FIXTURE_CONTRACT_HEALTH_CFG_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_ENEMY_FULL_REJECT_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_ENEMY_PERCENT_PASS_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_ENEMY_ABSOLUTE_PASS_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_NEUTRAL_FULL_PASS_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_OTHER_PASS_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_INVALID_TARGET_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_INVALID_SILENT_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_SECOND_CHECK_OK
FURKIN_FIXTURE_CONTRACT_HEALTH_READ_ONLY_OK
FURKIN_FIXTURE_CONTRACT_MULTIPART_REJECT_OK
FURKIN_FIXTURE_CONTRACT_MULTIPART_PASS_OK
```

夹具必须打印场景、预期值、实际值和关键快照字段，不能只打印 `PASS`。

### 6.2 实施后证据（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17.0.2；基线提交 `206a92732c843a7f0aeea72fa7a3a6287b01356d`，当前工作区未提交。
- 最终构建与启动：工作区版本升到 `1.19.2-0.0.3.0` 后，`build` 输出 `BUILD SUCCESSFUL`；无夹具 `runServer` 达到 `Done (2.371s)`；无夹具 `runClient` 完成 `Sound engine started` 与纹理图集创建。原始输出保留在 `D:\frukin_dev\_research\contract_precondition_final_build_20260927.log`、`contract_precondition_final_server_20260927.log`、`contract_precondition_final_client_20260927.log`。
- 配置兼容：最终服务端读取 `run/world/serverconfig/furkin-server.toml`，六项契约健康键为 `30.0/4.0/50.0/8.0/100.0/0.0`；旧 `activeLimit=3` 与远召六项键保留，旧开发值 `remoteSummonTimeoutTicks=100` 未被重置。
- 夹具运行：临时世界 `furkin_contract_precondition_20260927`，入口 `FURKIN_FIXTURE_CONTRACT_PRECONDITION=1`，最终日志 `FURKIN_FIXTURE_CONTRACT_HEALTH_SUMMARY pass=33 fail=0`。原始输出保留在 `D:\frukin_dev\_research\contract_precondition_fixture_20260927_final.log`。
- 分类与边界：Enemy 20/20 拒绝、6/20 与 4/20 通过；Neutral 8/8 与 4/8 通过；Cat 10/10 通过；百分比 100、绝对值 0、`INVALID_TARGET`、`INVALID_HEALTH`、pending 失效、确认阶段回血拒绝均已由夹具命中。
- 交互：真实 `PlayerInteractEvent.EntityInteract` 路径验证了通过场景、生命值拒绝、未注册、错误归属和重试；生命值拒绝时事件被取消，Wolf 坐姿未切换。
- 多部件：原版 `EnderDragonPart` 作为事件目标时解析到 EnderDragon 父实体；满血拒绝、4 点通过、确认建档（物品 8→7）、喂食（苹果 3→2、生命 4→6）、面板、收回均通过。首轮夹具把龙放在 4.12 格导致真实 `OUT_OF_REACH`，修正放置距离后全部通过；这是夹具几何问题，不是产品回归。
- 消息：夹具直接捕获 `furkin.msg.contract_health_with_absolute[30%, 4]`、`furkin.msg.contract_health[50%]`，与中英文资源键参数一致。
- 日志：无 Furkin 专属 `ERROR` / `FATAL`；仅保留运行世界历史数据的 `Legacy Furkin AI state` WARN、Minecraft 基线 `TagLoader` ERROR 与环境性 Netty/OSHI/WMI 噪声。
- 最终产物：`build/libs/furkin-1.19.2-0.0.3.0.jar`，大小 `388671` 字节，SHA-256 `4807B5877A53EE54D2257B0A3EE1009254A7E9978A3B3D308FEF280461F1F055`；`jar tf` 共 226 项，不含 `internal.debug` / fixture。
- 未闭边界：真实客户端人工验收 A～F 已由乌狸执行通过；按用户要求不保存截图，记录见 6.3。G/H 为可选项并标记 `NOT RUN`。Twilight Forest/Hydra 1.19.2 依赖未建立，T-01～T-04 标为 `ENV BLOCKED`，保留为残余债务。

修复记录：1.19.2 原版 `Cat` 最大生命为 10，不是早期文档中的 8；S-08/C-04 与实现计划已按实测值修正。

### 6.3 人工客户端实测（2026-09-27）

- 环境：Minecraft 1.19.2 / Forge 43.2.0；测试世界 `contract-precondition-manual-test`。用户按验收单在真实客户端执行，截图按用户要求不纳入本记录。
- A（NeutralMob 满血拒绝）：`30% / 4` 下满血 Wolf `8/8` 右键，不打开命名界面，动作栏显示 `目标生命值过高（需不高于 30% 或 4 点生命值）`，契约物品不变。PASS。
- B（NeutralMob 绝对值边界）：同一配置下 Wolf `4/8` 右键打开命名界面，确认后契约成功，`/furkin list` 出现新增绒亲。PASS。
- C（NeutralMob 默认值）：恢复 `50% / 8` 后满血 Wolf `8/8` 右键直接打开命名界面。PASS。
- D（其他分类）：默认配置下满血 Cat `10/10` 右键直接打开命名界面，无健康提示。PASS。
- E（未注册物种）：满血 Cow 右键无健康提示、无命名界面、物品不变。该路径当前不写 `UNREGISTERED` 日志，因此按界面行为判定，不从日志独立确认。PASS。
- F（禁用绝对值）：`50% / 0` 下满血 Wolf `8/8` 右键显示 `目标生命值过高（需不高于 50%）`，无绝对生命值 `8`、无命名界面、物品不变。PASS。
- 未运行：G（原版右键副作用取消）和 H（确认阶段回血拒绝）均为可选项；分别由夹具和真实事件路径覆盖，标记为 `NOT RUN`，不阻塞本次人工验收。


### 6.4 最终验收记录

- [x] B-01～B-06 有实际证据。
- [x] S-01～S-18 有实际证据。
- [x] C-01～C-10 有真实事件路径与消息组件证据；人工客户端 A～F 实测通过，未保存截图。
- [x] M-01～M-05 全部通过。
- [x] T-01～T-04 明确标为 `ENV BLOCKED` 环境债务。
- [x] 人工客户端 G/H 标为 `NOT RUN`，不阻塞本次必测项。
- [x] `run/logs/latest.log` 无新增 Furkin 专属错误。
- [x] 一次性夹具源码和夹具世界已清理；人工测试世界 `contract-precondition-manual-test` 按测试需要保留在 `run/saves`，未进入版本库。
- [x] 最终 JAR 不含 fixture / debug 类。
- [x] README、两份 changelog、版本号和 API/协议边界一致。

## 7. 不通过条件

出现以下任一情况即不能关闭本功能包：

- 健康失败仍建档、扣物品或写 capability。
- `HEALTH_TOO_HIGH` 不取消原版交互。
- `INVALID_HEALTH` 被当作健康提示显示。
- 确认阶段未重新检查健康。
- 健康失败覆盖 `UNREGISTERED`、`OWNED_BY_OTHER`、`OUT_OF_REACH` 等既有原因。
- `PartEntity` 只通过直接调用父实体测试，未测试事件 target 为部件。
- 最终 JAR 或日志带有测试夹具、临时世界或调试残留。