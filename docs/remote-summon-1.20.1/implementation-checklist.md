# 远距召唤 1.20.1 实施清单

- 日期：2026-09-28
- 用途：把 P0 / P1 / P2 拆成可逐项勾选、可验证、可回滚的实施步骤
- 口径来源：[决策记录](decision-log.md)（D-23 起为本次独立复查补正）、各阶段执行契约
- 使用方式：每完成一项勾选并附证据路径；未执行不得勾选，也不得把计划写成结果
- 当前结论（2026-09-28）：代码项已落地；一次性夹具 11 个模式（prepare / nbt / repair / cold / reload / orphan / safety / commands / stop-pending / restart / perf）全部 0 失败，最终 build 与去夹具 runServer 烟测通过；热区 / 冷区 / 串行 20 次 / 并发 4 性能已记录（确定性上限 + 实测两档，单机单次、无改动前基线，不能作为无回归结论），异步收口线程已现场取证；仍待补真实客户端绒亲录交互、v0 分维度旧档迁移、同 UUID 跨维度入世；提交/推送等待乌狸确认

## 0. 通用前置

- [x] 确认 `git status`，除本功能包代码、项目 README / CHANGELOG 和已跟踪的工作文档外没有夹带构建、运行、日志或临时文件
- [ ] 备份将用于 P1 / P2 测试的世界目录（P1 会删除实体，P2 会临时加载区块；实机 P1/P2 前执行）
- [x] 发布版本已按乌狸确认收口为 `1.20.1-0.0.3.0`；`gradle.properties` 已处于该版本
- [x] 发布版本已决定为 `1.20.1-0.0.3.0`；`gradle.properties`、changelog 版本节和构建产物名称一致。
- [x] 设置 JDK 17：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.2'`，`$env:Path="$env:JAVA_HOME\bin;$env:Path"`
- [x] 记录基线提交：`git rev-parse HEAD` = `a9870714`（当前工作树在其上叠加 P0-06 口径修正、`ServerStoppingEvent` 停服接线与 `TRAVEL_POUCH` 空值防御）
- [x] 确认 mapped jar 可读：`C:\Users\wanancat\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.2.0_mapped_official_1.20.1\forge-1.20.1-47.2.0_mapped_official_1.20.1.jar`
- [x] 确认 1.19.2 参考源码可读；未合并 1.19.2 分支，未做全局文本替换
- [x] 阅读 [口径冻结索引](README.md)（README 第 5.1 节），并按 D-33 与终态键口径修订

## 1. P0：未解析实体安全失败

### 1.1 代码

- [x] `FurkinCompanionManager` 新增 `TeleportResult { TELEPORTED, ENTITY_UNRESOLVED, DIMENSION_CHANGE_FAILED }`
- [x] `SummonResult` 在 `ACTIVE_LIMIT` 之后、`REBUILD_FAILED` 之前追加 `ENTITY_UNRESOLVED` / `DUPLICATE_CONFLICT` / `DIMENSION_CHANGE_FAILED`
- [x] `teleportToOwner` 返回类型 `boolean` → `TeleportResult`
- [x] 删除 `teleportToOwner` 中 `target == null` 分支的 `setSummoned(false)` / `clearEntityLocation()` / `archive.putEntry(...)`，改为只 WARN + `ENTITY_UNRESOLVED`
- [x] `summonOrTeleport` 已召唤分支按 `TeleportResult` 映射，不再经 `boolean`
- [x] `FurkinEntityLocator` 落地 `findLoadedByRecordedUuid(MinecraftServer, FurkinArchiveEntry)`
- [x] `summonOrTeleport` 未召唤分支在 active limit **之前**加 `findLoadedByRecordedUuid` 守卫
- [x] P1 落地后，在同一位置追加 `hasLoadedDuplicate` 守卫（顺序固定在 `findLoadedByRecordedUuid` 之后）
- [x] `rebuildCompanion` 在读取 species 之前保留最后一道同 UUID / 同身份守卫
- [x] `RequestSummonPacket` 补 `ENTITY_UNRESOLVED` / `DIMENSION_CHANGE_FAILED` 分支，绒亲录直接使用 `furkin.msg.remote_summon_unresolved` / `furkin.msg.summon_dimension_change_failed`
- [x] `FurkinCommand.summon` 补 `ENTITY_UNRESOLVED` / `DIMENSION_CHANGE_FAILED` 分支，使用 `furkin.command.summon.entity_unresolved` / `furkin.command.summon.dimension_change_failed`
- [x] 中英文新增最终入口键，键集合一致；不引入 `furkin.msg.summon_entity_unresolved`
- [x] 落地只读诊断日志：未解析传送使用 `missing-entity-uuid / missing-entity-dimension / dimension-unavailable / loaded-index-miss`；未召唤 / 重复守卫使用 `loaded-but-not-summoned / loaded-duplicate`

