# Furkin 1.20.1 WP-04 网络协议版本治理审计

- 文档状态：包清单、版本策略和回归验证完成；真实双版本混连拒绝实测保留为残余验证
- 审计日期：2026-09-25
- 基线提交：`7de91ba`（`fix: 统一跨维度绒亲档案`）
- 对应问题：`M-01` 网络协议版本未随包结构变化递增
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0

> 本文件记录 WP-04 的治理规则、包清单、版本历史和已完成验证；真实双版本混连拒绝仍作为残余验证保留。

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

本次没有构造两个不同版本模组并进行真实客户端/服务端握手测试，因此该部分只能记为接口语义核对，不能记为真实混连实测。

### 4.4 审计结论

- M-01 的代码边界已由 `"2"` 建立，历史 `"1"` 版本统一视为不兼容。
- 版本递增规则已经进入代码注释、项目级 `AGENTS.md` 和本审计文档。
- 当前无线格式变更，因此没有为了 WP-04 单独提升到 `"3"`。
- 真实双版本混连拒绝验证未执行，作为残余验证保留。

---

## 5. 关闭边界

WP-04 可以在以下边界内关闭：

- 当前包清单与 `"2"` 的映射明确。
- 后续协议变更规则已固化。
- 同源码双端启动验证通过。
- Forge 精确版本谓词提供不匹配拒绝路径。

仍未完成、不得伪造为已执行的残余项：

- 使用真实 `"1"` 客户端连接 `"2"` 服务端并确认握手拒绝。
- 使用真实 `"2"` 客户端与 `"2"` 服务端完成一次登录并进入游戏。
