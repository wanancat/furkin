# P1：重复实体恢复与规范实体守卫

- 状态：P0/P1 已实施并验收，P1 行为冻结；P2.1 已开始
- 依赖：P0 完成
- 目标：确保同 `companionId` 最多只有一只被档案承认的规范实体，并为已经产生重复体的存档提供可控修复路径
- 风险级别：高，涉及实体清理和物品搬运，必须先备份世界
- 执行规格：[P1 执行契约](p1-execution-contract.md)

## 1. 目标

P0 只阻止未来继续制造重复实体，但实际存档中可能已经存在：

- 原实体：仍在旧区块，带有真实装备和行囊。
- 新实体：在玩家附近，由旧档案重建，可能没有当前装备和行囊。
- 档案定位 UUID：具体指向哪一只取决于后来哪只实体触发过入世刷新。

P1 要解决：

1. 禁止入世事件把档案定位随意改到不同 UUID 的重复实体。
2. 提供只读诊断，列出同 `companionId` 的已加载候选实体。
3. 提供显式选择保留对象的修复流程。
4. 在移除重复实体前，优先把其装备和行囊转移给保留实体；无法转移则掉落到可回收位置。
5. 修复后档案仍指向唯一保留实体，绝不自动选择、自动删除或把物品凭空复制。

## 2. 当前缺陷

`CommonEvents.onEntityJoinLevel(...)` 当前存在类似逻辑：

```java
if (entry != null && entry.isSummoned()
        && (!entity.getUUID().equals(entry.getEntityUuid())
        || !serverLevel.dimension().equals(entry.getEntityDimension()))) {
    entry.setEntityLocation(entity);
    archive.putEntry(entry);
}
```

这会把档案定位改到“刚刚入世”的任意同身份实体。若旧实体和新实体都曾加载，档案会来回改指向，导致：

- 传送目标不确定。
- 新实体可能被标记为重复，但原实体仍保留物品。
- 解绑、强制解绑、名称、战斗模式可能作用到错误实体。
- P2 临时加载后事件触发时，档案被重复实体抢走。

## 3. 规范实体定义

本功能包只承认一个规范实体：

- 档案 `entity_uuid` 是规范实体的 UUID。
- 档案 `entity_dimension` 是该实体最后确认的维度。
- `summoned=true` 时，规范实体是唯一允许被传送、管理和同步的实体。
- 其他同 `companionId` 的实体一律视为重复/待修复实体。
- `entity_uuid == null` 只能由明确修复或首次合法入世流程采用，不能由任意事件自动抢绑。

P1 不新增实体 UUID 生成规则；`companionId` 仍是档案主键，实体 UUID 仍是实体自身身份。

## 4. 入世守卫

修改 `CommonEvents.onEntityJoinLevel(...)` 的 WP-09 定位刷新段：

### 4.1 允许刷新

- `entry == null`：不是已登记档案，按现有逻辑处理。
- `entry.isSummoned() == false`：不把该实体重新登记为已召唤。
- `entry.getEntityUuid() == null`：
  - 只在明确的恢复/迁移流程中采用。
  - 普通入世事件不能直接把任意实体认定为规范实体。
- `entry.getEntityUuid().equals(entity.getUUID())`：
  - 允许更新 `entity_dimension`，P2 后同时更新 `entity_pos`。
- 维度变化但 UUID 相同：
  - 允许更新维度；定位器本来会检查所有已加载维度。

### 4.2 禁止刷新

- `entry.getEntityUuid()` 非空且与入世实体 UUID 不同：
  - 不覆盖 `entity_uuid`。
  - 不覆盖 `entity_dimension` / `entity_pos`。
  - 记录结构化 `WARN` 日志：

```text
Furkin duplicate companion join: companion=<id>, canonical=<uuid>, incoming=<uuid>,
canonical_dimension=<dim>, incoming_dimension=<dim>, incoming_pos=<pos>
```

- 不自动 `discard()` 入世实体；它可能需要被人工确认后搬运物品。
- 不自动调用 `FurkinUnbindCleanup`；该流程会把实体恢复为普通动物并掉落物品，不适合作为重复实体合并策略。

### 4.3 记录诊断状态

