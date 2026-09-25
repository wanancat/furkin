# WP-02：AI 所有权、解绑清理与生命周期设计

- 文档状态：设计已完成；WP-02A A0-A8 已完成；WP-02B B0-B7 文档与运行验证关闭完成，H-02 已关闭；提交/推送待乌狸授权
- 制定日期：2026-09-24
- 工作分支：`mc1.20.1/dev`
- 审查基线：`66dd99b78357f224c66aafe6faf9aa76a6a8a78e`
- 当前代码基线：`184e82e0565f40961fd7b232c7e66f72d31f68c9`（WP-02A 已推送；WP-02B 工作区待提交）
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0
- 依据：
  - `docs/code_review_2026-09-24.md` H-02、M-03
  - `docs/code_review_2026-09-24_workflow.md` WP-02
  - `docs/contract_precondition_workflow.md` §4.4、§7.3

---

## 1. 背景与已确认问题

WP-02 同时覆盖两个存在技术依赖的问题：

- M-03：当前 `FurkinCombatMode` 按基类删除 goal，会误删原版或第三方 AI。
- H-02：解绑没有清理行囊、装备、运行时状态和本模组 AI，可能永久丢物品或留下行为残留。

必须先建立准确的 goal 所有权与恢复机制，再让解绑使用该机制完成完整清理。

### 1.1 M-03 当前实现

位置：

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatMode.java`

当前 `applyTo(TamableAnimal)`：

1. 先按基类删除已有攻击目标：
   - `HurtByTargetGoal`
   - `OwnerHurtByTargetGoal`
   - `OwnerHurtTargetGoal`
   - `NearestAttackableTargetGoal`
   - `MeleeAttackGoal`
2. 再按战斗模式重新添加本模组创建的 goal。

问题：

- 删除条件只判断基类，没有判断 goal 是否由 Furkin 创建。
- 原版狼、猫及第三方 `TamableAnimal` 可能本来就有同类 goal。
- 契约、切换模式和重新召唤都会再次执行这段删除逻辑。
- 解绑又明确不处理 goalSelector / targetSelector，因此被删掉的原版 goal 不会恢复。

### 1.2 H-02 当前解绑路径

位置：

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java`
- `src/main/java/com/wanancat/furkin/internal/equipment/EquipmentSlots.java`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java`

当前 `unbind`：

1. 校验档案与主人。
2. 如果档案标记已召唤，按 `companionId` 查找在场实体。
3. 找到实体则调用 `clearFurkinLayer`。
4. 删除档案条目。

当前 `clearFurkinLayer` 只清：

- `companionId`
- `ownerUuid`
- `level`、`xp`、`skillPoints`
- `skillLevels`
- `combatMode`
- `state`
- `TamableAnimal` 的 TAME、主人、坐定意图
- `CustomName`

当前未清：

- `FurkinData#getPouch()` 中的物品与容量。
- 四个盔甲槽。
- 契约时由 `EquipmentSlots.sealDrops` 设为 0 的 `ArmorDropChances`。
- `cooldowns`。
- `feedCount`、`lastFeedMillis`。
- 本模组添加的 goalSelector / targetSelector。
- 当前 Mob 目标。
- `setInSittingPose(false)`。

### 1.3 只读勘察新增发现

#### 发现 A：区块重载后不会自动恢复 Furkin AI

当前全仓只有 4 个 `applyTo` 调用点：

- 契约成功。
- 战斗模式切换。
- 召唤 / 复活重建。

没有 `EntityJoinLevelEvent` 或其他实体入世钩子。

实体经过区块卸载再加载后：

- 原版构造函数会重新注册原版 goal。
- 本模组此前添加的 goal 是运行时对象，不会随实体 NBT 持久化。
- 因此 Furkin 战斗模式可能失效，直到玩家再次切换模式或重新收回/召唤。

#### 发现 B：已召唤实体无法找到时仍会删档

当前 `unbind` 只搜索玩家当前 `ServerLevel`：

- 如果宠物在其他维度或实体所在区块未加载，`findLivingByCompanionId` 返回 null。
- 当前代码仍会执行 `archive.removeEntry(companionId)`。
- 实体之后加载时会保留 Furkin 能力数据和可能的本模组 AI，但档案已经不存在，形成无档案可管理的孤儿状态。

该问题与 H-03“档案按维度分裂”有交叉。WP-02 至少应避免静默丢失档案访问权。

### 1.4 已核对的 1.20.1 / Forge API

通过当前分支的 mapped official jar 核对：

- `GoalSelector#removeGoal(Goal)` 是公开方法。
- 其实现按 `goal == wrapped.goal` 做引用身份比较。
- 删除运行中的 goal 时会先调用 `stop()`。
- `GoalSelector#getAvailableGoals()` 返回 `Set<WrappedGoal>`。
- `WrappedGoal#getGoal()` 与 `getPriority()` 可用于保存原 goal 与优先级。
- `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE` 是公开常量，值为 `0.085F`。
- `Mob#setDropChance(EquipmentSlot, float)` 是公开方法。
- `Mob` 没有公开的原始掉率 getter；`getEquipmentDropChance(...)` 是 protected。
- `Mob#getItemBySlot(...)`、`setItemSlot(...)` 是公开方法。
- `Containers.dropContents(Level, Entity, Container)` 可用于把容器内容掉在实体脚下。
- `OcelotAttackGoal` 继承 `Goal`，不是 `MeleeAttackGoal`。
- `NonTameRandomTargetGoal` 继承 `NearestAttackableTargetGoal`，最终属于 `TargetGoal`。
- `ServerLevel#getEntity(UUID)` 是公开方法，内部调用 `LevelEntityGetter#get(UUID)`；用于按实体 UUID 做索引查询，不遍历实体列表。

