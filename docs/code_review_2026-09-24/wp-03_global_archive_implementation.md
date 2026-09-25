# Furkin 1.20.1 WP-03 全局档案统一实施记录

- 文档状态：已完成；代码、跨重启运行验证与文档闭环完成，提交/推送待乌狸授权
- 完成日期：2026-09-25
- 基线分支：`mc1.20.1`
- 实施基线：`77c1012`（`fix: 完成解绑清理与不可解析实体自救`）
- 对应问题：`H-03` 绒亲档案按当前维度读取，跨维度会拆成多份
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0

> 本文记录 WP-03 的实际实现、兼容策略、性能边界和验证证据。工作流状态以 `docs/code_review_2026-09-24/code_review_2026-09-24_workflow.md` 为准。

---

## 1. 问题与目标

原实现的 `FurkinArchiveData` 通过 `ServerLevel#getDataStorage()` 读取 `furkin_archive`，而每个 `ServerLevel` 都有自己的 `DimensionDataStorage`。因此主世界、下界、末地会各自持有一份同名档案：

- 主世界契约的绒亲进入其他维度后，可能从绒亲录中消失。
- 召唤、收回、改名、技能和复活路径可能命中错误维度中的档案。
- 活跃上限可能被每个维度分别放行。
- 同一 `companionId` 在不同维度可能表现为不同状态。

WP-03 的目标是让所有玩法路径统一读取服务器级主世界档案，并提供旧存档兼容迁移。

---

## 2. 范围

### 2.1 包含

- 服务器级 overworld 档案单例。
- 旧版本按维度分散档案的首次读取迁移。
- 契约、召唤、收回、改名、洗点、复活、死亡、技能和绒亲录路径的档案入口统一。
- 跨维度召唤与传送后的实体定位刷新。
- 服务器级活跃上限统计。
- 旧实体 UUID + 维度定位的调用点统一。
- 专用服务端夹具、跨重启验证、无夹具构建与双端启动验证。

### 2.2 不包含

- 不修改网络包字段、顺序、方向或 `PROTOCOL_VERSION`。
- 不修改公开 API `com.wanancat.furkin.api`。
- 不删除旧版本维度档案文件。
- 不猜测无法安全判定的同 ID 冲突新旧关系。
- 不引入全服实体扫描、区块加载或 tick 轮询。

---

## 3. 实现方案

### 3.1 服务器级权威档案

`FurkinArchiveData` 继续使用存储名 `furkin_archive`，但所有入口最终都读取主世界 `ServerLevel#getDataStorage()` 中的实例：

```java
public static FurkinArchiveData get(ServerLevel level) {
    return get(level.getServer());
}

public static FurkinArchiveData get(MinecraftServer server) {
    Objects.requireNonNull(server, "server");
    FurkinArchiveData data = server.overworld().getDataStorage()
            .computeIfAbsent(FurkinArchiveData::load, FurkinArchiveData::new, NAME);
    data.migrateLegacyArchives(server);
    return data;
}
```

保留 `get(ServerLevel)` 兼容入口，使现有玩法调用点无需同时重写；该方法不再按传入维度读取。

### 3.2 数据版本与旧档迁移

档案新增 `data_version` 字段，当前版本为 `1`。旧档缺少该字段时按 `0` 处理。

首次读取时，如果数据版本低于当前版本：

1. 遍历服务器当前已有的非主世界维度。
2. 读取这些维度中旧的同名 `furkin_archive`。
3. 按 `companionId` 导入主世界档案中不存在的条目。
4. 同 ID 冲突时保留主世界条目，记录 `WARN` 与冲突维度，不静默覆盖。
5. 将主世界数据版本写为 `1` 并标记脏数据。

旧维度档案文件不主动删除，保留作为回滚和人工排查副本。

### 3.3 全局活跃上限

契约、召唤和复活路径统一从服务器级档案统计 `summoned=true` 的条目：

- 契约时的 `countActive` 不再遍历当前维度实体。
- 召唤和复活的 `countSummoned` 不再按当前维度统计。
- 同一玩家跨维度持有的已召唤绒亲共同占用一个全局上限。

### 3.4 跨维度定位与传送

