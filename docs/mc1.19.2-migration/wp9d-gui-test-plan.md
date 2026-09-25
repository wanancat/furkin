---
work_package: WP9d
title: "Furkin 1.19.2 GUI manual test plan"
status: in_progress
recorded_at: "2026-09-24T01:20:00+08:00"
updated_at: "2026-09-24T03:16:52+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
java: 17.0.2
client_log: "run/logs/latest.log"
world: "run/saves/新的世界"
---

# WP9d：1.19.2 GUI 实机测试计划

## 目标

逐个验证迁移后的四个客户端界面在 Minecraft 1.19.2 / Forge 43.2.0 下的：

- 打开入口和前置条件。
- 布局、文本、按钮、输入框和滚动交互。
- 页面互跳、关闭和 ESC 行为。
- 客户端与服务端数据同步。
- 操作后的刷新、选中项保持和状态一致性。
- 异常、崩溃报告和相关日志。

本计划先记录测试顺序与判据，再由 WP9d.1 实机执行并回填结果。

## 测试对象

| 界面 | 类 | 主要职责 |
|---|---|---|
| 绒亲面板 | `FurkinPanelScreen` | 技能、行囊、装备三个页签 |
| 绒亲录 | `FurkinRecordScreen` | 绒亲列表、详情、召唤、收回、改名、模式和解绑 |
| 契约命名 | `ContractNameScreen` | 首次契约时输入名字 |
| 改名 | `RenameScreen` | 修改已有绒亲名字 |

## 测试环境与前置条件

| 项目 | 值 |
|---|---|
| 仓库 | `D:\frukin_dev\frukin_1_19_2` |
| 分支 | `mc1.19.2` |
| 世界 | `run/saves/新的世界` |
| 游戏模式 | 创造模式 |
| 作弊 | 开启 |
| Java | `C:\Program Files\Java\jdk-17.0.2` |
| Minecraft | `1.19.2` |
| Forge | `43.2.0` |
| 运行日志 | `run/logs/latest.log` |
| 崩溃报告 | `run/crash-reports` |

测试开始前确认背包中至少有：

- `furkin:record` × 1。
- `furkin:contract` × 64。
- `minecraft:cat_spawn_egg` × 64。
- `minecraft:wolf_spawn_egg` × 64。
- `furkin:respec_potion` × 64。

如果现有测试世界或背包状态发生变化，先记录变化，不直接覆盖或删除世界。

## 入口、前置条件与互跳

### 1. 契约命名 `ContractNameScreen`

- 触发：手持 `furkin:contract`，非潜行右键已注册物种（当前内置为猫、狼）。
- 服务端入口：`FurkinContractHandler.tryContract`。
- 前置条件：
  - 目标是已注册物种。
  - 目标具有可契约能力。
  - 目标尚未成为绒亲。
  - 当前活跃绒亲数量未达上限。
- 通过后服务端发送 `RequestContractNamePacket`，客户端打开 `ContractNameScreen`。
- 确认：发送 `ConfirmContractPacket(entityId, name)`，服务端执行契约并消耗 1 个契约物品。
- 取消或 ESC：不发送确认包，不消耗契约物品。
- 输入限制：最大 32 字符；留空时由服务端回退到物种名。
- 重要待验点：1.19.2 官方 `Screen.onClose()` 调用 `Minecraft.popGuiLayer()`；本屏通过 `setScreen` 打开，必须实机确认取消和 ESC 后是否正确回到世界。

### 2. 绒亲录 `FurkinRecordScreen`

- 触发：右键 `furkin:record`。
- 服务端入口：`FurkinRecordItem.use`，发送记录列表数据。
- 客户端入口：`RecordListPacket.handle` → `FurkinRecordScreen.open(data)`。
- 前置条件：无。没有绒亲时也应能打开并显示空列表提示。
- 界面结构：
  - 左侧为 `AbstractSelectionList` 绒亲列表。
  - 右侧为选中绒亲的属性详情和锚底管理按钮。
- 存活状态操作：召唤、收回、改名、模式、解绑。
- 已亡状态操作：重获魂石、解绑。
- 跳转：
  - “改名” → `RenameScreen.open(companionId, currentName)`。
  - “召唤” → `RequestSummonPacket`。
  - 其他管理操作 → `RecordActionPacket`，并请求刷新记录。
- 刷新：服务端回发 `openScreen=false` 的刷新包，客户端就地更新，不应重新打开界面。
- 关闭：关闭按钮或 ESC。
- 必须验证：刷新后选中项保持、滚动位置合理、按钮可用状态与绒亲状态一致。

### 3. 改名 `RenameScreen`

- 触发：在绒亲录详情卡点击“改名”。
- 输入框：预填当前名字，最大 32 字符。
- 确认：发送 `RecordActionPacket(RENAME, companionId, name)`。
- 取消或 ESC：不发送改名包。
- 重要待验点：与契约命名相同，确认 ESC/取消返回绒亲录还是世界，不能只靠静态推断。

### 4. 绒亲面板 `FurkinPanelScreen`

- 触发：潜行并右键本人、已契约且在场的绒亲；手持物品不能是 `FurkinContractItem`。
- 服务端入口：`CommonEvents` → `FurkinRecordActionHandler.openPanel`。
- 前置条件：
  - 目标 `isCompanion()`。
  - `companionId != null`。
  - `ownerUuid` 等于当前玩家 UUID。