这些结论均以当前 `forge-1.20.1-47.2.0_mapped_official_1.20.1.jar` 为准，不从 1.19.2 反推。

---

## 2. 目标与非目标

### 2.1 目标

- 本模组只删除自己创建的 goal。
- 对必须暂时替换的原版/第三方 goal，保存可恢复的原状态。
- 解绑后恢复原版/第三方 AI，并移除全部本模组战斗 goal。
- 解绑时完整处理行囊、装备、掉率和运行时状态。
- 常规解绑在实体清理和物品掉落成功后，才删除档案条目。
- 不可解析实体提供“强制解绑 + 注销墓碑”；不创建新实体，实体以后入世时继续延迟清理。
- 实体区块重载、跨维度移动、收回与重召唤后，Furkin AI 生命周期闭合。
- 对新契约直接启用完整机制；对尚未实施时的旧档残留给出明确边界。
- 不改变公开 API，不改变现有网络包字段与方向。

### 2.2 非目标

- 不扩展非 `TamableAnimal` 物种的完整 AI 生命周期。
- 不把 `AGGRESSIVE` 的 `Monster.class` 候选改成全 `Enemy`。
- 不在 WP-02 修复 H-03 的完整档案碎片化和跨维度迁移链路；仅新增低成本的服务器级注销墓碑，用于不可解析实体的玩家自救和延迟清理。
- 不保证自动修复旧版本已经永久删除且无法识别来源的原版/第三方 goal。
- 不引入通用 AI 框架、反射、全局文本替换或无必要的 access transformer。
- 不改变现有四档战斗模式的用户可见定义。
- 不把运行时 goal 对象写入实体 NBT、档案快照或网络包。

---

## 3. 设计决策

### 3.1 D1：goal 所有权机制

推荐：**私有 goal 类型 + 精确实例记录 + 原状态快照恢复**。

实现要点：

- 本模组创建的 goal 使用包内私有子类，并实现服务端内部标记接口。
- 每次 `applyTo` 先删除上一轮记录的精确 goal 实例。
- 删除使用 `GoalSelector#removeGoal(instance)`，不再按公共基类全局删除。
- 对必须接管的原版/第三方冲突 goal：
  - 首次在新实体上接管时，读取原 `WrappedGoal`。
  - 保存原 goal 实例和优先级。
  - 使用 `removeGoal(instance)` 精确移除。
  - 解绑时按原优先级重新加入。

为什么不只使用 `instanceof FurkinOwnedGoal` 删除：

- 精确实例记录能处理同一 mob 重复 apply、异常中断和部分残留。
- 私有子类和运行时记录同时存在时，既有强所有权，也有幂等恢复能力。
- 公共基类判断无法证明 goal 来源，继续作为主删除条件不满足 M-03。

### 3.2 D2：原版/第三方 goal 快照范围

推荐：只快照会被当前战斗模式直接冲突的 goal：

- 两个选择器中的全部 `TargetGoal`
- `MeleeAttackGoal`
- `OcelotAttackGoal`

原因：

- `TargetGoal` 覆盖猫的 `NonTameRandomTargetGoal`、狼的护主/反击/骷髅索敌及第三方目标 goal。
- `MeleeAttackGoal` 覆盖狼和通用近战目标。
- `OcelotAttackGoal` 覆盖猫的原版攻击动作。
- 不能清空全部 goal。`FloatGoal`、`FollowOwnerGoal`、`SitWhenOrderedToGoal`、`PanicGoal` 等非冲突行为必须原样保留。
- 不能扩大到无法枚举的第三方自定义攻击 goal；本模组不删除它们，避免制造新的兼容破坏。

快照结构推荐：

- 优先直接保存 `WrappedGoal` 引用；这些 wrapper 已由 `GoalSelector` 创建，不再复制 goal 对象。
- 如果担心 wrapper 被后续版本或第三方改动，再退化为保存 `Goal + int priority`。
- 两种方案都只保存引用和优先级，不复制、不序列化 goal。
- `GoalSelector#addGoal(int, Goal)` 会重新创建 wrapper。恢复时只保证原 goal
  实例与优先级一致，不保证 `WrappedGoal` 对象身份相同。

分别保存：

- `goalSelector` 中被移除的冲突 goal。
- `targetSelector` 中被移除的冲突 goal。

恢复时：

- 先清除本模组拥有的 goal。
- 再把快照中的原 goal 按原优先级逐项加回原选择器。
- 恢复只发生在解绑；模式切换不恢复，因为绒亲仍处于接管状态。

### 3.3 D3：旧档兼容

推荐：**不做猜测性硬编码修复；允许旧实体在重新召唤时升级。**

原因：

- 旧版本可能已经把原版/第三方 goal 永久删除。
- 当前 selector 中剩余的内容无法可靠区分：
  - 原版 goal。
  - 第三方 goal。
  - 旧 Furkin 代码添加的 goal。
- 根据当前 selector 反推“原始状态”可能把旧 Furkin goal 当成原版恢复，解绑后反而留下残留。
- 当前档内没有足够的旧数据来恢复任意第三方 goal 构造参数。

采用规则：

- 新增持久化的 `ai_state_version`。
- 缺失字段的旧实体视为 `version = 0`。
- `version = 0` 的在世旧实体：
  - 不再按错误方式恢复。
  - 本模组仍只管理自己新添加的 goal。
  - 解绑时能清除本模组 goal，但不能保证恢复旧版本已误删的原版/第三方 goal。
  - 记录旧档残留风险，不在日志中伪装成成功恢复。
- 旧档案通过收回/召唤或复活重建时：
  - 新实体由原版构造函数重新注册完整原版 goal。
  - 在重建时写入当前 `ai_state_version`。
  - 随后按新机制保存原状态，完成自动升级。

### 3.4 D4：运行时状态存放

推荐：`FurkinData` 持有仅运行时的 `FurkinCombatAiState`。