可以在服务端内存中维护 `Map<UUID companionId, Set<UUID> duplicateEntities>`，只用于诊断和命令列表：

- 实体入世且与规范 UUID 不一致时加入。
- 实体离开 Level 时移除。
- 不持久化重复实体列表；重启后由入世事件重新发现。
- 不把该列表当作权威档案，不参与传送或重建决策。

## 5. 定位器扩展

在 `FurkinEntityLocator` 中新增只读查询：

```text
List<LivingEntity> findAllLoaded(MinecraftServer server, FurkinArchiveEntry entry)
```

要求：

- 只查已加载实体，不加载区块。
- 遍历当前已加载的 `ServerLevel`。
- 该查询只服务 `repair list` / `repair choose` 这类显式 OP 诊断操作；可使用 `ServerLevel#getEntities().getAll()` 扫描已加载实体，因为当前没有 `companionId` 反向索引。
- 禁止在普通召唤、传送、入世或每次 tick 路径调用全量实体扫描。
- 普通定位仍优先使用 `ServerLevel#getEntity(UUID)`。
- 使用实体 capability 中的 `companionId` 校验匹配。
- 允许 `companionId == null` 的遗留清理实体仅在 UUID 命中时返回。
- 返回结果只用于诊断和显式修复，不能自动决定 Canonical。

保留现有 `locate(...)` 的语义不变：

- 先查记录维度，再查其它已加载维度。
- 找不到只返回 `null`，不修改任何状态。

## 6. 修复入口

### 6.1 入口形式

建议只增加 OP 命令，不增加 GUI，以避免 P1 改变网络协议：

```text
/furkin repair list <companion_id>
/furkin repair choose <companion_id> <keep_entity_uuid>
```

可选别名：

```text
/furkin repair duplicate <companion_id>
```

要求：

- 服务端重新校验玩家是档案主人；命令本身要求权限等级 2，OP 身份不自动绕过归属校验。
- `list` 仅输出已加载候选、坐标、维度、装备槽和行囊非空数量。
- `choose` 必须由调用方明确给出保留 UUID，不提供“自动选最近的”默认行为。
- 若保留 UUID 不是当前已加载实体，返回错误。
- 若档案 `summoned=false`、已亡或归属不匹配，拒绝修复。
- `choose` 执行完成后写服务端日志和操作者 UUID。

### 6.2 `list` 输出必须包含

- `companionId`
- 当前 canonical `entity_uuid`
- 每个候选的实体 UUID
- 维度
- 坐标
- 是否为 canonical
- 四件盔甲槽是否为空
- 行囊非空格子数量
- 等级 / 经验 / 技能点快照
- 实体是否仍带 `FurkinData` / `companionId`

### 6.3 `choose` 核心数据与物品搬迁顺序

执行以下顺序；物品搬运阶段对每个非保留候选重复：

1. 再次确认候选与保留实体属于同一 `companionId`。
2. 如果 keeper UUID 与当前 canonical UUID 不同：
   - 以当前 canonical 实体的实时 `FurkinData` 为唯一核心数据源，复制到 keeper。
   - 至少复制 `companionId`、`ownerUuid`、`level`、`xp`、`skillPoints`、`skillLevels`、`skillInvestments + known`、`state`、`combatMode`、`aiStateVersion`、`feedCount`、`lastFeedMillis`、`cooldowns`。
   - 不复制行囊物品；行囊物品仍按第 4 步处理，避免和 `FurkinData` 序列化路径重复搬运。
   - 清空 keeper 的旧战斗运行时状态，按复制后的技能等级重建持久技能效果，并重新应用战斗模式。
   - 若 keeper 的行囊容量随技能等级变化，按现有 `resizePouchToLevel()` / `PouchDrop` 路径处理溢出。
   - 复制、技能重建或容量重算失败时，立即返回 `CLEANUP_FAILED`，不更新档案、不删除实体。
3. 搬运装备：
   - 优先填入 keeper 对应空槽。
   - keeper 槽位已有物品时不覆盖；多余装备掉落到 keeper 脚下。
   - 使用官方 `ItemStack`、`Containers` 和既有 `EquipmentSlots` 工具路径，不手写物品序列化。