- 打开顺序：先发送 `OpenFurkinScreenPacket` 技能快照，再通过 `NetworkHooks.openScreen` 打开 `FurkinPouchMenu`。
- 页签：技能、行囊、装备。
- 行囊禁用条件：行囊为 0 格时隐藏行囊页。
- 页签记忆：关闭重开后恢复上次有效页签；上次页签不可用时回到技能页。
- 特殊控件：
  - 技能 `+1` 自绘按钮。
  - `SkillListWidget extends AbstractScrollWidget`。
  - 武器/装备等属性悬停提示。
  - 洗点按钮和官方 `ConfirmScreen` 二次确认。
  - 战斗模式按钮。
- 拖动待验点：`mouseDragged` 必须显式转发给技能列表，滚动条拖动和内容拖动都要验证。
- 关闭：ESC 和可能的界面关闭路径。
- `isPauseScreen() == false`：打开界面时世界逻辑不应因“暂停屏幕”而被错误挂起。

## 依赖和测试顺序

```text
空记录
  └─ 打开/关闭 FurkinRecordScreen
       └─ 生成猫/狼
            └─ ContractNameScreen
                 ├─ 取消：不消耗契约
                 └─ 确认：创建绒亲
                      └─ FurkinRecordScreen 有数据
                           ├─ 选择详情
                           ├─ RenameScreen
                           ├─ 召唤/收回/模式/解绑/重获魂石
                           └─ 潜行右键本人绒亲
                                └─ FurkinPanelScreen
                                     ├─ 技能页
                                     ├─ 行囊页
                                     └─ 装备页
```

建议严格按上述依赖顺序执行。每次测试只改变必要状态，并在关键操作后检查 `run/logs/latest.log` 是否出现新增异常。

## 实机测试步骤

### WP9d.1 空绒亲录和基础关闭行为

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D1-01 | 尚未契约任何绒亲时，右键 `furkin:record` | 打开绒亲录，显示空列表提示，不崩溃 |
| D1-02 | 点击关闭按钮 | 返回世界，鼠标正常释放，游戏输入恢复 |
| D1-03 | 再次打开绒亲录，按 ESC | 返回世界，不重复打开或残留界面层 |
| D1-04 | 检查 `run/logs/latest.log` 和崩溃报告目录 | 无新增 GUI 异常或崩溃报告 |

### WP9d.2 契约命名

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D2-01 | 生成猫或狼，手持 `furkin:contract` 非潜行右键 | 打开 `ContractNameScreen` |
| D2-02 | 输入自定义名字，点击确认 | 契约成功，消耗 1 个契约，绒亲使用该名字 |
| D2-03 | 生成第二只，打开命名界面后取消 | 不消耗契约，不创建绒亲 |
| D2-04 | 生成第三只，打开命名界面后按 ESC | 不消耗契约，不创建绒亲；返回目标正确 |
| D2-05 | 对已契约目标再次右键契约 | 不弹命名界面，不发生重复契约 |
| D2-06 | 尝试输入或粘贴超过 32 字符 | 输入被限制或提交结果不超过 32 字符，行为明确 |
| D2-07 | 命名留空并确认 | 使用物种名，契约数量正确扣减 |

#### 实机结果

测试时间：2026-09-24 01:59-02:02（Asia/Shanghai）。

| 编号 | 结果 | 备注 |
|---|---|---|
| D2-01 | 通过 | 非潜行右键猫/狼后正常打开 `ContractNameScreen` |
| D2-02 | 通过 | 自定义名字确认成功，契约物品正确扣减 1 个 |
| D2-03 | 通过 | 取消后不消耗契约、不创建绒亲 |
| D2-04 | 通过 | ESC 后不消耗契约、不创建绒亲，返回路径正确 |
| D2-05 | 通过 | 已契约目标不重复弹窗、不重复契约 |
| D2-06 | 通过 | 输入限制为 32 字符，边界行为明确 |
| D2-07 | 通过 | 空名确认后回退物种名，契约物品正确扣减 |

日志中确认到三次契约事件。第三条对应空名回退；终端显示中文名字为乱码，但实机结果已确认 D2-07 通过。

#### 日志与崩溃检查

- 客户端正常退出并正常保存世界。
- Gradle：`BUILD SUCCESSFUL in 2m 48s`。
- `run/crash-reports` 不存在，无崩溃报告。
- 未发现 GUI、契约命名、ESC 或网络包相关异常。
- 已知独立资源问题仍存在：

```text
Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
```

该贴图错误与本次契约命名功能无关，应作为后续资源修复项单独处理。

### WP9d.3 绒亲录有数据与选中

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D3-01 | 已契约后再次右键 `furkin:record` | 列表显示名字、物种、等级和状态 |
| D3-02 | 点击不同绒亲 | 右侧详情随选中项变化 |
| D3-03 | 滚轮滚动列表和详情 | 列表/详情按各自区域滚动，不越界 |
| D3-04 | 拖动列表滚动条 | 列表按拖动位置滚动，不误触项目 |
| D3-05 | 关闭后重新打开 | 数据刷新，界面不重复叠加 |

#### 实机结果

测试时间：2026-09-24 01:24-01:55（Asia/Shanghai）。

| 编号 | 结果 | 备注 |
|---|---|---|
| D3-01 | 通过 | 列表正确显示名字、物种、等级和状态 |
| D3-02 | 通过 | 切换不同绒亲时右侧详情正确刷新 |
| D3-03 | 通过 | 列表与详情滚动正常，修复后内容不再越过各自区域 |
| D3-04 | 通过 | 可拖动滚动条，未误触列表项目 |
| D3-05 | 通过 | 关闭重开后数据刷新正常，未重复叠加界面 |

