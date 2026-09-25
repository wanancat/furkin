# WP-02B：解绑清理、定位与自救实施计划

- 计划状态：P0-1 已确认，B0-B7 文档与关闭已完成；提交/推送待乌狸授权；每阶段开始前须由乌狸确认
- 制定日期：2026-09-25
- 工作分支：`mc1.20.1/dev`
- 代码基线：`184e82e0565f40961fd7b232c7e66f72d31f68c9`
- 前置完成：WP-02A goal 所有权与生命周期恢复
- 设计依据：`docs/code_review_2026-09-24/wp-02_ai_ownership_unbind_design.md`
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0

> 本文只定义 WP-02B 的实施步骤、确认门和验证门槛，不代表代码已经修改。
> 每个 B 阶段完成后必须停下汇报证据，等待乌狸确认后才能进入下一阶段；未经明确授权不提交、不推送。

---

## 1. 范围

WP-02B 负责关闭 H-02，并补齐不可解析实体的玩家自救路径。

必须完成：

- `FurkinArchiveEntry` 持久化实体 UUID 与维度。
- 常规解绑使用记录维度 + `ServerLevel#getEntity(UUID)` 定向定位。
- 未加载、跨维度或旧档缺失定位信息时，不遍历全服实体、不加载区块。
- 已召唤实体不可解析时返回 `ENTITY_UNRESOLVED`，不删档、不创建实体。
- 统一清理 AI、当前目标、行囊、四件盔甲、盔甲掉率、技能效果、技能等级、行囊容量、冷却、进食状态、TAME/主人、坐姿和名字。
- 只有全部清理成功后，才删除普通档案。
- 新增服务器级 `FurkinRevocationData` 注销墓碑。
- 提供二次确认的强制解绑；先写墓碑，成功后才删除普通档案。
- 原实体以后任意维度入世时，由 `EntityJoinLevelEvent` 命中墓碑并执行共享清理；清理成功才移除墓碑，失败保留重试。
- 命令、网络包、客户端反馈和临时服务端夹具覆盖上述路径。

不属于 WP-02B：

- H-03 的完整档案合并、跨维度档案迁移和统一全局索引。
- 用“创建新实体”替代不可解析实体。
- 自动加载区块、遍历 region 文件或 tick 轮询查找实体。
- 修改公开 API 包 `com.wanancat.furkin.api` 的兼容契约。
- 顺带调整战斗模式数值、行囊或装备平衡。

---

## 2. 执行前必须冻结的决策

### P0-1：未召唤/已亡档案中的装备快照如何处置

现状存在一个设计边界：

- 收回和死亡路径会先封印装备掉落，再把四件盔甲保存在 `equipmentSnapshot`。
- 若解绑时 `entry.isSummoned() == false`，现有设计只写“直接删档”。
- 这会让已死亡或已收回绒亲的 `equipmentSnapshot` 随档案删除，字面上仍违反 H-02 的“不永久丢物品”。

已确认（2026-09-25）：

1. 解绑目标档案若为未召唤/已亡状态，先检查 `equipmentSnapshot`。
2. 有非空盔甲快照时，在发起解绑的玩家脚下掉落，再删除普通档案。
3. 没有盔甲快照时直接删普通档案。
4. 该路径不创建实体，不恢复 AI，不处理已经不在档案中的行囊。


**前置状态：P0-1 已确认，B0-B7 文档与关闭已完成；提交/推送待乌狸授权。**

### P0-2：强制解绑的确认入口

推荐采用两种等价入口：

- 绒亲录：常规解绑返回 `ENTITY_UNRESOLVED` 后弹出强制解绑确认页，确认后发送 `FORCE_UNBIND`。
- 命令：`/furkin forget <id>` 常规执行；失败为不可解析时提示 `/furkin forget <id> force`。

服务端必须重新校验强制解绑条件，不能信任客户端是否真正确认。

### P0-3：协议版本

WP-02B 预计新增强制解绑动作和结果回执，使 `RecordActionPacket` 的语义/兼容面发生变化：

- 实施 B4 时把 `FurkinNetwork.PROTOCOL_VERSION` 从 `1` 提升到 `2`。
- 客户端和服务端必须同版本连接。
- 如果最终实现不需要改包结构，B4 需记录不提升协议版本的证据；不能默认保持旧版本。

---

## 3. 核心约束

以下规则贯穿所有阶段：