4. 搬运行囊：
   - 优先尝试加入 keeper 的 `FurkinInventory`。
   - 数量超出容量时通过 `PouchDrop` 在 keeper 脚下掉落。
   - 搬运后清空重复实体的行囊，避免 `discard` 时再次掉落。
5. 用档案中的名字覆盖 keeper 的 custom name；若档案无名字，不要在修复中凭空命名。
6. 停止重复实体的 AI 和运行时效果：
   - 清战斗目标、清技能运行时效果、必要时调用现有清理工具的安全子集。
   - 不调用完整 `FurkinUnbindCleanup`，因为它会改变身份和原版归属。
7. 所有核心数据与装备/行囊搬运/掉落完成后，先将档案 `entity_uuid` / `entity_dimension` / 未来 `entity_pos` 指向 keeper 并保存。
8. canonical 已指向 keeper 后，再调用 `discard()` 移除重复实体。
9. 重新扫描同 `companionId` 已加载实体，确认只剩 keeper；若仍有重复体，保留 keeper canonical 并返回 `CLEANUP_FAILED` 供重试。

## 7. 已受影响存档的操作流程

必须先备份世界，再执行以下步骤：

1. 不继续点击召唤，不再制造新的重建实体。
2. 尽量先加载原实体所在区块；如果知道大致位置，先到附近让旧区块入世。
3. 执行 `/furkin repair list <companion_id>`。
4. 比较候选的装备和行囊数量，确认哪一只是需要保留的实体。
5. 若无法判断，不要执行 `choose`，先人工记录并保留所有候选。
6. 执行 `/furkin repair choose <companion_id> <keep_uuid>`。
7. 检查保留实体装备、行囊、名字、等级、技能和档案状态。
8. 重新加载世界或切换维度，确认重复实体不会再次入世并被档案接管。
9. 若原实体仍无法加载，P1 只能诊断已加载候选，不能从 region 文件中直接取回物品；此时应保留所有档案和实体，等待原区块可加载或有专门的离线恢复工具。

## 8. 验证步骤

### 8.1 静态验证

- `rg -n "setEntityLocation|entity_uuid|isSummoned" src/main/java`
- 确认普通入世事件不再对 UUID 不匹配的实体调用 `setEntityLocation`。
- 确认修复命令是唯一能显式选择 canonical UUID 的管理入口。
- `.\gradlew.bat compileJava --console=plain`
- 命令、能力和实体逻辑改动后运行 `.\gradlew.bat runServer --console=plain` 并检查 `run/logs/latest.log`。

### 8.2 运行期验证

1. **canonical 入世**：
   - 正常契约一只宠，档案 UUID 指向它。
   - 让它离开区块再加载。
   - 预期档案 UUID、维度、位置一致，不产生重复日志。
2. **重复入世**：
   - 临时夹具制作两只同 `companionId` 实体，一只档案 canonical，一只非 canonical。
   - 让非 canonical 入世。
   - 预期档案仍指向 canonical，记录重复日志，重复实体未被自动删除。
3. **选择保留**：
   - 对重复夹具执行 `list`，确认候选列表完整。
   - 对非 canonical 执行 `choose`，保留 canonical。
   - 预期装备和行囊转移到 canonical 或正确掉落；若 keeper 不是当前 canonical，等级、经验、技能与战斗模式从当前 canonical 复制；重复实体消失，档案不变量成立。
4. **反向修复**：
   - 若旧实体才是需要保留的对象，将其 UUID 明确指定为保留实体。
   - 预期旧 canonical 被清除，新实体获得 canonical 的核心运行时数据与装备/行囊，不丢失进度。
5. **失败回滚**：
   - 注入搬运异常。
   - 预期重复实体、物品和档案全部保留，命令返回失败，不出现半清理状态。

## 9. 验收标准

- [x] UUID 不匹配的入世实体不会抢改档案定位。
- [x] 普通召唤/传送路径不会自动产生第二只实体。
- [x] `repair list` 只读，不修改档案或实体。
- [x] `repair choose` 在搬运成功后才移除重复实体。
- [x] 物品搬运失败时不会丢失装备或行囊。
- [x] keeper 不是当前 canonical 时，核心运行时数据按 D-14 复制，等级 / 经验 / 技能 / 战斗模式 / 冷却不回退。
- [x] 修复后同 `companionId` 只有一个已加载规范实体。
- [x] 服务端重启后档案仍指向正确 UUID，重复实体不再抢绑。