### WP9d.4 改名界面

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D4-01 | 在绒亲录点击“改名” | 打开 `RenameScreen`，输入框预填当前名字 |
| D4-02 | 修改名字并确认 | 名字更新；返回绒亲录后列表和详情就地刷新，选中项保持 |
| D4-03 | 再次改名后取消 | 名字不变，不发送改名包 |
| D4-04 | 再次改名后按 ESC | 名字不变；验证是否到达预期返回界面 |
| D4-05 | 输入 32 字符边界值和超过 32 字符 | 边界值可正常处理，超长值不会导致异常 |

#### 实机结果

测试时间：2026-09-24 02:18-02:47（Asia/Shanghai）。

| 编号 | 结果 | 备注 |
|---|---|---|
| D4-01 | 通过 | 点击“改名”正常打开 `RenameScreen`，输入框预填当前名字 |
| D4-02 | 通过 | 确认后名字更新并返回绒亲录；列表和详情就地刷新，选中项保持，滚动位置保持 |
| D4-03 | 通过 | 取消后名字不变、不发送改名包，并正确返回绒亲录 |
| D4-04 | 通过 | ESC 后名字不变，并正确返回绒亲录 |
| D4-05 | 通过 | 32 字符边界值和超长值均无异常；最终布局修改后，右侧详情完整保留名字截断规则，左侧仍在滚动条前截断 |

### WP9d.5 绒亲录管理动作

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D5-01 | 点击“召唤” | 绒亲状态更新，刷新后选中项保持 |
| D5-02 | 点击“收回” | 仅已召唤状态可操作；状态更新，刷新后选中项保持 |
| D5-03 | 点击“模式” | 仅已召唤状态可操作；模式切换结果正确 |
| D5-04 | 点击“解绑” | 需要二次确认时按官方确认流程执行；解绑结果与契约/魂石状态一致 |
| D5-05 | 制作并测试已亡状态 | 显示重获魂石和解绑按钮；重获魂石流程可用 |
| D5-06 | 反复执行管理操作 | 不重复打开界面、不丢失选中、不出现旧数据残留 |

#### 实机结果

测试日期：2026-09-24（Asia/Shanghai）。

| 编号 | 结果 | 备注 |
|---|---|---|
| D5-01 | 通过 | 修复后召唤状态更新，选中项保持 |
| D5-02 | 通过 | 收回后状态更新，按钮状态正确 |
| D5-03 | 通过 | 战斗模式切换结果正确 |
| D5-04 | 通过 | 解绑结果与契约、魂石状态一致 |
| D5-05 | 通过 | 重获魂石成功提示可用；面板打开时提示位于半透明面板后方，可接受 |
| D5-06 | 通过 | 反复操作不重开界面、不丢选中、无旧数据残留 |

D5-01 初次实测失败：服务端召唤已成功，但绒亲录仍显示旧状态。该问题已由 `WP9d-FIX-04` 修复并完成回归。

D5-05 说明：重获魂石与战斗模式切换共用 `RecordActionPacket` 的统一反馈分支；成功时调用 `player.displayClientMessage(..., true)` 显示 action bar。1.19.2 先绘制 action bar、再绘制当前 Screen，因此面板打开时提示位于面板后方；面板半透明，实测可接受。无需新增独立通知包或 Screen 内提示。

### WP9d.6 绒亲面板入口与页签

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D6-01 | 潜行右键本人、已契约且在场的绒亲 | 打开 `FurkinPanelScreen` |
| D6-02 | 非潜行右键同一绒亲 | 不打开面板，执行正常交互 |
| D6-03 | 潜行右键非本人绒亲 | 不打开面板 |
| D6-04 | 切换技能、行囊、装备页签 | 内容与页签一致，切换无错位 |
| D6-05 | 行囊为 0 格时观察页签 | 行囊页隐藏；不能切到不可用页面 |
| D6-06 | 关闭后重开 | 恢复上次有效页签；不可用页签回退到技能页 |

#### D6 实测结果（完成）

| 编号 | 结果 | 说明 |
|---|---|---|
| D6-01 | 通过 | 潜行右键本人、已契约且在场的绒亲可打开 `FurkinPanelScreen`；按钮底边与行距修复后，滚动、滚动条拖动和按钮命中均正常 |
| D6-02 | 通过 | 非潜行右键同一绒亲不打开面板，执行正常交互 |
| D6-03 | 通过 | 潜行右键非本人绒亲不打开面板 |
| D6-04 | 通过 | 技能、行囊、装备页签内容一致，切换无错位或残影 |
| D6-05 | 通过 | 行囊为 0 格时页签隐藏，不能进入不可用页面 |
| D6-06 | 通过 | 关闭后重开恢复上次有效页签；不可用页签回退到技能页 |

D6-01 补充：方案 2 移除技能列表黑底后，实机先后发现 `+1` 按钮底边素材缺失和列表行距过紧。两项视觉问题已修复并回归通过。

D6 结论：D6-01 至 D6-06 全部通过。

### WP9d.7 面板技能与滚动

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D7-01 | 技能列表滚轮滚动 | 列表滚动，页签和按钮不误动 |
| D7-02 | 拖动技能列表滚动条 | 滚动条拖动有效，释放后状态正确 |
| D7-03 | 在技能列表空白区按住拖动 | 官方语义不要求内容拖动；不得误触技能、`+1` 或页签 |
| D7-04 | 悬停已点/未点技能 | 显示正确的名称、描述、等级和消耗 |
| D7-05 | 点击可加点的 `+1` | 技能等级和点数更新，服务端状态一致 |
| D7-06 | 点击点数不足或前置不满足的 `+1` | 按钮置灰或不可点，不产生错误状态 |
| D7-07 | 悬停技能面板上侧抬头区域 | 显示完整属性明细 tooltip，内容与当前绒亲实时属性一致；移出后消失，不影响技能行提示 |

