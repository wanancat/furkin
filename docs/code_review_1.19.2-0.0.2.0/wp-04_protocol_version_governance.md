# WP-04：网络协议版本治理（M-01）

- 工作项：M-01
- 实施日期：2026-09-25
- 基线：`ca3b85b`（WP-09 已完成代码与专项服务端验证）
- 状态：代码、静态映射、专用服务端启动验证和真实同版本双端握手均已完成；协议 `"2"` 包边界已收口
- 适用版本：Minecraft 1.19.2 / Forge 43.2.0

---

## 1. 根因

`FurkinNetwork.PROTOCOL_VERSION` 长期为 `"1"`，但历史过程中网络包已经发生多次不兼容变化：

- 新增消息 ID；
- 在已有包之前插入新包，导致后续消息 ID 变化；
- 同一消息 ID 的字段布局变化；
- `RecordActionPacket.Action` 枚举追加 `FORCE_UNBIND`，改变后续枚举序号；
- WP-09 新增 ID 10 的 `RecordActionResultPacket`。

两个都声明 `"1"` 但包结构不同的构建可能通过 Forge 的版本相等检查，客户端和服务端随后按错误布局解码。WP-09 已改变 `RecordActionPacket` 语义并新增回执包，因此必须建立明确的版本边界。

---

## 2. 当前协议入口

`src/main/java/com/wanancat/furkin/internal/network/FurkinNetwork.java`：

```java
private static final String PROTOCOL_VERSION = "2";
```

通道仍使用 `NetworkRegistry.newSimpleChannel(...)`，客户端和服务端的版本谓词均为精确相等：

```java
PROTOCOL_VERSION::equals
```

因此协议 `"1"` 与 `"2"` 不兼容；Forge 在登录握手阶段拒绝版本不匹配的一端。

---

## 3. 协议 `"2"` 包清单

注册顺序即消息 ID。当前共 11 个包，ID `0-10`：

| ID | 包 | 方向 | 当前载荷 |
|---:|---|---|---|
| 0 | `SyncFurkinDataPacket` | 服务端 → 客户端 | `int entityId`、`CompoundTag data` |
| 1 | `RecordListPacket` | 服务端 → 客户端 | `boolean openScreen`、条目列表（UUID、物种名、等级、经验、技能点、名字、已召唤、存活、战斗模式、属性行） |
| 2 | `RequestSummonPacket` | 客户端 → 服务端 | `UUID companionId` |
| 3 | `RequestContractNamePacket` | 服务端 → 客户端 | `int entityId` |
| 4 | `ConfirmContractPacket` | 客户端 → 服务端 | `int entityId`、`String name` |
| 5 | `RecordActionPacket` | 客户端 → 服务端 | `Action`、`UUID`、`String name`、可空战斗模式序号、`boolean refreshRecord` |
| 6 | `UnlockSkillPacket` | 客户端 → 服务端 | `UUID companionId`、`ResourceLocation skillId` |
| 7 | `ResetSkillsPacket` | 客户端 → 服务端 | `UUID companionId` |
| 8 | `SelectTabPacket` | 客户端 → 服务端 | `int tab` |
| 9 | `OpenFurkinScreenPacket` | 服务端 → 客户端 | `UUID`、名字、技能点、技能视图列表、属性加成列表、服务端属性总值列表 |
| 10 | `RecordActionResultPacket` | 服务端 → 客户端 | `UUID`、`Action`、`Result`、`boolean forceUnbindAllowed` |

`RecordActionPacket` 和 `RecordActionResultPacket` 中的枚举使用 `writeEnum/readEnum`，因此枚举声明顺序也属于线格式。

---

## 4. 版本历史

### 4.1 协议 `"1"`

`0ce459c` 首次加入通道时只有：

- ID 0：`SyncFurkinDataPacket`。

随后下列变化仍错误地沿用 `"1"`：

