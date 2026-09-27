# P1 执行契约：重复实体恢复与 canonical 守卫（1.20.1）

- 状态：口径已冻结；代码已落地，2026-09-28 普通重复修复、装备同槽冲突、行囊 / 技能故障注入与重试、幂等、未加载 canonical 拒绝、两阶段重启已由 `repair`（34）+ `commands`（21）+ `restart`（9）夹具覆盖且 0 失败
- 依赖：P0 完成
- 适用环境：Minecraft 1.20.1 / Forge 47.2.0 / Java 17 / official 1.20.1 mappings
- 基线：`mc1.20.1@5ad0924`
- 目的：把 P1 从方向说明收紧为可直接编码、验证和回滚的 1.20.1 规格
- 行号只用于定位；实施后按符号名检索

## 1. 冻结口径

1. `companionId` 是档案主键；`entity_uuid` 是唯一 canonical 实体 UUID。
2. `entry.entityUuid` 非空时，UUID 不同的入世实体不能自动抢绑。
3. P1 只增加 OP 命令，不增加 GUI、网络包或协议版本。
4. `repair choose` 必须显式给出 `keep_entity_uuid`；禁止自动选择“最近”“最先加载”或当前 canonical。
5. 装备冲突不覆盖 keeper 已有槽位；重复体物品掉落到 keeper 脚下。
6. 核心数据复制、装备搬运、行囊搬运任一失败时，停止删除，不更新 canonical，不丢物品。
7. 当前 canonical 是核心运行时数据的唯一来源；keeper 被选择为新实体时也不能回退等级、经验、技能、战斗模式和冷却。
8. 只处理当前已加载实体；不扫描 region 文件，不为修复加载区块。
9. `repair` 不写名字（D-25）；keeper 的战斗 AI 必须用 `combatMode.applyTo(...)` 重建（D-26）。
10. 任意阶段异常都要保留 keeper、canonical、重复实体和可重试现场，除非该阶段已明确完成源槽清空。
11. `repair choose` 默认只预演，必须追加 `confirm` 才执行删除（D-33）。
12. 登记和 canonical 位置刷新覆盖所有已契约 `LivingEntity`；非 `TamableAnimal` 只跳过战斗 AI 重建（D-34）。

## 2. 类型与 API

### 2.1 `FurkinDuplicateRepair.Result`

```text
PREVIEWED          // 预演成功：只读校验 + 计划输出，未执行任何搬运或删除
OK
NOT_FOUND
NOT_OWNER
NOT_SUMMONED
NOT_ALIVE
NO_LOADED_CANDIDATE
CANONICAL_NOT_LOADED
KEEP_UUID_REQUIRED
KEEP_UUID_NOT_LOADED
CLEANUP_FAILED
```

`INVALID_UUID` 留在 Brigadier 参数层，不进入服务结果。`MULTIPLE_CANDIDATES` 不保留，因为 P1 禁止自动选择。

### 2.2 服务方法

```java
List<LivingEntity> FurkinEntityLocator.findAllLoaded(MinecraftServer server,
                                                     FurkinArchiveEntry entry)

FurkinDuplicateRepair.Result FurkinDuplicateRepair.choose(ServerPlayer player,
                                                          UUID companionId,
                                                          @Nullable UUID keepEntityUuid)
```

`list` 可以只由命令层组装，但必须复用 `findAllLoaded(...)` 和同一 owner / state 校验；不要让命令层复制另一套身份规则。

调用前提：`choose` 必须在服务端线程执行；命令层不得把结果抛到异步线程。

### 2.3 `FurkinData.copyCoreFrom`

1.20.1 现有 `FurkinData.copy()`（基线 `FurkinData.java:451`）字段清单与 1.19.2 `copyCoreFrom` 完全一致，且当前无调用点。实施时：

- 新增 `void copyCoreFrom(FurkinData source)`，把 `copy()` 的字段清单迁入；或保留 `copy()` 作为委托。
- 字段清单必须精确等于下面列表，多一个少一个都要在实施记录中说明。
- 不要直接复制整个 `serializeNBT()`，避免把行囊或旧 AI 运行时状态覆盖到 keeper。

必须复制：

