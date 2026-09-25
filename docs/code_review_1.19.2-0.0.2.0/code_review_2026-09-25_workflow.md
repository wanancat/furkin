# Furkin 1.19.2 代码审查整改工作流

- 日期：2026-09-25
- 工作目录：`D:\frukin_dev\frukin_1_19_2`
- 分支：`mc1.19.2`（HEAD `ba3132e`）
- 输入：`docs/code_review_1.19.2-0.0.2.0/code_review_2026-09-25.md`
- 参考：1.20.1 `docs/code_review_2026-09-24/code_review_2026-09-24_workflow.md` 与 `wp-01`～`wp-07`
- 目标版本：**`1.19.2-0.0.2.0`**（MINOR 递增；`mod_version` 只改 `gradle.properties`）
- 状态：计划已建立，**处置口径已于 2026-09-25 冻结**（见第 12 节）；WP-01～WP-09 均已完成代码、文档与阶段性验证；客户端进服后的实机交互矩阵保留为最终验收残余

> 重要：本文件描述的方案是以 1.19.2 / Forge 43.2.0 的真实 API 为前提重新设计的，**不是** 1.20.1 工作包的照抄。1.20.1 的代码差异点见第 3.4 节。只有逐项核对通过后，才允许把某条标记为「已完成」。

---

## 1. 工作流目标

把 `code_review_2026-09-25.md` 记录的 11 项问题（H-01～H-03、M-01～M-04、L-01～L-04）按批次收敛；H-02 的处置口径已于 2026-09-25 冻结（第 12 节），其中 D9 单列为 WP-09，每项都达到：

1. 根因已定位到具体代码位置；
2. 修复方案在 1.19.2 真实 API 上成立（有 `javap` 或官方源码取证）；
3. 有可复现的验证证据（编译 + 运行 + 日志）；
4. 文档（README / CHANGELOG / SKILL_TREE / WP 文档）与代码一致；
5. 不破坏公开 API；若破坏，已按项目规则升 API 版本段并双语记录。

---

## 2. 工作原则

### 2.1 小步实施

每个工作包一个内聚改动，完成即 `compileJava`，不留编译失败到包末。跨包共享的改动（如档案全局化）先做前置，再做依赖它的包。

### 2.2 服务端权威

客户端只发请求，一切结算在服务端。H-01 的核心就是把「契约是否合法」的判定收回服务端，不信任客户端传来的实体 ID / 名字。H-02/H-03 同理：持久数据的清理与归属由服务端裁定。

### 2.3 证据优先

- 每处 API 用法先确认 1.19.2 的真实符号（`javap` / mappings / 官方源码），**不依据 1.20.1 的存在性推断**。
- 运行验证必须看 `run/logs/latest.log`，启动成功但日志有 `ERROR` 不算通过。
- 异常路径（伪造包、解绑、跨维度、`/reload`）要留下可复现步骤与观察结果。

### 2.4 显式状态

不引入隐式全局状态。H-01 的「待确认会话」、M-03 的「goal 所有权」都必须有显式数据结构承载，并在实体卸载/玩家登出/超时后清理。

---

## 3. 基线与边界

### 3.1 输入

- `docs/code_review_1.19.2-0.0.2.0/code_review_2026-09-25.md`
- 1.20.1 对应工作包（`wp-01`～`wp-07`），仅作设计参考。

### 3.2 范围内

- H-01、H-02、H-03、M-01、M-02、M-03、M-04、L-01、L-02。
- 冻结口径 D1～D9（含 WP-09 强制解绑 + 墓碑）。
- 版本号推进到 `1.19.2-0.0.2.0`，并同步两份 CHANGELOG 与必要的 README 说明。
- 本轮不触碰 `com.wanancat.furkin.api`（已核对：计划改动全部落在 `internal`），故 MAJORAPI 段保持 0。
- 新增候选 L-03、L-04（可作为单独 WP-08，或并入相近 WP）。

### 3.3 范围外

- 1.20.1 分支的任何代码；跨版本整体合并。
- 新功能（M4 复活之后的内容）——本次只做审查问题的收敛，不夹带新机制。
- 1.20.1 `next_version_backlog.md` 的 NV-01（跟随传送距离）/ NV-02（友伤配置）：与 1.20.1 保持一致，**顺延到 0.0.2.0 之后**，不纳入本批次。
- 自动化 GameTest 框架（仓库当前无，不新增）。

### 3.4 前置条件（1.19.2 真实 API 差异，实施前必读）

- 世界访问用 `Entity#getLevel()`；**不存在** `level()` / `serverLevel()`。
- `ServerPlayer#getLevel()` 在 1.19.2 已直接返回 `ServerLevel`，无需 `instanceof`。
- Forge `ServerLevel#getDataStorage()` **按维度**各自实例化 `DimensionDataStorage`（H-03 的根因）；全局档案要走 `MinecraftServer#overworld()`。
- `GoalSelector` **没有**谓词版 `removeAllGoals(...)`；官方入口是 `getAvailableGoals()`（返回可修改活 Set）。**不要**用 1.20.1 的签名。
- `GuiGraphics` 无 `blitNineSliced(...)`；不要引入九宫格缩放。
- `EditBox` 无 `setHint(...)`；提示词用 responder 只在空值时给。
- `AbstractSelectionList.renderList(...)` 不自动裁剪；需要时用 `GuiComponent.enableScissor(...)`。
- 通用无攻击者伤害用 `DamageSource.GENERIC`；无敌穿透用 `DamageSource#isBypassInvul()`。
- 运行 Gradle 前显式设置 `JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'`。

