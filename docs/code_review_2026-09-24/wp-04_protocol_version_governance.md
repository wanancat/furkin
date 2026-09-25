# Furkin 1.20.1 WP-04 网络协议版本治理审计

- 文档状态：已完成（2026-09-25）；包清单、版本策略、真实双版本混连拒绝和同版本登录验证均已完成
- 审计日期：2026-09-25
- 基线提交：`7de91ba`（`fix: 统一跨维度绒亲档案`）
- 对应问题：`M-01` 网络协议版本未随包结构变化递增
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0

> 本文件记录 WP-04 的治理规则、包清单、版本历史和真实双端验证；M-01 已关闭。

---

## 1. 当前协议入口

当前协议版本定义在：

`src/main/java/com/wanancat/furkin/internal/network/FurkinNetwork.java:26`

```java
private static final String PROTOCOL_VERSION = "2";
```

通道使用 Forge `NetworkRegistry.newSimpleChannel(...)`，客户端和服务端的版本谓词都是：

```java
PROTOCOL_VERSION::equals
```

Forge 源码语义（`forge-1.20.1-47.2.0-sources.jar`）：

- `clientAcceptedVersions` 收到服务端发送的协议版本。
- `serverAcceptedVersions` 收到客户端发送的协议版本。
- 当前代码使用精确相等谓词，因此 `"1"` 与 `"2"` 不会被认为兼容。

---

## 2. 当前包清单

注册顺序即消息 ID，当前共 11 个包。

| ID | 包 | 方向 | 当前载荷 | 处理位置 |
|---:|---|---|---|---|
| 0 | `SyncFurkinDataPacket` | 服务端 → 客户端 | `int entityId`、`CompoundTag data`（可为 null） | 客户端写回实体本地 capability |
| 1 | `RecordListPacket` | 服务端 → 客户端 | `boolean openScreen`、条目列表：`UUID`、物种名、等级、经验、技能点、名字、已召唤、存活、战斗模式、属性行列表 | 客户端打开/刷新绒亲录 |
| 2 | `RequestSummonPacket` | 客户端 → 服务端 | `UUID companionId` | 服务端统一召唤/传送 |
| 3 | `RequestContractNamePacket` | 服务端 → 客户端 | `int entityId` | 客户端打开命名界面 |
| 4 | `ConfirmContractPacket` | 客户端 → 服务端 | `int entityId`、`String name` | 服务端待确认契约会话复核后落契约 |
| 5 | `RecordActionPacket` | 客户端 → 服务端 | `Action`、`UUID`、`String name`、可空战斗模式序号、`boolean refreshRecord` | 服务端执行收回/解绑/改名/重获魂石/战斗模式 |
| 6 | `UnlockSkillPacket` | 客户端 → 服务端 | `UUID companionId`、`ResourceLocation skillId` | 服务端技能解锁 |
| 7 | `ResetSkillsPacket` | 客户端 → 服务端 | `UUID companionId` | 服务端洗点 |
| 8 | `SelectTabPacket` | 客户端 → 服务端 | `int tab` | 服务端记录当前页签 |
| 9 | `OpenFurkinScreenPacket` | 服务端 → 客户端 | `UUID`、名字、技能点、技能视图列表、属性加成列表、服务端属性总值列表 | 客户端打开技能面板 |
| 10 | `RecordActionResultPacket` | 服务端 → 客户端 | `UUID`、`Action`、`Result`、`boolean forceUnbindAllowed` | 客户端处理动作结果与强制解绑确认 |

包清单中的枚举字段使用 `writeEnum/readEnum`，也就是依赖枚举声明顺序。

---

## 3. 版本历史

### 3.1 协议版本 1

`0ce459c` 首次加入通道时，协议版本为 `"1"`，当时只有：

- ID 0：`SyncFurkinDataPacket`

随后以下变更都仍使用 `"1"`：

| 提交 | 变更 | 兼容性影响 |
|---|---|---|
| `e09ddc8` | 新增 ID 1-4：绒亲录、召唤、契约命名、契约确认 | 新增消息 ID |
| `3a7692d` | 新增 ID 5：`RecordActionPacket` | 新增消息 ID；后续字段和枚举持续变化 |
| `c05a255` | 新增 ID 6：`UnlockSkillPacket` | 新增消息 ID |
| `0c502f2` | 新增 ID 7-8：`ResetSkillsPacket`、`OpenFurkinScreenPacket` | 新增消息 ID |
| `84981bd` | 在 `OpenFurkinScreenPacket` 前插入 `SelectTabPacket` | `OpenFurkinScreenPacket` 的 ID 从 8 变为 9，属于不兼容变化 |
| 后续 M2/M3/M4 开发 | `RecordActionPacket` 新增战斗模式、刷新标志；`RecordListPacket` 新增属性和战斗模式；面板包扩展字段 | 同一 ID 的字段布局变化 |
| `77c1012` 变更前 | `RecordActionPacket.Action` 增加 `FORCE_UNBIND`，改变后续枚举序号 | 同一 ID 的枚举语义变化 |

结论：历史上所有 `"1"` 版本彼此并不等价。两个都声明 `"1"` 但包结构不同的构建可能通过版本检查。