- `companionId`、`ownerUuid`
- `level`、`xp`、`skillPoints`
- `skillLevels`、`skillInvestments`、`skillInvestmentsKnown`
- `state`、`combatMode`、`aiStateVersion`
- `feedCount`、`lastFeedMillis`、`cooldowns`

不复制：

- keeper 的行囊物品；行囊按容器逐件搬运。
- 只属于原实体的 `combatAiState` goal 引用；复制后显式重建 keeper 的技能效果和战斗 AI。

注意：`copyCoreFrom` 会连 `companionId` / `ownerUuid` 一起复制，但 **canonical capability 的身份字段可能残缺**，所以复制后必须再用档案值显式回填（见 7.2 第 2.2 步）。

## 3. 入世守卫的精确规则

在 `CommonEvents.onEntityJoinLevel(...)`（基线 `CommonEvents.java:80`）中替换旧的“任意同身份实体抢绑”逻辑。当前旧逻辑：

```java
if (entry != null && entry.isSummoned()
        && (!entity.getUUID().equals(entry.getEntityUuid())
        || !serverLevel.dimension().equals(entry.getEntityDimension()))) {
    entry.setEntityLocation(entity);
    archive.putEntry(entry);
}
```

替换为：

```text
1. 取得 living 的 FurkinData 和 companionId。
2. entry == null 或 !entry.isAlive()：按原有非档案 / 死亡路径处理，不进入下面分支。
3. entry.isAlive()：
   a. FurkinDuplicateRegistry.onEntityJoin(living, entry)
   b. summoned == false：不写 canonical，只登记孤儿候选；输出 DEBUG 级 `Furkin companion join while archive not summoned: ...`
   c. summoned == true && entityUuid == null：输出 WARN `Furkin companion join without canonical: ...`，不自动采用 incoming UUID
   d. summoned == true && incoming UUID == entityUuid：entry.setEntityLocation(living)；archive.putEntry(entry)
   e. summoned == true && incoming UUID != entityUuid：输出 WARN `Furkin duplicate companion join: ...`，保留档案，登记 duplicate
```

规则：

- 维度不同但 UUID 相同：属于 (d)，刷新维度与位置，不判重复。
- 不要在 UUID 不匹配时调用 `discard()` 或 `FurkinUnbindCleanup`。重复体需要先被管理员选择和搬运物品。
- 现有 `SkillRuntimeCalibrator.rebuild(living, data)` 与 `combatMode.applyTo(tamable)` 逻辑保留，位置刷新插在其间或之前，顺序不影响档案一致性。

重复日志建议固定格式（字段缺失写 `none`）：

```text
Furkin duplicate companion join: companion=<id>, canonical=<uuid>, incoming=<uuid>, canonical_dimension=<dim>, incoming_dimension=<dim>, incoming_pos=<x,y,z>
```

不得因为日志格式化或 capability 缺失抛异常。

## 4. 重复注册表精确规则

新增只存在内存的 `FurkinDuplicateRegistry`：

```java
onEntityJoin(LivingEntity entity, FurkinArchiveEntry entry)
onEntityLeave(LivingEntity entity)
onCompanionCleared(UUID companionId, UUID entityUuid)
hasLoadedDuplicate(MinecraftServer server, FurkinArchiveEntry entry)
clear(MinecraftServer server)
clearCompanion(UUID companionId)
```

- `LOADED_ENTITIES: Map<UUID companionId, Set<UUID> entityUuids>` 只存在于内存。
- `onEntityJoin` 登记当前已加载已契约实体；登记不要求档案 `summoned=true`，以覆盖孤儿状态。
- `onEntityLeave`、`onCompanionCleared`、`clearCompanion` 从集合移除。
- `hasLoadedDuplicate(server, entry)`：
  - 只检查登记 UUID 是否仍能通过 `ServerLevel#getEntity(UUID)` 命中。
  - 命中实体 capability 的 `companionId` 必须相等。
  - canonical UUID 永远排除。
  - 不遍历全服实体。
- 只在服务端线程读写。
- `clear(server)` 在服务端停止时清空。
- 任何集合为空都要回收对应 key，避免长期积累。

## 5. `repair list` 命令契约