### 1.2 验证

- [x] `.\gradlew.bat compileJava --console=plain`
- [x] `.\gradlew.bat build --console=plain`
- [x] `rg -n "setSummoned\(false\)" src/main/java`，结果只剩 `dismiss` 与死亡侧写
- [x] 运行 [验证矩阵 P0 项](verification-matrix.md)：P0-01 / P0-02 / P0-03 / P0-04 / P0-06 / P0-07 / P0-09 均已有夹具证据；P0-05 为分段覆盖（`reload` / `cold` / `restart`），同实例串联未跑
- [x] 档案 NBT 快照：夹具 `nbt` 的 `P0 unresolved NBT byte-for-byte unchanged` 做 serializeNBT 前后逐字节比对；`reload` 的 `reload failures keep archive NBT unchanged` 复核同一口径。同身份实体计数由 `reload failures create no entity` + `orphan duplicate registry sees one entity` 覆盖
- [x] `runServer` 日志出现 `Furkin remote resolve failed ... reason=loaded-index-miss`；夹具归档日志未发现新增 Furkin ERROR / FATAL
- [ ] 作为独立切片提交，不与 P1 / P2 混合

## 2. P1：canonical 守卫与显式修复

### 2.1 入世守卫与注册表

- [x] `FurkinDuplicateRegistry` 落地（内存 `Map<UUID companionId, Set<UUID entityUuids>>`）
- [x] `CommonEvents.onEntityJoinLevel` 改为第 3 节决策表：同 UUID 刷新位置，不同 UUID 不抢绑并登记
- [x] 新增 `EntityLeaveLevelEvent` 接线：注销登记并按 canonical 刷新位置
- [x] 死亡 / `dismiss` / 解绑 / 强制解绑 / 停服时清理登记
- [x] `FurkinEntityLocator.findAllLoaded(...)` 落地，canonical 优先、其余按 UUID 排序
- [x] 审计 `findAllLoaded` 调用点只在 `repair list` / `repair choose`

### 2.2 修复实现

- [x] `FurkinData.copyCoreFrom(FurkinData source)` 落地（可用现有 `copy()` 字段清单）
- [x] `FurkinDuplicateRepair.Result` 落地 11 个返回值（10 个执行 / 失败结果 + `PREVIEWED`）
- [x] `FurkinDuplicateRepair.choose(...)` 前置校验按第 6 节顺序
- [x] 阶段 A：`copyCoreFrom` → 显式 `setCompanionId` / `setOwnerUuid` → `setTarget(null)` → `combatMode.applyTo(tamable)` → `SkillRuntimeCalibrator.rebuild` → `resizePouchToLevel` 溢出掉落
- [x] 阶段 A：装备搬运（keeper 空槽写、非空掉落、源槽成功后才清空）
- [x] 阶段 A：行囊搬运（记录 `targetBefore`、成功清源槽、失败 `fromTag` 回滚）
- [x] 阶段 B：`syncArchiveCore`（不写名字）→ `archive.putEntry` → `SyncFurkinDataPacket` → 注册表清理
- [x] 阶段 B：`clearDiscardRuntime` → `discard()` → 注册表清理 → `findAllLoaded` 复核
- [x] 五阶段异常注入点：`DATA / POUCH / EQUIPMENT / ARCHIVE / POSTCONDITION`
- [x] `FurkinCommand` 注册 `/furkin repair list|choose`，`choose` 默认预演、追加 `confirm` 才执行（D-33）
- [x] `FurkinDuplicateRepair.plan(...)` 与 `choose(...)` 共用同一段前置校验与计划构建；预演路径不写档案、不动物品、不删实体
- [x] 新增 `PREVIEWED` 结果与 3 个 `preview.*` 语言键
- [x] 中英文各加 17 个 repair 键（14 个既有 + 3 个 `preview.*`）
- [x] 结构化日志固定前缀与 stage 取值