---

## 4. 工作项登记表

| ID | 标题 | 严重度 | 工作包 | 依赖 |
| --- | --- | --- | --- | --- |
| H-01 | 契约确认服务端权威化 | 高 | WP-01 | 无 |
| H-02 | 解绑清理（行囊/装备/AI/状态） | 高 | WP-02 | WP-03（AI 所有权）/ 可与 WP-03 协同 |
| H-03 | 全局档案统一 | 高 | WP-03 | 无 |
| M-01 | 协议版本治理 | 中 | WP-04 | 应在其它改包工作之后统一升版 |
| M-02 | 退款乘 cost + schema 校验 | 中 | WP-05 | 无 |
| M-04 | 热重载效果一致 + 流血实时语义 | 中 | WP-05 | 无 |
| M-03 | goal 所有权化 | 中 | WP-02 | 无 |
| L-01 | 客户端类隔离 | 低 | WP-06 | 无 |
| L-02 | 命令回执本地化 | 低 | WP-07 | 无 |
| L-03 | 枚举反序列化容错 | 低 | WP-08 | 无 |
| L-04 | 技能 ID 重复校验 | 低 | WP-08 | 无 |
| D9 | 强制解绑 + 墓碑 + 实体定位（对齐 1.20.1 WP-02B） | 高 | WP-09 | WP-03（全局档案）、WP-04（协议版本） |

### 4.1 技术依赖覆盖

- WP-01 ↔ WP-04：WP-01 改契约确认包字段时，协议版本递增归 WP-04 统一处理。
- WP-02 ↔ WP-03：解绑/AI 恢复涉及活跃上限与档案查询，需在全局档案生效后复测。
- WP-09（D9）：墓碑/强制解绑依赖全局档案（WP-03）与协议版本治理（WP-04）；改 `RecordActionPacket` 必须升 `PROTOCOL_VERSION`。`FurkinEntityLocator` 在 1.19.2 用 `ServerLevel#getEntity(UUID)`（已核实存在）。
- WP-05：退款修正已落地实付表与旧档迁移；技能从树中移除时按 `cost=1` 兜底退款并告警，不再与 WP-08 共同决定。

---

## 5. 标准执行流程

### 5.1 阶段 0：建立工作记录

在本目录**追加**（不改历史结论）一份 `wp-XX_*.md`，写清问题、方案、1.19.2 取证、验证命令与结果。

### 5.2 阶段 1：代码勘察与方案设计

- 用 `javap` / mappings 确认涉及的每个 1.19.2 符号。
- 画清调用链，标出所有调用点（`rg` 全仓，避免漏改）。

### 5.3 阶段 2：实施

- 单包单内聚改动；改包即预留协议版本递增点（WP-04 收口）。
- 涉及公开 API 时按项目规则判断是否升 `MAJORAPI`。

### 5.4 阶段 3：静态质量门槛

- `.\gradlew.bat compileJava --console=plain`（每组改动）。
- Java/资源元数据变更追加 `build`。
- 检索是否误引入 1.20.1 专有 API（第 3.4 节清单）。

### 5.5 阶段 4：代码复核

- 对照本文件与审查报告逐项核对；确认没有把「1.20.1 已修复」当成「1.19.2 已修复」。

### 5.6 阶段 5：运行验证

- 客户端界面/渲染/输入 → `runClient`（并核对 `docs/wp9d-gui-test-plan.md` 回归项）。
- 注册/命令/能力/网络/存档/服务端逻辑 → `runServer`。
- 检查 `run/logs/latest.log`：无新 `ERROR`/`FATAL`/异常栈/资源缺失。
- **临时夹具已获授权**（2026-09-25）：为实现期异常注入，允许临时增加服务端夹具/调试入口；验证后移除，日志证据保留在 `docs/code_review_1.19.2-0.0.2.0/` 对应 WP 文档中。

### 5.7 阶段 6：文档、版本与变更记录

- 玩家/开发者可见变更同步 `README.md` + `README.zh-CN.md` + 两份 `CHANGELOG`。
- 技能 schema / 字段变更同步 `SKILL_TREE.md`。
- 版本号改动只走 `gradle.properties`。

### 5.8 阶段 7：关闭工作项

- 在工作包文档追加实际结果、日期、证据（不回写伪造结果）。
- 更新本文件第 11 节跟踪清单。

---
## 6. 工作包

### 6.1 WP-01：契约确认的服务端权威化（H-01）

- 状态：**已完成**（2026-09-25）；实施、运行取证明细见 `wp-01_contract_authority.md`。

**目标**：`ConfirmContractPacket` 只作为「玩家确认了这一步」的信号，不允许它携带的实体 ID / 名字绕过任何服务端边界。

**设计要点**：

1. 服务端在 `tryContract` 通过后记录「待确认会话」：`Map<UUID playerUuid, PendingContract>`，字段至少含 `entityId`、`species`、发起时刻、距离快照。写在一个显式持有并清理的服务端状态里（登出/超时/维度切换清理）。
2. `ConfirmContractPacket` 增加必要字段或用会话回查：以**会话里的 entityId** 为准，而不是信任包里传来的 ID。若必须保留包内 ID，则与会话比对，不一致即丢弃。
3. `executeContract` 内重新执行完整边界（不依赖前置）：
   - `target.isAlive()` 且未被移除；
   - `FurkinSpeciesRegistry.isRegisteredEntity(target)`；
   - 目标不是 `ServerPlayer` 等非法类型；
   - `player.getMainHandItem().getItem() instanceof FurkinContractItem`；
   - 玩家与目标距离上限；
   - 会话存在、未过期、与目标一致。