1. **服务端权威**：实体定位、清理、墓碑写入和档案删除只在服务端执行。
2. **档案最后删除**：任何常规清理成功前不调用 `archive.removeEntry(...)`。
3. **先墓碑后删档**：强制解绑必须先成功写 `FurkinRevocationData`，再删普通档案。
4. **不创建替代实体**：任何路径均不自动召唤、重建或复制绒亲。
5. **索引查询优先**：实体查找只使用 `ServerLevel#getEntity(UUID)`；不使用 `getEntities().getAll()` 做解绑定位。
6. **不加载区块**：定位失败即返回 `ENTITY_UNRESOLVED`，由玩家决定是否强制解绑。
7. **清理可重入**：已掉的物品不得因失败重试再复制；中途失败保留档案/墓碑供再次尝试。
8. **客户端不清理**：客户端只负责展示确认和发请求；不直接改目标、AI、物品或档案。
9. **失败可见**：异常必须记录 companionId、实体 UUID、维度和清理阶段，不吞异常冒充成功。
10. **阶段不跳步**：每个 B 阶段必须完成静态检查、编译和该阶段列出的验证后才进入下一阶段。

---

## 4. 实施阶段

### B0：档案实体定位字段与兼容读写

目的：让已召唤绒亲能够按 UUID 和维度定向解析，不再依赖全实体扫描。

#### 写入范围

- `src/main/java/com/wanancat/furkin/internal/record/FurkinArchiveEntry.java`
- 契约、召唤/复活、收回、死亡和实体入世的写入点
- 必要时新增只读定位辅助类型

#### 实现步骤

1. `FurkinArchiveEntry` 新增：
   - `UUID entityUuid`
   - `ResourceKey<Level> entityDimension`
2. NBT 字段固定为：
   - `entity_uuid`
   - `entity_dimension`
3. 反序列化兼容：
   - 旧档缺字段时保持 `null`。
   - 维度字符串无法解析时按 `null` 处理并记录诊断，不抛异常破坏整份档案。
4. 提供明确的位置更新方法：
   - 契约成功：写入目标实体 UUID 与 `serverLevel.dimension()`。
   - 召唤 / 复活重建成功：写入新实体 UUID 与目标维度。
   - 已召唤实体跨维度入世：仅当当前维度档案能找到同 ID 条目时刷新。
   - 收回 / 死亡：清理运行时位置，避免保留已失效实体 UUID。
5. 不改 `FurkinArchiveData` 的当前分维度存储方式；完整统一档案仍归 WP-03。
6. 不把 UUID/维度加入网络同步包，除非后续确认 UI 确实需要。

#### 阶段验证

- `compileJava` 通过。
- `FurkinArchiveEntry` 新增字段的 NBT 往返检查通过。
- 旧档缺少两个键时按 `null` 读出。
- 静态检查确认解绑目标路径尚未继续使用全实体扫描。

#### 实施结果（2026-09-25）

- `FurkinArchiveEntry` 已增加 `entityUuid`、`entityDimension`，NBT 键为 `entity_uuid`、`entity_dimension`。
- 契约、召唤/复活重建、传送和实体入世路径已写入/刷新定位；收回、死亡和传送自愈路径已清空失效定位。
- 旧档缺少两个键时读出 `null`；无效维度字符串记录告警并按 `null` 处理。
- 临时 NBT 往返夹具输出 `WP02B_B0_ROUNDTRIP_OK`，覆盖新字段保存/读取和旧档缺失字段。
- `compileJava` 通过；`runServer` 到达 `Done`，`latest.log` 无新增 `ERROR`、`FATAL` 或异常栈；服务端随后人工终止。
- 临时夹具和验证脚本已删除，`build/classes` 中无 `Wp02b` 类。

#### 确认门

B0 已完成；等待确认后进入 B1。

---

### B1：统一解绑清理管线

目的：先建立有明确顺序、失败可诊断、可重入的共享清理组件；B2 再把 `clearFurkinLayer` / `unbind` 接入该组件。

#### 写入范围

- 新增 `internal.contract.FurkinUnbindCleanup`
- B1 不修改 `FurkinRecordActionHandler` 的现有解绑入口；接线在 B2 完成
- `src/main/java/com/wanancat/furkin/internal/equipment/EquipmentSlots.java`
- 必要时给 `FurkinData` 增加内聚的运行时清理方法

