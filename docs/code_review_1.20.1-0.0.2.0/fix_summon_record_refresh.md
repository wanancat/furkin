# 绒亲录召唤后即时刷新修复

- 日期：2026-09-25
- 基线提交：`47f172a`（`docs: 关闭最终代码审查验收`）
- 状态：代码修复、构建、服务端启动和真实客户端实机复测通过；提交/推送待授权
- 影响模块：`internal.network.RequestSummonPacket`、`internal.client.FurkinRecordScreen`
- 协议影响：无。网络包字段、包 ID、方向、处理器和 `PROTOCOL_VERSION` 均未变化

---

## 1. 问题现象

在绒亲录内对未在场绒亲点击「召唤」后，实体已成功生成，但列表条目没有立即显示绿色「在场」后缀；必须关闭绒亲录再重新打开，服务端重发完整列表后才会显示。

该问题不是召唤失败，也不是档案状态未更新。

---

## 2. 根因

1. `FurkinRecordScreen` 的召唤按钮只发送 `RequestSummonPacket`，不进行本地状态更新。
2. 服务端 `FurkinCompanionManager.rebuildCompanion` 成功后已正确执行：
   - `entry.setAlive(true)`
   - `entry.setSummoned(true)`
   - `entry.setEntityLocation(living)`
   - `archive.putEntry(entry)`
3. `RequestSummonPacket.applyServer` 成功时只发送 action bar 文本，没有调用已有的 `FurkinRecordItem.refreshRecordList(player)`。
4. 绒亲录的状态后缀读取的是 `RecordListPacket.Entry.isSummoned()`；客户端仍持有召唤前的旧 `entries`，因此绿色「在场」不会出现。
5. `RecordActionPacket` 路径原本已通过 `refreshRecord = true` 回发刷新列表；召唤走独立的 `RequestSummonPacket`，漏掉了同等刷新。

结论：服务端权威状态正确，缺陷位于“召唤成功后未向已打开界面回发列表刷新”的客户端同步链路。

---

## 3. 修复方案

在 `RequestSummonPacket.applyServer` 结算完成后统一调用：

```java
FurkinRecordItem.refreshRecordList(player);
```

该调用：

- 复用现有 `RecordListPacket(openScreen = false)` 刷新路径，不重新打开界面；
- 成功召唤后立即更新绿色「在场」、右侧「收回」按钮和战斗模式按钮可用状态；
- 对失败分支同样执行是幂等且有界的：若传送失败时服务端已自愈 `summoned=false`，界面也能同步修正；
- 绒亲录已关闭或已切换到其他界面时，客户端 `handleRefresh` 会安全忽略该刷新包；
- 不改变网络包结构，因此不需要递增 `PROTOCOL_VERSION`。

---

## 4. 验证结果

### 4.1 静态与构建

- `.\gradlew.bat compileJava --rerun-tasks --console=plain`：`BUILD SUCCESSFUL`
- `.\gradlew.bat build --rerun-tasks --console=plain`：`BUILD SUCCESSFUL`

### 4.2 运行验证

- `runServer`：到达 `Done (2.677s)!`
- `runClient`：真实客户端进入集成世界，打开绒亲录后对未在场绒亲点击「召唤」
- 人工确认：不关闭界面即可立即看到绿色「在场」，右侧管理按钮同步刷新
- 日志检查：无本修复触发的 `ERROR`、`FATAL`、异常栈、注册失败或资源缺失

### 4.3 范围外观察

- 本次复测首次收回出现一次 `Can't keep up! ... Running 3110ms or 62 ticks behind` 服务端性能告警；后续同路径未复现。乌狸已决定本次不处理，因此不纳入本修复范围和关闭条件。

---

## 5. 变更文件

- `src/main/java/com/wanancat/furkin/internal/network/RequestSummonPacket.java`
- `CHANGELOG.md`
- `changelog.en.md`
- `docs/code_review_1.20.1-0.0.2.0/code_review_2026-09-24_workflow.md`
- `docs/code_review_1.20.1-0.0.2.0/fix_summon_record_refresh.md`

---

## 6. 关闭条件

- [x] 召唤成功后绒亲录即时刷新
- [x] 绿色「在场」与右侧管理按钮同步更新
- [x] `compileJava`、`build` 通过
- [x] `runServer`、`runClient` 和人工复测通过
- [x] 日志无本修复引入的错误级记录
- [x] 不改变协议版本
- [ ] 提交并推送至远端