4. 名字做服务端上限校验（长度 / 去除控制字符）；留空回退物种名逻辑保持不变。
5. 失败时静默或 action bar 提示，**不消耗**契约物品。

**验证**：

- 静态：`rg` 确认 `executeContract` 是唯一落契约入口，且包含全部边界。
- 运行：`runServer`；构造「未经过 tryContract 直接发确认包」的路径（可用测试客户端或临时调试注入）应被拒绝；正常契约流程不受影响。
- 回归：`docs/wp9d-gui-test-plan.md` 的 D2（契约命名）全部通过。

**1.19.2 注意**：距离判断用 `player.distanceToSqr(target)`；取实体用 `player.getLevel().getEntity(id)`（`ServerPlayer#getLevel()` 已返回 `ServerLevel`，无需再 `instanceof`）。

---

### 6.2 WP-02：AI 所有权与解绑清理（M-03 + H-02）

**目标**：本模组只增删**自己**的 goal；解绑把宠物完整回落为普通动物，不丢物品、不留状态。

**设计要点**：

1. **goal 所有权**：为每种模式创建本模组定义的 `Goal`/`TargetGoal` 子类（或持有实例引用的包装），存入 capability/实体侧结构；移除时只按**自有类型**匹配，不用 `instanceof HurtByTargetGoal` 之类的基类匹配。
2. **解绑清理**：按**第 12 节已冻结口径**新建 `FurkinUnbindCleanup` 管线，固定顺序为
   「校验 → 清 goal → 行囊掉落 → 四盔甲掉落+清空 → 还原默认掉率 → 摘技能效果+transient modifier/周期计时
   → 清技能等级/技能点/等级/经验/行囊容量 → 清 cooldowns/feedCount/lastFeedMillis
   → 清身份/状态 → 清 TAME/owner/sit → 清 CustomName → 后置检查」，档案最后删。
   - 行囊：`PouchDrop.dropAll(target, data.getPouch())`（**倒在脚下**，不再有「交还玩家」分支）。
   - 装备：`EquipmentSlots.dropAndClear` + `restoreDefaultDropChances`（1.19.2 无 `MobEquipmentContainer`，按 §12.3 用 `SimpleContainer` 实现）。
   - 未召唤/已亡档案：`EquipmentSlots.dropArchivedEquipment` 在发起解绑者脚下掉落，再删档。
3. **装备安全**：掉落率还原为 `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE`，确保解绑后不再出现「掉落率 0 + 无快照」→ 丢失。
4. **失败可见**：任一阶段异常即返回结构化失败（阶段 + 异常）并**保留档案**，允许重试；客户端不做清理。
5. 文档注明「解绑副作用」的最终口径（TAME 清除是有意接受的白送，装备一律掉落还回、不白送也不丢失）。

**验证**：

- 运行（`runServer` + 实机）：契约带装备的宠物 → 解绑 → 检查四件盔甲与行囊**落在宠物脚下**、掉落率已还原；解绑后死亡不出现装备凭空消失。**临时服务端夹具已获授权**（2026-09-25）：允许为实现期异常注入临时增加服务端夹具/调试入口，验证后移除，并保留日志证据。
- 运行：解绑后原版 AI（如狼的攻击行为）行为合理；未误伤第三方 goal。
- 检查活跃上限统计不受影响。

**1.19.2 注意**：`getAvailableGoals().removeIf(...)` 是唯一可用的官方删除入口；如果改用实例引用，需要自己维护该结构（1.19.2 的 `GoalSelector` 没有谓词版 API）。

---

### 6.3 WP-03：全局档案统一（H-03）

- 状态：**已完成**（2026-09-25）；实施、迁移与运行取证明细见 `wp-03_global_archive.md`。

**目标**：`furkin_archive` 成为**世界级单例**，跨维度一致。

**设计要点**：

1. 所有玩法路径改走全局入口：`FurkinArchiveData.get(MinecraftServer)`（内部 `get(server.overworld())`），不再按玩家/实体当前维度取档案。
2. 实体操作仍在**目标维度**进行（`server.getLevel(key)`），但档案读写同一份。
3. **迁移（口径已于 2026-09-25 冻结）**：`overworld` 副本为**权威**；
   - 其他维度中存在、而 overworld 中**没有**的 `companionId` → **并入** overworld；
   - 同一 `companionId` 在 overworld 已有 → **以 overworld 为准**，其余副本该条目不覆盖；
   - 其余维度的旧副本**不删除**（留作孤儿备份，避免不可逆丢失）；
   - 合并结果写回 overworld 的 `furkin_archive`，并记一条含「并入条数 / 冲突条数」的日志。
   - 冲突条目须在日志中列出 companionId，便于事后人工核对。
4. `rg` 复核所有 `FurkinArchiveData.get(` 调用点（本报告 §H-03 已列出 20+ 处）。

**验证**：

- 运行：主世界契约 → 去下界 → 绒亲录可见、可召唤/收回；回主世界状态一致。
- 运行：构造「overworld + 下界各有一份分叉档案」的夹具，确认并入条数、冲突取 overworld、旧副本保留。
- 运行：活跃上限跨维度一致（不因切维度而翻倍）。
- 运行：旧存档（已有分叉）加载后不丢宠物。