#### 管线输入与输出

实际输入为：

- 目标 `LivingEntity`
- 调用方已知的 `expectedCompanionId`（常规解绑取档案 ID，墓碑清理取墓碑 ID）
- 触发来源：常规解绑或墓碑延迟清理

实际输出为结构化 `Result`：

- 成功
- 失败 + 清理阶段 + 异常/诊断信息

`PouchDrop.dropAll(...)` 返回 `false` 只表示“本来就是空”，不能当作失败。失败判据以异常和清理后置条件为准。

#### 固定清理顺序

1. 校验目标有效、能力存在、`companionId` 匹配。
2. `TamableAnimal` 调用 `FurkinCombatMode.onUnbind(...)`，清空当前 target。
3. 行囊经 `PouchDrop.dropAll(...)` 掉落到实体脚下并清空。
4. 四件盔甲经新工具方法掉落到实体脚下并清空。
5. 四个盔甲槽恢复 `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE`。
6. `SkillEffectApplier.removeAll(...)` 移除静态技能运行效果，并由 `SkillPassiveDispatcher.clearRuntimeEffects(...)` 移除 `pack_tactics` transient modifier 与周期计时。
7. 清空技能等级、技能点、等级/经验，并把行囊容量缩回 0；缩容溢出立即掉落。
8. 清空 `cooldowns`、`feedCount`、`lastFeedMillis`。
9. 清空 `companionId`、`ownerUuid`、`combatMode` 回 `FOLLOW`、`state` 回 `WILD`、`aiStateVersion` 回 0。
10. `TamableAnimal` 设置 `TAME=false`、`owner=null`、`orderedToSit=false`、`inSittingPose=false`。
11. 清空 `CustomName` 和可见性。
12. 做后置条件检查。
13. 成功后由调用方同步客户端，并在常规路径最后删除档案。

#### 装备工具要求

- 新增“掉落并清空四槽”的方法。
- 新增“恢复四个槽默认掉落概率”的方法。
- 优先复用 `Containers.dropContents(...)` 和现有 `MobEquipmentContainer`，不手写新的物品实体散落算法。
- 主手/副手仍不处理。
- 第三方自定义盔甲掉率无公开精确 getter 时，按设计写回原版默认值，并在文档中保留边界说明。

#### 失败与重入

- 任一阶段抛异常：记录阶段和上下文，返回失败；常规路径保留档案。
- 掉落阶段必须逐项清空，失败重试只处理剩余物品。
- AI 恢复、运行时空表清理和后置检查必须幂等。
- 不做跨阶段事务性回滚；用“每步幂等 + 档案最后删除”保证玩家仍可再次尝试。

#### 阶段验证

- `compileJava` 通过。
- 静态检查确认所有必要字段均进入清理管线。
- 新管线已编译，但尚未接入现有 `unbind`；接线与失败不删档由 B2 验证。
- 需要运行期验证的场景登记到 B6 夹具清单。

#### 实施结果（2026-09-25）

- 新增 `FurkinUnbindCleanup`，按阶段返回成功或带失败阶段/异常的 `Result`。
- 清理链已覆盖 AI、行囊、四件盔甲、掉率、静态技能效果、被动运行时效果、技能等级、行囊容量、冷却、进食状态、身份、状态和原版归属。
- `EquipmentSlots` 新增 `dropAndClear` 与 `restoreDefaultDropChances`，复用官方 `Containers` 流程。
- `FurkinData#clearForUnbind` 收口运行时状态清理。
- `SkillPassiveDispatcher#clearRuntimeEffects` 额外移除 `pack_tactics` 的 transient 属性 modifier 和周期计时，覆盖 `SkillEffectApplier` 之外的运行时痕迹。
- 所有物品掉落动作都在清槽/清容器前按官方流程完成，重试只处理剩余内容，避免重复复制。
- `compileJava` 通过；新类尚未接入 `unbind`，常规路径的失败不删档由 B2 完成；运行期清理矩阵登记到 B6。

#### 确认门

B1 已完成；等待确认后进入 B2。

---

### B2：常规解绑定位与 `ENTITY_UNRESOLVED`

目的：常规解绑只解析档案记录的实体；找不到时不删档，不再用扫描或删档掩盖问题。

#### 写入范围

- `FurkinRecordActionHandler.Result`
- 新增或内聚的实体定位辅助
- 常规 `unbind(...)` 调用链
- 结果文案映射