```text
/furkin repair list <companion_id>
```

前置校验顺序（**D-28 冻结，与 `choose` 一致**）：

1. 命令源必须是实体玩家（否则返回 `0`）。
2. `companion_id` 必须可解析为 UUID；失败 `furkin.msg.invalid_pet_id`。
3. 档案存在；否则 `furkin.command.companion.not_found`。
4. 调用者是档案 owner；否则 `furkin.msg.not_owner`。
5. `entry.isSummoned() == true`；否则 `furkin.command.repair.list.not_summoned`。
6. `entry.isAlive() == true`；否则 `furkin.command.repair.list.not_alive`。

输出：

- 头部：`furkin.command.repair.list.header`（companion ID、当前 canonical UUID）。
- 每个候选：实体 UUID、`canonical=true|false`、维度、坐标、装备槽计数、行囊物品数、level / xp / skillPoints、capability companion ID。文本键 `furkin.command.repair.list.entry`。
- 空候选：`furkin.command.repair.list.empty`。
- 不把完整物品 NBT 或完整行囊内容写进聊天。

返回值：查询成功（含空列表）返回 `1`；参数 / 权限 / 档案校验失败返回 `0`。`repair list` 不得修改档案或实体。

候选集合由 `FurkinEntityLocator.findAllLoaded(server, entry)` 给出，canonical 优先，其余按 UUID 字符串排序，输出可复现。

## 6. `repair choose` 前置校验（完整顺序）

按 1.19.2 源码顺序冻结：

```text
1. player == null || companionId == null            -> NOT_FOUND
2. keepEntityUuid == null                           -> KEEP_UUID_REQUIRED
3. server == null                                   -> NOT_FOUND
4. archive.getEntry(companionId) == null            -> NOT_FOUND
5. ownerUuid == null || !ownerUuid.equals(player)   -> NOT_OWNER
6. !entry.isSummoned()                              -> NOT_SUMMONED
7. !entry.isAlive()                                 -> NOT_ALIVE
8. candidates = findAllLoaded(server, entry)
   candidates.isEmpty()                             -> NO_LOADED_CANDIDATE
9. entry.getEntityUuid() == null                    -> CANONICAL_NOT_LOADED
10. canonical 不在 candidates                        -> CANONICAL_NOT_LOADED
11. keeper 不在 candidates                           -> KEEP_UUID_NOT_LOADED
12. canonical 或 keeper 是 ServerPlayer             -> CLEANUP_FAILED
13. canonicalData == null || keeperData == null     -> CLEANUP_FAILED
14. canonicalMismatch(entry, canonicalData) != null -> CLEANUP_FAILED
15. discardCandidates 中任何项是 ServerPlayer       -> CLEANUP_FAILED
16. logSnapshot(canonical, keeper, discards)
17. 进入两阶段搬运（第 7 节）
```

`canonicalMismatch` 判定（三选一命中即失败，返回原因字符串）：

```text
identity : !entry.getCompanionId().equals(canonicalData.getCompanionId())
owner    : entry.getOwnerUuid() == null || !entry.getOwnerUuid().equals(canonicalData.getOwnerUuid())
state    : canonicalData.getState() != FurkinState.COMPANION   // 1.20.1 等价写法：!canonicalData.isCompanion()
```

`discardCandidates` = 候选集合中 UUID 不等于 keeper 的全部实体。keeper 恰好就是 canonical 时仍执行一次清理扫描；没有重复体时返回 `OK`，不改变实体。

## 7. 两阶段搬运顺序（精确）

### 7.1 阶段划分

阶段名固定为 `DATA / POUCH / EQUIPMENT / ARCHIVE / POSTCONDITION`（D-27）。语义映射：