#### D7 实测结果（已通过）

测试日期：2026-09-24（Asia/Shanghai）。

| 编号 | 结果 | 说明 |
|---|---|---|
| D7-01 | 通过 | 初次打开技能面板后，无需先点击列表，鼠标移入技能列表即可直接滚轮；列表滚动，页签和按钮不误动。修复前首次滚轮会被 `AbstractScrollWidget` 的焦点判断拦截 |
| D7-02 | 通过 | 拖动技能列表右侧滚动条时列表同步滚动；释放后位置保持，未误触技能或 `+1`，随后滚轮仍正常 |
| D7-03 | 通过（调整预期） | 按 0 代码方案保留 1.19.2 官方拖动语义：空白区不作为稳定拖动入口，不影响滚轮和滚动条拖动 |
| D7-04 | 通过 | 悬停已点与未点技能时，名称、描述、等级和升级消耗均正确显示 |
| D7-05 | 通过 | 点击满足条件的 `+1` 后等级增加、可用点数减少；关闭并重新打开面板后状态仍一致 |
| D7-06 | 通过 | 不可加点的 `+1` 呈禁用状态；点击后等级和点数不变，重开面板后状态仍一致 |
| D7-07 | 通过 | 悬停技能面板上侧抬头区域时显示完整属性明细 tooltip；移出后消失，技能行提示不受影响 |

D7-03 调整说明（0 代码方案）：1.19.2 与 1.20.1 的官方 `AbstractScrollWidget` 都只在点击滚动条时进入内部拖动状态，空白区点击仅负责聚焦；若滚动条拖动后在列表外松手，官方状态可能残留一次。为不复制或改写官方拖动算法，本项不要求空白区稳定拖动，只要求不误触技能、`+1` 或页签；滚轮和滚动条拖动仍按 D7-01、D7-02 验收。

### WP9d.8 面板确认框和战斗模式

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D8-01 | 点击洗点按钮 | 打开官方 `ConfirmScreen`，显示正确提示 |
| D8-02 | 在洗点确认框取消 | 点数不变，回到面板 |
| D8-03 | 在洗点确认框确认 | 洗点结果生效，技能状态和可用点数刷新 |
| D8-04 | 连续点击战斗模式按钮 | 按 `跟随 → 被动 → 保护 → 主动 → 跟随` 顺序循环切换，文字即时变化，重开面板后状态保持 |
| D8-05 | 从面板拖动或点击 | 不误打开绒亲录，不把面板事件传给下层界面 |

#### D8 实测结果（已通过）

测试日期：2026-09-24（Asia/Shanghai）。

| 编号 | 结果 | 说明 |
|---|---|---|
| D8-01 | 通过 | 点击洗点按钮后正常打开官方 `ConfirmScreen`，提示文字正确完整 |
| D8-02 | 通过 | 在洗点确认框取消后返回技能面板，技能点与技能等级均未变化；重开面板后状态仍一致 |
| D8-03 | 通过 | 确认洗点后技能状态和可用点数正确刷新，重开面板后保持一致；`latest.log` 中 `pouch 18 -> 0 slots` 与结果一致，未发现洗点相关异常，且无新增崩溃报告。日志中的 `bleeding.png` 缺失为该操作之前已有的无关问题 |
| D8-04 | 通过 | 战斗模式共四档，按 `跟随 → 被动 → 保护 → 主动 → 跟随` 顺序循环切换；每次点击文字即时变化，重开面板后保持最后一次选择 |
| D8-05 | 通过 | 面板空白区、技能列表空白处和边缘的点击/拖动均未误打开绒亲录，未误触技能、页签、`+1`、洗点或战斗模式按钮；事件未穿透到下层界面 |

D8 结论：D8-01 至 D8-05 全部通过。

### WP9d.9 各界面关闭和日志验收

| 编号 | 操作 | 预期结果 |
|---|---|---|
| D9-01 | 对四个界面分别测试关闭按钮（若存在） | 返回正确界面/世界 |
| D9-02 | 对四个界面分别测试 ESC | 返回正确界面/世界，不重复弹层 |
| D9-03 | 在输入框聚焦时测试 ESC | 按 1.19.2/Forge 实际行为执行，结果记录并判定是否合理 |
| D9-04 | 检查 `run/logs/latest.log` | 无新增 NullPointerException、ClassCastException、网络包异常或 GUI 栈异常 |
| D9-05 | 检查 `run/crash-reports` | 无新增崩溃报告 |
| D9-06 | 退出世界并正常关闭客户端 | 存档保存成功，Java 进程无残留 |

#### D9 实测结果（已通过）

测试日期：2026-09-24（Asia/Shanghai）。

| 编号 | 结果 | 说明 |
|---|---|---|
| D9-01 | 通过 | `FurkinRecordScreen` 点击“关闭”返回世界；`FurkinPanelScreen` 无独立关闭按钮，D9-01 不适用；`ContractNameScreen` 点击“取消”返回世界且未完成契约；`RenameScreen` 点击“取消”返回绒亲录且名字未变。未出现重复界面或错误返回 |
| D9-02 | 通过 | 四个界面在输入框未聚焦时按 ESC 均返回预期界面/世界；`RenameScreen` 先返回绒亲录，再按 ESC 返回世界，未出现重复 GUI 层、残留界面或输入锁定 |
| D9-03 | 通过 | `ContractNameScreen` 与 `RenameScreen` 的输入框聚焦时按 ESC 均直接关闭；代码未覆盖 `keyPressed`，符合 1.19.2 官方 `Screen` 的 ESC 处理路径 |
| D9-04 | 通过 | 检查当前会话 `latest.log`，未发现 `NullPointerException`、`ClassCastException`、网络包异常或 GUI 栈异常；已有的 `bleeding.png` 缺失、Realms 授权和 OSHI 系统信息警告均与 WP9d 测试操作无关 |
| D9-05 | 通过 | `run/crash-reports` 不存在，无新增崩溃报告 |
| D9-06 | 通过 | 正常返回主菜单并退出客户端；日志记录玩家断开、`Saving players`、`Saving worlds`、三个维度 `All chunks are saved`、`Stopping server` 和 `Stopping!`，`runClient` 以 `BUILD SUCCESSFUL` 结束；未检出残留客户端 Java 进程，也无新增崩溃报告 |