#### 实现步骤

1. 新增结果：
   - `ENTITY_UNRESOLVED`
   - 如内部需要区分“重新出现”，可增加 `ENTITY_RESOLVED`，但不得与成功混淆
2. 已召唤分支按以下顺序定位：
   - `entry.entityDimension` 有值且维度已加载：仅在该 `ServerLevel#getEntity(entityUuid)` 查询。
   - 记录维度无效或未命中：对服务器所有已加载维度各做一次 UUID 索引查询。
   - UUID 或维度均为 `null`：直接返回 `ENTITY_UNRESOLVED`，不做猜测性搜索。
3. 找到实体：
   - 校验能力中的 `companionId` 与档案一致。
   - 调用 B1 共享清理。
   - 清理成功后最后删档。
   - 清理失败返回内部错误，不删档。
4. 找不到实体：
   - 返回 `ENTITY_UNRESOLVED`。
   - 不删档、不创建实体、不自动召唤。
5. 未召唤分支：
   - 先执行 P0-1 已确认的装备快照处置。
   - 完成后删除普通档案。
6. 删除旧的 `findLivingByCompanionId` 扫描式解绑调用。
7. 日志记录查询维度和结果，但不记录敏感物品内容。

#### 阶段验证

- `compileJava` 通过。
- 静态检查解绑路径没有 `level.getEntities().getAll()`。
- 代码路径确认未命中时不调用 `archive.removeEntry(...)`。
- 运行期未加载、跨维度与旧档缺字段场景登记到 B6。

#### 实施结果（2026-09-25）

- 新增 `FurkinEntityLocator`：优先按档案记录维度调用 `ServerLevel#getEntity(UUID)`，未命中时只遍历服务器当前已加载维度做索引查询；不遍历实体列表、不加载区块。
- 已召唤路径改为定向定位；实体未解析时返回 `ENTITY_UNRESOLVED`，不删档、不创建实体。
- 定位成功但清理失败时返回 `CLEANUP_FAILED`，保留档案供重试。
- 清理成功后同步客户端状态，最后调用 `archive.removeEntry(...)`。
- 未召唤/已亡路径对非空 `equipmentSnapshot` 调用 `EquipmentSlots#dropArchivedEquipment(...)`，掉落到发起解绑的玩家脚下后再删除档案。
- 网络包和命令分别映射 `ENTITY_UNRESOLVED`、`CLEANUP_FAILED` 到新增语言键，失败原因不再统一折叠。
- 静态检查：`unbind(...)` 路径不再调用扫描式 `findLivingByCompanionId(...)`；`FurkinEntityLocator` 不使用 `getEntities().getAll()`；未解析分支没有 `archive.removeEntry(...)`。
- 验证：`compileJava` 通过；`build` 通过；`runServer` 到达 `Done (2.456s)`；`run/logs/latest.log` 无新增 `ERROR`、`FATAL` 或异常栈。
- B6 仍需补运行期夹具，覆盖未加载、跨维度、旧档缺字段、清理失败重试和未召唤装备归还。

#### 确认门

B2 已完成；等待确认后进入 B3。

---

### B3：服务器级注销墓碑

目的：建立跨维度、跨重启有效的 `companionId -> 注销请求` 数据。

#### 写入范围

- 新增 `src/main/java/com/wanancat/furkin/internal/record/FurkinRevocationData.java`
- 必要时新增墓碑记录类型

#### 实现步骤

1. `FurkinRevocationData extends SavedData`。
2. 数据名称固定为 `furkin_revocation`。
3. 锚定主世界，使用 `MinecraftServer#overworld()` 获取，不按维度分裂。
4. 主键为 `companionId`。
5. 每条墓碑至少保存：
   - `companionId`
   - `ownerUuid`
   - 强制解绑请求时的 game time
6. 提供：
   - `put(...)`
   - `get(...)`
   - `contains(...)`
   - `remove(...)`
   - 只读视图
7. 写入/删除均调用 `setDirty()`。
8. NBT 使用列表序列化，读取时跳过无效 UUID，避免单条坏数据使整份墓碑失效。
9. 墓碑不保存实体列表、物品内容或 tick 任务。

#### 阶段验证

- `compileJava` 通过。
- `save -> load` 往返检查覆盖多条墓碑、跨维度语义和缺失字段。
- 静态检查确认只从服务器主世界获取，不按当前玩家维度创建副本。