| 阶段 | 内容 | 失败处理 |
|---|---|---|
| `DATA` | `copyCoreFrom`、显式身份回填、`setTarget(null)`、`combatMode.applyTo`、`SkillRuntimeCalibrator.rebuild` | 返回 `CLEANUP_FAILED`，不更新 canonical，不删除 |
| `POUCH` | `resizePouchToLevel` 溢出掉落；discard 行囊搬运 | 同上；已成功项不重复 |
| `EQUIPMENT` | discard 装备搬运 / 冲突掉落 | 同上；源槽只在成功搬运后清空 |
| `ARCHIVE` | `syncArchiveCore`、`archive.putEntry`、`SyncFurkinDataPacket`、注册表 keeper 清理 | 捕获异常返回 `CLEANUP_FAILED`；此时尚未删除实体 |
| `POSTCONDITION` | `clearDiscardRuntime`、`discard()`、注册表逐项清理、`findAllLoaded` 复核 | 捕获异常返回 `CLEANUP_FAILED`；keeper 已是 canonical，用 keeper 重试清理 |

说明：1.19.2 源码中 `ARCHIVE` 作为第一个后置 `try` 的 catch 标签、`POSTCONDITION` 作为删除 `try` 的 catch 标签。1.20.1 按上表语义固定，不再复制源码中 `stage` 变量与 catch 标签不一致的写法。

### 7.2 阶段 A：只搬入，不删除

按顺序执行：

1. 规范身份与 owner 校验（第 6 节已做）。
2. 若 `keeper.getUUID() != canonical.getUUID()`（`replacingCanonical == true`）：
   1. `keeperData.copyCoreFrom(canonicalData)`。
   2. 显式回填授权身份：`keeperData.setCompanionId(entry.getCompanionId())`、`keeperData.setOwnerUuid(entry.getOwnerUuid())`。理由：档案主键 / owner 是授权身份，不能被 canonical capability 的残缺字段覆盖。
   3. 若 keeper 是 `TamableAnimal tamable`：`tamable.setTarget(null)`；然后 `if (!keeperData.getCombatMode().applyTo(tamable)) throw new IllegalStateException("keeper combat AI apply rejected")`。**禁止 `clearCombatAiState()`**（D-26）。
   4. `SkillRuntimeCalibrator.rebuild(keeper, keeperData)`。
   5. `resizePouchToLevel()`：记录 `pouchSizeBefore = keeperData.getPouch().getContainerSize()`，调用 `overflow = keeperData.resizePouchToLevel()`，逐项用 `PouchDrop.dropStacks(keeper, List.of(stack))` 掉落到 keeper 脚下。掉落失败时把当前及后续项放回原容量，已成功丢弃的靠前项保持丢弃，抛异常。
   6. 任一步抛异常 → 返回 `CLEANUP_FAILED`，不修改 canonical。
3. 遍历 `discardCandidates` 的装备槽（`MobEquipmentContainer.SLOT_COUNT`，用 `MobEquipmentContainer.slotFor(index)`）：
   - 空槽跳过。
   - keeper 对应槽为空：`keeper.setItemSlot(slot, moving)`；写入后复查 keeper 槽非空，否则先把 keeper 槽复位为空再抛异常。
   - keeper 对应槽非空：`Containers.dropItemStack(keeper.getLevel(), keeper.getX(), keeper.getY(), keeper.getZ(), moving)`。
   - 只有搬运或掉落成功后才 `discard.setItemSlot(slot, ItemStack.EMPTY)`。
   - 不得覆盖 keeper 已有物品。
4. 遍历 `discardCandidates` 的行囊：
   - `source = discardData.getPouch()`，`target = keeperData.getPouch()`。
   - 从 `source.getContainerSize() - 1` 倒序逐槽取 `ItemStack`。
   - 搬运前记录 `targetBefore = target.createTag()`。
   - `ItemStack remainder = target.addItem(stack.copy())`；`remainder` 非空时 `PouchDrop.dropStacks(keeper, List.of(remainder))`；掉落失败抛异常。
   - 捕获异常时 `target.fromTag(targetBefore)` 恢复 keeper 行囊，源槽保持原样，避免重复计数。
   - 成功后 `source.setItem(index, ItemStack.EMPTY)`。
   - 全部处理完后断言 `source.isEmpty()`，否则抛异常。
   - 物品总数必须守恒，不能复制、吞掉或重复掉落。
5. 阶段 A 全部成功前不能 `discard()` 任何实体，不能更新档案 canonical。

### 7.3 阶段 B：更新 canonical 后删除