### 2.3 验证

- [x] `.\gradlew.bat compileJava --console=plain`、`.\gradlew.bat build --console=plain`
- [x] p0p1 夹具覆盖 P1-06/P1-08/P1-14/P1-15 的当前已加载修复核心路径；P1-01/P1-07 部分通过；P1-03/P1-04/P1-09~P1-13 仍未覆盖
- [ ] 故障注入：装备写入失败、行囊部分失败、溢出掉落失败、技能重建失败、AI apply 失败
- [x] 普通路径物品守恒断言：装备槽计数 + 行囊物品数 + 掉落物总数；故障注入路径仍未覆盖
- [x] 反向 keeper 前后核心字段对比（level / xp / points / skill / mode / feed / lastFeed / cooldown）
- [ ] 两阶段重启验证 canonical 不抢绑

## 3. P2.1：档案位置字段与迁移

- [x] `FurkinArchiveEntry` 增加 `@Nullable BlockPos entityPos` 与访问器
- [x] `setEntityLocation` 同步写 UUID / 维度 / `blockPosition()`
- [x] `clearEntityLocation` 同步清三项
- [x] NBT 写入 `entity_pos`，读取校验 `Tag.TAG_COMPOUND` + `X/Y/Z` int
- [x] 位置存在但 UUID / 维度缺失时忽略并 WARN
- [x] `CURRENT_DATA_VERSION=2`，新增 `LEGACY_GLOBAL_ARCHIVE_VERSION=1`
- [x] `FurkinArchiveData.migrate(server)` 拆成 v0 → v1 → v2
- [x] v1 → v2 空迁移，不动任何 entry；版本实际变化才 `setDirty()`
- [x] 检查契约 / 召唤 / 复活 / 传送 / 入世 / 离场 / 终态全部刷新位置
- [x] 夹具 `nbt` 覆盖 P2-03 / P2-04 / P2-05 / P2-07 / P2-08；夹具 `cold` + `restart` 覆盖位置持久化和冷区重载；P2-02 / P2-06 仍未覆盖

## 4. P2.2：传送公共路径

- [x] 抽出 `FurkinCompanionManager.teleportLoadedEntity(player, entry, target)`
- [x] `teleportToOwner` 与 `RemoteSummonService` 共用该方法
- [x] 保持同维度 / 跨维度、位置刷新、坐姿清理、能力同步语义
- [x] p0p1 夹具覆盖 P2-10；cross_dimension 夹具覆盖 P2-11（已加载跨维度，物品保持）

## 5. P2.3：异步远招服务

- [x] 新增 `RemoteSummonService` / `RemoteSummonRequest` / `RemoteSummonResult` / `RemoteSummonOrigin` / `RemoteSummonFeedback`
- [x] `SERVICES = WeakHashMap<MinecraftServer, RemoteSummonService>`
- [x] `byCompanion` / `pendingPerPlayer` / `cooldownUntil` / `nextRequestId`
- [x] `REMOTE_SUMMON_TICKET` owner 为 `ChunkPos`，level 用 `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)`
- [x] `request(...)` 顺序按 [p2-true-remote-summon 第 4.1 节](p2-true-remote-summon.md)
- [x] `startRemoteRequest` 添加 ticket、快照 radius / deadline、后台 `collectLoadFutures`
- [x] `WAIT_ENTITY_LOAD` 用 `areEntitiesLoaded(long)` 轮询，不提前判 unresolved
- [x] `finishPending` 幂等；`releaseTicket` 用 `ticketAdded` 一次释放
- [x] `notifyFeedback` 跳过 `PLAYER_LOGOUT` / `SERVER_STOPPING`，回调异常只 WARN
- [x] `recordsCooldown` 排除 9 个不计冷却结果
- [x] 取消接线：登出 / 死亡 / 收回 / 解绑 / canonical 改变 / 停服 / tick
- [x] `CommonEvents` 加 `ServerStoppingEvent` 监听取消 pending（`onServerStopping`），`ServerStoppedEvent` 只清 `FurkinDuplicateRegistry`；依据 `MinecraftServer.stopServer()` 先 `removeTicketsOnClosing()` 再发 `ServerStoppedEvent`
- [x] `CommonEvents.onServerTick` 加 `tickIfPresent(server)`