#### 实施结果（2026-09-25）

- 新增 `FurkinRevocationData extends SavedData`，数据名称固定为 `furkin_revocation`。
- 数据只从 `MinecraftServer#overworld()` 的 `DataStorage` 获取，属于服务器级单例，不按玩家或实体所在维度分裂。
- 主键为 `companionId`；每条墓碑保存 `companion_id`、可选 `owner_uuid` 和 `requested_at_game_time`。
- 提供 `put(...)`、`get(...)`、`contains(...)`、`remove(...)` 和 `all()` 只读视图；写入与实际移除均调用 `setDirty()`。
- NBT 使用 `entries` 列表：缺失 `companion_id` 的坏条目跳过；缺失 owner 的条目保留墓碑并使用 `null`，记录 WARN；缺失请求时间默认 `0L`。这样不会让诊断字段损坏阻断真正需要的实体延迟清理。
- 生命周期：B4 在确认实体仍不可解析后写入墓碑并删除普通档案；B5 在实体入世命中后执行共享清理，成功才 `remove(...)`。
- 恢复语义：墓碑仅保留身份、所有者和请求时间，不保存实体列表、物品或 tick 任务；跨维度与跨重启的清理依据是实体能力中的 `companionId`。
- 临时探针验证通过：3 条墓碑往返、缺失诊断字段、坏 UUID 跳过、只读视图、二次往返与删除；输出 `WP02B_B3_ROUNDTRIP_OK entries=3`。
- 静态检查：`FurkinRevocationData` 仅通过 `server.overworld().getDataStorage()` 获取；没有按维度获取或 tick 路径。
- 验证：`compileJava` 通过；`clean build` 通过；`runServer` 到达 `Done (2.614s)`；`run/logs/latest.log` 无新增 `ERROR`、`FATAL` 或异常栈；临时探针源码与 Gradle 脚本已删除，最终 JAR 不含 `RevocationRoundtripProbe`。
- B3 只提供数据层，尚未接入强制解绑或入世事件；接线分别属于 B4、B5。

#### 确认门

B3 已完成；等待确认后进入 B4。

---

### B4：强制解绑、二次确认与结果反馈

目的：在常规解绑不可解析时，让玩家能够先写墓碑、再注销档案，且不创建替代实体。

#### 写入范围

- `FurkinRecordActionHandler`
- `RecordActionPacket`
- 新增服务端 -> 客户端结果包
- `FurkinNetwork`
- `FurkinRecordScreen` 或新增确认界面
- `FurkinCommand`
- `en_us.json`、`zh_cn.json`

#### 实现步骤

1. `FurkinRecordActionHandler` 新增 `forceUnbind(...)`：
   - 档案存在且属于玩家。
   - 再次执行定向定位。
   - 实体仍可解析时返回“已可解析”，要求走常规解绑，不写墓碑。
   - 实体仍不可解析时写 `FurkinRevocationData.put(...)`。
   - 墓碑写入成功后才 `archive.removeEntry(...)`。
   - 不创建实体，不发送普通召唤动作。
2. `RecordActionPacket` 新增 `FORCE_UNBIND` 动作，或等价的显式强制字段。
3. 新增结果回执包，至少携带：
   - companionId
   - 动作类型
   - 结果枚举
   - 服务端是否允许进入强制确认
4. 常规解绑失败为 `ENTITY_UNRESOLVED` 时：
   - 客户端展示二次确认页。
   - 文案必须说明：原实体当前不可访问；其行囊和装备会在以后入世时于原位置掉落；该操作不会重建实体。
   - 确认后才发送 `FORCE_UNBIND`。
5. 命令：
   - `/furkin forget <id>` 走常规解绑。
   - `/furkin forget <id> force` 走强制解绑。
   - `force` 是显式二次确认，不绕过服务端重新校验。
6. 协议修改时把 `PROTOCOL_VERSION` 提升到 `2`，并检查全部注册包方向与两端处理器。
7. 所有玩家可见文案使用翻译键；`en_us.json` 与 `zh_cn.json` 键集合同步。

#### 阶段验证

- `compileJava` 通过。
- 网络字段、方向、处理器和协议版本静态核对通过。
- 客户端隔离检查通过，不引入 `net.minecraft.client.*` 到共享代码。
- 实机确认流程留到 B6。

#### 实施结果（2026-09-25）

