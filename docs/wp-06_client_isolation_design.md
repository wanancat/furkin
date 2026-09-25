# WP-06 客户端类隔离设计

- 文档状态：已完成；静态、服务端和客户端烟测通过；强制解绑特有分支未能实机复现
- 审计日期：2026-09-25
- 基线提交：`6f11f68`（`fix: 修正技能热重载效果残留`）
- 对应问题：`L-01` 共享网络包直接引用客户端类，专用服务端隔离缺少充分证据
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0

---

## 1. 现状与审计结果

### 1.1 共享源码引用

对 `src/main/java` 检索 `net.minecraft.client.*`、`net.minecraftforge.client.*`、`com.mojang.blaze3d.*`，以及非客户端包对 `internal.client` 的反向引用。除 `internal.client` 自身外，共发现 5 处：

| 文件 | 引用 | 当前用途 | 风险 |
|---|---|---|---|
| `internal/network/SyncFurkinDataPacket.java` | `net.minecraft.client.Minecraft`、`Entity`、`FurkinCapability` | 客户端按实体 ID 定位实体并写回能力数据 | 共享包直接解析客户端类；专用服务端是否加载客户端类缺少证据 |
| `internal/network/OpenFurkinScreenPacket.java` | `internal.client.FurkinPanelScreen` | `DistExecutor` 回调中把技能快照交给面板 | 客户端界面类型留在共享包 |
| `internal/network/RecordActionResultPacket.java` | `internal.client.FurkinRecordScreen` | 回调中处理绒亲录动作结果和强制解绑资格 | 客户端界面类型留在共享包 |
| `internal/network/RequestContractNamePacket.java` | `internal.client.ContractNameScreen` | 回调中打开契约命名界面 | 客户端界面类型留在共享包 |
| `internal/network/RecordListPacket.java` | `internal.client.FurkinRecordScreen` | 回调中打开或刷新绒亲录界面 | 客户端界面类型留在共享包 |

