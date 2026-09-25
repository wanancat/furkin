# WP-09：强制解绑、墓碑与实体定位

- 工作项：D9（H-02 的不可解析实体自救路径；对齐 1.20.1 WP-02B B0～B7）
- 实施日期：2026-09-25
- 基线：`c3e1e81`（`mc1.19.2`）
- 状态：代码与专项运行验证已完成；网络协议版本递增留给紧随其后的 WP-04 收口
- 提交范围：仅 WP-09；不推送

---

## 1. 根因

常规解绑按当前维度遍历实体来定位目标。档案标记为“已召唤”但实体未加载、已跨维度或旧档只有 companion ID 时，调用方无法拿到实体引用：

- 旧实现可能直接删除档案，形成无法再清理的幽灵记录；
- 不能把实体当成“未召唤”处理，因为它的能力数据仍在世上；
- 不允许为了解绑而召唤、重建或复制实体；
- 如果清理中途失败，下一次请求还必须能重新找到同一实体继续清理。

WP-09 因此把“实体定位”和“身份档案”分开持久化，并在定位失败时增加显式的强制解绑出口。

---

## 2. 实施内容

### 2.1 档案实体定位字段（B0）

`FurkinArchiveEntry` 新增并持久化：

- `entity_uuid`：最近一次确认的在世实体 UUID；
- `entity_dimension`：该实体最近一次确认所在维度。

兼容规则：

- 旧档缺少两个键时读出 `null`；
- 非法维度字符串记 `WARN` 并按 `null` 处理；
- 契约、召唤/复活重建、跨维度传送和实体重新入世时刷新；
- 收回、死亡和传送自愈时清空，避免留下失效定位。

### 2.2 定向定位（B2）

新增 `FurkinEntityLocator`：

- 只使用 `ServerLevel#getEntity(UUID)` 索引查询；
- 先查档案记录的维度，再遍历当前已加载维度；
- 不调用 `getEntities().getAll()`，不加载区块；
- 查询命中后校验能力中的 companion ID；
- companion ID 为空但 UUID 匹配时允许命中，用于上一次清理在末段失败后的重试。

### 2.3 常规解绑（B2）

`FurkinRecordActionHandler.unbind(...)` 现在：

1. 先校验档案、所有权和已召唤状态；
2. 已召唤时按档案定位实体；
3. 定位失败返回 `ENTITY_UNRESOLVED`，**不删档、不重建**；
4. 定位成功先刷新档案定位，再复用 `FurkinUnbindCleanup`；
5. 清理成功后才删除档案；失败返回 `CLEANUP_FAILED`，档案保留供重试。

### 2.4 注销墓碑（B3）

新增服务器级 `FurkinRevocationData`（SavedData，名称 `furkin_revocation`），锚定主世界：

- 键为 companion ID；
- 保存 owner UUID 与请求时游戏刻，仅用于诊断和审计；
- 强制解绑必须先成功写入墓碑，成功后才删除普通档案；
- 墓碑只有在入世延迟清理成功后才会移除；
- 加载时跳过缺少 companion ID 的损坏条目，并保留缺少 owner UUID 的条目继续清理。

### 2.5 强制解绑入口（B4）

- `RecordActionPacket.Action` 新增 `FORCE_UNBIND`；
- 新增 `RecordActionResultPacket`，以 PLAY_TO_CLIENT 方向回传动作结果和 `forceUnbindAllowed`；
- 绒亲录收到常规解绑的 `ENTITY_UNRESOLVED` 后打开二次确认页；
- 确认页只发送 `FORCE_UNBIND`；服务端重新校验所有权、档案状态和实体是否仍不可解析；
- 命令入口为 `/furkin forget <pet_id> [force]`；
- 服务端在实体已经可解析时返回 `ENTITY_RESOLVED`，不会绕过常规清理路径。

### 2.6 入世延迟清理（B5）

`CommonEvents#onEntityJoinLevel` 在服务端实体入世时：

1. 按能力的 companion ID 查询墓碑；
2. 命中后执行 `FurkinUnbindCleanup.cleanup(..., Trigger.REVOCATION)`；
3. 清理失败保留墓碑并返回，实体不再按绒亲 AI 继续运行；
4. 清理成功后移除墓碑并同步一次能力数据；
5. 若实体重新入世时档案仍存在且标记已召唤，刷新实体 UUID 与维度。

### 2.7 1.19.2 API 取证

已对 1.19.2 mapped official jar 解包/`javap` 核对：