1. 若 `keeper.getUUID() != canonicalUuid`：先 `RemoteSummonService.cancelIfPresent(server, companionId, CancelReason.CANONICAL_CHANGED)`（P2 引入该 service；P1 单独实施时可先留空）。
2. `syncArchiveCore(entry, keeper, keeperData)`，精确刷新：
   - `setLevel` / `setXp` / `setSkillPoints`
   - `setSkillSnapshot(data.syncNBT().getCompound("skill_levels"))`
   - `setSkillInvestments` / `setSkillInvestmentsKnown`
   - `setCombatMode`
   - `species == null` 时 `setSpecies(keeper.getType())`
   - `setAlive(true)` / `setSummoned(true)` / `setEntityLocation(keeper)`
   - **不写名字**（D-25）。
3. `archive.putEntry(entry)`，确保 keeper 先成为 canonical。
4. `FurkinNetwork.channel().send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> keeper), new SyncFurkinDataPacket(keeper.getId(), keeperData.syncNBT()))`。
5. `FurkinDuplicateRegistry.onCompanionCleared(companionId, keeper.getUUID())`，把 keeper 从 duplicate 集合中移除。
6. 逐个 `discard`：
   - `clearDiscardRuntime(discard)`：`Mob#setTarget(null)`；`data.clearCombatAiState()`；若行囊非空 `PouchDrop.dropAll(discard, data.getPouch())` **失败抛异常**；剩余装备用 `Containers.dropItemStack(...)` 掉落到 discard 脚下并清槽。
   - `discard.discard()`。
   - `FurkinDuplicateRegistry.onCompanionCleared(companionId, discard.getUUID())`。
7. 再次 `findAllLoaded(server, entry)` 复核：
   - 只剩 keeper → 返回 `OK`，记录 `Furkin duplicate repair complete: companion=..., keeper=..., removed=..., operator=...`。
   - 仍有多余候选 → 保留 keeper canonical，记录 ERROR，返回 `CLEANUP_FAILED` 供重试。

注意：`clearDiscardRuntime` 使用 `data.clearCombatAiState()` 是**对被删除实体**的清场，与 keeper 的 AI 重建不同；不要混淆 D-26。

### 7.4 身份回填与 AI 的因果关系

`copyCoreFrom(canonicalData)` 会把 canonical 的 `companionId` / `ownerUuid` / `state` 一起复制到 keeper。若 canonical capability 已经半损坏，keeper 可能继承错误身份，因此：

- 复制后必须立即用档案值回填 `companionId` / `ownerUuid`（7.2 第 2.2 步）。
- `canonicalMismatch` 在第 6 节已经拦截 `identity / owner / state` 不一致，所以正常情况下复制本身是干净的；回填是第二道保险，不是替代 `canonicalMismatch`。

## 8. 失败与重试语义

- 阶段 A 是“源槽只在成功搬运后清空”的可重试流程。
- 装备写入抛出异常时，keeper 对应槽恢复为空，discard 槽保留原物品。
- 行囊部分成功、部分失败时，keeper 行囊恢复到本次搬运前，未成功项仍留在 discard；已成功并已清空的源槽不会重复生成。
- 溢出掉落失败时，当前及后续 overflow 放回 **原容量** keeper 行囊；已成功丢弃的靠前项不重复掉落。
- 技能重建 / AI apply 失败不能让 keeper 带走半复制数据成为 canonical；返回 `CLEANUP_FAILED`。
- 阶段 B 档案写入失败时不得开始删除实体；服务器崩溃窗口不承诺跨事务原子性，因此操作前必须备份世界。
- canonical 已更新后删除阶段失败时，不再回滚到旧 canonical；管理员用同一 keeper 重试清理。
- `repair choose` 返回 `CLEANUP_FAILED` 后，`repair list` 必须仍能列出当前真实候选，供下一次判断。

## 9. 命令与文案

命令树追加：

```text
/furkin repair list <companion_id>
/furkin repair choose <companion_id> <keep_entity_uuid>            # 预演（默认，只读）
/furkin repair choose <companion_id> <keep_entity_uuid> confirm    # 执行
```

Brigadier 结构（D-33）：