理由：

- `FurkinData` 的生命周期就是实体能力对象生命周期。
- 区块卸载后运行时状态随实体对象释放，不产生静态 Map 泄漏。
- 跨维度传送时同一实体对象和能力状态可继续使用，不需要在 leave/join 间搬家。
- goal 实例、原优先级和 owner 记录不应进入实体 NBT、档案快照或网络同步。
- 静态全局注册表需要额外处理实体卸载、传送、死亡、服务端停止和 GC，复杂度更高，且容易在维度切换时错误重建快照。

字段建议：

- `FurkinData` 增加非持久化 `FurkinCombatAiState combatAiState`。
- `serializeNBT()` 不写该字段。
- `syncNBT()` 不写该字段。
- `deserializeNBT(...)` 开始时置空运行时状态，避免 provider 复用时携带旧对象。
- `copy()` 不复制 goal 实例与快照；只复制版本数字。

### 3.5 D5：解绑物品处置

推荐：

| 物品 | 处置 |
|---|---|
| 行囊 | 掉落到实体脚下，然后清空并将容量缩回 0 |
| 未召唤/已亡档案的盔甲快照 | 掉落到发起解绑的玩家脚下，然后删除档案 |
| 四个盔甲槽 | 掉落到实体脚下，然后清空 |
| 盔甲掉率 | 清空后恢复 `Mob.DEFAULT_EQUIPMENT_DROP_CHANCE` |
| 主手/副手 | 不属于绒亲装备功能线，不处理 |
| 档案 | 所有清理完成后最后删除 |

不推荐“归还主人背包”作为 WP-02 默认：

- 需要额外实现背包插入、溢出掉落、槽位顺序和失败回滚。
- 与现有收回、死亡路径的 D6 语义不一致。
- 当前项目已经用 `PouchDrop.dropAll` 定义“实体承载的物品在生命周期结束前掉落”，装备应沿用同一语义。

不推荐“装备继续穿在实体上并恢复掉率”：

- 玩家需要再次手动取回。
- 解绑后普通动物仍可能带着四件盔甲，视觉和物品所有权不清。
- 第三方原始掉率无法读取，只能写默认值，仍有精确恢复缺口。

### 3.6 D6：找不到已召唤实体时的解绑与玩家自救

已确认：**常规解绑不得以删档掩盖实体未解析；同时必须提供玩家可执行的自救路径。**

常规定位规则：

- `FurkinArchiveEntry` 持久化 `entity_uuid` 与 `entity_dimension`。
- 契约、召唤、复活和维度切换后更新这两个字段。
- 解绑优先在记录维度调用 `ServerLevel#getEntity(UUID)`；该公开 API 内部走 `LevelEntityGetter#get(UUID)` 索引查询。
- 记录维度已过期时，最多对服务器已加载维度各做一次 UUID 索引查询，不遍历实体列表。
- 不加载区块，不扫描 region 文件。

解绑规则：

- 找到实体：按 §5.6 正常清理，全部成功后删除档案。
- `entry.isSummoned() == true` 但找不到实体：
  - 返回独立、可诊断的失败结果，例如 `ENTITY_UNRESOLVED`。
  - 不调用 `archive.removeEntry(...)`。
  - 不自动召唤，不创建新实体。
- 玩家二次确认后可执行“强制解绑”：
  - 在服务器级 `FurkinRevocationData` 写入按 `companionId` 索引的注销墓碑。
  - 写入成功后才移除普通档案条目。
  - 强制解绑不创建任何新实体。
- 原实体以后通过任意维度入世时：
  - `EntityJoinLevelEvent` 查询注销墓碑。
  - 命中后按正常解绑顺序清理行囊、装备、技能、状态和本模组 AI。
  - 物品掉落在实体真实位置。
  - 清理全部成功后移除墓碑；失败则保留墓碑，下次入世重试。
- `entry.isSummoned() == false` 时实体本就不在场；若档案存在非空 `equipmentSnapshot`，先掉落到发起解绑的玩家脚下，再删除档案。

边界：

- 墓碑必须为服务器级数据，不能依赖单维度 `FurkinArchiveData`。
- 实体长期不加载时，墓碑只占极小持久化空间，不产生 tick 负载。
- H-03 的完整档案合并、跨维度迁移和其他全局索引仍归 WP-03；本决策只解决不可解析实体的自助解绑与延迟清理。

---

### 3.7 性能与官方 API 复用评估

结论：如果快照只保存运行时引用、只覆盖冲突 goal、且不进入 NBT / 档案 / 网络同步，那么服务端和客户端负荷都可以忽略。

#### 3.7.1 负荷评估

| 项目 | 评估 |
|---|---|
| 服务端内存 | 每只在场绒亲通常只保存 2～5 个冲突 goal 引用和优先级，远小于 1 KiB |
| 服务端 CPU | 首次 apply / 实体入世时遍历一次 `GoalSelector`，O(G)；正常 AI tick 不变 |
| 服务端存档 | 不保存 goal 对象，只多一个 `ai_state_version` 整数 |
| 客户端 | 不创建快照，不接收快照，不执行 apply，基本零额外负荷 |
| 网络 | 不增加包字段；快照不进入 `syncNBT()` |
| 全部已契约绒亲 | 未加载 / 已收回的绒亲不持有运行时快照；只有当前加载实体需要 |

当前默认活跃上限为 3，配置最高为 64。即使每名玩家满上限、服务器多人同时在线，快照引用量仍然很小。

关键点：

- 所谓“原 AI 快照”不是复制 goal 对象。
- 原版构造实体时本来就已经创建了这些 goal。
- 本模组只是把被暂时移除的同一批实例从 selector 移到引用列表中保存。
- goal 对象总数基本不增加，只增加一段很短的引用列表。
- `EntityJoinLevelEvent` 只在实体入世时执行一次 O(G) 遍历，不在 tick 中维护快照。