所有按 `companionId` 查找在场实体的调用点统一委托 `FurkinCompanionManager`，再通过 `FurkinEntityLocator` 使用档案中的实体 UUID 和维度做定向索引查询。

已召唤实体位于其他维度时，召唤路径使用 `Entity#changeDimension(ServerLevel, ITeleporter)` 迁移到玩家所在维度；`FixedTeleporter` 提供玩家前方固定落点。迁移成功后刷新档案中的实体 UUID 与维度。

该路径不遍历全服实体、不加载区块、不轮询 tick。

### 3.5 只读列表视图

`allEntries()` 返回不可修改集合视图，避免外部代码通过集合引用直接增删档案。条目自身仍只能通过既有档案写入口更新。

---

## 4. 性能评估

| 路径 | 成本 | 说明 |
|---|---:|---|
| 常规档案读取 | O(1) Map 查询 | 固定主世界实例，不随维度增加而分裂 |
| 旧档迁移 | O(维度数 + 档案条目数) | 仅在数据版本升级后的首次读取执行一次 |
| 活跃上限统计 | O(档案条目数) | 只读内存 Map；不使用实体列表或区块扫描 |
| 定向实体定位 | O(1) 索引查询，未命中时按已加载维度有限回退 | `ServerLevel#getEntity(UUID)`；不加载区块 |
| 跨维度召唤 | 原版维度迁移成本 | 只迁移一个实体，不创建替代实体 |

旧版本档案文件保留不会产生运行时 tick 成本；迁移日志只在存在导入或冲突时输出。

---

## 5. 验证证据

### 5.1 专用服务端夹具

夹具文件：临时类 `src/main/java/com/wanancat/furkin/internal/event/Wp03ArchiveFixture.java` 与 `src/main/java/com/wanancat/furkin/internal/event/Wp03DismissFixture.java`（均已删除，最终 JAR 不包含）。

覆盖内容：

- 主世界、下界、末地返回同一档案实例。
- 旧维度档案迁移导入与同 ID 冲突策略。
- 跨维度全局活跃上限。
- 主世界已召唤实体在下界被召唤时执行维度迁移。
- 下界玩家对主世界宠物执行收回，确认跨维度定位与收回路径。
- 档案实体 UUID 与维度刷新，收回后清除失效定位。
- 服务器退出重载后档案和定位信息仍可解析。

实际结果：

```text
第一轮：WP03_FIXTURE_OK checks=13 failed=0 restartPass=phase1-armed
第二轮：WP03_FIXTURE_OK checks=12 failed=0 restartPass=true
收回轮：WP03_DISMISS_FIXTURE_OK checks=9 failed=0
合计：34 项检查，failed=0
```

### 5.2 发布构建与无夹具启动

已执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

结果：

- `clean build`：`BUILD SUCCESSFUL`
- 无夹具 `runServer`：正常输出 `Done`
- 无夹具 `runClient`：正常启动至客户端主界面
- `run/logs/latest.log`：未发现新增 `ERROR`、`FATAL`、异常栈、注册失败或本模组资源缺失
- 最终 JAR：`build/libs/furkin-1.20.1-0.0.1.1.jar`
- JAR 扫描：不包含 `Wp03ArchiveFixture` 或 `wp03` 类

---

## 6. 已知边界

- 旧档同 ID 冲突没有时间戳，保留主世界条目并记录 `WARN`；需要人工判断时可依据日志和旧维度文件处理。
- 旧维度档案文件不自动删除，避免升级后无法回滚。
- 迁移按服务器首次读取时已存在的维度执行；新维度不会生成旧格式档案，因此不受此边界影响。
- WP-03 不处理 M-01 协议版本治理；本次未改变网络包。
- WP-03 不改变公开 API，不改变技能 schema、配方或平衡数值。

---

## 7. 关闭结论

- [x] 玩法路径不再按当前维度读取档案。
- [x] 跨维度查看、召唤、定位和活跃上限使用同一服务器级状态。
- [x] 旧档案有数据版本、迁移、冲突诊断和回滚副本策略。
- [x] 跨重启夹具、发布构建、无夹具服务端和客户端启动验证通过。
- [x] 临时夹具未进入最终 JAR。
- [x] 审查报告、总工作流和双语 changelog 已同步。

WP-03 的 H-03 可关闭；提交与推送等待乌狸明确授权。