---

### 6.4 WP-04：协议版本治理（M-01）

**目标**：包结构变化与协议版本绑定。

**设计要点**：

1. 递增 `PROTOCOL_VERSION`（建议改为整数字符串或语义版本，且集中一处）。
2. 建立约定：新增/删除/改字段/改 ID 顺序 ⇒ 必升版本；在 WP 文档记录「版本 → 包结构」映射表。
3. 复核全部 10 个包的注册顺序与 ID 稳定；不要复用旧 ID 表示新含义。
4. 与 WP-01 协同：契约确认包若改字段，版本递增在此收口。

**验证**：静态对比注册表；运行 `runClient`+`runServer` 正常握手；故意改动一端版本应被拒（可临时验证）。

---

### 6.5 WP-05：技能退款、schema 与热重载一致性（M-02 + M-04）

- 状态：**已完成**（2026-09-25）；实施、验证和残余边界见 `wp-05_skill_consistency.md`。真实客户端联调在 WP-06 后统一收口。

**已实施口径**：

1. **schema 校验**：按第 13.7 节选项 A 严格拒绝非正 `cost`、非法 `maxLevel` / `tier` / `requiresLevel`、悬空 `requires` / `requiresLevel` / `levelGate`，以及超过目标有限 `maxLevel` 的 `requiresLevel`；被拒技能的依赖链级联拒绝。
2. **退款**：实体与档案持久化按技能累计的实际支付额，加点累计当前 `cost`，洗点按实付额退款；旧档首次加点/洗点时按当前定义迁移，已删除定义按 `cost=1` 兜底并告警。
3. **热重载一致**：`furkin:attribute` modifier 使用固定名称清理；重载后在服务器 tick 末尾重建已加载绒亲，实体再次入世时定向校准，不加载区块、不扫描未加载档案。
4. **流血语义**：已明确采用实时语义——`/reload` 立即改变已有流血的 DPS，但不改写已施加持续时间；定义失效后停止伤害并自然到期。
5. 同步更新 `SKILL_TREE.md`、工作流状态和两份 changelog；未改公开 API、网络包结构或协议 `"2"`。

**验证**：

- 服务端临时夹具覆盖 schema、实付/退款、旧档迁移、属性重建、真实资源重载、已加载实体扫描和流血持续时间保持；归档日志 `run/logs/2026-09-25-1.log.gz` 记录 `SUMMARY pass=30 fail=0`。
- 夹具删除后 `clean build` 通过，最终 JAR 不含 `Wp05` / `wp05`；无夹具 `runServer` 到达 `Done`，项目包自身无 `ERROR` / `FATAL`。
- 真实客户端 `/reload`、技能界面加点/洗点和流血伤害数值观察保留为最终客户端联调残余。

---

### 6.6 WP-06：客户端类隔离（L-01）

- 状态：**已完成**（2026-09-25）；实施、静态检查、双端启动和残余边界见 `wp-06_client_isolation.md`。真实进服后的五条网络交互仍待人工矩阵。

**目标**：共享网络包不再在类级引用客户端类型。

**设计要点**：

1. 新建 `Dist.CLIENT` 专用辅助类（如 `ClientPacketHandlers`），把 `Minecraft`、`FurkinRecordScreen`、`FurkinPanelScreen`、`ContractNameScreen` 的调用集中进去。
2. 共享包只 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ...)` 调用该辅助类的方法，删除共享包里的客户端 import。
3. 复核 `@OnlyIn(Dist.CLIENT)` 使用一致。

**验证**：`runServer` 启动无类加载错误；`runClient` 界面行为不变。

---

### 6.7 WP-07：命令回执本地化（L-02）

- 状态：**已完成**（2026-09-25）；实施、API 差异、键集合校验和残余边界见 `wp-07_command_localization.md`。游戏内中英文切换仍待人工验收。

**目标**：玩家可见文本全部走翻译键。

**设计要点**：

1. 为 `FurkinCommand` 的每条回执建立键（如 `furkin.command.reload.success`），参数用 `Component.translatable(key, args...)`。
2. `en_us.json` 与 `zh_cn.json` 同步补键，键集合保持一致。
3. 复核 `Component.literal` 只剩「玩家输入的名字」这类必须字面量的场景。

**验证**：`runClient` + 语言切换，命令输出随语言变化。

---

### 6.8 WP-08：存档与数据完整性加固（L-03 + L-04）

- 状态：**已完成**（2026-09-25）；实施、临时夹具 7/7 和残余边界见 `wp-08_archive_skill_integrity.md`。

**目标**：坏数据不炸读档；重复定义可见。

**设计要点**：

1. 用容错解析替代 `Enum.valueOf`（遍历 `values()` + `equalsIgnoreCase`，非法回退默认 + 告警）。
2. `FurkinArchiveData.load` 逐条 try/catch，单条损坏不拖垮全档。
3. `SkillTree.register` 遇重复 ID 记 `WARN`（含来源），或返回布尔由加载器决定策略。
4. 补文档说明容错语义。

**验证**：手工构造非法枚举 / 重复 ID 的技能数据，确认不崩、有日志、其余数据完好。

---

### 6.9 WP-09：强制解绑、墓碑与实体定位（D9，对齐 1.20.1 WP-02B）

**目标**：档案标记「已召唤」但实体定位不到时，仍能给玩家一个**安全**的脱困出口，且不会重复给物品或留下幽灵档。

**范围（对齐 1.20.1 B0～B7）**：

1. **B0 档案实体定位字段**：`FurkinArchiveEntry` 增加实体所在维度与实体 UUID 的写入/兼容读取。
2. **B1 清理管线**：即 WP-02 的 `FurkinUnbindCleanup`（本 WP 复用，不重复实现）。
3. **B2 常规解绑定位**：定位只用 `ServerLevel#getEntity(UUID)`（1.19.2 已核实存在），**不用** `getEntities().getAll()`；定位失败返回 `ENTITY_UNRESOLVED`，不删档、不重建实体。
4. **B3 墓碑**：新建 `FurkinRevocationData`（`SavedData`，NAME `furkin_revocation`），**先写墓碑成功、再删普通档案**。
5. **B4 强制解绑入口**：`Action.FORCE_UNBIND` + `RecordActionResultPacket` 的 `forceUnbindAllowed` 标志 + 绒亲录二次确认页 + `/furkin forget <id> [force]`。**服务端重新校验**强制条件，不信任客户端是否真的确认过。
6. **B5 入世延迟清理**：实体以后入世时按墓碑执行清理并移除墓碑。
7. **B6 服务端夹具与运行期验证**：覆盖「实体不在场却标已召唤」「强制解绑后入世清理」等场景。
8. **B7 文档/变更记录/关闭**。