其余共享包未发现客户端类引用。`DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 能避免在服务端执行回调，但它的调用点仍位于共享包；开发环境的 `runServer` 使用包含客户端类的合并类路径，不能单独证明专用服务端隔离安全。

### 1.2 范围

范围内：

- `internal.network` 的协议数据、编解码、服务端校验和客户端分发边界。
- 将界面、`Minecraft` 和客户端状态修改逻辑移动到明确的 `internal.client` 处理器。
- 专用服务端启动、进服和网络交互验证。

范围外：

- 修改网络包字段、编码格式、包 ID 或协议版本。
- 修改界面布局、交互文案或玩法语义。
- WP-07 命令回执本地化。

## 2. 设计决策

### 2.1 客户端处理器

- 新增 `internal.client.FurkinClientPacketHandler`，类标记 `@OnlyIn(Dist.CLIENT)`，入口方法为客户端专用静态方法。
- 处理器集中承接 5 条客户端回调：
  - 同步绒亲数据并写回本地 capability。
  - 打开技能面板并投递技能快照。
  - 处理绒亲录动作结果。
  - 打开契约命名界面。
  - 打开或刷新绒亲录界面。
- 共享网络包只保留字段、`encode` / `decode`、服务端校验和分发调用；通过 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 间接调用客户端处理器。

### 2.2 包内访问

- `SyncFurkinDataPacket` 增加最小只读访问器，供客户端处理器读取实体 ID 和数据 NBT；不改变持久化或网络字段。
- 其余包已有读取接口；不改变线格式。
- 客户端处理器不得把可空 NBT 直接写入无效实体；空数据仍按“收回并清回 `WILD`”的既有语义处理。

### 2.3 Forge 分发 API 核对

- 已用当前 Forge 47.2.0 的 `DistExecutor` 字节码核对签名和调用顺序。
- `unsafeRunWhenOn(Dist, Supplier<Runnable>)` 先比较 `FMLEnvironment.dist`，仅在目标端匹配时调用外部供应商；因此服务端不会执行引用客户端处理器的 lambda 体。
- `safeRunWhenOn` 在开发环境会先执行 `validateSafeReferent`，再判断端位；这会在开发服务端提前求值外部供应商，不适合本包“服务端不加载客户端处理器”的目标。
- 结论：保留现有 `unsafeRunWhenOn` 分发方式，只把被调用的客户端实现迁入 `internal.client`。

### 2.4 兼容与协议

- 不新增、删除或重排网络包；不改变 `PROTOCOL_VERSION`，仍为 `2`。
- 不修改公开 API、存档数据格式、技能树格式或资源文件。
- 仅调整端位代码归属；客户端行为和服务端权威校验保持不变。

## 3. 实施拆分

### WP-06-01 审计

状态：已完成。

- 盘点共享源码中的客户端类引用。
- 确认 5 处引用及其调用路径。
- 明确开发环境 `runServer` 不能替代专用服务端隔离结论。

### WP-06-02 客户端处理器隔离

状态：已完成。

- 新增 `FurkinClientPacketHandler`。
- 把 5 条回调的客户端逻辑从网络包迁入处理器。
- 删除共享网络包中的 `Minecraft` 和 `internal.client` 界面类型引用。
- 保留包字段和现有服务端校验。

### WP-06-03 静态与运行验证

状态：已完成；保留强制解绑特有分支未能复现的残余说明。

- 编译、构建并检查专用服务端日志。
- 验证进服及 5 条网络交互链路。
- 记录实际命令、结果、残余风险和未执行项。

## 4. 验证门槛

### 4.1 静态检查

- 共享源码中不存在 `net.minecraft.client.*`、`net.minecraftforge.client.*`、`com.mojang.blaze3d.*` 引用；仅客户端包允许这些引用。
- `internal.network` 不直接引用 `internal.client` 的界面类；客户端处理器的引用只存在于 `Dist.CLIENT` 分发路径。
- `compileJava` 和 `build` 通过。

### 4.2 运行检查

- `runServer` 完成启动，日志无 `NoClassDefFoundError`、`ClassNotFoundException`、`ERROR`、`FATAL` 或异常栈。
- `runClient` 能进服，并覆盖：技能面板打开、绒亲录打开/刷新、契约命名界面、强制解绑结果提示、绒亲数据同步。
- 服务端和客户端协议仍匹配，交互不出现包处理异常。
- 不能只凭静态判断宣称专用服务端安全。

## 5. 关闭条件

- 审计列出的 5 处共享客户端引用已按设计迁移或消除。
- 关闭条件对应的静态检查和 `runServer` / `runClient` 证据齐全。
- 没有网络格式、协议版本、存档格式或公开 API 的变化，或变化已单独升级版本并记录。
- 文档、变更记录和残余验证同步更新。

## 6. 实施与验证结果

### 6.1 实施结果

- 新增 `internal.client.FurkinClientPacketHandler`，迁移数据同步、技能快照、绒亲录回执、契约命名和绒亲录列表 5 条客户端回调。
- `SyncFurkinDataPacket` 增加 `getEntityId()` 和 `getData()`，移除 `Minecraft`、`Entity`、`LivingEntity`、capability 和客户端状态修改代码。
- `OpenFurkinScreenPacket`、`RecordActionResultPacket`、`RequestContractNamePacket`、`RecordListPacket` 改为只分发到客户端处理器。
- 网络字段、包 ID、两端处理方向、服务端校验和 `PROTOCOL_VERSION = 2` 均未改变。

### 6.2 静态验证

- `compileJava`：`BUILD SUCCESSFUL`。
- `build --rerun-tasks`：`BUILD SUCCESSFUL`。
- 全量源码扫描：非客户端包仅剩 `FurkinMod` 注释中的规则说明，没有实际客户端类引用。
- `internal.network` 字节码检查：5 个包只引用 `FurkinClientPacketHandler`，未出现 `net/minecraft/client` 或具体客户端界面类。

### 6.3 运行验证

- `runServer`：到达 `Done (2.689s)!`；日志无 `NoClassDefFoundError`、`ClassNotFoundException`、`ERROR`、`FATAL` 或异常栈。
- `runClient`：启动并进入集成世界；实机确认绒亲数据同步、技能面板、绒亲录打开/刷新、契约命名均正常。
- 普通解绑：实机确认回执正常，覆盖 `RecordActionResultPacket` 的客户端分发路径。
- 最终客户端日志无 `ERROR`、`FATAL`、类加载错误、包处理异常或异常栈。

### 6.4 残余说明

- `ForceUnbindConfirmScreen` 的强制解绑特有状态未能在现有存档中复现；该分支不是本次 WP-06 迁移新增逻辑，普通解绑已经覆盖同一 `RecordActionResultPacket` 处理器入口。
- 该项保留为未执行的特定状态验证，不外推为“强制解绑 UI 已实机通过”。

## 7. 负荷评估

- 服务端不新增包、tick 处理、实体扫描、档案扫描或缓存；仅降低客户端处理类进入服务端路径的风险。
- 客户端不新增渲染、轮询或数据复制；每条相关回调只多一层客户端静态处理器分发，属于常量级开销。
- 网络线格式、包数量和协议版本不变，因此没有新增带宽或序列化成本。
