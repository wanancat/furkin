# 远距召唤 1.20.1 统一验证矩阵

- 日期：2026-09-27
- 状态：代码已落地；一次性夹具已完成 P0/P1、P2 冷区/重启和已加载跨维度核心路径验证，完整客户端交互、故障注入、生命周期取消和性能矩阵仍待验证，提交 / 推送等待乌狸确认。
- 基线：`mc1.20.1@5ad092473a48b411782c16ebdbd81893d7674c11`
- 参考：`mc1.19.2@206a92732c843a7f0aeea72fa7a3a6287b01356d`
- 原则：只有源码事实、一次性夹具日志、真实客户端 / 服务端运行日志或明确历史记录才能支撑“通过”；计划描述和推断不能替代证据
- 用法：每条先按“操作”构造前置状态，再执行步骤，用“预期结果”判定，用“失败判定 / 证据”留证；任何一条的执行结果都不得提前写成“通过”

### 当前可复核证据（2026-09-27）

- `.\gradlew.bat compileJava --console=plain`：`BUILD SUCCESSFUL`（JDK 17.0.2）。
- `.\gradlew.bat build --console=plain`：`BUILD SUCCESSFUL`，产物 `build/libs/furkin-1.20.1-0.0.3.0.jar`；版本已按乌狸确认收口为 `1.20.1-0.0.3.0`。
- `.\gradlew.bat runServer --console=plain`：到达 `Done (2.685s)`；`run/logs/latest.log` 无 Furkin 专属 ERROR / FATAL / 异常栈。
- 配置实测：`run/world/serverconfig/furkin-server.toml` 存在 6 个 `remoteSummon*` 键，默认值符合 D-22。
- 语言键实测：`en_us.json` 与 `zh_cn.json` 均 201 键，集合 diff 为空；不存在 `furkin.msg.summon_entity_unresolved`。
- 静态审计：无永久 `FORCED` / `setChunkForced`、无主线程 `managedBlock`、无 `LivingTickEvent`；`findAllLoaded` 只在 repair 路径。
- Forge 47 mapped bytecode：`PersistentEntitySectionManager.addEntity(...)` 在实体加入/回载路径 post `EntityJoinLevelEvent`，`ServerLevel$EntityCallbacks.onTrackingEnd(...)` 在 section 卸载/离场路径 post `EntityLeaveLevelEvent`；本包依赖的入世 / 离场位置刷新路径可成立。
- `runClient` 已启动到客户端渲染初始化（前次执行记录见 [p2-true 第 10 节](p2-true-remote-summon.md#10-实施状态2026-09-27)）；完整绒亲录在途态、交互和性能场景未在本矩阵中伪造通过。
- 一次性夹具 p0p1：32 项检查、0 失败；覆盖未解析只读失败、合法重建、重复注册表、repair preview/confirm、核心字段复制、物品精确守恒、已加载即时传送、DISABLED、NO_POSITION、DIMENSION_MISSING。
- 一次性夹具 prepare_p2 -> 服务端重启 -> verify_p2：32 项检查、0 失败；覆盖 4 次冷区加载远召、canonical UUID / 身份 / 物品保持、per-player 与 global pending 上限、同 companion 冷却、异步终态和 ticketReleased=true，并检查平均 tick 未出现大尖峰。
- 一次性夹具 cross_dimension：5 项检查、0 失败；覆盖已加载 Nether 绒亲经 changeDimension 到主世界、档案维度刷新和行囊物品保持。
- 上述夹具代码已从最终工作树删除；2026-09-27 最终 clean build 成功，jar 内容审计无 RemoteSummonVerificationFixture / internal/debug。


## 1. 结果图例

| 状态 | 含义 |
|---|---|
| 待实施 | 还没有对应生产代码 |
| 待验证 | 代码已存在，但没有当前 1.20.1 证据 |
| 部分通过 | 只覆盖了部分路径，仍有边界未测 |
| 通过 | 有当前 1.20.1 的直接证据 |
| 阻断 | 发现会导致物品丢失、复制、脏状态或崩溃的问题 |

## 2. P0 安全失败

| ID | 操作（前置 → 步骤） | 预期结果 | 失败判定 / 证据 | 状态 |
|---|---|---|---|---|
| P0-01 | 契约一只绒亲并让其区块卸载 → 点击召唤一次 | 返回 `ENTITY_UNRESOLVED` | 若返回 `REBUILD_FAILED` / `TELEPORTED` 即失败；证据：`Furkin remote resolve failed ... reason=loaded-index-miss` | 通过（夹具：未解析返回 ENTITY_UNRESOLVED） |
| P0-02 | 同上 → 失败前后读档案 `summoned` | `summoned` 仍为 `true` | 变为 `false` 即失败；证据：档案 NBT 前后快照 | 部分通过（夹具内存档案 summoned/UUID/维度/位置不变；未做 NBT 重读） |
| P0-03 | 同上 → 对比 `entity_uuid` / `entity_dimension` / `entity_pos` | 三项完全不变 | 任一被清空或改写即失败；证据：NBT diff | 部分通过（夹具对象值不变；未做 NBT diff） |
| P0-04 | 同上 → 连续点击两次 | 同 `companionId` 已加载实体数为 0 或 1，不变成 2 | 出现第二只实体即阻断；证据：实体计数 + 两次日志 | 部分通过（夹具守卫阻止重复重建；真实连续 UI 点击与实体计数未测） |
| P0-05 | 卸载区块 → 失败两次 → 回载原区块 | 仍是同一 UUID，装备和行囊保持 | UUID 变化或物品丢失即阻断；证据：UUID + 装备 / 行囊计数 | 待验证（卸载后回载的 UUID/装备/行囊未覆盖） |
| P0-06 | 契约后 `dismiss`，再手动加载该 UUID 实体 → 点击召唤 | 拒绝重建，返回 `ENTITY_UNRESOLVED` | 生成第二只实体即阻断；证据：`reason=loaded-but-not-summoned` | 待验证（夹具断言名与场景不匹配，不能作为证据） |
| P0-07 | `dismiss -> summon` 正常路径 | 按档案快照重建成功 | 重建失败或状态残留即失败；证据：运行时日志 | 部分通过（夹具从 summoned=false 档案合法重建；真实 dismiss 生命周期未测） |
| P0-08 | 对 `RequestSummonPacket.encode/decode` 与 `FurkinNetwork` 做 diff | 仍是一个 `UUID`，包 ID / 方向 / `PROTOCOL_VERSION=2` 不变 | 任一变化即需先走协议治理；证据：源码 diff | 通过（源码 diff） |
| P0-09 | 同一场景分别走命令与绒亲录 | 两者返回同一语义结果，各用各的键 | 两边规则不一致即失败；证据：两条反馈 + 同一档案状态 | 待验证（命令与绒亲录同场景未覆盖） |

## 3. P1 canonical 与修复

| ID | 操作（前置 → 步骤） | 预期结果 | 失败判定 / 证据 | 状态 |
|---|---|---|---|---|
| P1-01 | canonical 已加载 → 触发另一 UUID 的同身份实体入世 | 档案不被抢绑，记录 `Furkin duplicate companion join` | 档案 UUID 被改写即阻断；证据：入世日志 + 档案快照 | 部分通过（夹具命中 duplicate registry；未核对入世日志 + 档案快照） |
| P1-02 | 同 UUID 实体在另一维度入世 | 刷新 `entity_dimension` 与位置，不判重复 | 被误判重复或档案未刷新即失败 | 待验证 |
| P1-03 | `/furkin repair list <id>` 只读查询 | 完整列出候选与计数，档案 / 实体不变 | 有写操作或漏字段即失败；证据：命令输出 + 前后状态 | 待验证 |
| P1-04 | 非 OP / 非 owner / 未召唤 / 已亡 / 非法 UUID 分别执行 `repair list` | 分别返回权限、owner、状态、参数错误 | 任一越权或错误结果即失败；证据：命令行返回 | 待验证 |
| P1-05 | `/furkin repair choose <id>` 不传 keeper UUID | 返回 `KEEP_UUID_REQUIRED`，现场不变 | 自动选择即失败 | 部分通过（命令树源码） |
| P1-06 | keeper=物品为空的重复体执行 choose | 核心数据搬入，删除重复体，只剩 keeper | 物品丢失或残留候选即失败 | 通过（夹具 preview/confirm：删除重复体，档案指向 keeper） |
| P1-07 | keeper 槽已有装备，重复体同槽有装备 → choose | keeper 不覆盖，重复体装备掉落到 keeper 脚下 | 装备被覆盖或消失即阻断；证据：物品守恒计数 | 部分通过（空槽装备搬运 + 总量守恒；同槽冲突掉落未测） |
| P1-08 | 选 keeper=新实体（非 canonical）→ choose | keeper 获得 canonical 的等级 / 经验 / 技能 / 战斗模式 / 冷却 | 任一进度回退即失败；证据：修复前后字段对比 | 通过（夹具核心字段从 canonical 复制到 keeper） |
| P1-09 | 注入行囊搬运中途失败 → 重试 choose | 失败时物品守恒、keeper 行囊回滚，重试收敛 | 物品复制 / 吞掉 / 重复掉落即阻断；证据：两次前后计数 | 待验证 |
| P1-10 | 注入 skill rebuild 或 AI apply 失败 | 返回 `CLEANUP_FAILED`，不更新 canonical，不删除实体 | 更新 canonical 或删实体即阻断；证据：stage 日志 | 待验证 |
| P1-11 | canonical 未加载，仅重复体加载 → choose | 返回 `CANONICAL_NOT_LOADED`，现场不变 | 允许选择其它 keeper 即失败 | 待验证 |
| P1-12 | 同一 keeper 连续执行两次 `confirm` | 第二次幂等，无重复搬运、无额外删除 | 重复搬运或额外删除即失败 | 待验证 |
| P1-15 | 不带 `confirm` 执行 `choose` | 只输出计划并返回 `PREVIEWED`；档案、实体、物品全不变 | 出现搬运 / 删除 / 档案写入即阻断；证据：预演前后状态对比 | 部分通过（夹具确认 preview 不删实体；档案/物品完整 diff 未全测） |
| P1-13 | 修复后重启服务端，再让原重复体入世 | 档案仍指向 keeper，不被重新抢绑 | 重新抢绑即阻断；证据：两阶段重启日志 | 待验证 |
| P1-14 | `rg` 审计全部 `findAllLoaded` 调用点 | 只出现在 `repair list` / `repair choose` | 出现在 summon / teleport / tick / 入世路径即失败 | 通过（源码审计） |

## 4. P2.1 数据与位置

| ID | 操作（前置 → 步骤） | 预期结果 | 失败判定 / 证据 | 状态 |
|---|---|---|---|---|
| P2-01 | 传送 / 入世 / 契约 / 召唤 / 复活后读档案 | `entityUuid` / `entityDimension` / `entityPos` 同时写入 | 任一缺失或不同步即失败 | 部分通过（夹具覆盖重建/传送/跨维度刷新；未逐路径） |
| P2-02 | `clearEntityLocation()` 后读三项 | 三项同时为 null | 遗留旧位置即失败 | 待验证 |
| P2-03 | 构造缺少 `entity_pos` 的旧档 | 读作 `null` | 变成 `(0,0,0)` 即阻断 | 待验证 |
| P2-04 | 构造 `X/Y/Z` 类型不对或缺失的 `entity_pos` | 读作 `null` 并 WARN | 崩溃或读成坐标即失败 | 待验证 |
| P2-05 | 构造有位置但缺 UUID 或维度的档案 | 忽略位置并 WARN | 用孤立位置触发远召即失败 | 部分通过（缺维度重载后位置读 null 且请求返回 DIMENSION_MISSING；缺 UUID 分支未测） |
| P2-06 | 用 v0 分维度旧档启动 | 合并到主世界档案，字段保持，冲突保留 overworld | 字段丢失或静默覆盖即失败 | 待验证 |
| P2-07 | 用 v1 档启动 | 只升到 v2，`entity_pos` 保持 `null`，不扫描实体 | 补位置或改状态即失败 | 部分通过（启动迁移日志） |
| P2-08 | 迁移后保存并重读 | `data_version=2`，`summoned` / `alive` / UUID 不变 | 版本未落盘或状态被改即失败 | 部分通过（prepare→冷启动重读，UUID/身份/物品保持；data_version 未显式断言） |
| P2-09 | 逐条触发契约 / 召唤 / 复活 / 传送 / 入世 / 离场 | 每条后位置都刷新 | 需要 `LivingTickEvent` 才能刷新即失败；证据：路径日志 | 部分通过（重建/传送/跨维度路径） |
## 5. P2.2-P2.4 传送、服务与入口

| ID | 操作（前置 → 步骤） | 预期结果 | 失败判定 / 证据 | 状态 |
|---|---|---|---|---|
| P2-10 | 已加载同维度绒亲 → 召唤 | 同一实体传送到玩家身边，UUID 不变 | 新建实体或 UUID 变化即阻断 | 通过（夹具同维度传送，UUID 不变） |
| P2-11 | 已加载跨维度绒亲 → 召唤 | `changeDimension` 后仍是同一实体，能力保留 | 返回非生物或能力丢失即失败 | 通过（cross_dimension 夹具 changeDimension，物品保持） |
| P2-12 | 未加载同维度、位置已记录 → 召唤 | 临时加载后按 canonical UUID 定位并传送同一实体 | 新建实体或 UUID 变化即阻断；证据：ticket 生命周期日志 | 通过（verify_p2 4 只冷区实体按 canonical UUID 加载/传送） |
| P2-13 | 未加载跨维度 → 召唤 | 加载后跨维度传送，终态落在玩家当前 Level | 落点错误或能力丢失即失败 | 待验证（冷区跨维度未覆盖） |
| P2-14 | 观察 `ChunkStatus.FULL` 成功后、实体 section 未就绪的窗口 | 保持在 `WAIT_ENTITY_LOAD`，不提前判 unresolved | 提前返回 `ENTITY_UNRESOLVED` 即失败；证据：`areEntitiesLoaded` 断言 | 部分通过（verify_p2 多次等待实体加载，未构造窗口断言） |
| P2-15 | 旧档无 `entity_pos` → 召唤 | `NO_POSITION`，不加 ticket，不重建 | 加 ticket 或重建即失败 | 通过（夹具 NO_POSITION） |
| P2-16 | 档案缺 `entity_dimension` → 召唤 | `DIMENSION_MISSING`，不加 ticket | 加 ticket 即失败 | 通过（夹具 DIMENSION_MISSING） |
| P2-17 | 请求开始时重复体已加载 → 召唤 | `DUPLICATE_CONFLICT`，pending=0，ticket=0 | 添加 ticket 或进入加载即失败 | 待验证（请求前置重复冲突未计 ticket） |
| P2-18 | 同 companion 已 pending → 再次请求 | `ALREADY_PENDING`，ticket 数不增加 | 新增 ticket 或第二 pending 即失败 | 待验证（同 companion pending 未测） |
| P2-19 | 触发 per-player / global pending 上限 → 再请求 | `TOO_MANY_PENDING` | 超限受理即失败 | 通过（夹具触发 per-player/global 上限） |
| P2-20 | 构造 deadline 已过 | `TIMEOUT` 并释放全部 ticket | ticket 残留即阻断 | 待验证 |
| P2-21 | 注入 chunk loading failure | `CHUNK_LOAD_FAILED` 并释放 ticket，不进入实体定位 | 继续定位或残留 ticket 即失败 | 待验证 |
| P2-22 | pending 期间玩家换维度 | 不取消，终态使用玩家当前 Level | 误取消或落点仍用旧 Level 即失败 | 待验证 |
| P2-23 | pending 期间分别触发登出 / 死亡 / 收回 / 解绑 | 各自取消并释放 ticket，reason 独立可辨 | 任一未取消或 reason 混淆即失败；证据：逐原因日志 | 待验证 |
| P2-24 | 停止服务端后重启 | 不持久 pending、无残留 ticket、档案字段保持 | 新实例带旧 pending 即失败；证据：两阶段日志 | 部分通过（prepare→重启→verify，冷实体/档案保持；stop 时 pending/ticket 收敛未测） |
| P2-25 | 立即 / 异步 / PENDING 三类结果各走一次绒亲录 | `PENDING` 不刷新列表，非 pending 都刷新并解锁 | `PENDING` 刷新或终态不刷新即失败 | 待验证 |
| P2-26 | 绒亲录点击召唤 | 按钮 `summoning` 且禁用；终态刷新后解锁 | 同一次点击发出两个 pending 或按钮不解锁即失败 | 待验证 |
| P2-27 | 分别走命令立即 / pending / 异步终态 | 反馈键正确，异步不持有过期 `CommandSourceStack` | 键错误或 NPE 即失败 | 待验证（夹具覆盖异步终态，真实 command/UI 文字链路未覆盖） |
| P2-28 | 中英文 lang 键集合 diff | 完全一致，且无 P0 遗留死键 | 出现单侧键或死键即失败；证据：key diff | 通过（key diff） |

## 6. P2.5-P2.6 配置、边界与证据

| ID | 操作（前置 → 步骤） | 预期结果 | 失败判定 / 证据 | 状态 |
|---|---|---|---|---|
| P2-29 | 启动专用服后读 `run/world/serverconfig/furkin-server.toml`；集成服读 `run/saves/<存档目录>/serverconfig/furkin-server.toml` | 6 个 `remoteSummon*` 键存在，默认与范围符合 D-22 | 缺键或范围错误即失败；证据：`rg -n "remoteSummon" run/world/serverconfig/furkin-server.toml` | 通过（配置文件） |
| P2-30 | 设 `enabled=false`，分别召唤已加载 / 未加载绒亲 | 已加载传送与合法重建仍工作；未加载返回 `DISABLED` | 关闭后误伤已加载路径即失败 | 部分通过（夹具禁用未加载路径 DISABLED；已加载路径关闭未测） |
| P2-31 | 创建 pending 后运行中改 radius / timeout | 已创建请求保持快照值，下一次请求用新值 | 已创建请求被改即失败 | 待验证 |
| P2-32 | `rg` 审计 `FORCED` / `setChunkForced` | 只允许临时 ticket，无永久强加载 | 出现永久强加载即失败 | 通过（源码审计） |
| P2-33 | 审计 `getChunkFuture` 调用线程 + 运行期堆栈 | 只在 `Util.backgroundExecutor()` 调用，无主线程 `managedBlock` | 主线程调用即失败 | 部分通过（源码线程路径；运行期堆栈待补） |
| P2-34 | 审计 `LivingTickEvent` | 默认方案不监听全体 LivingEntity 逐 tick | 出现逐 tick 位置记录即失败 | 通过（源码审计） |
| P2-35 | 冷区 / 热区 / 串行 20 次 / 并发请求各跑一轮 | 有当前 1.20.1 的 tick / MSPT 或等价指标 | 只有 1.19.2 数据即失败；1.19.2 434 tick / 3.69ms / 4 并发仅作对照 | 部分通过（verify_p2 4 次冷区 + 2 并发 + average tick 断言；完整矩阵未测） |
| P2-36 | `.\gradlew.bat compileJava --console=plain` | 通过，保留原始日志 | 编译失败即阻断 | 通过（最终 clean build 内含 compileJava） |
| P2-37 | `.\gradlew.bat build --console=plain` | 通过，产物版本一致 | 构建失败或产物版本不符即失败 | 通过（最终 clean build，产物已生成） |
| P2-38 | `.\gradlew.bat runServer --console=plain` | 到达 `Done`，无 Furkin 专属 ERROR / FATAL / 异常栈 | 有专属错误即失败；证据：`run/logs/latest.log` | 通过（去夹具 runServer Done 2.574s） |
| P2-39 | `.\gradlew.bat runClient --console=plain` | 到主菜单并完成绒亲录在途态与刷新场景 | 界面异常或刷新失败即失败；证据：客户端日志 / 实机记录 | 部分通过（启动；完整在途态实机待验证） |
| P2-40 | 审计最终 jar | 不含 fixture / debug 类 / 临时世界 | 含一次性夹具即失败 | 通过（最终 jar 无 fixture/debug） |
| P2-41 | 对比 README / 中英 CHANGELOG / lang / 配置说明 | 与实际行为一致，README 只写公开边界 | 文档与实现不一致即失败；证据：文档 diff + key diff | 通过（文档已同步；版本节为 1.20.1-0.0.3.0） |

## 7. 建议执行顺序

1. 先完成 P0，建立“不会误删状态、不会误造实体”的安全基线。
2. 完成 P1 入世守卫、重复注册表、`repair list`，再做 `repair choose` 和故障注入。
3. 完成 P2.1 位置字段和档案迁移，再做 P2.2 公共传送路径。
4. 完成 P2.3 service 的立即 / pending / 终态状态机。
5. 接入 P2.4 命令和绒亲录，再做 P2.5 配置与文档。
6. 最后执行 P2.6 统一门槛；P0/P1 任一条阻断未清空，不得宣称 P2 完成。

任何阶段发现口径与 [决策记录](decision-log.md) 冲突，先停下更新决策记录与相关文档，再继续编码。

## 8. 证据记录模板

每个完成项追加：

```text
- ID：
- 日期：
- 提交 / 工作树：
- 场景：
- 前置状态：
- 操作：
- 结果：
- 原始日志或证据路径：
- 未覆盖边界：
```

夹具日志必须包含可检索的固定前缀，例如：

```text
FURKIN_FIXTURE_1_20_1_P0_REMOTE_FAIL_OK
FURKIN_FIXTURE_1_20_1_P1_RELOAD_OK
FURKIN_FIXTURE_1_20_1_P2_CHUNK_OK
FURKIN_FIXTURE_1_20_1_P2_DUPLICATE_OK
FURKIN_FIXTURE_1_20_1_P2_RESTART_OK
```

夹具源码、临时世界和临时 `server.properties` 完成后清理，不进入最终 jar。

## 9. 状态回填规则

- 只有“操作—预期—证据”三段齐全才允许把某条置为“通过”。
- 单项通过不代表整组通过；P0 / P1 / P2 各组必须逐项清空。
- 发生阻断时先把状态改为“阻断”，记录复现步骤和证据，再决定修复或回滚；修复后重新从“待验证”走一遍。
- 不得用删除日志、改写断言或回填文档的方式伪造通过。