D9 结论：D9-01 至 D9-06 全部通过。

## 官方法行为核对重点

1. `Screen.keyPressed` 在 `shouldCloseOnEsc()` 为真时调用 `onClose()`。
2. 1.19.2 的 `Screen.onClose()` 调用 `Minecraft.popGuiLayer()`。
3. Forge 的 GUI 层栈在栈空时由 `ForgeHooksClient` 调回 `Minecraft.setScreen(null)`。
4. `ContractNameScreen` 和 `RenameScreen` 由业务代码直接 `setScreen` 打开，因此不能用 1.20.1 的行为直接假设返回路径。
5. 所有 ESC 结果都要以实机、日志和界面层状态为准；若返回错误，先查官方 Forge 层栈语义，再决定修复方式。

## 失败判据

出现以下任一情况即判定 WP9d.1 未通过：

- 客户端崩溃、卡死或出现新增 crash report。
- 界面无法打开、无法关闭或关闭后输入被锁定。
- 取消/ESC 仍发送确认包，导致误消耗契约或误改名。
- 属性、技能、模式或绒亲状态与服务端数据不一致。
- 刷新后选中项丢失、界面重复叠加或旧数据残留。
- 滚动条、拖动区域与按钮命中区域错位。
- 面板打开时错误触发绒亲录，或事件穿透到下层界面。
- 日志出现与本次操作直接相关的新异常。

## 证据与记录要求

每次失败或关键行为至少记录：

- 测试编号和复现步骤。
- 当时的界面/世界状态。
- `run/logs/latest.log` 中对应时间段的日志。
- 如有崩溃，记录 `run/crash-reports` 中新增报告的文件名。
- 操作系统与 Java 版本、Minecraft 版本、Forge 版本。
- 失败截图（如果 Codex UI 可直接获取）或准确的屏幕文字。

WP9d.1 完成后在本文件回填：

- 各编号的通过/失败状态。
- 实际返回界面和 ESC 行为。
- 已确认可复现的问题。
- 需要进入后续修复工作包的问题清单。

## 后备方案

### 方案 2：将界面重构为使用显式父屏/导航栈

如果 WP9d.1 实测发现以下任一问题：

- `ContractNameScreen` 或 `RenameScreen` 的 ESC/取消不能稳定返回预期界面。
- Forge `popGuiLayer()` 与 Minecraft 原生界面层栈出现不一致。
- 从绒亲录打开改名后关闭，偶发回到世界或残留 GUI 层。
- 多个界面连续打开/关闭时出现重复回调或输入锁定。

则采用后备方案：

1. 不直接改变 1.19.2 官方 `Screen`/Forge GUI 层行为。
2. 为业务 Screen 引入显式父屏引用或轻量导航上下文。
3. 取消时优先恢复显式父屏；没有父屏时再调用官方关闭路径。
4. 将导航上下文限制在客户端 `Screen` 生命周期内，不写入存档。
5. 新增回归测试覆盖：世界 → 绒亲录 → 改名 → ESC，以及世界 → 契约命名 → ESC。
6. 保留官方 `popGuiLayer()` 作为最终兜底，避免破坏其他 Forge 模组的 GUI 栈。

方案 2 已在 D4-02 达到触发条件，并针对 `RenameScreen` <b>局部启用</b>：由绒亲录显式传入父屏，确认、取消和 ESC 统一返回该父屏；不改写 1.19.2 原生 `Screen` / Forge GUI 层栈行为，也不把全部业务界面迁移到导航栈。

`ContractNameScreen` 仍保持官方关闭路径，因为契约命名取消/ESC 返回世界的实测结果正确。方案 2 的其余部分继续作为后备方案保留，只有后续出现稳定复现的层栈问题时才扩大适用范围。

## 当前状态

- WP9d.0：只读梳理完成。
- WP9d.1：已完成。D1-01 至 D1-04、D2-01 至 D2-07、D3-01 至 D3-05、D4-01 至 D4-05、D5-01 至 D5-06、D6-01 至 D6-06、D7-01 至 D7-07、D8-01 至 D8-05、D9-01 至 D9-06 全部通过。
- WP9d-FIX-01：输入提示词迁移修复记录已补入。
- WP9d-FIX-02：绒亲录属性列表越界绘制修复已补入，并通过编译、字节码和实机验证。
- WP9d-FIX-03：改名返回、滚动位置恢复和长名字布局修复已补入；方案 2 已对 `RenameScreen` 局部启用。
- WP9d-FIX-04：召唤成功路径复用绒亲录刷新入口，修复召唤后列表状态不刷新；已通过编译和实机回归。
- WP9d-FIX-05：技能面板 `+1` 按钮底边与技能列表行距修复已补入；已通过编译和实机回归。
- WP9d-FIX-06：技能列表首次滚轮焦点修复已补入；无需先点击列表即可滚动，D7-01 已通过。
- 本文件中的单次通过结论仅代表对应测试项，不代表 WP9d 全部完成。