**硬约束**：档案/墓碑写入只在服务端；任何路径都不自动召唤、重建或复制绒亲；墓碑写入失败即返回 `CLEANUP_FAILED` 且**不删档案**。

**协议**：改 `RecordActionPacket` 语义 ⇒ **必须升 `PROTOCOL_VERSION`**（与 M-01 协同）。

**1.19.2 差异**：`FurkinEntityLocator` / `FurkinRevocationData` / `Result.ENTITY_UNRESOLVED` 等在本分支**均不存在**，属净新增（见 §12.4 缺口清单），不可从 1.20.1 整体合并。

---

## 7. 统一测试矩阵

| 场景 | 命令 | 关注点 |
| --- | --- | --- |
| 编译 | `compileJava` | 无错误；无 1.20.1 专有 API |
| 构建 | `build` | 资源/元数据/打包通过 |
| 客户端 | `runClient` | 绒亲录、技能面板、契约命名、改名、本地化 |
| 服务端 | `runServer` | 注册、命令、能力、网络、存档、无类加载错误 |
| 日志 | `run/logs/latest.log` | 无新 `ERROR`/`FATAL`/异常栈/资源缺失 |
| 异常注入 | 伪造确认包 / 解绑带装备 / 跨维度 / `/reload` | H-01/H-02/H-03/M-04 的边界行为 |

---

## 8. 风险与未决问题

- **1.19.2 与 1.20.1 的方案不能互换**：`getDataStorage()` 语义、`GoalSelector` 删除 API、`GuiGraphics` 渲染 API 均不同，移植时必须逐项重设计。
- ~~**H-02 的装备口径未决**~~：**已冻结**（2026-09-25，见第 12 节）。D9 纳入范围，工作量与依赖见 §12.5。
- **H-03 的旧档迁移**：已完成（WP-03）；`overworld` 权威合并、冲突保留 overworld、旧副本不删均已运行取证，见 `wp-03_global_archive.md`。
- **D9 的协议升号**：WP-09 已实现强制解绑与墓碑，WP-04 已把协议从 `"1"` 提升到 `"2"` 并记录 `0-10` 包映射；真实双端握手仍作为客户端联调验收项。
- **M-04 的旧树引用**：已按当前实现收口——重建在服务器 tick 末尾通过 `SkillRuntimeCalibrator` 遍历已加载维度/实体，入世和召唤走定向校准；未加载实体不强制加载。真实客户端 / 数据包重载联调仍保留为 WP-06 后的验收项。
- **L-01 的实现风险**：5 处共享客户端引用已迁入 `FurkinClientPacketHandler`，静态/字节码/双端启动已通过；真实进服后的五条网络交互仍需人工验收，不外推为运行期全路径已证实。

---

## 9. 最终完成标准

- 第 4 节全部工作项关闭，每项有静态 + 运行证据。
- `build`、必要的 `runClient`/`runServer` 通过，`latest.log` 无新增错误。
- 文档（README×2、CHANGELOG×2、SKILL_TREE、本目录 WP 文档）与代码一致。
- 若涉及公开 API 变更：API 版本段已升，双语说明已补（本轮按计划不涉及）。
- `gradle.properties` 的 `mod_version` 已推进到 `1.19.2-0.0.2.0`，产物为 `furkin-1.19.2-0.0.2.0.jar`；两份 CHANGELOG 已记录本批修复。
- 报告与工作流中「运行未确认」的项全部转为「运行已确认」或明确记录为已知残余风险。
- D9 / WP-09（强制解绑 + 墓碑 + 实体定位）关闭，且与 WP-02、WP-03、WP-04 的接口一致。

---

## 10. 执行跟踪清单

