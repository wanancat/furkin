# WP-01：契约确认服务端权威化设计

- 文档状态：已关闭（2026-09-25）；代码、静态构建、两轮共 26 项服务端夹具和真实客户端正常/取消/同步闭环均通过；生命周期残余已明确记录
- 制定日期：2026-09-24
- 工作分支：`mc1.20.1/dev`
- 审查基线：`66dd99b78357f224c66aafe6faf9aa76a6a8a78e`
- 依据：
  - `docs/code_review_2026-09-24.md` H-01
  - `docs/code_review_2026-09-24_workflow.md` WP-01
  - `docs/contract_precondition_workflow.md` §4.3
- 运行期已由服务端夹具与真实客户端覆盖；无法通过普通界面稳定保留会话的卸载、重启、实体 ID 复用和真实登出链保留为残余，不替代已完成的安全夹具结论。

---

## 1. 已确认的基线问题

当前确认链路为：

1. `CommonEvents` 在服务端拦截实体右键。
2. `FurkinContractHandler.tryContract` 检查注册物种、能力、未契约和当前维度活跃上限。
3. `tryContract` 向客户端发送 `RequestContractNamePacket(entityId)`。
4. `ContractNameScreen` 确认后发送 `ConfirmContractPacket(entityId, name)`。
5. `ConfirmContractPacket.handle` 按玩家当前 `ServerLevel` 和整数实体 ID 取目标。
6. `FurkinContractHandler.executeContract` 只复检能力、未契约和活跃上限，随后写数据、建档、改名和扣物品。

已确认缺口：

- 确认包可以绕过 `tryContract` 直接构造。
- 确认时没有复检注册物种、存活、未移除、维度身份、可达距离和主手物品。
- 没有服务端待确认会话，不能证明客户端确认的是最初请求的目标。
- `readUtf()` 可接收远超客户端 32 字符限制的名字，且没有控制字符校验。
- 同一确认包可重复发送，首次成功后第二次会因 `isCompanion()` 返回，但缺少明确的单次会话语义。
- `FurkinAttachHandler` 把能力挂到所有 `LivingEntity`，因此“有能力”不能代替“注册物种”。

---

## 2. 目标与非目标

### 2.1 目标

- 确认动作的最终权威判定全部由服务端完成。
- 通过短生命周期会话绑定玩家、目标稳定身份、维度和发起时状态。
- 所有校验必须在第一个状态变更之前完成。
- 重放、伪造、竞态、目标死亡、超距、错误主手和跨维度场景不能写持久状态或扣物品。
- 不破坏正常契约、命名、建档、装备封印和能力同步流程。

### 2.2 非目标

- 不在 WP-01 中修复跨维度档案分裂 H-03。
- 不在 WP-01 中增加契约血量门槛；该功能属于 `contract_precondition_workflow.md`。
- 不在 WP-01 中重做战斗 AI 或解绑流程 H-02 / M-03。
- 不在 WP-01 中顺带处理协议版本 M-01；只有包字段或编码发生变化时才提前处理。
- 不改变契约物品、配方、成长或技能数据格式。

---

## 3. 推荐设计

### 3.1 服务端会话

在 `FurkinContractHandler` 内维护服务端线程专用的待确认会话表：

```text
Map<UUID, PendingContract>
```

`UUID` 为发起玩家 UUID。每名玩家的一次新请求覆盖其旧请求，不需要协议 token。

`PendingContract` 建议包含：

- `UUID targetUuid`：目标稳定身份。
- `int targetEntityId`：发起时的实体 ID，用于拒绝过期客户端请求。
- `ResourceKey<Level> dimension`：发起维度。
- `long issuedAtGameTime`：使用 `ServerLevel#getGameTime()` 记录。
- `int selectedSlot`：发起时的主手快捷栏槽位。
- `ItemStack expectedHand`：发起时主手物品的副本。
- `long expiresAtGameTime`：建议 TTL 为 600 tick（30 秒）。

会话只存在于服务端内存，不写入实体或存档。服务器重启后自然失效。

### 3.2 会话生命周期