## 10. 首轮切片执行记录

- 日期：2026-09-26。
- 已实施：
  - `CommonEvents.onEntityJoinLevel(...)` 只允许 canonical UUID 刷新档案定位；UUID 不匹配时记录固定格式 WARN 并登记重复体。
  - `FurkinDuplicateRegistry` 只读内存诊断注册表，覆盖入世登记、离场/死亡/解绑/服务端停止清理；不自动删除或改绑实体。
  - `FurkinEntityLocator.findAllLoaded(...)` 仅服务显式诊断，canonical 优先排序，不加载区块。
  - `/furkin repair list <pet_id>` 只读输出候选 UUID、canonical、维度、坐标、装备槽数、行囊非空数、等级 / 经验 / 技能点和 capability companionId。
- 已验证：
  - `.\gradlew.bat compileJava --console=plain`：通过。
  - `.\gradlew.bat build --console=plain`：通过。
  - `runServer --console=plain`：启动至 `Done`，命令树注册成功；随后停止。
- 尚未实施：`repair choose`、核心数据复制、装备/行囊搬运和实体删除，留作下一 P1 切片。

## 11. 第二轮切片执行记录

- 日期：2026-09-26。
- 已实施：
  - `FurkinData.copyCoreFrom(...)`：只复制 companionId / ownerUuid / 等级 / 经验 / 技能点 / 技能等级 / 技能实付 / 状态 / 战斗模式 / AI 版本 / 喂食递减 / 冷却；不复制行囊，也不复制 transient 战斗 AI goal 引用。
  - `FurkinDuplicateRepair.choose(...)`：显式接收 keeper UUID，校验 owner、`summoned`、`alive`、canonical 已加载与 keeper 已加载。
  - keeper 不是 canonical 时，以 canonical 实时能力对象为进度来源，先复制核心数据，再按 keeper 自身 goal 快照重应用战斗模式，重建技能效果，最后按 `travel_pouch` 等级重算行囊容量并掉落溢出物。
  - 装备按 `MobEquipmentContainer.slotFor(...)` 的头 / 胸 / 腿 / 脚顺序转移；keeper 槽位已有装备时掉落 keeper 脚下，不覆盖、不合并。
  - 行囊从高槽位到低槽位搬运；keeper 装不下的 remainder 走 `PouchDrop.dropStacks(...)`；`addItem` 已部分写入但后续掉落失败时，恢复 keeper 行囊快照并保留源槽，避免重复计数。
  - 所有核心数据与物品处理后，先把档案 canonical 更新到 keeper 并保存，再逐个 `discard()` 其余候选；不使用 `die(...)`。
  - canonical 更新后复用既有 `SyncFurkinDataPacket` 把 keeper 的核心能力数据同步给追踪客户端，不新增网络包或协议版本。
  - 删除后重新执行 `findAllLoaded(...)`，确认只剩 keeper；否则保留 keeper canonical 并返回 `CLEANUP_FAILED` 供同 keeper 重试。
  - `/furkin repair choose <pet_id> <keep_entity_uuid>` 命令与中英文文案已接入。
  - canonical 安全门槛：实时 capability 的 `companionId`、owner 或状态若不是 `COMPANION`，拒绝搬运并返回 `CLEANUP_FAILED`，避免把半清理状态下的残缺等级 / 技能复制给 keeper；已于 2026-09-27 确认并固化。
- 已验证：
  - `.\gradlew.bat compileJava --console=plain`：通过。
  - `.\gradlew.bat build --console=plain`：通过，产物生成。
  - `runServer --console=plain`：最终代码启动至 `Done (2.501s)`，Furkin 与命令注册阶段无异常；随后终止开发服务端。
  - `run/logs/latest.log`：未发现 Furkin 新增异常栈；仍存在基线已知的 Minecraft tag 缺失 ERROR。
  - `en_us.json` / `zh_cn.json`：键数量一致（182 / 182），JSON 解析通过。
