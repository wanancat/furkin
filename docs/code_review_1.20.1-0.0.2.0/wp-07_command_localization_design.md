# WP-07 命令回执本地化设计

- 文档状态：实现、构建、双语言烟测和旧注释清理已完成；异常注入分支保留为残余风险；已提交并推送至 `origin/mc1.20.1/dev`（`f4db61f`），工作区干净
- 审计日期：2026-09-25
- 基线提交：`46164fc`（`fix: 隔离网络包客户端类引用`）
- 对应问题：`L-02` 命令回执硬编码英文
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0
- 关联工作流：`docs/code_review_1.20.1-0.0.2.0/code_review_2026-09-24_workflow.md`

---

## 1. 目标与边界

### 1.1 目标

让 `furkin` 命令的玩家可见回执随客户端语言切换，同时保持：

- 命令执行和权限判断仍由服务端负责。
- 中英文语言文件键集合完全同步。
- 动态值通过 translation args 传入，不把服务端语言下的字符串拼死后下发给客户端。
- 不影响命令树、参数类型、权限、业务行为、网络协议和存档格式。

### 1.2 范围内

- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java`。
- `src/main/resources/assets/furkin/lang/en_us.json`。
- `src/main/resources/assets/furkin/lang/zh_cn.json`。
- 命令回执、错误、状态后缀、列表行、成功提示和复制提示。

### 1.3 范围外

- 命令字面量本身：`furkin`、`summon`、`list`、`forget`、`force` 等仍是稳定的 Brigadier 标识符，不作为翻译键。
- 参数名、UUID、玩家自定义名称和物品动态名称本身。
- 客户端界面、网络包和依赖该命令的业务逻辑。
- 1.19.2 分支移植。

---

## 2. 其他模组与原版的常规做法

本次检索先看本地原版 1.20.1 资源包和下载的模组源码，结论用于确认 `FurkinCommand` 的整改口径，不把单个模组的写法当成 Forge API 约束。

### 2.1 原版 Minecraft 1.20.1

原版 `assets/minecraft/lang/en_us.json` 使用 `commands.*` 命名空间承载命令回执，动态值通过 `%s` 参数插入。例如：

- `commands.summon.success`: `Summoned new %s`
- `commands.give.success.single`: `Gave %s %s to %s`
- `commands.help.failed`: `Unknown command or insufficient permissions`

这说明玩家的命令结果属于可本地化文本，而不是只能保留英文的调试输出。命令标识符与显示回执分开；参数位置由翻译模板决定，不能靠固定顺序的字符串拼接。

### 2.2 Twilight Forest 1.20.1

参考路径：

- `D:\frukin_dev\_research\twilightforest-1.20.1\src\main\java\twilightforest\command\TFCommand.java`
- `D:\frukin_dev\_research\twilightforest-1.20.1\src\main\java\twilightforest\command\InfoCommand.java`
- `D:\frukin_dev\_research\twilightforest-1.20.1\src\main\resources\assets\twilightforest\lang\zh_cn.json`

观察到的常规做法：

1. 命令反馈使用 `Component.translatable("commands.tffeature.*", args...)`。
2. `sendSuccess` 的回调返回可翻译组件。
3. 参数错误或运行条件错误使用 `SimpleCommandExceptionType(Component.translatable(...))`。
4. 动态名称、数量和位置作为 translation args 传入，例如 `commands.tffeature.structure.spawn_info`。
5. 中文语言文件与英文语言文件使用同一批 `commands.tffeature.*` 键。
6. 不是所有命令都绝对清零：`MapBiomesCommand` 的调试/管理输出仍有 `Component.literal`，`InfoCommand` 也有一个不规范的 `Component.translatable("This command is still WIP...")`。这说明大型模组通常优先保证正式玩家命令本地化，但调试命令可能留下例外。

本仓库的 `FurkinCommand` 面向玩家日常使用，因此采用比调试命令更严格的口径：正式回执不再保留英文 `literal`。

### 2.3 Ice and Fire / Alex's Mobs 快照

- `D:\frukin_dev\_research\IceAndFire-src`：未检索到命令注册入口或命令类。
- `D:\frukin_dev\_research\AlexsMobs-src`：未检索到 `RegisterCommandsEvent`、`Commands.literal(...)` 或命令类。

因此这两份快照不能提供“模组命令回执如何处理”的有效样本，不作为本次实现依据。

### 2.4 归纳出的常规操作

- 命令反馈、失败信息和用法错误都使用翻译键。
- 使用 `commands.<mod>.*` 或项目自己的消息命名空间；本仓库沿用既有 `furkin.command.*` 与 `furkin.msg.*`。
- 一条完整语义对应一个键，不让翻译者拼装“前半句 + 后半句”的碎片。
- UUID、数量、名称、物品名等动态值作为 translation args 传入。
- 能传 `Component` 的名称/物品名时传组件，避免在服务端过早 `getString()` 固定语言。
- 纯动态值、空格、标点、调试标记可以保留 `Component.literal`，不应为了清零而给每个符号制造翻译键。

---

## 3. 盘点结果

### 3.1 基线范围

以 `46164fc` 中 `FurkinCommand.java` 为基线，命令树注册入口只有 `CommonEvents#onRegisterCommands`，命令回执集中在 `FurkinCommand`。

