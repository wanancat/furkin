# WP-01：契约确认的服务端权威化

- 工作项：H-01
- 实施日期：2026-09-25
- 基线：`ba3132e`（`mc1.19.2`）
- 状态：已完成
- 提交范围：仅 WP-01；不推送
- 参考：1.20.1 `docs/code_review_2026-09-24/wp-01_contract_authority_design.md`

---

## 1. 根因

`ConfirmContractPacket.handle` 直接信任客户端回传的实体 ID，并在客户端指定的当前主手上调用
`FurkinContractHandler.executeContract(...)`。旧实现没有服务端待确认会话，且执行入口没有完整复检：

- 目标是否为已注册物种；
- 主手是否为 `FurkinContractItem`；
- 目标是否在交互距离内；
- 目标是否仍存活 / 未被移除 / 同维度；
- 目标是否属于其他玩家；
- 活跃绒亲上限；
- 名字长度、控制字符和旧版格式化标记。

因此，改造客户端可跳过第一阶段的右键交互，对同维度任意活体发起并完成契约，甚至绕过物品消耗。

---

## 2. 实施内容

### 2.1 服务端待确认会话

在 `FurkinContractHandler` 中新增服务端内存会话
`Map<UUID, PendingContract>`，仅由服务端线程访问。会话保存：

- 目标 UUID 和运行时实体 ID；
- 发起时维度；
- 发起时世界游戏时间；
- 发起时快捷栏槽位；
- 发起时主手 `ItemStack` 的副本。

`tryContract(...)` 先使同一玩家的旧会话失效，再执行完整前置检查；检查通过后才创建会话并发送命名请求。
新请求会覆盖旧请求，避免旧命名窗口继续有效。

### 2.2 唯一权威确认入口

`ConfirmContractPacket.handle` 不再查找实体、不再调用执行方法，只改为：

```java
FurkinContractHandler.confirmContract(player, packet.entityId, packet.name)
```

`confirmContract(...)` 的执行顺序：

1. 先 `remove` 服务端会话；不存在会话直接丢弃；
2. 校验会话未过期、维度一致、`entityId` 一致；
3. 按会话中的目标 UUID 从当前服务端世界重新取实体；
4. 对目标重跑 `passesContractChecks(...)`；
5. 校验快捷栏槽位未变化、当前主手与发起时 `ItemStack.matches(...)`；
6. 校验名字合法；
7. 通过后才调用私有的 `executeContract(...)`。

`executeContract(...)` 已改为 `private`，且只在所有权威状态提交成功后最后执行 `hand.shrink(1)`。

### 2.3 复检边界

`passesContractChecks(...)` 统一检查：

- 排除玩家目标；
- 目标与玩家在同一 `ServerLevel`；
- 目标存活且未移除；
- `FurkinSpeciesRegistry.isRegisteredEntity(target)`；
- capability 存在且不是已契约状态；
- `TamableAnimal` 已属于其他玩家时拒绝；
- 与玩家的距离不超过 4 格；
- 活跃绒亲上限；
- 当前主手非空且为 `FurkinContractItem`。

会话有效期为 `600 tick`（30 秒），名字最长 32 字符；名字拒绝控制字符和 `§`。

### 2.4 生命周期清理

`CommonEvents.onPlayerLoggedOut(...)` 调用
`FurkinContractHandler.clearPendingContract(...)`，玩家登出后清理待确认会话。
会话过期或再次发起时也会被替换，不依赖登出作为唯一清理路径。

### 2.5 1.19.2 API 差异

1.19.2 没有 1.20.1 的 `Player#canReach(Entity, double)`。本分支按真实 API 使用：

```java
player.distanceToSqr(target) > CONTRACT_MAX_DISTANCE * CONTRACT_MAX_DISTANCE
```

常量 `CONTRACT_MAX_DISTANCE = 4.0D`，保留“右键后命名”期间的少量位移容差。
本差异已通过 1.19.2 mapped official jar 的 `javap` 核实，不以 1.20.1 的存在性推断。