#### 3.7.2 同物种 AI 是否相同

“结构经常相同”不等于“实例可以共用”：

- 每个 Mob 实例都会执行自己的 `registerGoals()`。
- goal 构造时通常直接绑定 `this`，例如 `OwnerHurtByTargetGoal(tamable)`、`MeleeAttackGoal(mob, ...)`。
- 同一个 goal 实例不能安全地跨实体复用。
- goal 内部可能有运行状态、目标状态、记忆 tick 等。
- 第三方模组可能按实体、状态或事件动态增减 goal。
- 猫、狼、幼年 / 成年、变种、其他模组注入行为都可能造成差异。

因此不能只保存一份“狼物种模板”然后给所有狼恢复。最多能缓存“哪些类型属于冲突 goal”的静态判定规则，但真正被移除的实例仍然要按实体保存引用。

引用列表本身已经非常轻，做物种级复用省不了多少内存，却会引入恢复不完整、第三方兼容和版本漂移风险，不值得。

#### 3.7.3 官方可复用的轮子

可复用的官方 API 是：

- `GoalSelector#getAvailableGoals()`
- `WrappedGoal#getGoal()`
- `WrappedGoal#getPriority()`
- `GoalSelector#removeGoal(Goal)`
- `GoalSelector#addGoal(int, Goal)`
- `Mob#goalSelector` / `Mob#targetSelector` 公开字段

这些已经足够实现最小快照与恢复，属于使用官方原语，不需要重写 AI。

不可直接复用的官方能力：

- 没有公开的 `Mob#registerGoals()`；它是 `protected`。
- 没有“克隆 AI / 恢复原 AI / 重置某个 goal”的官方方法。
- 没有按单个 goal 启停的公开 API；`GoalSelector#disableControlFlag` 是整个选择器级别，不适合这里。
- `MobSpawnEvent.FinalizeSpawn` 只适合生成阶段，不适合解绑时恢复原状态。

不建议为了调用 `registerGoals()` 加 access transformer：

- 它是追加式注册，不会自动清理旧 goal。
- 直接调用会造成重复 goal。
- 先清空全部 selector 会误删第三方 goal。
- 不同实体对重复调用未必幂等。
- 这比保存少量引用更脆弱。

事件层拦截 `LivingChangeTargetEvent` 理论上是另一种方案，但会把静态的 AI 所有权问题变成长期运行时判定，而且要区分 FOLLOW / PASSIVE / PROTECT / AGGRESSIVE 的目标来源，复杂度和误判风险更高，不建议作为 WP-02 主方案。

已排除方案：解绑路径不使用“创建新实体”。该方案不作为默认路径，也不作为旧档修复或显式重置的兜底方案。

### 3.8 解绑定位与注销墓碑成本

结论：低成本方案不进行全服实体遍历，服务器增量成本可忽略。

| 操作 | 增量成本 |
|---|---|
| 正常解绑 | 记录维度内 1 次实体 UUID 索引查询；维度记录失效时最多 D 次，D 通常为 3 |
| 强制解绑 | 上述查询 + 1 次墓碑 Map 写入 + 1 次 `SavedData#setDirty()` |
| 原实体入世 | 仅对带 `companionId` 的实体做 1 次墓碑查询 |
| 每个服务器 tick | 0 |
| 区块加载 | 0 |
| 客户端 / 网络 | 0 |
| 立即磁盘 I/O | 0，跟随正常存档刷新 |
| 存档空间 | 每个实体 UUID 和墓碑记录都很小 |

唯一可能为 `O(N)` 的兼容路径，是旧档既没有实体 UUID、又选择主动搜索全部已加载实体。该路径不属于正常解绑，也不是强制解绑的前置条件；每人、每只旧宠物最多触发一次。

因此本方案不会产生持续扫描、区块加载或 tick 负担。

## 4. 推荐架构

### 4.1 新增 `FurkinCombatAiState`

建议位置：

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatAiState.java`

职责：

- 表示当前实体的 AI 接管状态。
- 保存原 goal 快照。
- 保存本模组拥有的精确 goal 实例。
- 记录快照是否已经建立。
- 记录是否为无法恢复的旧档实体。
- 不进入 NBT、网络同步或 API 包。

建议内容：

```text
boolean originalCaptured
boolean legacyPolluted

List<WrappedGoal> savedGoalSelectorGoals
List<WrappedGoal> savedTargetSelectorGoals

Set<Goal> ownedGoals
```

`ownedGoals` 建议使用 identity set：

```java
Collections.newSetFromMap(new IdentityHashMap<>())
```

原因：goal 的默认 `equals` 是对象身份；显式 identity set 与 `GoalSelector#removeGoal` 的 `==` 语义一致。

### 4.2 新增内部 goal 类型

建议在 `FurkinCombatMode` 内定义私有静态类，或集中在新内部工具类中：

- `FurkinMeleeAttackGoal extends MeleeAttackGoal`
- `FurkinHurtByTargetGoal extends HurtByTargetGoal`
- `FurkinOwnerHurtByTargetGoal extends OwnerHurtByTargetGoal`
- `FurkinOwnerHurtTargetGoal extends OwnerHurtTargetGoal`
- `FurkinNearestAttackableTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T>`

共同实现内部标记接口：

```text
FurkinOwnedGoal
```

要求：

- 类型不能被第三方继承或实例化。
- 所有 goal 构造后记录到当前实体的 `FurkinCombatAiState.ownedGoals`。
- 删除时优先按记录实例 `removeGoal(instance)`。
- 额外使用私有标记做兜底清理，避免状态记录异常时留下本模组 goal。
- 不改变 goal 的优先级和构造语义。

### 4.3 `FurkinData` 扩展

新增字段：