基线中该文件有 77 行包含 `Component.literal`（78 个调用点）。其中玩家可见回执主要是：

| 命令域 | 基线硬编码示例 | 处理结果 |
|---|---|---|
| 通用参数错误 | `Invalid pet id: ...` | 复用 `furkin.msg.invalid_pet_id` |
| 召唤 | `Summoned ...`、`Teleported ...`、`No such companion: ...`、`Active companion limit reached.` | 迁移到 `furkin.command.summon.*` / `furkin.command.companion.*` |
| 列表 | `Your companions:`、`[Fallen]`、`[Summoned]`、`Click to copy ID`、`Lv.`、`xp`、`sp`、`(none)` | 迁移到 `furkin.command.list.*` |
| 改名 | `Renamed ... → ...` | 迁移到 `furkin.command.rename.success`，失败键复用既有消息 |
| 加经验 | `gained ... xp`、`[LEVEL UP]`、实体/召唤状态错误 | 迁移到 `furkin.command.addxp.*` / `furkin.command.companion.*` |
| 战斗模式 | 无效模式、模式设置成功、未召唤、设置失败 | 迁移到 `furkin.command.mode.invalid`，其余复用既有消息 |
| 技能 | 解锁成功、未知技能、物种/前置/点数/满级错误 | 迁移到 `furkin.command.skill.*`，复用既有技能错误消息 |
| 查看 | `Inspect ...`、属性行、行囊行 | 迁移到 `furkin.command.inspect.*` |
| 行囊添加/清空 | 空物品、实体不存在、数据缺失、成功、满仓、部分放入、清空成功 | 迁移到 `furkin.command.pouch.*` / `furkin.command.companion.*` |

说明：`forget` 的既有回执在基线中已经使用翻译键，本次只保持并复用现有消息，没有新增重复键。

### 3.2 当前残留 `literal`

整改后 `FurkinCommand.java` 仅剩 7 个 `Component.literal` 调用：

- 短 UUID 与完整 UUID。
- 列表行的缩进空格。
- 玩家自定义名称。
- 未注册物种的回退符号 `?`。

这些值是动态显示内容或结构性符号，不属于需要翻译的固定语句，保留 `literal` 不会造成英文界面泄露。

### 3.3 其他命令检查

全项目检索 `RegisterCommandsEvent`、`Commands.literal(...)` 和 `CommandSourceStack` 后，没有发现第二个独立的命令注册实现。该工作项不需要修改其他命令类。客户端界面中的数值/符号字面量不属于 L-02，且不在本工作包范围内。

---

## 4. 本步骤完成内容

### 4.1 代码

- 将 `FurkinCommand` 中玩家可见的固定回执改为 `Component.translatable`。
- 动态参数继续通过 translation args 传入。
- 列表状态、经验、技能点和悬浮复制提示改为可翻译键。
- 行囊成功、满仓、部分放入和清空回执改为完整翻译模板，避免中英文语序被字符串拼接破坏。
- 战斗模式名复用现有 `furkin.combat_mode.*` 子键。
- 改名和行囊物品名在需要保留样式时传入 `Component` 参数。

### 4.2 语言键

`en_us.json` 与 `zh_cn.json` 各新增 34 个键，键总数从 130 增至 164。新增键分组如下：

- `furkin.command.companion.*`：5 个
- `furkin.command.summon.*`：5 个
- `furkin.command.list.*`：8 个
- `furkin.command.rename.*`：1 个
- `furkin.command.addxp.*`：2 个
- `furkin.command.mode.*`：1 个
- `furkin.command.skill.*`：2 个
- `furkin.command.inspect.*`：3 个
- `furkin.command.pouch.*`：7 个

复用的既有键包括：

- `furkin.msg.invalid_pet_id`
- `furkin.msg.not_owner`
- `furkin.msg.rename_failed`
- `furkin.msg.mode_set`
- `furkin.msg.mode_not_summoned`
- `furkin.msg.mode_failed`
- `furkin.msg.skill_not_summoned`
- `furkin.msg.skill_species_mismatch`
- `furkin.msg.skill_prerequisites`
- `furkin.msg.skill_no_points`
- `furkin.msg.skill_maxed`

### 4.3 兼容性

- 命令树、权限等级、参数类型和返回值没有变化。
- 没有修改网络包、协议版本、存档数据或公开 API。
- 没有修改技能 schema 或 `SKILL_TREE.md` 约束的内容。
- 中英文键集合一致，均为 164 个。

---

## 5. 验证记录

### 5.1 已完成