- 尚未验证：
  - 尚未在游戏内制作两只同 `companionId` 夹具并执行 `repair choose`；本轮未启动 `runClient`，避免与乌狸当前外部游戏客户端冲突。

## 12. 夹具验收记录

- 日期：2026-09-27。
- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端夹具。
- 夹具构造：
  - 构造两只已加载、同 `companionId` 的猫实体；档案 canonical 指向旧实体，显式选择新实体作为 keeper。
  - canonical：等级 17、经验 42、技能点 5、`travel_pouch=1`、`AGGRESSIVE`、cooldown `12345`、行囊 64 骨头、头部铁头盔、胸部铁胸甲。
  - keeper：等级 3、`travel_pouch=2`、行囊 32 骨头与 10 泥土、头部钻石头盔。
  - 通过真实 `FurkinDuplicateRepair.choose(operator, companionId, keeper.getUUID())` 执行修复，不绕过产品修复流程。
- 结果：
  - `.\gradlew.bat runServer --console=plain` 启动至 `Done (2.144s)`。
  - `run/logs/latest.log` 记录 duplicate join、repair snapshot、repair complete。
  - `FURKIN_FIXTURE_REPAIR_DETAIL ... bones=96 drops=ok`。
  - `FURKIN_FIXTURE_REPAIR_CLEANUP ...`。
  - `FURKIN_FIXTURE_REPAIR_OK`。
- 夹具断言通过：
  - 修复返回 `OK`，旧 canonical 已移除，keeper 存活，档案 `entity_uuid` 更新为 keeper。
  - keeper 核心数据更新为等级 17 / 经验 42 / 技能点 5；cooldown `12345` 与 `AGGRESSIVE` 保留。
  - keeper 原有钻石头盔未被覆盖；旧 canonical 的铁胸甲转移到 keeper；旧 canonical 装备槽已清空。
  - keeper 行囊骨头合计 96；缩容后 10 泥土掉落；槽位冲突的铁头盔掉落。
  - 修复后只剩一个已加载候选，重复体注册表不再报告重复。
- 失败记录：第一次夹具运行因夹具断言把“掉落堆数”误当成“掉落物品数量”而失败，实际掉落 10 个泥土物品；修正夹具断言后第二次运行通过，产品修复路径未因该失败修改。
- 验证边界：临时夹具源码和 `internal.debug` 已删除；最终 jar 中不再包含 `FurkinRepairFixture`。
- 干净回归：移除夹具环境变量后再次运行 `runServer --console=plain`，启动至 `Done (2.301s)`；`run/logs/latest.log` 无 `FURKIN_FIXTURE_REPAIR`、夹具类或 `internal.debug` 记录，也无 Furkin 专属 `ERROR` / `FATAL`。Minecraft tag 缺失仍是当前基线已知问题。

## 13. P1-09 重启持久化验收

- 日期：2026-09-27。
- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端双阶段夹具。
- 第一阶段：
  - 构造同 `companionId` 的 canonical 与 keeper，调用真实 `FurkinDuplicateRepair.choose(...)`。
  - 修复成功后写入 fixture 状态，显式保存世界并正常停服。
  - 日志：`FURKIN_FIXTURE_P1_09_PREPARED`；服务端生成存档并进入 `Saving worlds`。
- 第二阶段：
  - 服务端重新启动后等待 40 tick，确保出生区块和实体索引完成加载。
  - 验证档案件仍为 `summoned=true`、`alive=true`，owner / keeper UUID / overworld 维度未变化。
  - `FurkinEntityLocator.findLoadedByRecordedUuid(...)` 找到 keeper；`findAllLoaded(...)` 仅返回 keeper；`FurkinDuplicateRegistry.hasLoadedDuplicate(...)` 为 false。
  - keeper capability 保留 `level=17`、`xp=42`、`skill_points=5`、`travel_pouch=1`、`AGGRESSIVE` 和 cooldown `12345`。
  - 日志：`FURKIN_FIXTURE_P1_09_RESTART_OK companion=... keeper=... level=17 xp=42 points=5`。