```text
private transient FurkinCombatAiState combatAiState;
private int aiStateVersion;
```

建议方法：

```text
getOrCreateCombatAiState()
getCombatAiState()
clearCombatAiState()
getAiStateVersion()
setAiStateVersion(int)
```

持久化规则：

- `ai_state_version` 写入 `serializeNBT()`。
- `ai_state_version` 不写入 `syncNBT()`。
- `combatAiState` 不写入任何 NBT。
- `deserializeNBT(...)` 读取不到 `ai_state_version` 时按 0。
- 当前版本常量建议为 `1`。

### 4.4 `FurkinCombatMode` 改造

建议保留枚举对外语义，新增内部生命周期方法：

```text
applyTo(TamableAnimal)
clearOwnedGoals(TamableAnimal, FurkinCombatAiState)
captureAndRemoveOriginalGoals(...)
restoreOriginalGoals(...)
onUnbind(...)
```

`applyTo` 推荐流程：

1. 客户端侧直接返回。
2. 获取实体 `FurkinData`；无能力则记录告警并返回。
3. 取得或创建 `FurkinCombatAiState`。
4. 清除上一轮本模组 owned goals，并清空 Mob 当前目标。
5. 如果尚未建立快照：
   - `aiStateVersion >= CURRENT`：快照并移除冲突原版/第三方 goal。
   - `aiStateVersion < CURRENT`：标记 legacy，只移除冲突 goal，不保存可恢复快照。
6. 按当前 `combatMode` 添加本模组 goal。
7. 把新 goal 实例加入 `ownedGoals`。

模式与 goal 对应关系保持不变：

| 模式 | 本模组 goal |
|---|---|
| `FOLLOW` | `MeleeAttackGoal` |
| `PASSIVE` | `MeleeAttackGoal` + `HurtByTargetGoal` |
| `PROTECT` | `PASSIVE` + `OwnerHurtByTargetGoal` + `OwnerHurtTargetGoal` |
| `AGGRESSIVE` | `PROTECT` + `NearestAttackableTargetGoal<Monster>` |

说明：

- `MeleeAttackGoal` 继续恒挂，保持现有行为，不在 WP-02 顺带改语义。
- 所有优先级维持当前实现：行动目标 5，护主 1/2，反击 3，主动索敌 4。

### 4.5 解绑入口改造

建议在 `FurkinRecordActionHandler` 中把清理拆成明确阶段：

```text
boolean cleanupAi(...)
boolean dropPouch(...)
boolean dropEquipment(...)
boolean clearRuntimeData(...)
void clearVanillaOwnership(...)
```

`clearFurkinLayer` 改为只在全部阶段完成后返回成功。

原则：

- 任何阶段未完成，不执行 `archive.removeEntry(...)`。
- 清理动作尽量使用现有工具：
  - `PouchDrop.dropAll`
  - `PouchDrop.dropStacks`
  - `SkillEffectApplier.removeAll`
  - `EquipmentSlots` 新增的设备掉落与掉率恢复方法
- 新的装备方法不应重复实现物品实体生成逻辑，优先复用 `Containers.dropContents`。

### 4.6 `EntityJoinLevelEvent` 重应用与注销清理

建议在 `CommonEvents` 新增服务端事件处理：

```text
onEntityJoinLevel(EntityJoinLevelEvent)
```

处理条件：

- 事件未被取消。
- `!event.getLevel().isClientSide()`。
- 实体是 `LivingEntity`，能力存在且 `data.getCompanionId()` 非空。

行动顺序：

1. 查询服务器级 `FurkinRevocationData` 是否包含该 `companionId`。
2. 命中注销墓碑：执行共享的强制解绑清理路径，成功后移除墓碑并同步客户端。
3. 未命中且实体是 `TamableAnimal`：调用 `data.getCombatMode().applyTo(animal)`。
4. 不再额外发送普通能力同步包；该包由原有跟踪 / 召唤路径负责。

作用：

- 区块卸载再加载时重新恢复 Furkin AI。
- 跨维度移动后重新确认当前选择器状态。
- 不可解析实体在以后入世时完成强制解绑的延迟清理。
- 新建、召唤和复活实体仍有显式 apply，事件重入时通过 `ownedGoals` 和快照状态保持幂等。

需要运行验证：

- `EntityJoinLevelEvent` 时能力 NBT 已经反序列化完成。
- `addFreshEntity` 路径与区块加载路径均不会重复添加上下文 goal。
- 注销清理失败时墓碑保留，下一次入世仍会重试。
- 客户端侧不执行 goal 修改或强制清理。

---

### 4.7 档案定位字段与服务器级注销墓碑

`FurkinArchiveEntry` 新增持久化字段：

```text
UUID entity_uuid
ResourceKey<Level> entity_dimension
```

写入与更新时机：

- 契约成功。
- 召唤 / 复活重建成功。
- 已契约实体维度切换或入世路径确认当前维度后。
- 旧档缺失时保持 `null`，不做猜测性补写；正常解绑会走 `ENTITY_UNRESOLVED`，玩家仍可使用强制解绑。

新增内部类型：

```text
com.wanancat.furkin.internal.record.FurkinRevocationData
```

要求：

- 继承 `SavedData`，锚定在主世界，属于服务器级数据，不按维度分裂。
- 数据名称建议为 `furkin_revocation`。
- 主键为 `companionId`。
- 值至少保存 `ownerUuid` 和请求时的游戏时间，便于诊断与后续扩展。
- 提供 `put`、`contains`、`remove` 和只读查询。
- 只记录真正执行了强制解绑的身份，不保存实体列表。
- 与原实体能力中的 `companionId` 配合，使任意维度入世都能完成延迟清理。

## 5. 生命周期顺序

### 5.1 新契约

顺序：