```java
Commands.literal("repair")
    .then(Commands.literal("list")
        .then(Commands.argument("pet_id", StringArgumentType.word())
            .executes(ctx -> repairList(...))))
    .then(Commands.literal("choose")
        .then(Commands.argument("pet_id", StringArgumentType.word())
            .then(Commands.argument("keep_entity_uuid", StringArgumentType.word())
                .executes(ctx -> repairChoosePreview(ctx))            // 默认：预演
                .then(Commands.literal("confirm")
                    .executes(ctx -> repairChooseConfirm(ctx))))));    // 追加 confirm：执行
```

预演与执行必须复用同一段前置校验和计划构建：

```java
record RepairPlan(UUID companionId, UUID canonicalUuid, UUID keeperUuid,
                  List<UUID> discardUuids, boolean replacingCanonical,
                  int equipmentMoves, int equipmentDrops,
                  int pouchItems, int pouchOverflow) {}

// 校验 + 构建计划；planOut != null 时填充计划
FurkinDuplicateRepair.Result FurkinDuplicateRepair.plan(ServerPlayer player,
                                                        UUID companionId,
                                                        UUID keepEntityUuid,
                                                        @Nullable Consumer<RepairPlan> planOut)

// 预演：planOut = sink，返回 PREVIEWED
// 执行：planOut = sink，校验通过后再调用现有 choose 主体
```

约束：

- 预演路径不得调用 `copyCoreFrom`、`setItemSlot`、`addItem`、`discard`、`archive.putEntry`、任何 `FurkinNetwork` 发送。
- 预演结果 `PREVIEWED` 只表示“计划可执行”，不表示已修复；只有 `confirm` 路径的 `OK` 才是修复完成。
- 预演只测试 `furkin.command.repair.choose.*` 校验结果；不合法 keeper / canonical 未加载等情况在预演阶段就要返回对应失败码，而不是等到执行。

- 命令注册沿用当前 `FurkinCommand.register(...)` 风格；根节点已有 `.requires(src -> src.hasPermission(2))`，`repair` 自然继承权限 2（D-28 / D-13）。
- `pet_id` 与 `keep_entity_uuid` 都用 `StringArgumentType.word()`，在 handler 内用 `UUID.fromString` 解析；无效 UUID 是命令层参数错误，不进入 `FurkinDuplicateRepair.Result`。
- handler 必须确认 `src.getEntity() instanceof ServerPlayer`。

必须同步的中英文键（键集合两侧一致）：

```text
furkin.command.repair.list.header
furkin.command.repair.list.entry
furkin.command.repair.list.empty
furkin.command.repair.list.not_summoned
furkin.command.repair.list.not_alive
furkin.command.repair.choose.preview.header
furkin.command.repair.choose.preview.line
furkin.command.repair.choose.preview.confirm_hint
furkin.command.repair.choose.success
furkin.command.repair.choose.no_loaded_candidate
furkin.command.repair.choose.canonical_not_loaded
furkin.command.repair.choose.keep_not_loaded
furkin.command.repair.choose.cleanup_failed
furkin.command.repair.choose.not_summoned
furkin.command.repair.choose.not_alive
furkin.command.repair.choose.keep_uuid_required
furkin.command.repair.choose.invalid_keep_uuid
```

复用键（已存在，不要重复新增）：`furkin.msg.invalid_pet_id`、`furkin.command.companion.not_found`、`furkin.msg.not_owner`。

命令返回码：

- `repair list`：成功（含空候选）返回 `1`；参数 / 权限 / 档案 / 状态失败返回 `0`。
- `repair choose` 预演：`PREVIEWED` 返回 `1` 并输出计划；校验失败返回 `0`。
- `repair choose ... confirm`：`OK` 返回 `1`；其余结果返回 `0`。执行路径不返回 `PREVIEWED`。

聊天消息只展示 UUID、维度、坐标、计数、等级、经验和技能点；不要输出完整 NBT。

## 10. 结构化日志

固定前缀（可 `rg`）：