## WP9d-FIX-01：输入提示词迁移修复记录

### 问题

契约命名界面和改名界面存在同一显示缺陷：

- 输入框为空时，灰色提示词显示正常。
- 键入文字后，灰色提示词没有消失，而是从现有文字末尾继续显示，视觉上表现为“跟着文字向后移动”。

用户手动复现后提供了截图；该问题随后进入修复与回归验证。

### 根因

1.20.1 原实现使用 `EditBox.setHint(Component)`，其在有真实输入文字时不会把提示词当作追加文本继续绘制。

1.19.2 的官方 `EditBox` 没有 `setHint(...)`，只有 `setSuggestion(String)`。目标版本映射和字节码核对结果：

- `setSuggestion(String)` 是 1.19.2 可用的官方提示 API。
- `renderButton` 在 `suggestion != null` 且尚未达到 `maxLength` 时，会在当前输入文本末尾继续绘制 suggestion。
- 因此把 1.20.1 的 `setHint(...)` 直接替换为 `setSuggestion(...)` 会改变语义：有输入时 suggestion 仍存在，并表现为跟随文字移动。

这不是字体、坐标、阴影或布局问题，而是跨版本 API 语义迁移缺陷。

### 方案 1：最小修复

不绕开官方 API，也不复制 `EditBox` 的渲染逻辑；继续使用 1.19.2 官方 `setSuggestion(String)`，但让它只在该输入框为空时存在。

修改文件：

- `src/main/java/com/wanancat/furkin/internal/client/ContractNameScreen.java`
- `src/main/java/com/wanancat/furkin/internal/client/RenameScreen.java`

实现要点：

```java
String hint = Component.translatable("<hint.translation.key>").getString();
this.nameInput.setSuggestion(hint);
this.nameInput.setResponder(value ->
        this.nameInput.setSuggestion(value.isEmpty() ? hint : null));
```

`RenameScreen` 在安装 responder 后再设置预填名字，因此非空预填会自动清除提示词；清空输入时 responder 会恢复提示词。

### 编译验证

使用 JDK 17.0.2 执行：

```text
.\gradlew.bat compileJava --console=plain

Java: 17.0.2, JVM: 17.0.2+8-LTS-86 (Oracle Corporation), Arch: amd64
> Task :compileJava
BUILD SUCCESSFUL in 9s
```

### 实机回归结果

测试时间：2026-09-24 01:24-01:26（Asia/Shanghai）。

| 编号 | 操作 | 结果 |
|---|---|---|
| A1 | 契约命名输入框保持为空 | 通过：灰色提示正常显示且位置固定 |
| A2 | 契约命名输入框输入文字 | 通过：提示立即消失，不跟随文字 |
| A3 | 全选并清空输入 | 通过：提示重新出现 |
| A4 | 再次输入文字 | 通过：提示不出现 |
| A5 | 契约命名取消或 ESC | 通过：正常返回世界，无异常 |
| B1 | 改名界面显示预填名字 | 通过：显示现有名字，不显示灰色提示 |
| B2 | 清空改名输入 | 通过：灰色提示出现 |
| B3 | 改名输入文字 | 通过：灰色提示立即消失 |
| B4 | 再次清空改名输入 | 通过：灰色提示重新出现 |
| B5 | 改名取消或 ESC | 通过：正常取消，不误改名 |

### 日志与崩溃检查

- 客户端正常退出并正常保存世界。
- Gradle：`BUILD SUCCESSFUL in 2m 34s`。
- `run/crash-reports` 不存在，无崩溃报告。
- 未发现契约命名或改名界面的异常、`ERROR`、`FATAL`。
- 已知独立资源问题仍存在：

```text
[01:24:16] [Worker-Main-8/ERROR] [TextureAtlas/]:
Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
```

该贴图错误发生在客户端启动阶段，早于本次 GUI 操作，与提示词修复无关；应作为后续资源修复项单独处理。

### 方案 2 状态

本文档中的“方案 2：将界面重构为使用显式父屏/导航栈”继续保留为后备方案。本次输入提示词问题通过方案 1 的官方 API 语义修正解决，未启用方案 2，也未修改 1.19.2 原生或 Forge GUI 层栈行为。

## WP9d-FIX-02：绒亲录属性列表越界绘制修复记录

### 问题

在绒亲录右侧拖动属性滚动条后，只显示一部分的属性行仍被完整绘制：

- 上半部分进入固定的“属性”标题区域。
- 下半部分进入下方“召唤 / 改名 / 解绑”按钮区域。

### 根因

1.19.2 的 `AbstractSelectionList.renderList(...)` 只判断属性行是否与 `y0` / `y1` 区域相交。只要相交，就会完整调用 `Entry.render(...)`，列表本身没有启用裁剪。

官方字节码核对结果：

- `AbstractSelectionList` 的 `x0`、`y0`、`x1`、`y1` 是 `protected` 字段，其中 `x1 = x0 + width`。
- `GuiComponent.enableScissor(int, int, int, int)` 和 `GuiComponent.disableScissor()` 是 1.19.2 公开静态方法。
- 官方 `AbstractScrollWidget.renderButton(...)` 在绘制滚动内容前调用 `enableScissor(...)`，绘制完成后调用 `disableScissor()`。

因此不能依赖 `AbstractSelectionList.renderList(...)` 自动处理越界；需要在属性列表这一层显式限制绘制区域。

### 方案 1：最小修复

修改文件：

- `src/main/java/com/wanancat/furkin/internal/client/FurkinRecordScreen.java`

在 `RecordAttributeList` 中覆写 `render(...)`，仅把绘制过程包在属性列表的 `x0`、`y0`、`x1`、`y1` 视口内：