---

## 3. 修改文件

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java`
- `src/main/java/com/wanancat/furkin/internal/network/ConfirmContractPacket.java`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java`

---

## 4. 验证

### 4.1 编译与构建

命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --console=plain
```

结果：

- `compileJava`、`processResources`、`jar`、`reobfJar` 全部执行成功；
- `BUILD SUCCESSFUL in 14s`；
- 一次性夹具删除后的干净构建成功，未留下夹具 class。

### 4.2 专用服务端运行取证

按用户授权加入一次性 `ContractAuthorityFixture`，在 `ServerStartedEvent` 中通过
`ServerLevel` + Forge `FakePlayer` 运行 6 个探针。探针验证完成后已删除夹具源码，日志保留在
`run/logs/latest.log`（该日志不入库）。

运行命令：

```powershell
.\gradlew.bat runServer --console=plain
```

结果（2026-09-25 16:00:23，`run/logs/latest.log`）：

```text
[WP01-FIXTURE] P1 forged-confirm-without-session: PASS (rejected)
[WP01-FIXTURE] P2 normal-two-step: PASS (contracted, item 8 -> 7)
[WP01-FIXTURE] P3 unregistered-species: PASS (rejected)
[WP01-FIXTURE] P4 entity-id-mismatch: PASS (rejected)
[WP01-FIXTURE] P5 hand-swapped: PASS (rejected)
[WP01-FIXTURE] P6 invalid-name: PASS (rejected)
[WP01-FIXTURE] SUMMARY pass=6 fail=0 failed=none
```

探针含义：

- P1：没有服务端会话的伪造确认包被拒绝；
- P2：正常两步流程成功、物品数量 `8 -> 7`；
- P3：未注册物种被拒绝；
- P4：`entityId` 与会话不匹配被拒绝；
- P5：发起后主手被换掉被拒绝；
- P6：名字含 `§` 被拒绝。

同次运行：

- 服务端到达 `Done`；
- `[WP01-FIXTURE] SUMMARY pass=6 fail=0`；
- 未发现项目自身的 `ERROR`、`FATAL` 或异常栈；
- 仅出现环境/上游的 OSHI/WMI 警告。

### 4.3 验证边界

- 已验证：服务端权威校验、会话消费、正常两步流程、关键伪造路径。
- 真实客户端 UI 端到端收发包已由第 7 节完成收口。
- 未执行：多人并发命名；该边界不改变本 WP 的服务端权威结论。

---

## 5. 残余风险与后续约束

- `PENDING_CONTRACTS` 是短生命周期内存态，不持久化；服务端重启后旧命名窗口无法继续确认，这是预期行为。
- 当前距离口径为 4 格，和 1.20.1 语义对齐但由本分支的真实 API 等价实现。
- WP-01 不修改公开 API，不改 `AGENTS.md`，不修改 `.gitignore`。
- H-02、H-03、M-01～M-04、L-01～L-04 仍按工作流后续 WP 处理。

---

## 6. 关闭判据

- `ConfirmContractPacket` 不再直接调用执行入口：已满足；
- 无服务端会话的确认包不能落契约：运行已确认；
- 正常两步流程仍可完成并消耗物品：运行已确认；
- 关键伪造路径均被服务端拒绝：运行已确认；
- 干净构建通过：已满足；
- 临时夹具已移除：已满足。

---

## 7. 最终真实客户端链路（2026-09-25 追加）

真实双端验收已覆盖本 WP 原先的 UI 残余：客户端可见 `ContractNameScreen`，随后发送真实 `ConfirmContractPacket`；服务端记录 `Furkin contracted`，同步包回填 companion UUID。无会话伪造、登记物种、主手、距离、名字等边界仍以本 WP 原先的 6/6 服务端探针为准；本轮额外证明正常两步流程确实能由真实客户端界面触发。

证据：`run/logs/latest.log`（2026-09-25 18:10:22），日志哈希见总工作流第 14 节。