| 事件 | 处理 |
|---|---|
| `tryContract` 全部前置通过 | 写入或覆盖该玩家的会话 |
| 收到确认包 | 先原子取走并删除会话，再校验和执行，保证单次确认 |
| 会话超时 | 拒绝并删除 |
| 玩家登出 | 删除该玩家会话 |
| 目标死亡、卸载或换维度 | 确认时重新查找失败则拒绝 |
| 客户端取消命名 | 不发包；服务端记录保留到 TTL 或下一次请求覆盖，不产生持久影响 |
| 确认失败 | 会话已经消费，不能原包重试；玩家需重新右键发起 |

选择“按玩家覆盖”而不是“协议 token”的原因：

- 服务端本来就是命名窗口的发起方，可以可靠保存目标。
- 客户端只能提交玩家自己的确认，不能伪造其他玩家的 `ctx.getSender()`。
- 即使改造客户端提前发送确认，仍需通过当前会话和全部权威复检。
- 不改变 `ConfirmContractPacket` 字段，不需要为 WP-01 单纯递增 `PROTOCOL_VERSION`。

### 3.3 确认处理入口

建议把网络包与业务执行解耦：

- `ConfirmContractPacket.handle` 只负责：
  - 获取 `ServerPlayer`；
  - 在服务端线程调用 `FurkinContractHandler.confirmContract(player, packet.entityId, packet.name)`；
  - 标记包已处理。
- `FurkinContractHandler` 新增公开的 `confirmContract` 作为唯一确认入口。
- 原 `executeContract` 改为私有实现，只能由已经通过会话和权威校验的 `confirmContract` 调用。
- 不再由网络包直接执行 `player.getMainHandItem()` 并传入执行函数。

---

## 4. 确认校验顺序

所有步骤按以下顺序执行，任一失败都不写能力、不建档、不设名字、不扣物品、不发同步包。

| 顺序 | 校验 | 失败处理 |
|---:|---|---|
| 1 | 会话存在且未超时 | 删除过期会话，安静拒绝 |
| 2 | 玩家当前维度等于会话维度 | 删除会话，拒绝 |
| 3 | 包内 `entityId` 等于会话记录的实体 ID | 删除会话，拒绝 |
| 4 | 当前 `ServerLevel.getEntity(targetUuid)` 找到目标 | 拒绝 |
| 5 | 目标仍是同一实体 UUID | 拒绝 |
| 6 | 目标是 `LivingEntity`、`isAlive()` 且 `!isRemoved()` | 拒绝 |
| 7 | 目标类型仍在 `FurkinSpeciesRegistry` 中 | 拒绝 |
| 8 | 能力存在且 `!data.isCompanion()` | 拒绝 |
| 9 | 目标归属规则通过 | 拒绝 |
| 10 | 当前活跃数量小于上限 | 使用现有 `furkin.msg.active_limit` 提示并拒绝 |
| 11 | 主手快捷栏槽位未变化 | 拒绝 |
| 12 | 当前主手与 `expectedHand` 仍匹配，且仍是 `FurkinContractItem` | 拒绝 |
| 13 | `player.canReach(target, 3.0D)` 通过 | 拒绝 |
| 14 | 名字合法 | 拒绝 |
| 15 | 全部通过 | 执行原契约落盘流程并消耗当前主手一张 |

### 4.1 距离 API

使用 Forge 提供的 `IForgePlayer#canReach(Entity, double)`，通过 `ServerPlayer` 调用：

```java
player.canReach(target, 3.0D)
```

1.20.1 / Forge 47.2.0 服务端实体交互补丁使用相同的 `3.0` padding。该方法由 `IForgePlayer` 默认实现提供，不是 `ServerPlayer` 自身声明的实例方法。

### 4.2 主手语义

推荐采用严格语义：

- 发起时记录快捷栏槽位和完整 `ItemStack` 副本。
- 确认时要求槽位未变化，并用 `ItemStack.matches(current, expectedHand)` 检查内容与数量。
- 这样不会在主手变成空手、普通物品、别的契约堆叠时免费契约或消耗错误物品。
- 若产品希望允许换成任意一叠契约物品，需要在实施前明确放宽并调整测试；默认不采用该语义。