- 时序记录：首次在 `ServerStartedEvent` 内立即查询时实体索引尚未完成加载，夹具误判 keeper 缺失；改为等待 40 tick 后，同一持久化现场通过。该失败属于夹具时机问题，不是 `FurkinEntityLocator` 的产品回归。
- 收尾：第二阶段测试数据已清理；临时夹具源码已删除；执行 `.\gradlew.bat clean build --console=plain` 通过，最终 jar 不含夹具类。无夹具环境变量再次启动服务端，达到 `Done (2.309s)`，日志无夹具记录和 Furkin 专属 `ERROR` / `FATAL`。

## 14. P1 失败注入与重试验收

- 日期：2026-09-27。
- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，开发服务端一次性夹具，环境变量 `FURKIN_FIXTURE_P1_FAILURE=1`，临时世界 `furkin_p1_failure_20260927`。
- 夹具通过真实 `FurkinDuplicateRepair.choose(...)` 入口执行，不使用反射访问产品私有实现；故障点通过临时实体子类在真实装备槽写入或技能属性重建处抛出异常。
- P1-06：空装备、空行囊的重复体执行 `choose` 返回 `OK`；旧 canonical 被移除，档案指向 keeper。日志：`FURKIN_FIXTURE_P1_FAILURE_EMPTY_OK ... canonicalRemoved=true`。
- P1-07：canonical 头部铁头盔转移到 keeper 时注入写入异常，返回 `CLEANUP_FAILED`；canonical 与 keeper 都未删除，档案 canonical UUID 不变，铁头盔仍只在 canonical 上，总数 1。日志：`FURKIN_FIXTURE_P1_FAILURE_EQUIPMENT_OK ... result=CLEANUP_FAILED ... totalHelmets=1`。
- P1-12：canonical 头部铁头盔先成功转移，胸部铁胸甲转移时注入异常；第一次返回 `CLEANUP_FAILED`，第二次重试返回 `OK`，最终 keeper 持有两件装备，物品总数始终为 2，旧 canonical 只在第二次成功后删除。日志：`FURKIN_FIXTURE_P1_FAILURE_PARTIAL_OK ... first=CLEANUP_FAILED second=OK totalBefore=2 totalAfter=2`。
- P1-14：核心数据复制后、技能重建时注入异常，第一次返回 `CLEANUP_FAILED`；canonical 与 keeper 都未删除，档案 canonical UUID 不变；解除故障后的第二次重试返回 `OK`，keeper 保留等级 17 / 经验 42 / 技能点 5 / `AGGRESSIVE` / cooldown `12345`。日志：`FURKIN_FIXTURE_P1_FAILURE_CORE_OK ... first=CLEANUP_FAILED second=OK level=17 xp=42 points=5 mode=AGGRESSIVE cooldown=12345`。
- 三条注入失败对应的 `Furkin duplicate repair failed` ERROR 栈是夹具计划内证据，不是未处理异常；最终 `FURKIN_FIXTURE_P1_FAILURE_OK` 出现，服务端正常保存并停服。
- 原始日志副本：`D:\frukin_dev\_research\p1_failure_20260927.log`。
- 第一次运行失败原因：夹具把最终装备总数重复计了一次 `keeper`，断言得到 4；修正夹具计数后全组通过，产品代码未因该失败修改。
- 收尾：夹具源码与临时世界已删除，`server.properties` 已恢复；`clean build` 通过，最终 jar 不含 `internal.debug` / fixture 类；无夹具 `runServer` 达到 `Done (21.399s)`，日志副本 `D:\frukin_dev\_research\p1_failure_clean_20260927.log` 无夹具记录、无 Furkin 专属 ERROR / FATAL。该干净 `runServer` 在 `Done` 后通过 Ctrl+C 终止，未记录为正常停服；本次干净场景没有 pending 请求。

## 15. P1 命令与反向修复验收