| 提交 | 变化 |
|---|---|
| `e09ddc8` | 新增 ID 1-4：绒亲录、召唤、契约命名、契约确认 |
| `3a7692d` | 新增 ID 5：`RecordActionPacket` |
| `c05a255` | 新增 ID 6：`UnlockSkillPacket` |
| `0c502f2` | 新增 ID 7-8：`ResetSkillsPacket`、`OpenFurkinScreenPacket` |
| `84981bd` | 在 `OpenFurkinScreenPacket` 前插入 `SelectTabPacket`，原包 ID 后移 |
| 后续 M2/M3/M4 | 同一 ID 增加字段、枚举和处理器语义 |
| WP-09（`ca3b85b`） | `RecordActionPacket.Action` 增加 `FORCE_UNBIND`，新增 ID 10：`RecordActionResultPacket` |

因此协议 `"1"` 的所有历史构建并不等价，不能作为兼容版本继续使用。

### 4.2 协议 `"2"`

从 WP-04 起，协议 `"2"` 明确对应：

- ID `0-10`；
- 上表载荷布局；
- `RecordActionPacket.Action` 含 `FORCE_UNBIND`；
- 服务端 → 客户端回执 `RecordActionResultPacket` 已注册。

后续规则：

1. 消息 ID 只允许追加，不得插入、重排或复用。
2. 新增/删除包、修改方向、字段或字段顺序、枚举顺序、处理器语义时，必须递增协议版本。
3. 只改注释、日志或服务端内部校验且不改变线格式时，不递增。
4. 协议 `"1"` 视为历史废弃版本，不提供适配层。
5. 在 WP-05 等后续工作包中，如果只新增不进入网络包的内部持久字段，则协议保持 `"2"`；一旦改动线格式，必须同批升版本。

---

## 5. 验证

### 5.1 静态验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
.\gradlew.bat compileJava --console=plain
```

结果：`BUILD SUCCESSFUL`。

已静态确认：

- `FurkinNetwork` 中只有一处 `PROTOCOL_VERSION`；
- 注册顺序与上表一致，ID `0-10` 连续且没有复用；
- `RecordActionResultPacket` 位于末尾，不改变既有 ID；
- `RecordActionPacket.Action` 的追加项位于 `UNBIND` 之后，属于已由协议 `"2"` 覆盖的不兼容变化；
- 没有把 UUID / 维度定位字段加入网络同步包；WP-09 只改变动作枚举和结果通道。

### 5.2 专用服务端启动

```powershell
.\gradlew.bat runServer --console=plain
```

结果：服务器到达 `Done (2.730s)! For help, type "help"`；随后人工正常停止。`run/logs/latest.log` 无项目 `ERROR` / `FATAL` / 类加载异常 / 注册失败；仅有 OSHI/WMI 等环境警告。

### 5.3 真实双端握手

同版本客户端 → 服务端握手已由第 7 节完成真实网络路径验证。对应的 Forge 语义是：

- `clientAcceptedVersions` 收到服务端版本；
- `serverAcceptedVersions` 收到客户端版本；
- 两边均为 `PROTOCOL_VERSION::equals`，因此 `"1"` 与 `"2"` 必然拒绝。

本轮没有为了制造混连而在正式工作树留下旧版本构建；若后续最终验收需要，可用一次性独立构建或临时工作树复现，证据不得回写成本轮已执行。

---

## 6. 关闭状态

协议 `"2"` 与当前 `0-10` 包结构已绑定，M-01 的代码边界已建立。WP-04 的协议文档、静态映射、专用服务端启动验证和真实同版本双端握手均已收口。

---

## 7. 最终真实双端握手（2026-09-25 追加）

真实客户端连接本地真实服务端后，客户端记录：

```text
[ACCEPT-WP04] client joined server with protocol 2
```

`run/logs/debug.log` 同时记录：

```text
Channel 'furkin:main' : Version test of '2' from server : ACCEPTED
```

因此同版本 `"2"` 双端握手已在真实网络路径确认。协议 `"1"` 的拒绝仍由 `PROTOCOL_VERSION::equals` 的精确相等语义保证；本轮没有为制造混连保留旧版本产物。