## 6. P2.4：入口、反馈与文案

- [x] `RequestSummonPacket` 改走 `RemoteSummonService.request(...)`，线格式不变
- [x] `FurkinCommand.summon` 改走 service，`PENDING` → `furkin.command.summon.remote_pending`
- [x] 绒亲录新增 `pendingSummonCompanionId` / `pendingSummonTicks` / `SUMMON_UI_TIMEOUT_TICKS=620`
- [x] `requestSummon` / `clearPendingSummon` / `tick` / `acceptRefresh` 行为按 [p2-true-remote-summon 第 6.1 节](p2-true-remote-summon.md)
- [x] 文案结果映射按 p2-true 第 6.3 节与 p2-execution-contract 第 9 节
- [x] 确认 `furkin.msg.summon_entity_unresolved` 不存在于两份 lang 或 Java 调用点
- [x] 中英文键集合 diff 为空；以 Java 调用点反查死键（已执行：en=201、zh=201，键集合无差异）

## 7. P2.5：配置与文档

- [x] `FurkinServerConfig` 加 6 个 `remoteSummon*` 键，默认与范围符合 D-22
- [x] 确认专用服 `run/world/serverconfig/furkin-server.toml` 生成全部键；集成服对应 `run/saves/<存档目录>/serverconfig/furkin-server.toml`
- [x] 更新 `CHANGELOG.md` 与 `changelog.en.md`，只写用户可感知变更
- [x] README 只写公开配置边界与行为，不粘贴完整内部表
- [x] 不改公开 API；协议仍为 `2`，除非实际新增包并先走协议治理

## 8. P2.6：收口验证

- [x] `.\gradlew.bat compileJava --console=plain`
- [x] `.\gradlew.bat build --console=plain`
- [x] `.\gradlew.bat runServer --console=plain` 到达 `Done`
- [x] `.\gradlew.bat runClient --console=plain` 启动到客户端渲染初始化；完整绒亲录在途态场景仍待实机验证
- [x] `run/logs/latest.log` 无 Furkin 专属 ERROR / FATAL / 异常栈 / 资源缺失
- [x] 静态审计：无永久 `FORCED`、无主线程 `managedBlock`、无全体 LivingEntity 逐 tick
- [x] 性能记录：确定性上限（默认 ≤36 区块 / ≤30s / 稳态 0）+ 实测两档（热区 ~14ms；冷区 ~511ms 已生成 / ~1897ms 需生成，单次最差 3500ms）；并发 4 单 tick 峰值 178.5ms 已记为后续优化项。见验证矩阵「性能记录」
- [x] 生命周期取消：夹具 `cold` 覆盖登出 / 死亡 / 收回 / 解绑四路取消，夹具 `stop-pending` 覆盖停服取消（`reason=SERVER_STOPPING ticketReleased=true`）
- [x] 最终 jar 不含 fixture / debug 类 / 临时世界
- [x] 验证矩阵 P0 / P1 / P2 全部清空或显式标注未覆盖边界；未闭环项集中在验证矩阵第 7 节“仍未闭环”
- [x] `git status` 无 `build/` / `run/` / 日志 / IDE / 临时文件

## 9. 提交切片建议

```text
1. 本目录工作文档（已完成，单独提交）
2. P0 安全失败与回归验证
3. P1 入世守卫 + 重复注册表 + repair list
4. P1 repair choose + 核心数据复制 + 物品守恒
5. P2.1 位置字段、NBT 兼容、1 -> 2 迁移
6. P2.2 已加载传送公共路径
7. P2.3 异步 service、ticket、pending、timeout、取消
8. P2.4 命令 / 绒亲录接入、在途反馈、双语文案
9. P2.5 配置、README 边界、CHANGELOG
10. P2.6 实机验证、性能与静态边界审计
```

每个切片独立编译、独立留证；不把 P0 / P1 / P2 混成一次大提交。