- 日期：2026-09-27。
- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，临时服务端夹具；环境变量 `FURKIN_FIXTURE_P1_COMMAND=1`，临时世界 `furkin_p1_command_20260927`。夹具源码、临时世界和 `server.properties` 已恢复，最终 jar 不含 `internal.debug` / fixture 类。
- P1-03：通过真实 Brigadier dispatcher 执行 `/furkin repair list <companion_id>`。权限 2 返回 3 条候选消息，诊断前后档案和实体快照一致；权限 1 被拒绝；缺少 keeper UUID 的 `choose` 被拒绝；非法 keeper UUID 被拒绝。日志：`FURKIN_FIXTURE_P1_COMMAND_LIST_OK companion=6f45b432-806d-3625-9027-9e220d2e61a5 result=1 messages=3 permissionDenied=true missingKeeperRejected=true`。
- P1-04：保留者明确选择当前 canonical，另一只已加载重复体被清理。重复体头部铁头盔与 7 个骨头行囊物品转移到 canonical，canonical UUID 保持不变，最终同身份已加载候选为 1。日志：`FURKIN_FIXTURE_P1_COMMAND_REVERSE_OK companion=9025f689-0bfa-3ded-9752-5ff68bf2cf04 keeper=1d305490-e6bb-41f5-9faa-f9976e9917ae duplicateRemoved=true bones=7`。
- P1-08：同一现场用同一 keeper 连续执行两次 `choose`。第二次返回 `OK` 且为无操作，日志中 `discards=none`，状态快照不变，keeper 未被误删，最终同身份已加载候选仍为 1。日志：`FURKIN_FIXTURE_P1_COMMAND_REPEAT_OK companion=c5d57240-ac2f-32b1-9079-e00c7268ad85 first=OK second=OK stable=true`。
- P1-10：canonical 未加载、重复体已加载时直接调用真实 `choose`，返回 `CANONICAL_NOT_LOADED`；重复体保留，档案 canonical UUID 不变。日志：`FURKIN_FIXTURE_P1_COMMAND_CANONICAL_NOT_LOADED_OK companion=755404f1-3a9b-318e-a7e8-decf389e779e result=CANONICAL_NOT_LOADED duplicateLoaded=true archiveUuid=6e818859-f809-46d1-91d1-6c8c02c3302a`。
- 第一次夹具运行在 P1-08 断言读取旧 canonical 时失败；第一次 `choose` 已把 canonical 切到 keeper，旧实体被正常删除。修正夹具改为观察 keeper 与档案件后通过，产品代码未因该失败修改。
- 原始日志：`D:\frukin_dev\_research\p1_command_20260927.log`。
- 干净回归：夹具删除后 `clean build` 通过；无夹具 `runServer` 达到 `Done (24.177s)`，日志副本 `D:\frukin_dev\_research\p1_command_clean_20260927.log` 无夹具标记、无 Furkin 专属 `ERROR` / `FATAL`。该干净启动烟测在 `Done` 后由批处理终止，未记录为正常停服。

## 16. P1-01 canonical 卸载 / 回载验收

- 日期：2026-09-27。
- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17；两阶段服务端夹具，环境变量 `FURKIN_FIXTURE_C_REMOTE_PERSIST=prepare|verify`，临时世界 `furkin_p1p0p2_closure_20260927`。
- prepare 阶段：在 Overworld `(1024, 100, 1024)` 创建单只 canonical，档案记录 `companionId`、owner、实体 UUID、维度、位置、`summoned=true`、`alive=true`、等级 17 / 经验 42 / 技能点 5 / `AGGRESSIVE`，实体头部为钻石头盔、行囊有 7 个骨头。夹具仅在 prepare 保存期间使用临时 force ticket，清除 force 后停服，确保验证开始时目标区块确实未加载。
- verify 阶段：目标区块未加载时，真实 `FurkinCompanionManager.summonOrTeleport(...)` 返回 `ENTITY_UNRESOLVED`，不改档案也不创建第二只实体；随后加载原区块，按档案 UUID 命中同一实体，档案 UUID / 维度 / 位置保持不变，钻石头盔和 7 个骨头均在。日志：`FURKIN_FIXTURE_C_P1_01_RELOAD_OK companion=236a11b4-f44f-388c-96a8-94116fb48257 entity=e8a81e84-a5f9-4491-ad19-62b8b62591d6 helmet=true bones=7`。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，最终 jar 不含 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (20.112s)`，日志无 Furkin 专属 `ERROR` / `FATAL`。该干净启动烟测在 `Done` 后由批处理终止，未记录为正常停服。
- 原始日志：`D:\frukin_dev\_research\p1p0p2_closure_prepare_20260927.log`、`D:\frukin_dev\_research\p1p0p2_closure_20260927.log`。