| ID | 工作包 | 状态 |
| --- | --- | --- |
| H-01 | WP-01 | 已完成（2026-09-25，`wp-01_contract_authority.md`） |
| H-02 | WP-02 | WP-02A/B 已完成；跨维度定位/墓碑由 WP-09 收口 |
| M-03 | WP-02A | 已完成（2026-09-25，`wp-02a_ai_ownership.md`） |
| H-03 | WP-03 | 已完成（2026-09-25，`wp-03_global_archive.md`） |
| M-01 | WP-04 | 代码/静态/服务端验证完成（2026-09-25，`wp-04_protocol_version_governance.md`）；真实双端握手待客户端联调 |
| M-02 | WP-05 | 已完成（2026-09-25，`wp-05_skill_consistency.md`） |
| M-04 | WP-05 | 已完成（2026-09-25，`wp-05_skill_consistency.md`） |
| L-01 | WP-06 | 已完成（2026-09-25，`wp-06_client_isolation.md`）；进服交互为人工验收残余 |
| L-02 | WP-07 | 已完成（2026-09-25，`wp-07_command_localization.md`）；游戏内双语切换为人工验收残余 |
| L-03 | WP-08 | 已完成（2026-09-25，`wp-08_archive_skill_integrity.md`）；真实损坏磁盘读档为残余 |
| L-04 | WP-08 | 已完成（2026-09-25，`wp-08_archive_skill_integrity.md`） |
| D9 | WP-09 | 代码/夹具完成；协议 `"2"` 已由 WP-04 收口（2026-09-25，`wp-09_force_unbind_tombstone.md`） |

---

## 11. 文档维护规则

- 本文件是 1.19.2 分支的整改路线图；1.20.1 的报告与工作包**不**作为本分支的完成依据。
- 实施过程中只在对应 WP 文档**追加**实际结果与证据，不回写历史结论。
- 本文件的第 10 节随进度更新；关闭工作项时一并更新第 8 节的风险状态。
- **归档规则（2026-09-25 定）**：本迭代的全部文档（审查报告、本工作流、`wp-XX_*.md`）统一放在 **`docs/code_review_1.19.2-0.0.2.0/`** 下，按版本归档；**不加入 `.gitignore`**，随仓库提交。
- **`.gitignore` 的 `AGENTS.md` 条目保持不变**（2026-09-25 定，乌狸确认不改）。
- 审查报告（`code_review_2026-09-25.md`）为只读基线，除非重新审查，否则不改动其结论。

---

## 12. 已冻结的处置口径（H-02 / 解绑清理）

- 冻结日期：2026-09-25
- 决策人：乌狸
- 冻结内容：**D4 按本分支实测口径（1.19.2 无公开掉率 getter，写回原版默认值），其余各项与 1.20.1 对齐**。
- 本节为实施依据；WP-02 与 WP-09 均按本节执行。

### 12.1 冻结结论一览

| 编号 | 决策点 | 冻结答案（对齐 1.20.1） |
| --- | --- | --- |
| D1 | 在场宠物行囊内容 | **倒在宠物脚下并清空**（`PouchDrop.dropAll`）—— 1.20.1 B1 第 3 步 |
| D2 | 在场宠物身上四件盔甲 | **掉落到宠物脚下 + 清空四槽 + 还原掉落率** —— B1 第 4/5 步 |
| D3 | 档案里已存的盔甲快照（未召唤 / 已亡） | **在发起解绑的玩家脚下掉落，再删档**；无快照直接删 —— 1.20.1 P0-1 |
| D4 | 掉落率还原值 | **`Mob.DEFAULT_EQUIPMENT_DROP_CHANCE`**（本分支实测：无公开 getter 可读原值） |
| D5 | `TAME` / 主人归属 | **清 `TAME` / owner / `orderedToSit` / `inSittingPose` → 回野生** —— B1 第 10 步 |
| D6 | 战斗 AI | **只移除本模组自有的 goal**（所有权模型）—— 对齐 1.20.1 `FurkinCombatMode.onUnbind` + AI 状态 |
| D7 | 其它运行时状态 | **清技能效果 / 技能等级 / 技能点 / 等级 / 经验 / 冷却 / 进食计数 / 行囊容量（溢出掉落）** —— B1 第 6/7/8 步 |
| D8 | 失败与重入 | **档案最后删**；失败保留档案/墓碑供重试；异常记阶段 + companionId + 维度；幂等 —— 1.20.1 核心约束 |
| D9 | 实体定位不到时 | **纳入**：`ENTITY_UNRESOLVED` + 墓碑 + 强制解绑 + 入世延迟清理 —— 对齐 1.20.1 WP-02B（B0～B7） |

**补充冻结**：D9 会改动 `RecordActionPacket` 的语义与字段 ⇒ **必须同时升 `PROTOCOL_VERSION`**（1.20.1 由 `1` 升到 `2`），与 M-01 协同收口。

### 12.2 冻结的清理顺序（对齐 1.20.1 B1 固定顺序）

1. 校验目标有效、能力存在、`companionId` 匹配（`expectedCompanionId` 与实体实际值比对）。
2. `TamableAnimal`：清当前 target 并移除本模组自有的战斗 goal（D6）。
3. 行囊 → `PouchDrop.dropAll(...)` 掉落并清空（D1）。
4. 四件盔甲 → 掉落到脚下并清空四槽（D2）。
5. 四个盔甲槽掉落率 → 还原为 `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE`（D4）。
6. 移除静态技能效果，并移除 `pack_tactics` transient modifier + 周期计时（D7）。
7. 清空技能等级 / 技能点 / 等级 / 经验；行囊容量缩回 0，溢出立即掉落（D7）。
8. 清空 `cooldowns`、`feedCount`、`lastFeedMillis`（D7）。
9. 清空 `companionId` / `ownerUuid`，`combatMode` 回 `FOLLOW`，`state` 回 `WILD`（D5/D7）。
10. `TamableAnimal`：`TAME=false`、`owner=null`、`orderedToSit=false`、`inSittingPose=false`（D5）。
11. 清 `CustomName` 与可见性。
12. 后置条件检查。
13. 成功后同步客户端；**常规路径最后**删除档案（D8）。