```text
Furkin duplicate companion join: companion=..., canonical=..., incoming=..., canonical_dimension=..., incoming_dimension=..., incoming_pos=...
Furkin duplicate repair rejected: companion=..., keeper=..., reason=...
Furkin duplicate repair snapshot: companion=..., canonical=..., keeper=..., discards=...
Furkin duplicate repair failed: companion=..., keeper=..., stage=..., cause=...
Furkin duplicate repair complete: companion=..., keeper=..., removed=..., operator=...
Furkin duplicate repair cleanup failed: companion=..., keeper=..., remaining=...
```

`stage` 只允许 `DATA` / `POUCH` / `EQUIPMENT` / `ARCHIVE` / `POSTCONDITION`（第 7.1 节映射）。异常日志必须带 `stage` 和 cause，便于故障注入定位。

## 11. 定向验证

### 11.1 静态

```powershell
rg -n "setEntityLocation|setSummoned\(false\)|repair|findAllLoaded|hasLoadedDuplicate|copyCoreFrom|applyTo|clearCombatAiState" src/main/java
.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
```

确认：

- 普通的 summon / teleport / tick 路径没有调用 `findAllLoaded` 全量扫描（只允许 `repair list` / `repair choose`）。
- keeper 分支没有 `clearCombatAiState()`；discard 清场才有。
- `repair list` 与 `repair choose` 前置校验顺序一致（档案 → owner → summoned → alive）。
- 没有对名字字段的写入。

### 11.2 服务端夹具或真实世界

必须覆盖：

1. canonical 入世：UUID 相同、维度不同，刷新维度 / 位置，无 duplicate 日志。
2. duplicate 入世：UUID 不同，档案不抢绑，重复实体不自动删除。
3. `repair list`：权限、owner、`summoned=false`、`alive=false`、非法 UUID、空候选、正常候选。
4. `repair choose`：keeper=canonical 的幂等清理。
5. 反向修复：keeper 选新实体，canonical 核心数据复制到 keeper，装备 / 行囊搬运，最后删除旧实体。
6. 故障注入：装备写入失败、行囊部分搬运失败、溢出掉落失败、技能重建失败、AI apply 失败；失败时实体和物品保留，重试收敛。
7. 服务端重启后 canonical 不抢绑，`repair list` 仍能找到正确候选。

### 11.3 证据

- 每个候选的装备 / 行囊物品计数前后对比（物品守恒）。
- `Furkin duplicate repair` 日志命中对应 stage。
- 档案 NBT 前后指向。
- 服务端重启两级证据。

## 12. 回滚策略

- P0 安全失败不能回滚。
- 若修复流程引入不可接受风险，先禁用 `/furkin repair choose` 的执行分支，但保留 `repair list` 和入世守卫。
- 核心数据字段和命令键采用追加方式，旧版本读取时忽略即可。
- 任何回滚不得恢复“任意同身份实体自动抢绑”的旧代码。

## 13. 1.20.1 API 映射（实施前核对）

| 1.19.2 用法 | 1.20.1 现状 | 备注 |
|---|---|---|
| `player.getLevel()` | 已存在 | 1.20.1 同签名可用 |
| `Entity#changeDimension(ServerLevel, ITeleporter)` | 已存在 | 当前 `teleportToOwner` 已使用 |
| `FurkinData.copy()` / `copyCoreFrom` | `copy()` 已存在（`:451`），无调用点 | 抽取 `copyCoreFrom` |
| `FurkinData.resizePouchToLevel()` | 已存在（`:274`） | 返回 `List<ItemStack>` 溢出 |
| `FurkinCombatMode.applyTo(TamableAnimal)` | 已存在（`internal/contract/FurkinCombatMode.java:87`） | 返回 boolean |
| `FurkinInventory.createTag/fromTag/addItem/setItem` | 已存在 | 用于行囊事务性搬运 |
| `PouchDrop.dropAll/dropStacks` | 已存在 | 掉落失败返回 false |
| `MobEquipmentContainer.SLOT_COUNT/slotFor` | 已存在 | 4 个装备槽 |
| `FurkinEntityLocator.findAllLoaded` | 已落地 | 仅由 `repair list` / `repair choose` 调用 |
| `FurkinDuplicateRegistry` | 已落地 | 入世登记、离场清理、热路径只读查询 |
| `FurkinDuplicateRepair` | 已落地 | `plan` 预演与 `choose` 执行共用准备逻辑 |