```java
@Override
public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
    enableScissor(this.x0, this.y0, this.x1, this.y1);
    super.render(pose, mouseX, mouseY, partialTick);
    disableScissor();
}
```

不修改 1.19.2 原生或 Forge GUI 层行为，也不复制字体或列表的渲染逻辑。

### 过程说明

第一次实机验证未生效。排查发现，整理中文注释时替换范围误包含了紧随其后的整个 `render(...)` 方法，导致源码中只剩注释、没有覆写方法；因此编译虽然成功，类文件却不包含裁切调用。

随后补回方法，强制执行 `compileJava --rerun-tasks`，再用 `javap` 核对类文件，确认以下指令链已实际编入：

```text
x0, y0, x1, y1
→ enableScissor
→ AbstractSelectionList.render
→ disableScissor
```

### 编译与产物验证

使用 JDK 17.0.2 执行：

```text
.\gradlew.bat compileJava --rerun-tasks --console=plain

Java: 17.0.2, JVM: 17.0.2+8-LTS-86 (Oracle Corporation), Arch: amd64
> Task :compileJava
BUILD SUCCESSFUL in 11s
```

编译产物 `FurkinRecordScreen$RecordAttributeList.class` 已确认包含 `render(...)`、`enableScissor` 和 `disableScissor` 调用。

### 实机回归结果

测试时间：2026-09-24 01:53-01:54（Asia/Shanghai）。

| 检查项 | 结果 |
|---|---|
| 属性文字进入“属性”标题区域 | 已修复，不再发生 |
| 属性文字进入下方按钮区域 | 已修复，不再发生 |
| 属性列表滚轮滚动 | 通过 |
| 右侧滚动条拖动 | 通过 |
| 左侧绒亲列表滚动与选中 | 通过 |
| D3-01 至 D3-05 回归 | 全部通过 |

### 日志与崩溃检查

- 客户端正常退出并正常保存世界。
- Gradle：`BUILD SUCCESSFUL in 1m 41s`。
- `run/crash-reports` 不存在，无崩溃报告。
- 未发现 GUI、裁剪或 `FurkinRecordScreen` 相关异常。
- 已知独立资源问题仍存在：

```text
[01:52:59] [Worker-Main-14/ERROR] [TextureAtlas/]:
Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
```

该贴图错误发生在客户端启动阶段，与属性列表裁剪无关，应作为后续资源修复项单独处理。

## WP9d-FIX-03：改名返回、滚动位置与长名字布局修复记录

### 问题

D4 实机测试依次暴露了三个 1.19.2 迁移差异：

1. 从绒亲录进入改名界面后，取消、ESC 或确认会回到世界，而不是返回绒亲录。
2. 返回绒亲录时列表跳回顶部，当前选中项没有保持原滚动位置。
3. 32 字符名字在右侧抬头区域视觉溢出；左侧列表的长名字也必须限制在滚动条之前。

### 根因

- 1.19.2 的 `Screen.onClose()` 会执行 `Minecraft.popGuiLayer()`。
- `RenameScreen` 由业务代码通过 `Minecraft.setScreen(...)` 直接打开，并未向 Forge GUI 层栈压入绒亲录层；因此官方关闭路径无法恢复父屏。
- 返回父屏必须显式调用 `Minecraft.setScreen(parent)`。该调用会重新执行父屏 `init()`，所以列表滚动量会被重置。
- `AbstractSelectionList.renderList(...)` 不负责按像素截断文本；名单和详情抬头必须在绘制前自行测量并截断。

### 方案

#### 1. 显式父屏返回（方案 2 局部启用）

修改文件：

- `src/main/java/com/wanancat/furkin/internal/client/RenameScreen.java`
- `src/main/java/com/wanancat/furkin/internal/client/FurkinRecordScreen.java`

`RenameScreen` 保存打开它的绒亲录实例，`open(...)` 接收并传递父屏；确认、取消和 ESC 统一调用 `returnToParent()`：

```java
private void returnToParent() {
    if (this.parent != null && this.minecraft != null) {
        this.minecraft.setScreen(this.parent);
    } else {
        super.onClose();
    }
}
```

这是对后备方案 2 的局部启用，不全局重构 GUI 导航；`ContractNameScreen` 仍使用原有官方关闭路径，因其返回世界的实测行为正确。

#### 2. 恢复绒亲录滚动位置

官方 `AbstractSelectionList` 已提供：

- `getScrollAmount()`
- `setScrollAmount(double)`

`FurkinRecordScreen.rebuild()` 重建列表前保存旧滚动量，装配并恢复选中项后写回，覆盖父屏重新初始化和服务端刷新两条路径。

#### 3. 长名字截断

使用官方 `Font` API：

- `width(...)` 测量像素宽度。
- `split(...)` 按宽度拆分带样式文本。
- `plainSubstrByWidth(...)` 在极窄空间内截取纯文本。

增加 `drawTruncatedString(...)` 辅助方法；超宽文本末尾追加 `...`。

布局规则：

- 左侧绒亲列表按滚动条前可用宽度截断。
- 右侧优先完整保留“物种、等级、经验、技能点、状态”，名字只使用剩余宽度并在不足时截断。
- 当详细信息本身超过可用宽度时，才截断详细信息。

### 编译验证

使用 JDK 17.0.2 执行：

```text
.\gradlew.bat compileJava --rerun-tasks --console=plain

Java: 17.0.2, JVM: 17.0.2+8-LTS-86 (Oracle Corporation), Arch: amd64
> Task :compileJava
BUILD SUCCESSFUL in 11s
```

### 实机回归结果