1. 确认目标和能力。
2. 将 `ai_state_version` 设为 `CURRENT`。
3. 调用 `FOLLOW.applyTo(target)`：建立原 goal 快照、移除冲突原版/第三方
   goal、添加 FOLLOW 的本模组 goal。
4. apply 成功后提交 companion 身份、等级、装备掉落封印和档案。
5. 给实体回写名字。
6. 最后消耗契约物品并同步客户端。

要求：

- 快照必须在第一个冲突 goal 被移除之前建立。
- `ai_state_version` 必须在首次 apply 之前设置为当前版本。
- apply 失败时不得消耗契约物品，也不得提交 companion 身份或档案。
- 实施顺序以“先可回滚的 AI 接管，后不可撤销的建档与扣物”为准；公开 API 不变。

### 5.2 战斗模式切换

顺序：

1. 服务端校验主人、已召唤、实体存在。
2. 写能力与档案模式。
3. 调用新模式的 `applyTo`。
4. `applyTo` 只删除 `ownedGoals` 中的本模组实例。
5. 保留原 goal 快照，不恢复、不重建。
6. 按新档位添加并记录新 goal。
7. 同步客户端。

重复调用结果：

- 删除旧的本模组 goal。
- 添加一组新实例。
- 不允许出现两组 Furkin 目标。
- 不允许触碰原版/第三方非冲突 goal。

### 5.3 收回与重召唤

收回：

- 实体 `discard`，运行时 `FurkinCombatAiState` 随实体对象释放。
- 档案只保存原有实体快照和业务数据。
- 不把 goal 实例或 AI 快照放进实体快照。

重召唤：

1. 新建实体。
2. 回灌实体快照。
3. 设置当前 `ai_state_version`。
4. 重建能力与技能。
5. 由 `EntityJoinLevelEvent` 或显式调用建立新实体上的原 goal 快照。
6. 应用战斗模式。

### 5.4 死亡与复活

死亡：

- 现有死亡快照、装备快照和行囊掉落顺序不变。
- 不尝试保存运行时 goal 状态。
- 装备仍然先封印掉落并保存快照。

复活：

- 新建实体。
- 走与重召唤相同的 AI 初始化路径。
- 对新实体保存原 goal 快照后接管。

### 5.5 区块加载与维度切换

区块加载：

- 新建实体完成原版 `registerGoals`。
- Forge 发出 `EntityJoinLevelEvent`。
- 本模组读取已加载的 `FurkinData`。
- 建立新运行时 AI 快照并应用模式。

维度切换：

- 同一实体对象如果跨维度保持能力对象，则复用已有快照。
- 如果维度流程重建实体，则新实体按区块加载路径重新建立快照。
- 不依赖静态全局 Map。

### 5.6 解绑

#### 5.6.1 常规路径

推荐顺序：

1. 校验档案主人。
2. 读取 `entry.isSummoned()`；未召唤/已亡时先按 §3.5 处置非空 `equipmentSnapshot`。
3. 如果已召唤，使用 `entity_uuid` 和 `entity_dimension` 做 `ServerLevel#getEntity(UUID)` 索引查询；记录维度失效时最多尝试所有已加载维度。
4. 如果已召唤但实体仍不可解析，返回 `ENTITY_UNRESOLVED` 并停止；不删档，不创建实体，不自动召唤。
5. 清除本模组 goal。
6. 清空 Mob 当前目标。
7. 恢复保存的原版/第三方 goal。
8. 行囊掉落到实体脚下并清空。
9. 四个盔甲槽掉落到实体脚下并清空。
10. 恢复四个盔甲槽默认掉落概率。
11. 先移除技能效果。
12. 清空技能等级并缩容行囊到 0；如有溢出，立即掉落。
13. 清空 cooldowns。
14. 清空 feedCount、lastFeedMillis。
15. 清空 companionId、ownerUuid、等级、经验、技能点。
16. combatMode 回 FOLLOW，state 回 WILD。
17. `ai_state_version` 回 0，清除运行时 AI 状态。
18. TamableAnimal 设置 TAME=false、owner=null、orderedToSit=false、inSittingPose=false。
19. 清空 CustomName 和可见性。
20. 同步客户端。
21. 最后 `archive.removeEntry(companionId)`。

顺序理由：

- AI 恢复必须先于状态清空，避免后续逻辑依赖 companion 判断。
- 行囊与装备必须在档案删除前掉落。
- 技能效果必须在技能等级清空前移除。
- 行囊容量必须在技能等级清空后重算，才能保证旧容量不残留。
- 档案最后删除，失败时玩家仍保有档案访问权和实体控制权。

#### 5.6.2 强制解绑路径

仅在常规路径返回 `ENTITY_UNRESOLVED` 后开放，并要求二次确认：

1. 再次校验主人和当前实体仍不可解析。
2. 向服务器级 `FurkinRevocationData` 写入 `companionId` 注销墓碑。
3. 写入成功后删除普通档案条目，使绒亲录不再显示该记录。
4. 不召唤、不重建、不创建任何替代实体。
5. 原实体以后入世时，由 `EntityJoinLevelEvent` 命中墓碑并执行与常规路径相同的清理。
6. 清理和物品掉落成功后移除墓碑；失败或异常时保留墓碑，下一次入世继续重试。

强制解绑的提示必须明确：原实体当前不可访问，因此其行囊和装备要到实体以后加载时才会在原位置掉落。

---

## 6. 旧档与版本策略

### 6.1 `ai_state_version`

建议值：

```text
0 = 旧版本实体或从未初始化 AI 所有权
1 = WP-02 之后建立的完整 AI 所有权状态
```

写入时机：

- 新契约成功后。
- 收回、死亡快照之外的完整重召唤 / 复活重建后。
- 不写入 `syncNBT()`。

清零时机：

- 解绑完成后。
- 实体重新回到普通动物语义时。

### 6.2 旧档行为