任何阶段抛异常 ⇒ 返回结构化失败（阶段 + 异常），常规路径**保留档案**。

### 12.3 1.19.2 实施要点（已 `javap` 核实，2026-09-25）

| 事实 | 1.19.2 结论 | 影响 |
| --- | --- | --- |
| `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE` | **存在**（`public static final float`） | D4 可直接写回默认值 |
| `Mob#setDropChance(EquipmentSlot, float)` | **public**，存在 | 还原掉率可用（与 `sealDrops` 对称） |
| `Mob#getEquipmentDropChance(EquipmentSlot)` | **`protected`** ⇒ 外部不可调用 | **读不回原值**，只能写默认值（D4 冻结依据） |
| `MobEquipmentContainer` | **1.19.2 不存在** | `dropAndClear` **不能照抄** 1.20.1，改用 `SimpleContainer` 或逐槽掉落 |
| `Containers.dropContents(Level, Entity, Container)` / `dropItemStack(...)` | **存在** | 掉落复用官方流程 |
| `Containers.dropContents` 是否清空容器 | **不清空**（本仓库 `PouchDrop` 既有注释已说明） | 掉落后必须**显式清空槽位/容器**，不能依赖返回值 |
| `TamableAnimal#setInSittingPose(boolean)` / `isInSittingPose()` | **存在**（public） | D5 第 10 步可实现 |
| `ServerLevel#getEntity(UUID)` | **存在** | 1.20.1 约束「定位只用 `getEntity(UUID)`、不用 `getEntities().getAll()`」在 1.19.2 可实现 |
| `SimpleContainer` | **存在** | `dropArchivedEquipment` 可直接照搬 |

**`dropAndClear` 的 1.19.2 写法要点**：`MobEquipmentContainer` 不可用 ⇒ 用 `SimpleContainer(4)` 承载四个盔甲槽（`mob.getItemBySlot(...)`），`Containers.dropContents(level, living, container)` 掉落，然后**显式** `mob.setItemSlot(slot, ItemStack.EMPTY)` 清空四槽（不能只依赖 `container.clearContent()`，那清的是临时容器）。空槽不生成物品实体。

### 12.4 相对 1.20.1 的构件缺口（须在 1.19.2 新建）

| 1.20.1 已有构件 | 1.19.2 现状 | 处置 |
| --- | --- | --- |
| `internal.contract.FurkinUnbindCleanup` | 无 | 新建（顺序对齐 §12.2） |
| `FurkinCombatAiState` + `FurkinData.aiStateVersion` | 无 | **新建**（M-03 的核心，非移植可得） |
| `FurkinCombatMode.onUnbind(...)` | 无 | 新建（自有 goal 所有权注册 + 卸载） |
| `EquipmentSlots.dropAndClear` / `restoreDefaultDropChances` / `dropArchivedEquipment` | 无 | 新建（`dropAndClear` 按 §12.3 改写） |
| `SkillEffectApplier.clearAll` | 只有 `removeAll` | 补齐/改名（需支持「树里已删的技能」兜底清除，与 M-04 同批） |
| `SkillPassiveDispatcher.clearRuntimeEffects` | 只有 `clearPeriodicTimers` | 新建公开入口：移除 `PACK_TACTICS_UUID` transient modifier + 周期计时 |
| `FurkinData.clearForUnbind` | 无 | 新建（内聚清空身份/状态） |
| `FurkinEntityLocator` + 档案实体定位字段 | 无 | 新建（WP-09） |
| `FurkinRevocationData`（墓碑 SavedData） | 无 | 新建（WP-09） |
| `Result.ENTITY_UNRESOLVED` / `ENTITY_RESOLVED` / `CLEANUP_FAILED`、`Action.FORCE_UNBIND`、`RecordActionResultPacket` 的 `forceUnbindAllowed` | 无（现有 `Result` 只有 OK / NOT_FOUND / NOT_OWNER / NOT_SUMMONED / INVALID_NAME / NOT_DEAD / ON_COOLDOWN / NO_DIAMOND） | 新建（WP-09） |
| 强制解绑二次确认界面 + `/furkin forget <id> [force]` | 无 | 新建（WP-09） |

### 12.5 边界与残余风险

- **第三方自定义掉率无法精确还原**：写回原版默认值，已在文档保留边界说明；若第三方依赖自身掉率 getter，本分支不为其背书。
- **主/副手不在范围内**：沿用「绒亲装备只含四盔甲」的既有边界。
- **D9 显著扩大工作量**：1.20.1 WP-02B 含 B0～B7（定位字段、清理管线、墓碑、强制解绑、入世延迟清理、服务端夹具）。故在 1.19.2 拆为独立 **WP-09**，与 WP-02 分批实施，不混在同一提交里。
- **D6 与 M-03 强耦合**：所有权模型未落地前，解绑后 AI 一定残缺 ⇒ H-02 与 M-03 必须同批。
- **D9 与 M-01 强耦合**：改 `RecordActionPacket` 即须升协议版本。