测试时间：2026-09-24 02:44 起（Asia/Shanghai）。

| 检查项 | 结果 |
|---|---|
| D4-01 打开改名界面并预填名字 | 通过 |
| D4-02 确认后返回绒亲录、刷新并保持选中 | 通过 |
| D4-02 返回后保持原滚动位置 | 通过 |
| D4-03 取消不发包并返回绒亲录 | 通过 |
| D4-04 ESC 不发包并返回绒亲录 | 通过 |
| D4-05 32 字符边界值和超长值 | 通过，无异常 |
| 右侧完整保留物种、等级、经验、技能点、状态 | 通过 |
| 右侧名字仅在剩余宽度不足时截断并显示 `...` | 通过 |
| 左侧列表在滚动条前截断 | 通过 |
| 普通短名字和状态颜色 | 通过 |

### 当前结论

- D4-01 至 D4-05 全部通过。
- 方案 2 已对 `RenameScreen` 局部启用，其余部分继续保留为后备方案。
- 完整关闭、日志和崩溃报告验收仍按 D9 执行。
- 已知独立资源问题仍存在：`furkin:textures/mob_effect/bleeding.png` 缺失，与本次改名和布局修复无关。

## WP9d-FIX-04：召唤后绒亲录状态刷新修复记录

### 问题

D5-01 点击“召唤”后，服务端已经成功召唤，但绒亲录仍显示召唤前的旧状态。

### 根因

`RequestSummonPacket` 的成功分支只发送召唤/传送反馈，没有回发最新绒亲列表；客户端因此不会触发绒亲录的就地刷新。

### 修复

成功反馈后复用既有刷新入口：

```java
FurkinRecordItem.refreshRecordList(player);
```

该入口使用 `openScreen=false` 语义，不重开 `FurkinRecordScreen`，只刷新当前列表数据。

### 验证

- `compileJava --rerun-tasks` 通过，`BUILD SUCCESSFUL`。
- 重启最新构建后，D5-01 实机复测通过：召唤状态更新，选中项保持。

## WP9d-FIX-05：技能面板按钮底边与列表行距调整记录

### 问题

启用滚动列表方案 2 后，技能面板实机回归发现两项视觉问题：

- 行内 `+1` 按钮位置正常，但底部素材显示不完整。
- 技能列表每行间距过紧，按钮之间没有垂直空隙。

### 根因

- 1.20.1 原实现依赖 `GuiGraphics#blitNineSliced(...)`，可把官方 200×20 按钮贴图压缩到 22×14。1.19.2 没有该现成方法；直接复用 `AbstractWidget#renderButton` 画 14px 高按钮时，只采样官方贴图顶部 14px，底部 6px 被裁掉。
- 原 `ROW_HEIGHT_MIN = 20`，与 20px 高的官方按钮相同。当前 5 行技能对应的列表可用高度约为 96px，最终每行仍计算为 20px，因此行间没有额外空隙。

### 调整

修改文件：

- `src/main/java/com/wanancat/furkin/internal/client/FurkinPanelScreen.java`

1. `SKILL_BUTTON_HEIGHT` 由 `14` 改为官方原生高度 `20`，不再裁掉底部素材；按钮命中区继续复用同一常量。
2. `ROW_HEIGHT_MIN` 由 `20` 改为 `24`，`ROW_HEIGHT_MAX` 由 `26` 改为 `32`。当前每行增加 4px 垂直间距，内容超出后继续交给官方 `AbstractScrollWidget` 滚动。

未自行移植九宫格缩放逻辑，优先复用 1.19.2 官方按钮原生尺寸和既有滚动容器。

### 验证

- 使用 Java 17.0.2 执行 `compileJava --rerun-tasks`，结果 `BUILD SUCCESSFUL in 10s`。
- 重启客户端后，D6-01 回归通过：列表无黑底和灰白外框，`+1` 按钮底边完整，行距更宽松，按钮命中、滚轮和滚动条拖动正常。

## WP9d-FIX-06：技能列表首次滚轮焦点修复记录

### 问题

首次打开技能面板时，鼠标不点击技能列表而直接滚动滚轮无效；必须先点击列表一次，后续滚轮才生效。

### 根因

1.19.2 的 `AbstractScrollWidget#mouseScrolled(...)` 会先检查 `visible && isFocused()`，未聚焦时直接返回 `false`。

面板初开时，屏幕虽然已经把技能列表登记为当前焦点控件，但列表自身内部聚焦状态仍为 `false`。`Screen#setFocused(...)` 只更新父容器的焦点引用，不会替 `AbstractWidget` 写入其内部聚焦位，因此仅调用外层 `setFocused(this.skillList)` 仍不足以让官方滚轮逻辑放行。

### 修复

修改文件：

- `src/main/java/com/wanancat/furkin/internal/client/FurkinPanelScreen.java`

1. 在面板层重写 `mouseScrolled(...)`：当鼠标位于技能列表或滚动条范围内时，先把屏幕焦点与列表自身内部焦点同步，再调用官方 `skillList.mouseScrolled(...)`。
2. 在 `SkillListWidget` 中增加 `focusForScroll()`，由控件自身调用 `setFocused(true)` 更新内部聚焦位；没有复制或改写官方滚动算法。
3. 鼠标不在技能列表范围内时，继续调用 `super.mouseScrolled(...)`，避免影响其他页面和控件。

### 验证

- 使用 Java 17.0.2 执行 `compileJava --rerun-tasks`，结果 `BUILD SUCCESSFUL in 11s`。
- 重启客户端并进入世界后，D7-01 实机通过：首次打开技能面板，无需点击列表即可直接滚轮；列表正常滚动，页签和按钮不误动。