- `FurkinRecordActionHandler.Result` 新增 `ENTITY_RESOLVED`。
- `forceUnbind(...)` 校验档案、主人和已召唤状态；再次执行定向定位；实体仍可解析时返回 `ENTITY_RESOLVED`，仍不可解析时先写 `FurkinRevocationData`，确认墓碑可读后才删除普通档案，不创建、召唤或重建实体。
- `RecordActionPacket.Action` 新增 `FORCE_UNBIND`；常规 `UNBIND` 返回 `ENTITY_UNRESOLVED` 时附带 `forceUnbindAllowed=true`，确认后发送 `FORCE_UNBIND`。
- 新增服务端 -> 客户端 `RecordActionResultPacket`，注册方向为 `PLAY_TO_CLIENT`；协议版本从 `1` 提升到 `2`。
- 新增 `ForceUnbindConfirmScreen` 二次确认页，文案明确原实体当前不可访问、行囊和装备会在以后入世时于原位置掉落、不会重建实体。
- `/furkin forget <id>` 走常规解绑，`/furkin forget <id> force` 走显式强制解绑；两种入口都调用同一套服务端校验。
- `en_us.json` 与 `zh_cn.json` 均新增对应文案，键集合一致（各 130 键）。

#### 验证证据

- `compileJava` 通过。
- `clean build` 通过。
- B4 网络包往返探针输出 `WP02B_B4_PACKET_ROUNDTRIP_OK`。
- B4 的 `runServer` 启动到达 `Done`，`runClient` 客户端启动成功；`run/logs/latest.log` 无新增 `ERROR`、`FATAL` 或异常栈。
- 最终 JAR 不含 `B4PacketRoundtripProbe` 或临时 Gradle 脚本；客户端实机确认流程留到 B6。

#### 确认门

B4 已完成；等待确认后进入 B5。

---

### B5：实体入世时的墓碑延迟清理

目的：原实体以后加载时完成强制解绑未完成的物品掉落、AI 恢复和状态清理。

#### 写入范围

- `CommonEvents#onEntityJoinLevel`
- B1 共享清理管线接口
- 客户端同步调用

#### 实现步骤

1. 服务端入世事件先检查任何 `LivingEntity` 能力中的 `companionId`。
2. 用 `companionId` 查询服务器级 `FurkinRevocationData`。
3. 命中墓碑：
   - 调 B1 共享清理。
   - 成功后才移除墓碑。
   - 物品按实体真实位置掉落。
   - 恢复原/第三方 AI，清空全部 Furkin 状态。
   - 成功同步客户端。
   - 失败时保留墓碑并记录 ERROR/WARN，后续入世继续重试。
4. 未命中墓碑：
   - 保持现有已契约 `TamableAnimal` 的 AI 重应用逻辑。
   - 不因墓碑查询影响普通实体。
5. 清理过程中不查普通档案，因为强制解绑路径已经删除档案。
6. 避免事件重入造成重复掉落：清理前先检查 `companionId`，成功后置空；未成功时保留墓碑且清理步骤必须幂等。

#### 阶段验证

- `compileJava` 通过。
- 静态检查确认墓碑查询无 tick 调用、无区块加载、无全服实体扫描。
- 失败保留墓碑和下次入世重试的代码路径清晰。
- 运行期场景登记到 B6。

#### 实施结果（2026-09-25）

- `CommonEvents#onEntityJoinLevel` 改为先按任意 `LivingEntity` 的能力 `companionId` 查询服务器级 `FurkinRevocationData`，命中墓碑时进入延迟清理，不再要求实体先是 `TamableAnimal`。
- 墓碑命中后调用 `FurkinUnbindCleanup.cleanup(living, companionId, Trigger.REVOCATION)`：成功才 `revocations.remove(companionId)`，随后用 `TRACKING_ENTITY_AND_SELF` 发送清空后的 `SyncFurkinDataPacket`。
- 清理失败时记录携带 companionId、entity UUID 和失败阶段的 WARN，保留墓碑，不查普通档案，也不继续按已注销档案重应用 AI；下一次实体入世可再次命中。
- 未命中墓碑时，原已契约 `TamableAnimal` 的定位刷新和战斗 AI 重建逻辑保持不变；普通实体只多一次 capability 检查和墓碑 map 查询。
- 对“清理后段失败时 `clearForUnbind()` 已清空 companionId”的边界做了加固：REVOCATION 路径失败后会把 companionId 放回，避免延迟清理永久失去重试入口。

