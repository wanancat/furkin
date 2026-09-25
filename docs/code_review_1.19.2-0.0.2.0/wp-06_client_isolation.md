# WP-06 客户端类隔离（L-01）

- 状态：代码、静态检查、启动烟测和真实进服五条交互路径均已完成；WP 已收口
- 日期：2026-09-25
- 基线：WP-05 提交 `65acefc`
- 对应问题：共享网络包直接引用客户端类，专职服务端隔离缺少充分证据
- 适用范围：Minecraft 1.19.2 / Forge 43.2.0

---

## 1. 审查结论

修复前 `internal.network` 有 5 处客户端代码入口：

| 文件 | 原客户端依赖 | 原用途 |
| --- | --- | --- |
| `SyncFurkinDataPacket` | `net.minecraft.client.Minecraft`、`Entity`、capability | 在客户端按实体 ID 写回同步数据 |
| `OpenFurkinScreenPacket` | `internal.client.FurkinPanelScreen` | 把技能快照交给技能面板 |
| `RecordListPacket` | `internal.client.FurkinRecordScreen` | 打开或刷新绒亲录 |
| `RequestContractNamePacket` | `internal.client.ContractNameScreen` | 打开契约命名界面 |
| `RecordActionResultPacket` | `internal.client.FurkinRecordScreen` | 处理动作结果与强制解绑确认资格 |

`DistExecutor.unsafeRunWhenOn` 能阻止回调在服务端执行，但原实现把客户端界面类型直接放在共享包，仍不满足端位纪律。开发环境 `runServer` 使用合并类路径，不能替代专职服务端隔离结论。

## 2. 1.19.2 API 取证

对 Forge `43.2.0` 的 `fmlcore` 字节码执行 `javap -c -p net.minecraftforge.fml.DistExecutor`，确认：

```text
unsafeRunWhenOn(Dist, Supplier<Runnable>):
  if (FMLEnvironment.dist != target) return;
  supplier.get().run();
```

比较先于 `Supplier#get()`，因此服务端不会求值引用客户端处理器的 lambda。`safeRunWhenOn` 会先执行 `validateSafeReferent`，不符合本工作包“服务端不解析客户端实现”的目标；继续使用 `unsafeRunWhenOn`。

## 3. 实施结果

- 扩充 `internal.client.FurkinClientPacketHandler`，集中承接 5 条客户端回调：数据同步、技能快照、绒亲录回发、契约命名、绒亲录打开/刷新。
- `SyncFurkinDataPacket` 增加 `getEntityId()` / `getData()`，把 `Minecraft`、实体定位、capability 写入逻辑移到客户端处理器。
- 其余 4 个包只通过 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 调用 `FurkinClientPacketHandler`。
- 未改变网络字段、编解码顺序、包 ID、处理方向、服务端校验或协议版本。

## 4. 验证证据

### 4.1 静态

- `rg` 检查非 `internal.client` 源码：不存在 `net.minecraft.client.*`、`com.mojang.blaze3d.*` 或客户端界面类型的实际引用。
- `internal.network` 的 5 个包只引用 `FurkinClientPacketHandler`；`javap -c -p` 检查 5 个 class，均未出现 `net/minecraft/client` 或具体客户端界面类。
- `compileJava`：`BUILD SUCCESSFUL`。
- `clean build`：`BUILD SUCCESSFUL`，8 个任务执行；收尾版本号变更后的产物为 `build/libs/furkin-1.19.2-0.0.2.0.jar`。

### 4.2 服务端

- `runServer` 达到 `Done (2.259s)!`。
- 日志无 `NoClassDefFoundError`、`ClassNotFoundException`、项目包 `ERROR`、`FATAL` 或异常栈；恢复为无夹具的 13 个内置技能加载。

### 4.3 客户端

- `runClient` 启动到主菜单；日志出现 OpenAL 初始化、Sound engine started 和资源图集创建。
- 客户端启动日志无类加载错误、项目包 `ERROR` 或 `FATAL`。
- 启动烟测本身只覆盖启动阶段；真实进服后的五条网络回调已由第 4.4 节收口。

### 4.4 最终进服五路径（2026-09-25 追加）

真实客户端进入本地服务端后，以下五类包回调均在 `run/logs/latest.log` 出现并完成：

- `SyncFurkinDataPacket`：`sync handler applied companion=...`；
- `OpenFurkinScreenPacket`：技能面板可见；
- `RecordListPacket`：绒亲录可见；
- `RequestContractNamePacket`：契约命名界面可见；
- `RecordActionResultPacket`：强制解绑确认页可见，并由临时客户端夹具执行真实按钮 `onPress()`。

因此 WP-06 原先的“五条网络回调仍需人工验收”已收口；本结论不外推到第三方界面类型或未列入协议 `"2"` 的新包。

## 5. 负荷与边界

- 仅把已有逻辑迁移到客户端处理器，不新增包、tick 扫描、缓存、序列化字段或网络往返。
- 客户端调用多一层静态分发，规模和原实现同阶。
- 本 WP 的协议列表为 `"2"` 的共享包边界已收口；结论不外推到第三方界面类型或未来新增包。