旧档案可能缺少：

- `ai_state_version`
- 原始 goal 快照
- 运行时 owned goal 列表

迁移规则：

- 旧档按 `version = 0` 读入。
- 在世旧实体不做猜测性恢复。
- 重新召唤 / 复活时按新实体重建，自动升级。
- 日志应记录 `legacy AI state`，不写成“原版 AI 已完整恢复”。
- 验证必须覆盖“旧档实体当前仍在区块内”和“旧档实体先收回再召唤”两条路径。

---

## 7. 测试矩阵

仓库当前没有实际 GameTest 内容，WP-01 使用过临时服务端夹具；WP-02 建议沿用“临时夹具 + runServer/runClient”验证，最终夹具删除，不提交。

### 7.1 AI 所有权

| 场景 | 期望 |
|---|---|
| 新契约原版狼 | 原版狼冲突 goal 被快照并暂时移除；本模组 goal 只添加一组 |
| 新契约原版猫 | `OcelotAttackGoal`、`NonTameRandomTargetGoal` 被正确识别，不误删 `FollowOwnerGoal` 等非冲突 goal |
| 第三方 TamableAnimal | 仅快照/移除冲突基类，其他 goal 保持原实例和优先级 |
| FOLLOW -> PASSIVE -> PROTECT -> AGGRESSIVE -> FOLLOW | 每次最多一组本模组 goal，无重复 |
| 连续 apply 同一模式 | owned goal 数量不增长 |
| 解绑 FOLLOW | 本模组 goal 全部移除，原 goal 恢复 |
| 解绑 PASSIVE/PROTECT/AGGRESSIVE | 本模组目标、护主和主动索敌行为全部移除 |
| 解绑后再次查看 selector | 原版/第三方冲突 goal 的实例身份与优先级恢复 |
| 区块卸载再加载 | `EntityJoinLevelEvent` 后战斗模式重新生效 |
| 跨维度移动 | goal 不重复、快照不丢失、模式仍生效 |

### 7.2 解绑物品与数据

| 场景 | 期望 |
|---|---|
| 解绑带满行囊的绒亲 | 所有物品掉落在实体脚下，行囊空且容量为 0 |
| 解绑穿四件盔甲的绒亲 | 四件装备掉落，四个槽清空 |
| 解绑后杀死实体 | 不会因掉落概率为 0 而永久丢失新的装备 |
| 解绑后重新契约同一实体 | 旧行囊、旧冷却、旧进食状态不回流 |
| 解绑前有九命或其他冷却 | cooldowns 清空 |
| 解绑前 feedCount 已接近上限 | feedCount 与 lastFeedMillis 归零 |
| 解绑前各种技能效果 | attribute modifier 等运行时效果全部移除 |
| 解绑时实体坐在原地 | orderedToSit 与 inSittingPose 都归零 |
| 解绑后死亡 | `markFallenIfCompanion` 因 WILD 提前返回，不写死亡快照 |

### 7.3 失败、定位与自救

| 场景 | 期望 |
|---|---|
| 档案缺失 | 返回 `NOT_FOUND`，不改实体 |
| 主人不匹配 | 返回 `NOT_OWNER`，不清理 |
| 已召唤实体已加载且 UUID/维度有效 | UUID 索引命中，正常解绑成功 |
| 已召唤实体在未加载区块 | 常规解绑返回 `ENTITY_UNRESOLVED`，不删档；不扫描区块 |
| 已召唤实体在其他已加载维度 | 维度记录有效或遍历已加载维度 UUID 索引后命中，不遍历实体列表 |
| 已召唤实体在其他未加载区域 | 常规解绑返回失败；玩家可使用二次确认的强制解绑 |
| 强制解绑成功 | 墓碑写入后才删档；不创建新实体；绒亲录不再显示该身份 |
| 墓碑实体以后入世 | 在实体真实位置掉落行囊/装备，恢复原 AI 并清除全部 Furkin 状态，最后删除墓碑 |
| 延迟清理中途异常 | 不删除墓碑，下次入世继续重试 |
| 服务器重启且实体仍未入世 | 墓碑仍存在，不产生 tick 负载 |
| 旧档缺少 `entity_uuid` | 不做全服遍历；返回 `ENTITY_UNRESOLVED`，仍可使用强制解绑 |
| 空行囊 / 空装备槽 | 不生成物品实体，不报错 |
| 常规清理中途异常 | 不执行删档；保留已有档案和可诊断日志 |
### 7.4 构建与运行验证

至少执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

检查：

- `run/logs/latest.log` 无新增 `ERROR`、`FATAL`、异常栈、注册失败、资源缺失。
- 客户端侧无 goal 修改行为。
- 专用服务端能正常加载和卸载实体。
- 最终 JAR 不包含临时夹具类。

---

## 8. 风险与未决问题

| 风险/决策 | 影响 | 建议 |
|---|---|---|
| 旧档已被旧版本删除原版 goal | 无法完美恢复原版 AI | 接受历史残留；新契约和重新召唤启用完整机制 |
| 第三方自定义攻击 goal | 未被当前冲突谓词覆盖 | 不主动删除未知 goal；保留并记录验证缺口 |
| 第三方自定义盔甲掉率 | 没有公开 getter，无法精确恢复 | 解绑掉落并清空装备，恢复原版默认掉率 |
| `EntityJoinLevelEvent` 时机 | 可能影响首次快照 | 运行验证能力 NBT 已加载、客户端侧不处理、重复 apply 幂等 |
| 已召唤实体不可解析 | 当前删档会产生孤儿 | 常规解绑返回 `ENTITY_UNRESOLVED` 且不删档；提供强制解绑 + 服务器级注销墓碑，实体以后入世时清理，不创建新实体 |
| H-03 未完成 | 完整档案合并和跨维度迁移仍不完整 | WP-02 只新增服务器级注销墓碑与 UUID 定向查询；完整统一归 WP-03 |
| 墓碑实体长期不入世 | 可能长期保留一条墓碑 | 记录极小且无 tick 成本；默认不自动过期，避免漏清理旧实体 |