### 12.6 结论

处置口径已全部冻结，WP-02 与 WP-09 可进入实施。实施前请确认第 12.4 节的「新建」清单已被纳入批次计划（其中 AI 所有权模型与墓碑系统是本分支净新增，不是移植）。

---

## 13. 已冻结：技能 schema 校验口径（M-02）

### 13.1 拟议的校验规则

| 规则 | 现状 | 违反后的语义后果 |
| --- | --- | --- |
| `cost >= 1` | 无校验 | `cost=0` 免费加点；`cost<0` 加点反而涨点 → 洗点放大刷点 |
| `maxLevel >= 1` | 无校验 | 等级语义崩坏（不可加点或等级越界进入效果计算） |
| `tier >= 1` | 无校验 | 排序/展示异常，前置链语义不清 |
| `requiresLevel >= 1` | 无校验 | 门限语义不明 |
| 引用完整性（`requires` / `requiresLevel` / `levelGate` 指向存在的技能） | 无校验 | 前置永远不满足 ⇒ 技能**永久锁死**（静默） |
| `requiresLevel <= 目标技能 maxLevel` | 无校验 | 前置永远达不到 ⇒ 技能永久不可达 |

### 13.2 已核实（2026-09-25）：内置 13 个技能**全部通过**上述规则

- `id` 集合：`bleeding_bite`、`bone_harvest`、`fish_harvest`、`forager`、`night_watch`、`nimble_grace`、`nine_lives`、`pack_tactics`、`self_feeder`、`sharp_fang`、`sturdy_constitution`、`thick_fur`、`travel_pouch`。
- 全部 `cost=1`；`maxLevel ∈ {1,3,5}`；`tier ∈ {1,2}`。
- `requires` / `levelGate` / `requiresLevel` **引用完整**（无悬空），且 `requiresLevel`（仅 `nine_lives → nimble_grace=3`）不超过目标 `maxLevel=3`。
- 结论：**加校验不会影响本模组自带内容**，影响面只落在第三方/整合包数据包。

### 13.3 为什么第三方确实会受影响

`SkillLoader.load` 使用 `manager.listResources("skills", loc -> loc.getPath().endsWith(".json"))` —— 这会扫描**所有命名空间**下的 `data/<ns>/skills/*.json`。第三方包现在没有任何 schema 约束，历史上「能跑」的 JSON 在加校验后会被拒。

### 13.4 修复前风险：**「被拒」与「被删」在运行期是同一状态**

这是选择口径的依据，不能只看加载期：

- `SkillEffectApplier.applyAll` 不会重建它（树里没有）；
- `SkillEffectApplier.removeSkill` / `removeAll` 在 `tree.get(skillId)` 为空时**直接 return** ⇒ 属性 modifier 清不掉（**就是 M-04 的残留缺陷**）；
- 玩家**已经投过点**的技能被禁用/删除后，点数既退不回（退款要经树取 `cost`），效果也享受不到。

⇒ 以上是修复前必须正视的连带风险；WP-05 已先落地 M-04 的未知技能兜底清理，并采用实付表处理未知技能的退款。

### 13.5 三个选项

| 选项 | 行为 | 优点 | 代价 |
| --- | --- | --- | --- |
| **A（已采用）严格校验** | 非法技能不加载 + `WARN`（含引用完整性） | 消除负 `cost` 刷点与语义崩坏 | 旧整合包技能会消失（有日志）；已投点需 M-04 兜底 |
| B 只告警不拒绝 | 非法技能照常加载 | 完全保兼容 | 负 `cost` / `maxLevel=0` 的漏洞保留，"校验"名不副实 |
| C 分级校验 | `cost`/`maxLevel`/`tier` 非法 → 拒绝；引用完整性 → 加载但 `WARN` | 堵住账目漏洞，又不因引用笔误废掉技能 | 规则更复杂，需文档写明软/硬边界 |

### 13.6 选项 A / C 都涉及的附加口径

**已投点、但技能已被禁用或从树中删除时，退款按什么算？**

- 建议：**按 `cost = 1` 兜底退还**并记 `WARN`（不猜原 `cost`，保证玩家不亏点、也不会凭空多出点）。
- 备选：按「最后已知成本快照」退（需要在档案里额外持久化每技能的投入成本，改动面更大）。

### 13.7 冻结结论（2026-09-25）

1. 采用**选项 A：严格校验** —— 违反下列任一规则的技能**不予加载**并记 `WARN`（含引用完整性；即悬空 `requires` / `levelGate`、`requiresLevel > 目标 maxLevel` 也硬拒）：
   - `cost >= 1`、`maxLevel == -1 || maxLevel >= 1`、`tier >= 1`、`requiresLevel >= 1`；
   - `requires` / `requiresLevel` / `levelGate` 指向的技能必须存在；
   - `requiresLevel <= 目标技能的 maxLevel`；目标 `maxLevel=-1` 视为无限，不设该上限。
2. 退款兜底（§13.6）：**按 `cost = 1` 兜底退还并记 `WARN`**；不引入"最后已知成本快照"。
3. 前置依赖：本口径**必须与 M-04 的未知技能兜底清理同批落地**（WP-05），否则被拒技能会残留属性与点数。

4. 实施结果（2026-09-25）：WP-05 已按上述口径落地；静态构建、服务端夹具和残余边界见 `wp-05_skill_consistency.md`。