### 4.3 名字语义

- 空串允许，继续回退物种名。
- 服务端先 `trim()`，再要求长度不超过 32。
- 拒绝 `Character.isISOControl` 控制的字符和换行。
- 建议同时拒绝旧版格式标记 `§`，避免名字注入颜色或格式。
- 不信任客户端 `EditBox#setMaxLength(32)`，服务端必须独立判定。

### 4.4 归属语义

推荐规则：

- 若目标是 `TamableAnimal` 且已有 owner，只有 owner 与发起玩家一致时才允许契约。
- 无 owner 或 owner 为发起玩家时允许。
- 其他玩家拥有的宠物拒绝契约，避免静默转移所有权。
- 规则必须在 `tryContract` 和 `confirmContract` 两阶段检查，覆盖命名期间发生归属变化的情况。

### 4.5 失败反馈

- 伪造、无会话、过期、身份不匹配、名字非法等攻击性请求安静拒绝，建议仅 `DEBUG` 日志。
- 活跃上限继续使用现有 action bar 提示。
- WP-01 不新增通用错误提示键，避免把本地化和错误分类扩大成第二个工作包。
- 正常玩家因目标死亡、超距或更换主手而失败时当前保持安静；如需友好提示，后续单独设计并同步中英文语言文件。

---

## 5. 建议改动文件

| 文件 | 计划改动 |
|---|---|
| `internal/network/ConfirmContractPacket.java` | 移除直接取实体和调用 `executeContract`，改为委托 `confirmContract` |
| `internal/contract/FurkinContractHandler.java` | 增加会话表、会话类、`confirmContract`、完整校验、名字校验和单一执行入口 |
| `internal/event/CommonEvents.java` | 增加 `PlayerLoggedOutEvent` 清理该玩家待确认会话 |
| `assets/furkin/lang/en_us.json` | WP-01 默认不新增键；若最终决定增加失败反馈再同步 |
| `assets/furkin/lang/zh_cn.json` | 同上 |
| `docs/contract_precondition_workflow.md` | 实施后把“确认包可伪造”的现状说明更新为已实现边界；血量门槛状态仍保持设计态 |

无新增注册项、无存档格式变化、无公开 API 变化。

---

## 6. 协议与版本影响

- 推荐方案不改变 `ConfirmContractPacket` 的字段、顺序和方向。
- 服务端校验变严不影响旧客户端正常流程，因此 WP-01 本身不要求递增 `PROTOCOL_VERSION`。
- M-01 仍按 WP-04 处理，单独审计历史包结构变化。
- 如果最终选择在包内增加会话 token 或目标 UUID，则协议不兼容，必须把 M-01 并入 WP-01：
  - 修改 encode/decode；
  - 更新客户端发送端和服务端接收端；
  - 递增 `PROTOCOL_VERSION`；
  - 完成双端验证。

---

## 7. 测试矩阵

### 7.1 正常流程

- [x] 右键注册物种，命名并确认，契约成功。
- [x] 名字留空，仍回退物种默认名。
- [x] 正常只消耗一张契约物品。
- [x] 能力和档案只写入一次。
- [x] 客户端同步正常：留空命名确认后契约物品 `-1`，实体头顶图标和绒亲录档案同步出现。

### 7.2 伪造与重放

- [x] 不触发 `tryContract`，直接伪造确认调用，拒绝。
- [x] 未注册实体伪造确认，拒绝。
- [x] 对玩家实体伪造确认，拒绝。
- [x] 实体 ID 与会话不一致，拒绝。
- [x] 会话过期后确认，拒绝。
- [x] 同一确认包连续发送，第二次因会话已消费而拒绝。
- [x] 同一玩家重新发起后，旧实体 ID 的确认不能命中新会话。
- [x] 两名玩家先后确认同一目标，最多一人成功。

### 7.3 目标竞态