### 3.2 协议版本 2

`77c1012` 同时完成：

- 将 `PROTOCOL_VERSION` 从 `"1"` 提升为 `"2"`。
- 新增 ID 10：`RecordActionResultPacket`。
- 扩展 `RecordActionPacket` 的 `FORCE_UNBIND` 动作和结果回执链路。

`7de91ba`（WP-03 全局档案）没有修改网络包字段、顺序、方向或消息 ID，因此当前包清单与 `"2"` 对应。

---

## 4. 实施与验证结果

### 4.1 已固化的规则

`FurkinNetwork` 已保留：

```java
private static final String PROTOCOL_VERSION = "2";
```

并在常量上补充长期规则：

- 消息 ID 只允许追加，不插入、不重排、不复用。
- 新增/删除包、修改方向、字段或字段顺序、枚举顺序或处理器语义时，必须递增协议版本。
- 只改注释、日志或不改变线格式的服务端内部校验时，不递增。
- 协议 `1` 视为历史废弃；协议 `2` 对应当前 `0-10` 包结构。

### 4.2 验证结果

已执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

结果：

- `compileJava`：`BUILD SUCCESSFUL`
- `build`：`BUILD SUCCESSFUL`
- `runServer`：成功启动并输出 `Done`；`latest.log` 无 `ERROR`/`FATAL`。验证完成后主动终止测试进程，因此 Gradle 任务以非零退出码结束，这不是游戏启动失败。
- `runClient`：成功进入客户端主界面；`latest.log` 无 `ERROR`/`FATAL`。验证完成后主动终止测试进程，因此 Gradle 任务以非零退出码结束。
- 未使用临时夹具，`run/` 与 `build/` 未纳入提交。

### 4.3 混连拒绝结论

Forge `NetworkInstance` 的实现是：

- `tryServerVersionOnClient` 调用 `clientAcceptedVersions`；
- `tryClientVersionOnServer` 调用 `serverAcceptedVersions`；
- 当前两个谓词都是 `PROTOCOL_VERSION::equals`，因此 `"1"` 与 `"2"` 不相等，不能通过版本协商。

### 4.3.1 真实混连验证（2026-09-25）

测试组合：

- 协议 `1` 客户端：独立工作树检出 `184e82e`，`PROTOCOL_VERSION = "1"`。
- 协议 `2` 服务端：当前 `55f6b19`，专用服务器监听 `25565`。
- 连接地址使用 `127.0.0.1:25565`。此前 `localhost` 解析到链路本地 IPv6 地址并出现超时，改用 IPv4 回环后握手立即完成；该现象属于测试环境地址选择，不是模组协议行为。

结果：

- 客户端界面显示 `Connection closed - mismatched mod channel list`。
- 客户端 `debug.log` 记录 `Channel 'furkin:main' : Version test of '2' from server : REJECTED`。
- 客户端随后记录 `Channels [furkin:main] rejected their server side version number` 和 `Terminating connection with server, mismatched mod list`。
- 服务端未进入游戏，连接在登录握手阶段断开。

### 4.3.2 相同协议版本登录验证（2026-09-25）

测试组合：

- 协议 `2` 客户端：独立工作树检出 `55f6b19`。
- 协议 `2` 服务端：当前 `55f6b19`。

结果：

- 客户端 `debug.log` 记录 `Channel 'furkin:main' : Version test of '2' from server : ACCEPTED`，随后记录 `Accepted server connection`。
- 服务端 `debug.log` 记录 `Channel 'furkin:main' : Version test of '2' from client : ACCEPTED`，随后记录 `Accepted client connection mod list`。
- 服务端 `latest.log` 记录 `Dev joined the game`，确认客户端完成登录并进入世界。
- 两端 `latest.log` 均未发现 `ERROR`、`FATAL`、Java 异常栈或协议处理错误；仅有既有的 Forge、OSHI、Realms 等环境警告。

### 4.4 审计结论

- M-01 的代码边界已由 `"2"` 建立，历史 `"1"` 版本统一视为不兼容。
- 版本递增规则已经进入代码注释、项目级 `AGENTS.md` 和本审计文档。
- 当前无线格式变更，因此没有为了 WP-04 单独提升到 `"3"`。
- 协议 `1` 客户端连接协议 `2` 服务端的真实握手拒绝已复现，并由客户端日志、服务端日志和界面提示三层证据确认。

---

## 5. 关闭结论

WP-04 已于 2026-09-25 关闭，关闭条件均满足：

- 当前包清单与协议 `2` 的映射明确。
- 后续协议变更规则已固化在 `FurkinNetwork`、项目级 `AGENTS.md` 和本审计文档。
- 同源码双端启动验证通过。
- 协议 `1` 客户端连接协议 `2` 服务端时，Forge 精确版本谓词返回 `REJECTED`，界面显示 `mismatched mod channel list`，未进入游戏。
- 协议 `2` 客户端连接协议 `2` 服务端时，双方通道 `furkin:main` 版本 `2` 均为 `ACCEPTED`，并成功进入世界。
- 测试使用独立工作树，未污染当前分支；测试工作树和运行目录已清理。