### 实施前决策确认状态

1. **[已确认]** 解绑装备掉落到世界并清空，同时恢复默认掉率。
2. **[已确认]** 旧档已丢失的原版/第三方 goal 不做猜测性修复；重新召唤或复活重建时升级为新机制。
3. **[已确认]** 已召唤但实体不可解析时，常规解绑失败且不删档；提供“强制解绑 + 服务器级注销墓碑”的玩家自救路径，不创建新实体。
4. **[已确认]** 未召唤/已亡档案存在非空 `equipmentSnapshot` 时，先将其掉落到发起解绑的玩家脚下，再删除普通档案。

---

## 9. 实施拆分

### WP-02A：goal 所有权与生命周期

- 新增 `FurkinCombatAiState`。
- 扩展 `FurkinData` 的运行时状态和 `ai_state_version`。
- 新增私有 goal 类型与标记。
- 改造 `FurkinCombatMode` 的 apply、快照、恢复、清理。
- 增加 `EntityJoinLevelEvent` 重应用。
- 验证狼、猫、第三方和区块重载。

### WP-02B：解绑清理、定位与自救

- `FurkinArchiveEntry` 增加 `entity_uuid`、`entity_dimension` 的持久化与兼容读取。
- 新增服务器级 `FurkinRevocationData`。
- `EquipmentSlots` 增加装备掉落、清空和默认掉率恢复。
- 改造 `FurkinRecordActionHandler.unbind(...)`：定向定位、共享清理，失败时不删档。
- 实施状态（2026-09-25）：WP-02B B0-B7 已完成；B6 夹具实际输出 checks=114 failed=0 restartPass=true；跨重启墓碑、延迟清理、失败重试、物品掉落和 AI 恢复均已验证；B7 已完成 H-02 关闭与文档/变更记录同步；提交/推送待乌狸授权。
- 清理行囊、装备、冷却、进食、技能效果和原版归属。
- 新增 UUID 定向定位；维度记录失效时只做已加载维度的 UUID 索引查询。
- 已召唤实体不可解析时返回 `ENTITY_UNRESOLVED`，不删档，不重建。
- 增加带二次确认的强制解绑：先写墓碑，再删普通档案。
- B4 已实现 FORCE_UNBIND、动作结果回执、确认界面和 /furkin forget <id> force；协议版本提升到 2。B5 已接入入世清理与失败重试。
- `EntityJoinLevelEvent` 的墓碑命中路径已实现：成功才移除墓碑并同步客户端，失败保留墓碑供下次入世重试。
- 验证解绑后杀死实体不丢物品、重新契约不回流旧状态。
- 验证未加载区块、跨维度、服务器重启和延迟清理失败重试。

### WP-02C：验证与文档

- 临时服务端夹具覆盖 AI 所有权、生命周期与解绑矩阵。
- `compileJava`、`build`、`runServer`、`runClient`。
- 更新总工作流状态。
- 根据结果更新 README / changelog 是否属于用户可见修复；若仅内部修复，发布时再按发布流程决定。
- 不改 `PROTOCOL_VERSION`，除非实施过程中改变包字段或方向。

---

## 10. 关闭条件

- 新契约完整建立 goal 快照。
- 模式切换只操作本模组 goal。
- 解绑恢复原版/第三方冲突 goal，且无 Furkin goal 残留。
- 行囊和装备有明确、可验证、不丢失的处置。
- cooldowns、feedCount、lastFeedMillis、技能效果和行囊容量不回流。
- 区块重载与跨维度场景下 Furkin AI 可恢复且不重复。
- 已召唤实体不可解析时，常规解绑不删档，且玩家可通过强制解绑完成自救。
- 强制解绑不创建新实体；实体以后入世时，墓碑清理能够掉落物品、恢复 AI 并清除全部 Furkin 状态。
- 注销墓碑跨维度、跨重启有效，清理失败可重试，且不引入全服实体扫描或 tick 负载。
- 旧档边界在文档、日志和验证中明确。
- 所有临时夹具删除，最终构建产物不包含测试类。
- `compileJava`、`build`、`runServer`、`runClient` 与日志复核通过。
- B6 证据（2026-09-25）：两轮 runServer 夹具输出 `WP02B_B6_FIXTURE_OK checks=114 failed=0 restartPass=true`；临时夹具已删除，最终 JAR 不含夹具；删除夹具后的 runServer 与 runClient 启动验证通过。B7 已据此完成文档、变更记录和 H-02 关闭。
- 关闭结论（2026-09-25）：WP-02B B0-B7 的代码、运行验证、文档、变更记录与 H-02 关闭已完成；提交/推送待乌狸明确授权。

---

## 附录 A：当前调用点

现有 `applyTo` 调用点：

- `FurkinContractHandler`：契约后应用默认 FOLLOW。
- `FurkinCombatModeHandler`：切换模式后应用新模式。
- `FurkinCompanionManager#rebuildCompanion`：召唤 / 复活重建后应用档案模式。

WP-02 实施后新增：

- `CommonEvents#onEntityJoinLevel`：服务端实体入世时应用已有档案模式。

## 附录 B：当前解绑前状态

`clearFurkinLayer` 当前没有处理：

- `FurkinData.pouch`
- 四个装备槽
- `ArmorDropChances`
- `FurkinData.cooldowns`
- `FurkinData.feedCount`
- `FurkinData.lastFeedMillis`
- `goalSelector`
- `targetSelector`
- 当前 target
- `setInSittingPose(false)`

本设计文档正式将这些问题纳入 WP-02 的关闭范围。