#### 验证证据

- `compileJava` 通过。
- `clean build` 通过。
- `runServer` 启动到达 `Done (2.653s)`；`run/logs/latest.log` 无 `ERROR`、`FATAL` 或 Java 异常栈。
- 静态检查确认 B5 的墓碑查询与清理路径无 tick 调用、无区块加载、无全服实体扫描。
- 尚未执行真实墓碑实体入世、物品落地和重启重试；这些运行期场景留到 B6。

#### 确认门

B5 已完成；等待确认后进入 B6。

---

### B6：专项服务端夹具与运行期验证

目的：在不提交临时夹具的前提下，重现并关闭 H-02 的完整矩阵。

#### 夹具范围

临时夹具至少覆盖：

- 旧档 `entity_uuid/entity_dimension` 缺失。
- 已召唤实体同维度 UUID 命中。
- 已召唤实体跨已加载维度 UUID 命中。
- 已召唤实体未加载或 UUID 缺失时返回 `ENTITY_UNRESOLVED`，档案仍在。
- 带满行囊解绑：物品落地、行囊清空且容量为 0。
- 穿四件盔甲解绑：四件落地、槽位清空、掉率恢复默认。
- 解绑前设置 cooldowns、feedCount、lastFeedMillis：全部归零。
- 解绑前施加技能属性效果：全部移除。
- 解绑前坐定：坐姿和坐定意图都归零。
- 解绑后重新契约：旧行囊、旧冷却、旧进食状态不回流。
- FOLLOW / PASSIVE / PROTECT / AGGRESSIVE 解绑后无 Furkin goal 残留，原 goal 恢复。
- 未召唤/已亡档案按 P0-1 决议处置装备快照。
- 强制解绑：墓碑写入成功后才删档案，且不创建实体。
- 墓碑实体以后入世：在真实位置掉落、恢复 AI、清空全部状态、最后删墓碑。
- 清理失败：墓碑保留；修复触发条件后下一次入世继续清理。
- 墓碑 `SavedData` 保存/重载后仍有效；必要情况下做一次真实 `runServer` 重启验证。
- 常规清理失败：普通档案不删除，不丢失玩家访问权。

#### 验证命令

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

#### 日志门槛

- 不用未执行结果代替运行结果。
- `run/logs/latest.log` 不得出现新增 `ERROR`、`FATAL`、异常栈、注册失败或资源缺失。
- 最终 JAR 必须不包含临时夹具类。
- 夹具删除后再执行一次 `build`，并用 `jar tf` 确认测试类不存在。

#### 实施结果（2026-09-25）

- 临时服务端夹具覆盖 B6 矩阵：旧档定位信息缺失、已加载维度 UUID 命中、未加载实体不创建替代、常规清理与属性技能移除、解绑后重新契约不回流、未召唤档案装备快照掉落并删档、四种战斗模式 AI 恢复、强制解绑墓碑、墓碑入世延迟清理与失败重试、常规清理失败保留档案，以及墓碑 `SavedData` 跨真实重启持久化。
- 两轮 `runServer` 完成运行期验证：第一轮准备夹具数据并写出重启标记，第二轮读取重启状态并完成延迟阶段断言；夹具最终输出 `WP02B_B6_FIXTURE_OK checks=114 failed=0 restartPass=true`。
- 临时夹具源码已删除，重启状态文件已清理；`clean build` 通过，`jar tf` 确认最终 JAR 不包含 `Wp02bUnbindFixture`。
- 删除夹具后的 `runServer` 到达 `Done`；`runClient` 客户端初始化完成后人工终止。两端分别复核启动日志，均未出现新增 `ERROR`、`FATAL`、Java 异常栈、注册失败或资源缺失。

#### 确认门

B6 已完成；等待乌狸确认后进入 B7。

---

### B7：文档、变更记录与关闭

目的：把已实现和已验证的结论写回项目文档，关闭 H-02。

#### 写入范围

- `docs/code_review_2026-09-24/wp-02_ai_ownership_unbind_design.md` 的执行状态
- `docs/code_review_2026-09-24/code_review_2026-09-24_workflow.md`
- WP-02B 实施计划状态
- 面向玩家的 `CHANGELOG.md`、`changelog.en.md`
- 必要时更新 README 中解绑行为说明
- 不在实施阶段擅自递增发布版本；正式发布时按项目版本规则决定 PATCH