- [x] 命名期间目标死亡，确认拒绝。
- [x] 命名期间目标被移除，确认拒绝。
- [x] 命名期间目标被其他路径契约，确认拒绝。
- [ ] 命名期间目标卸载后重载，确认拒绝。（残余：普通客户端命名界面无法在保持同一会话时稳定卸载并重载目标；移除语义已由服务端夹具覆盖。）
- [x] 命名期间目标被其他玩家驯服/转交，按归属规则拒绝。
- [x] 命名期间活跃上限被占满，确认拒绝并保持物品、档案不变。

### 7.4 手持与距离

- [x] 发起后主手换成空手，确认拒绝。
- [x] 发起后主手换成普通物品，确认拒绝且普通物品不消耗。
- [x] 发起后主手切换到其他快捷栏槽位，确认拒绝。
- [x] 发起后移走或改变契约堆叠数量，确认拒绝。
- [x] 命名期间玩家离开 `canReach(target, 3.0D)` 范围，确认拒绝。

### 7.5 名字

- [x] 32 字符以内的普通名字成功。
- [x] 超过 32 字符的名字被服务端拒绝。
- [x] 换行和控制字符被拒绝。
- [x] `§` 格式标记按最终决策拒绝或明确允许。
- [x] 名字失败时能力、档案、物品和实体自定义名均不变。

### 7.6 生命周期

- [x] 登出清理入口会清除待确认会话。
- [ ] 服务器重启后旧会话不存在，确认包不能恢复执行。（残余：会话为纯内存表，真实旧包注入需专用客户端/夹具；当前由实现与夹具证据支持。）
- [x] 新请求覆盖旧请求，不产生多个有效目标。
- [x] 契约成功后会话被消费，不残留。

---

## 8. 验证命令

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

用户已确认 EULA；`runServer` 启动和真实 `runClient` 正常/取消/同步闭环已完成，业务场景矩阵见 §7。

---

## 9. 已确认实施方案

本次实施按以下默认方案执行：

1. 使用按玩家 UUID 覆盖的服务端会话，不增加协议 token。
2. TTL 使用 600 tick。
3. 主手要求同一快捷栏槽位且 `ItemStack.matches`。
4. 其他玩家已拥有的 `TamableAnimal` 拒绝契约。
5. 名字最长 32 字符，拒绝控制字符和 `§`。
6. WP-01 不新增通用失败提示，不处理血量门槛，不同步 M-01。

以上六项已用于本工作包实现；后续若调整语义，需要重新评审和验证。

## 10. 实施记录

### 2026-09-24

已修改：

- `internal/contract/FurkinContractHandler.java`
  - 增加按玩家 UUID 覆盖的服务端待确认会话。
  - 增加 600 tick TTL、目标 UUID、实体 ID、维度、快捷栏槽位和主手快照绑定。
  - 增加 `confirmContract` 唯一确认入口和完整服务端复检。
  - 在请求与确认共用校验中加入 `player.canReach(target, 3.0D)`。
  - `executeContract` 改为私有，只接受已通过权威校验的参数。
- `internal/network/ConfirmContractPacket.java`
  - 移除直接按实体 ID 取目标和直接执行契约的逻辑。
  - 改为委托 `FurkinContractHandler.confirmContract`。
- `internal/event/CommonEvents.java`
  - 玩家登出时清理待确认契约会话。

未修改：

- `PROTOCOL_VERSION` 未变化，因为包字段、顺序和方向没有变化。
- 未实现契约血量门槛。
- 未处理 H-02、H-03、M-01 至 M-04、L-01、L-02。

已执行：

```powershell
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
```

结果：

- `compileJava`：`BUILD SUCCESSFUL`
- `build`：`BUILD SUCCESSFUL`
- 构建产物：`build/libs/furkin-1.20.1-0.0.1.1.jar`

运行启动验证：

- 首次 `runServer` 到达 EULA 确认点，生成 `run/eula.txt`；用户本人将 `eula` 设为 `true`。
- 第二次 `runServer`：`Done (6.836s)!`
- `runClient`：进入渲染主循环，音频、纹理和资源加载完成。
- 第二次服务端启动和客户端启动日志均无 `ERROR`、`FATAL`、Java 异常栈或 `NoClassDefFoundError`。
- 首次服务端启动产生过一次 `server.properties` 缺失 `ERROR`，该文件已由首次启动生成；第二次启动复检未复现。