- `net.minecraft.core.Registry#DIMENSION_REGISTRY` 存在；本分支没有 `net.minecraft.core.registries.Registries`；
- `ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation)` 可用；
- `ServerLevel#getEntity(UUID)` 存在；
- `Entity#changeDimension(ServerLevel, ITeleporter)` 公开可用；
- `ITeleporter#getPortalInfo(Entity, ServerLevel, Function<ServerLevel, PortalInfo>)` 签名可用；
- `MinecraftServer#overworld()`、`getLevel(ResourceKey<Level>)`、`getAllLevels()` 可用。

因此跨维度传送使用 `changeDimension` 加固定落点 `ITeleporter`，并在返回的新实体引用上刷新档案定位，而不是调用 1.20.1 的世界/传送方法。

---

## 3. 修改文件

新增：

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinEntityLocator.java`
- `src/main/java/com/wanancat/furkin/internal/record/FurkinRevocationData.java`
- `src/main/java/com/wanancat/furkin/internal/network/RecordActionResultPacket.java`
- `src/main/java/com/wanancat/furkin/internal/client/FurkinClientPacketHandler.java`
- `src/main/java/com/wanancat/furkin/internal/client/ForceUnbindConfirmScreen.java`

修改：

- `FurkinArchiveEntry`
- `FurkinRecordActionHandler`
- `FurkinCompanionManager`
- `FurkinCombatModeHandler`
- `FurkinCommand`
- `FurkinContractHandler`
- `CommonEvents`
- `RecordActionPacket`
- `FurkinNetwork`
- `FurkinRecordScreen`
- `SkillProgress`
- `en_us.json`、`zh_cn.json`

所有新增或修改的玩家可见文本均同步提供中英键。

---

## 4. 验证

### 4.1 静态验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
.\gradlew.bat compileJava --console=plain
```

结果：`BUILD SUCCESSFUL`。首次编译暴露的 3 个 1.19.2 API/import 差异已按实际符号修正，未使用文本替换移植 1.20.1 代码。

### 4.2 临时服务端夹具

依据用户 2026-09-25 授权，加入一次性 `Wp09ServerFixture`，在 `ServerStartedEvent` 执行以下场景：

1. 实体未加入世界、档案却标记 `summoned=true`；
2. 常规解绑返回 `ENTITY_UNRESOLVED`，档案仍保留、墓碑未创建；
3. 强制解绑成功写入墓碑并删除普通档案；
4. 带同一 companion ID 的实体重新入世，延迟清理执行；
5. 注入 `VANILLA_OWNERSHIP` 阶段异常，确认墓碑保留且 companion ID 恢复，可在下一次入世重试；
6. 正常实体再次入世，墓碑被移除，身份 / 状态清理完成。

执行命令：

```powershell
.\gradlew.bat runServer --console=plain
```

关键日志（`run/logs/latest.log`）：

```text
Furkin force-unbound: id=... by WP09Fixture
Furkin unbind cleanup failed: ... trigger=REVOCATION, stage=VANILLA_OWNERSHIP
Restored companion id after failed revocation cleanup for retry: id=...
Furkin revocation cleanup deferred: ... stage=VANILLA_OWNERSHIP
Furkin unbind cleanup complete: ... trigger=REVOCATION
WP09_FIXTURE_RESULT PASS
```

同次运行到达服务器停止流程并最终显示 `BUILD SUCCESSFUL`。日志中的一条 `ERROR` 和异常栈是夹具刻意注入的失败分支，后续同一进程中的重试成功；除此之外没有项目自身的意外 `ERROR`、`FATAL`、类加载错误或资源缺失。

夹具文件已在提交前删除，`build/`、`run/` 和日志均不纳入提交。

### 4.3 未验证边界

- 未执行真实客户端的二次确认页点击链；WP-09 只完成了服务端夹具验证和客户端静态接线，最终客户端联调在 WP-06 后统一执行。
- 强制解绑不会在实体永远不再入世时立即归还物品：这是墓碑延迟清理的既有边界，物品保留在实体能力数据中，档案不保留可在实体不可解析时删除。
- 未单独模拟真实跨维度玩家传送；`changeDimension` 的签名与返回引用已在 1.19.2 上静态核实，强制解绑夹具覆盖了墓碑清理路径。

---

## 5. 关闭状态

WP-09 的代码、静态验证、临时服务端夹具和文档已齐备。由于本 WP 改动了 `RecordActionPacket` 的枚举和处理器语义，下一步 WP-04 必须把 `PROTOCOL_VERSION` 从 `"1"` 提升到 `"2"`，并在协议文档中记录版本到包结构的映射；完成 WP-04 后才把 WP-09 标记为完全关闭。