#### 实施结果（2026-09-25）

- 已完成 H-02 关闭文档：更新本实施计划、WP-02 设计、总工作流、代码审查报告、双语 changelog 和 README/README.zh-CN 的解绑说明。
- 关闭口径：常规解绑完整清理并在失败时不删档；不可解析实体可通过强制解绑登记墓碑，原实体以后入世时完成延迟清理并重试。
- 提交/推送未执行；当前工作区改动保留给乌狸确认后再提交。

#### 关闭清单

> B7 已汇总 B6 技术验证证据并完成文档与关闭；提交/推送仍待乌狸授权。

- [x] 常规解绑恢复原版/第三方冲突 goal，无 Furkin goal 残留。
- [x] 行囊和装备有明确、可验证、不丢失的处置。
- [x] cooldowns、feedCount、lastFeedMillis、技能效果和行囊容量不回流。
- [x] 已召唤实体不可解析时常规解绑不删档，并返回 `ENTITY_UNRESOLVED`。
- [x] 强制解绑先写服务器级墓碑，再删普通档案，不创建实体。
- [x] 墓碑跨维度、跨重启有效。
- [x] 原实体以后入世时墓碑清理成功才移除。
- [x] 清理失败保留档案或墓碑，可再次重试。
- [x] 解绑定位无全服实体扫描、无区块加载、无 tick 轮询。
- [x] 临时夹具已删除，最终构建产物不含夹具。
- [x] `compileJava`、`build`、`runServer`、`runClient` 和日志复核完成。
- [x] 文档状态已与当前工作区实现、验证证据和未提交状态对齐。
- [x] 未在未授权情况下提交、推送；提交/推送仍待乌狸授权。

---

## 5. 阶段依赖图

```text
P0-1 决策
  -> B0 档案 UUID/维度
  -> B1 统一清理管线
  -> B2 常规定位与 ENTITY_UNRESOLVED
  -> B3 服务器级墓碑
  -> B4 强制解绑与确认交互
  -> B5 入世墓碑延迟清理
  -> B6 服务端夹具与运行验证
  -> B7 文档与关闭
```

不得跳过 B1 直接实现 B4，否则强制解绑会继续调用不完整清理。不得跳过 B2 直接写墓碑，否则无法证明“实体确实不可解析”。

## 6. 预计风险与处理

| 风险 | 影响 | 处理 |
|---|---|---|
| 未召唤档案的装备快照边界 | 文字上仍可能丢装备 | 已确认：非空快照掉落在玩家脚下，再删除档案 |
| 清理中途异常 | 可能部分清理但未删档 | 每步幂等、档案最后删除、失败可重试 |
| 装备掉落重复 | 重试复制物品 | 掉落后立即清槽/清容器，先摘后掉按原版流程核对 |
| 协议包变更未同步两端 | 客户端连接或动作失败 | B4 同步更新包、方向、处理器、`PROTOCOL_VERSION` |
| 墓碑清理失败 | 旧实体保留 Furkin 状态 | 保留墓碑，下一次 `EntityJoinLevelEvent` 重试 |
| 旧档无 UUID/维度 | 常规解绑无法定位 | 返回 `ENTITY_UNRESOLVED`，强制解绑写墓碑，不做全服扫描 |
| H-03 未完成 | 跨维度档案仍可能分裂 | WP-02B 只做墓碑和定向查询，完整统一留 WP-03 |
| 第三方掉率/自定义 goal | 无法完整还原第三方内部状态 | 仅恢复可公开读取/保存的冲突 goal；掉率写回原版默认值并记录边界 |

## 7. 当前执行状态

- [x] 计划文档已创建
- [x] P0-1 决策已确认：非空装备快照掉落到玩家脚下，再删除档案
- [x] B0 档案定位字段（2026-09-25）
- [x] B1 统一清理管线（2026-09-25）
- [x] B2 常规定位与 `ENTITY_UNRESOLVED`（2026-09-25）
- [x] B3 服务器级墓碑（2026-09-25）
- [x] B4 强制解绑与二次确认（2026-09-25）
- [x] B5 入世墓碑延迟清理（2026-09-25）
- [x] B6 专项夹具与运行验证（2026-09-25）
- [x] B7 文档与关闭（2026-09-25；提交/推送待授权）