后续验证：

- 规范中的主要服务端权威场景已通过临时夹具复验，详见下节。
- 真实客户端交互和网络包路径仍需后续实机矩阵覆盖，不能用启动成功替代。

### 服务端夹具与最终复验（2026-09-24）

#### 第一轮：14 项基础权威测试

- 用户手工验证：正常契约成功；命名期间目标死亡后确认未新增契约；等待超过 35 秒后确认未新增契约。
- 临时服务端夹具（FakePlayer）执行 14 项用例，结果：`passes=14 failures=0`。
- 覆盖：正常契约、无会话伪造、错误实体 ID、切换主手、重放、超长名称、控制字符、`§`、其他主人、玩家目标、未注册目标、新请求覆盖旧会话、两名玩家竞态、跨维度确认。

#### 第二轮：12 项补充矩阵

- 补充夹具执行 12 项用例，覆盖空名称回退、精确消耗一张、成功后重放不重复写入、目标移除、已被其他路径契约、被其他玩家驯服、活跃上限、空手、普通物品、堆叠数量变化、超出可达距离和登出清理。
- 首轮补充测试结果：`passes=11 failures=1`；唯一失败为 `out_of_reach_rejected`，确认实现缺少距离校验。
- 修复：在请求与确认共用的 `passesContractChecks` 中加入 `player.canReach(target, 3.0D)`。该值与原版服务端实体交互路径一致；`3.0D` 是 Forge 的 padding，不是 3 格硬上限。
- 修复后复测：`passes=12 failures=0`。
- 两轮 `runServer` 日志均未出现 `ERROR`、`FATAL`、Java 异常栈、`NoClassDefFoundError` 或夹具崩溃；仅出现 2 条与模组无关的 Windows 性能计数器 `WARN`。

临时夹具直接调用服务端业务入口并使用 FakePlayer，未覆盖真实网络包编解码、客户端命名界面和客户端同步确认。以下边界在服务端夹具阶段仍属于待验证项，后续客户端覆盖情况见 §11：

- 客户端状态只同步一次。
- 目标卸载后重载、实体 ID 复用或同位置重建。
- 真实客户端网络包路径、取消命名流程和界面交互。
- 服务器重启后的会话失效，以及真实玩家登出事件链。

临时夹具已删除；删除后重新执行 `compileJava` 和 `build`，结果均为 `BUILD SUCCESSFUL`。最终产物为 `build/libs/furkin-1.20.1-0.0.1.1.jar`（293588 字节），经 JAR 内容检查不包含 `ContractAuthority` 或 `internal/debug` 测试类。WP-01 的服务端核心实现与主要权威场景已闭环；当时真实客户端、网络包、卸载重载和重启生命周期尚未实测，后续关闭记录见 §11。

## 11. 关闭记录（2026-09-25）

### 真实客户端最小闭环

- `runClient` 于 2026-09-25 启动成功，进入集成服务端测试世界。
- 正常流程：对未契约 Dog 右键，命名保持空白并确认。
- 结果：契约物品只减少 1 张，实体头顶绒亲状态图标出现，绒亲录可见新建档案。
- 取消流程：对另一只未契约动物打开命名界面后取消，未消耗物品、未新增档案、无绒亲图标。
- 日志证据：`13:11:24.320` 出现 1 条 `Furkin contracted: Dog (id=0a9d715e-34b9-4a5c-9af4-2ecb5e41fc8d) by Dev`。
- 日志检查：本轮无 `ERROR`、`FATAL`、Java 异常栈、`NoClassDefFoundError` 或网络处理错误。

### 残余与关闭结论

- 目标卸载后重载、实体 ID 复用或同位置重建、服务器重启后的旧确认包、真实 `PlayerLoggedOutEvent` 网络事件链仍无法仅靠普通客户端界面稳定复现。
- 上述残余的服务端安全语义已由两轮 26 项夹具覆盖；真实正常网络包编解码、命名界面、取消流程和客户端同步已由本轮实测覆盖。
- H-01 于 2026-09-25 关闭。残余项作为后续协议故障注入/生命周期测试债务记录，不改写为“已实际执行”。