- `git diff --check`：通过。
- `compileJava --rerun-tasks --console=plain`：`BUILD SUCCESSFUL`（2026-09-25 实际执行编译）。
- `build --rerun-tasks --console=plain`：`BUILD SUCCESSFUL`；产物 `build/libs/furkin-1.20.1-0.0.1.1.jar` 已生成。
- 语言文件键集合比对：`en_us.json = 164`，`zh_cn.json = 164`，无缺键。
- 静态检索：`FurkinCommand` 中玩家可见固定英文回执已无 `Component.literal`；剩余字面量仅为动态值或结构符号。
- 翻译键引用：`FurkinCommand` 的 37 个静态键均存在；运行时拼接前缀 `furkin.combat_mode.` 对应的 follow / passive / protect / aggressive 四个键均存在。
- `runClient` 第一轮：2026-09-25 12:18-12:25，覆盖中英文列表、传送、四种战斗模式、加经验升级、技能成功/物种不符/前置不足/点数不足、查看、未召唤失败、行囊添加和清空。
- `runClient` 第二轮：2026-09-25 12:30-12:39，覆盖改名成功、召唤成功、未知技能、无效模式、技能满级、行囊成功/部分放入/满仓、格式错误 pet id、死亡列表状态、死亡召唤限制和活跃上限。
- `runClient` 第三轮：2026-09-25 12:42-12:46，覆盖空物品、`mode`/`skill`/`addexp` 的未召唤分支、不存在的合法 UUID 和空列表；中文与英文均观察到对应回执。
- 第三轮已确认回执：`Empty item.` / `物品为空。`、`Companion is not summoned...` / `绒亲未在场，请先召唤...`、`No such companion...` / `不存在该绒亲...`、`(none)` / `（无）`。
- `furkin.combat_mode.*`：follow / passive / protect / aggressive 四种动态键在中文和英文均正确解析。
- `run/logs/latest.log`：三轮运行均未发现 `ERROR`、`FATAL`、异常栈、缺失翻译键或资源加载失败；仅有 Forge 依赖元数据、OSHI 性能采集、原版音效、Realms 授权和 Shader sampler 等既有环境警告。
- 每次客户端退出均完成世界保存；最后一次日志为 `Stopping!`，Gradle 返回 `BUILD SUCCESSFUL`。

### 5.2 已确认的交互

- `list.click_to_copy`：2026-09-25 实机确认列表中的悬浮提示和点击复制均正常。

### 5.3 高成本或异常注入分支

以下分支不适合普通实机烟雾测试，保留为静态/代码路径证据：

- `not_owner`：需要第二玩家或构造所有权不匹配。
- `companion.data_missing`、`pouch.data_missing`：需要破坏或缺失 capability 数据。
- `companion.entity_not_found`：需要构造“档案认为已召唤、世界中找不到实体”的状态。
- `summon.failed`、`mode.failed` 等内部失败分支：需要故障注入。

### 5.4 提交状态

- 日志可覆盖的成功、失败和空状态分支均已通过。
- 实现与可复现验证已完成，异常注入分支已记录为残余；已提交并推送至 `origin/mc1.20.1/dev`（`f4db61f`）。
- 提交 `f4db61f` 已推送至 `origin/mc1.20.1/dev`；当前工作区干净。

---

## 6. 残余风险与关闭条件

### 6.1 残余风险

- `list.click_to_copy` 已由实机确认，不再作为未验证项。
- `not_owner`、数据缺失、实体状态不一致和内部失败分支需要专门的异常状态或测试夹具。
- `FurkinRecordScreen` 和 `RequestSummonPacket` 的历史注释已更新；注释清理后 `compileJava --rerun-tasks` 通过。

### 6.2 关闭条件

- 记录不可复现的异常分支及依据。
- 清理与当前行为不符的命令本地化注释。
- 日志无新增翻译键缺失、异常栈或 `ERROR` / `FATAL`。
- 文档、变更记录、提交和推送按工作流完成。

### 6.3 残余归档与关闭结论

| 残余分支 | 未做实机注入的原因 | 当前证据 | 对 L-02 的影响 |
|---|---|---|---|
| `not_owner` | 需要第二玩家或构造所有权不匹配 | 翻译键存在，调用路径静态可审查 | 不影响固定回执本地化结论 |
| `companion.data_missing`、`pouch.data_missing` | 需要破坏或缺失 capability 数据 | 翻译键存在，失败分支静态可审查 | 不影响语言键同步 |
| `companion.entity_not_found` | 需要构造档案与实体状态不一致 | 翻译键存在，分支静态可审查 | 不影响正常可复现路径 |
| `summon.failed`、`mode.failed` 等内部失败 | 需要故障注入 | 翻译键存在，默认/失败分支静态可审查 | 仅保留异常路径残余 |

上述分支已按要求记录为残余；固定文本已全部迁移，中英文键集合一致，主路径和可复现边界均已通过实机烟测。因此 L-02 以“实现、可复现验证、残余归档完成”为结论关闭；提交与推送已完成：`f4db61f` -> `origin/mc1.20.1/dev`。
