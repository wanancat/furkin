# WP-03：全局档案统一

- 工作项：H-03
- 实施日期：2026-09-25
- 基线：`ba3132e`（`mc1.19.2`）
- 状态：已完成
- 提交范围：仅 WP-03；不推送
- 参考：1.20.1 `docs/code_review_1.20.1-0.0.2.0/wp-03_global_archive_implementation.md`

---

## 1. 根因与目标

旧实现直接使用传入 `ServerLevel` 的 `DimensionDataStorage` 读取 `furkin_archive`。
1.19.2 的 `ServerLevel#getDataStorage()` 是维度级存储，因此主世界、下界、末地会各自持有
一份同名档案，造成：

- 跨维度看不到同一只宠物；
- 召唤、收回、改名、洗点、复活等路径可能操作不同副本；
- 活跃上限可能按维度分别放行；
- 同一 `companionId` 在不同维度状态不一致。

本 WP 的目标是让 `furkin_archive` 成为服务器级主世界档案，并兼容旧版本已经分裂的档案。

---

## 2. 实施内容

### 2.1 固定主世界权威实例

`FurkinArchiveData` 统一为：

- `get(MinecraftServer)`：从 `server.overworld().getDataStorage()` 取主世界实例；
- `get(ServerLevel)`：保留旧调用签名，但转发到 `get(level.getServer())`；
- 不再按传入维度创建或读取独立档案。

现有 20 余处 `FurkinArchiveData.get(level)` 无需逐个改写，语义已被收口。

### 2.2 档案数据版本

新增 NBT 字段 `data_version`，当前版本为 `1`。旧档没有该字段时按 `0` 处理。
当主世界档案数据版本低于当前版本时，执行一次性迁移。

### 2.3 旧维度档案迁移

迁移遍历服务器当前已有的非主世界维度：

1. 读取该维度旧的同名 `furkin_archive`；
2. 对主世界不存在的 `companionId` 直接并入；
3. 对主世界已存在的 `companionId` 保留主世界条目，不覆盖；
4. 每条冲突记录 `WARN`，包含 `companionId` 和旧维度；
5. 迁移结束记录 `imported` / `conflicts` 数量；
6. 数据版本写为 `1` 并标记脏；
7. 旧维度副本不删除，保留作为回滚与人工核对材料。

该口径与 2026-09-25 冻结决策一致：`overworld` 权威、冲突取 overworld、其余副本不删。

### 2.4 全局活跃上限

契约路径的 `countActive(...)` 和召唤/复活路径的 `countSummoned(...)` 不再扫描当前维度实体，
改为统计服务器级档案中 `summoned=true` 且 owner 匹配的条目。这样同一玩家跨维度持有的已召唤
绒亲共同占用同一个全局上限，不会因切换维度而翻倍。

### 2.5 只读列表视图

`allEntries()` 改为 `Collections.unmodifiableCollection(...)`，避免调用方拿到集合引用后直接增删，
所有写入继续走 `putEntry(...)` / `removeEntry(...)` 并正确 `setDirty()`。

---

## 3. 1.19.2 API 核对

- `ServerLevel#getDataStorage()` 与 `DimensionDataStorage#computeIfAbsent(...)`：1.19.2 可用；
- `DimensionDataStorage#get(Supplier, String)`：1.19.2 可用；
- `MinecraftServer#getAllLevels()`：1.19.2 可用；
- `Level.OVERWORLD`、`ResourceKey<Level>`：1.19.2 可用；
- 本 WP 未调用客户端类，未改网络包字段，故不触发协议版本。

以上以本分支 `compileJava` 成功和 1.19.2 mapped official jar 的实际符号使用为准，
未依据 1.20.1 的存在性推断。

---

## 4. 修改文件

- `src/main/java/com/wanancat/furkin/internal/record/FurkinArchiveData.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java`

---

## 5. 验证

### 5.1 编译与构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --console=plain
```

结果：

- `BUILD SUCCESSFUL in 14s`；
- 夹具删除后的干净构建通过；
- JAR 扫描未发现 `Wp03ArchiveFixture` 或 `ContractAuthorityFixture`。

### 5.2 专用服务端夹具

按用户授权加入临时 `Wp03ArchiveFixture`，验证后在提交前删除，日志保留在
`run/logs/latest.log`（日志不入库）。夹具覆盖：

- 主世界、下界、末地返回同一档案实例；
- 旧维度条目的导入；
- 同 ID 冲突保留 overworld；
- 旧维度副本仍保留；
- 契约与召唤的全局活跃计数能看到跨维度条目。

日志证据（2026-09-25 16:08:00）：

```text
[WP03-FIXTURE] overworld returns server archive: PASS
[WP03-FIXTURE] nether returns server archive: PASS
[WP03-FIXTURE] end returns server archive: PASS
Furkin archive migration conflict: id=f0d5db1a-..., keeping overworld entry; legacy dimension=minecraft:the_nether
Furkin archive migration completed: imported=1, conflicts=1
[WP03-FIXTURE] migration keeps server singleton: PASS
[WP03-FIXTURE] legacy entry imported: PASS
[WP03-FIXTURE] imported entry keeps data: PASS
[WP03-FIXTURE] conflicting entry retained: PASS
[WP03-FIXTURE] overworld wins conflict: PASS
[WP03-FIXTURE] legacy dimension retains entries: PASS
[WP03-FIXTURE] global active count sees both dimensions: PASS
[WP03-FIXTURE] SUMMARY pass=12 fail=0
```

同次服务端运行到达 `Done`；没有项目自身 `ERROR`、`FATAL` 或异常栈，只有环境层 OSHI/WMI 警告。

### 5.3 验证边界

- 已运行确认同 JVM 内的跨维度单例、迁移、冲突策略和全局计数；
- 未在本夹具中做跨服务器重启的完整存档回放；WP-03 的存档字段 `data_version` 与 SavedData
  序列化已由实际 `runServer` 加载/保存路径覆盖；
- 未修改客户端界面行为。

---

## 6. 已知边界与后续

- 迁移只扫描首次读取时服务器当前已有的维度；旧版本未加载的维度不会凭空创建档案。
- 同 ID 冲突没有时间戳，无法自动判定“哪份更新”，按冻结口径保留 overworld 并记录日志。
- 旧维度档案不删除，升级后如需回滚或人工取回仍可用。
- 实体定向定位、跨维度传送和墓碑属于 WP-09 范围，不在本 WP 内混入。
- 本 WP 不修改公开 API，不修改 `AGENTS.md` 或 `.gitignore`。
