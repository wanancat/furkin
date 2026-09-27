# P1 执行契约：重复实体恢复与 canonical 守卫

- 状态：已冻结，可直接实施
- 依赖：P0 完成
- 适用版本：Minecraft 1.19.2 / Forge 43.2.0
- 目的：把 P1 从“方向说明”收紧为可直接编码、测试和回滚的执行规格

## 1. 冻结口径

以下口径在本功能包内默认生效：

1. `companionId` 是档案主键；`entity_uuid` 是唯一规范实体 UUID。
2. `entry.entityUuid` 非空时，任何 UUID 不同的入世实体都不能自动抢绑档案。
3. P1 修复入口只做 OP 命令，不增加 GUI、不增加网络包、不升协议版本。
4. 修复必须显式指定保留实体 UUID；禁止自动选择“最近”“最先加载”或“档案当前 UUID”。
5. 两只实体都有物品时不做静默合并且不自动覆盖。保留实体槽位已有物品时，重复实体的装备掉落到保留实体脚下，行囊剩余物品同样掉落。
6. 任一核心数据复制或物品搬运失败时，停止删除操作，不丢失物品，返回失败。
7. 修复必须在删除前保证 canonical 的核心运行时数据不会因 keeper 选择而回退；当前 canonical 是核心数据来源。
8. 只处理当前已加载实体；不扫描 region 文件，不为修复加载未加载区块。

## 2. 状态和类型

在 `FurkinRecordActionHandler` 或独立的 `FurkinDuplicateRepair` 中定义：

```text
RepairResult
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

建议的服务端方法：

```java
List<LivingEntity> FurkinEntityLocator.findAllLoaded(MinecraftServer server,
                                                     FurkinArchiveEntry entry)

RepairResult FurkinDuplicateRepair.list(ServerPlayer player, UUID companionId)

RepairResult FurkinDuplicateRepair.choose(ServerPlayer player,
                                         UUID companionId,
                                         UUID keepEntityUuid)
```

`INVALID_UUID` 是命令参数解析错误，不放进服务端 `RepairResult`：命令层先把字符串解析成 UUID，再把合法 UUID 交给修复服务。`MULTIPLE_CANDIDATES` 不作为 P1 结果保留，因为本阶段禁止自动选择，`list` 负责展示全部候选，`choose` 必须显式指定保留者。

返回给命令层的 `RepairResult` 不经过网络包；命令层负责转译成 `Component` 文案。

## 3. 入世守卫精确规则

修改 `CommonEvents.onEntityJoinLevel(...)` 的档案定位刷新段，按以下决策表执行：

| entry 状态 | 入世实体 UUID | 动作 |
|---|---|---|
| `entry == null` | 任意 | 不处理，沿用原有非档案逻辑 |
| `summoned=false` | 任意 | 不重新登记为已召唤 |
| `entityUuid == null` | 任意 | 不自动采用；记录 `WARN`，等待修复命令 |
| `entityUuid == incoming` | 相同 | 刷新维度和 P2 后位置 |
| `entityUuid != incoming` | 不同 | 不刷新；记录重复实体日志；加入内存诊断集合 |
| `alive=false` | 任意 | 不按存活队友刷新，沿用死亡处理 |

重复实体日志格式固定为：

```text
Furkin duplicate companion join: companion=<uuid>, canonical=<uuid>,
incoming=<uuid>, canonical_dimension=<dimension>,
incoming_dimension=<dimension>, incoming_pos=<x,y,z>
```

字段缺失时写 `none`，不得因此抛异常。

### 3.1 重复实体诊断注册表

新增内部类 `FurkinDuplicateRegistry`，避免 P2 远召热路径为了检测重复体而全量扫描所有实体：

```java
final class FurkinDuplicateRegistry {
    static void onEntityJoin(LivingEntity entity, FurkinArchiveEntry entry);
    static void onEntityLeave(LivingEntity entity);
    static boolean hasLoadedDuplicate(MinecraftServer server, UUID companionId);
    static void clear(MinecraftServer server);
}
```

约束：

- 只在服务端线程读写。
- `onEntityJoin` 仅在 `entry.summoned=true`、`entry.entityUuid` 非空且入世 UUID 不等于 canonical 时登记。
- `hasLoadedDuplicate` 只检查登记过的 UUID 是否仍能在当前已加载维度通过 `ServerLevel#getEntity(UUID)` 命中；不得退化成全实体遍历。
- 实体离场、死亡、解绑或服务停止时移除登记。
- 注册表不持久化，不参与 canonical 决策，不自动删除实体；P2 只用它返回 `DUPLICATE_CONFLICT`。
- 该注册表不能用 `findAllLoaded(...)` 替代；后者仍只服务 OP 诊断/修复。

## 4. `findAllLoaded` 的边界

该方法是 P1 的显式诊断查询，不是热路径：

- 只在 `repair list` 和 `repair choose` 调用。
- 遍历 `server.getAllLevels()` 已加载维度。
- 只允许使用 `ServerLevel#getEntities().getAll()` 做一次性候选扫描；不得在 summon、tick、入世、传送路径调用。
- 只返回 `LivingEntity`。
- 用 `FurkinData.companionId` 匹配目标；允许 `companionId == null` 的遗留清理实体仅在实体 UUID 等于规范 UUID 时返回。
- 不加载区块、不创建实体、不修改档案。
- 输出顺序固定：canonical 优先，其余按实体 UUID 字符串排序，保证命令结果可复现。

## 5. 命令规格

### 5.1 语法

```text
/furkin repair list <pet_id>
/furkin repair choose <pet_id> <keep_entity_uuid>
```

参数类型：

- `pet_id`：`StringArgumentType.word()`，按现有命令风格调用 `UUID.fromString(...)`。
- `keep_entity_uuid`：`StringArgumentType.word()`，按同样方式解析。
- 两个参数都必须是完整 UUID；不接受短 ID、名字或实体选择器。
- 命令继承 `/furkin` 的 `hasPermission(2)`。
- 服务层仍要求调用者是档案主人；OP 只提供进入命令的权限，不自动跨 owner 修复。
- 解析失败返回 `INVALID_UUID`，不能把字符串直接当档案主键。

### 5.2 `repair list` 输出

每个候选输出一行，至少包含：

```text
uuid=<full uuid> canonical=<true|false> dimension=<dimension>
pos=<x,y,z> armor=<n/4> pouch=<non-empty count>
level=<n> xp=<n> points=<n>
```

若没有候选：

```text
no loaded entities for companion=<id> canonical=<uuid|none>
```

`list` 永远不修改实体、档案、ticket 或玩家物品栏。玩家可见输出必须走翻译键，不能把上面的调试格式硬编码成命令文本；上面的 `uuid=...` 只是字段清单，不是最终 lang 文案。

### 5.3 命令文案键

命令层至少需要以下中英文键，键集合必须同步：

- `furkin.command.repair.list.header`
- `furkin.command.repair.list.entry`
- `furkin.command.repair.list.empty`
- `furkin.command.repair.choose.success`
- `furkin.command.repair.choose.canonical_not_loaded`
- `furkin.command.repair.choose.keep_not_loaded`
- `furkin.command.repair.choose.cleanup_failed`
- `furkin.command.repair.choose.not_summoned`
- `furkin.command.repair.choose.not_alive`

带变量的键只传 UUID、维度、坐标、等级 / 经验 / 技能点和计数；实体物品内容只以数量/槽位数展示，不把完整物品 NBT 写进聊天消息。

### 5.4 `repair choose` 行为

1. 校验档案存在、归属正确、`entry.isAlive()` 为真、`summoned=true`；keeper 与所有候选都必须是当前已加载且仍在世的 `LivingEntity`。
2. 调用 `findAllLoaded(...)`。
3. 若候选数小于 1，返回 `NO_LOADED_CANDIDATE`。
4. 若 `entry.entityUuid` 非空且不在候选集合中，返回 `CANONICAL_NOT_LOADED`；本阶段不允许在旧 canonical 未加载时把档案改指向其他实体。
5. 若 `keep_entity_uuid` 不在候选集合中，返回 `KEEP_UUID_NOT_LOADED`。
6. 将保留实体定为 `keeper`，其余候选定为 `discardCandidates`。
7. 执行第 6 节的阶段 A：先迁移 canonical 核心运行时数据（keeper 与 canonical 不同时），再搬运/掉落装备和行囊，并清空已成功处理的源槽；此阶段禁止删除任何实体。
8. 确认所有 `discardCandidates` 的核心数据、装备和行囊后置条件都满足。
9. 更新档案：
   - `entity_uuid = keeper.getUUID()`
   - `entity_dimension = keeper.getLevel().dimension()`
   - P2 后 `entity_pos = keeper.blockPosition()`
   - `summoned` 保持 `true`
   - `alive` 保持当前真值
10. `archive.putEntry(entry)`，使 keeper 先成为 canonical，再进入删除阶段。
11. 逐个删除 `discardCandidates` 的实体。
12. 重新调用 `findAllLoaded(...)`，确认只剩 keeper；若仍有候选，返回 `CLEANUP_FAILED`。此时档案仍指向 keeper，管理员可用同一 keeper 重试清理。
13. 输出成功结果和保留 UUID。

如果 `keep_entity_uuid` 恰好等于当前 `entry.entityUuid`，仍执行一次清理扫描；只移除其它已加载重复体。若没有重复体，返回 `OK` 且不改变实体。

档案更新必须先于删除重复实体：否则当 keeper 是被显式选中的“新实体”时，旧 canonical 一旦先被删除，而档案更新失败或服务器崩溃，就会留下档案指向已消失 UUID 的窗口。P1 不承诺跨崩溃事务原子性；修复前仍必须备份世界，且删除阶段失败后不得回滚档案到已删除的旧 canonical。

## 6. 核心数据与物品搬迁精确顺序

搬迁是“先保留、后清除”的两阶段流程：阶段 A 迁移核心运行时数据、转移/掉落物品并清空已成功处理的源槽；阶段 B 只有在阶段 A 全部成功后才更新 canonical 并删除重复实体。任一阶段 A 步骤失败都不得删除源实体，也不得更新 canonical。

### 6.1 核心运行时数据

仅当 `keeper.getUUID()` 与当前 `entry.entityUuid` 不同时执行；两者相同时跳过本节。

1. 读取当前 canonical 实体的 `FurkinData` 作为 `source`，keeper 的 `FurkinData` 作为 `target`；任一侧 capability 缺失或不是已契约绒亲时返回 `CLEANUP_FAILED`。
2. 将 `source` 的核心字段复制到 `target`。至少包括：
   - `companionId`、`ownerUuid`。
   - `level`、`xp`、`skillPoints`。
   - `skillLevels`、`skillInvestments` 和 known 标记。
   - `state`、`combatMode`、`aiStateVersion`。
   - `feedCount`、`lastFeedMillis`、`cooldowns`。
3. 不复制 `source` 的行囊物品；行囊在 6.3 节按 `FurkinInventory` 逐件搬运，避免核心数据复制和容器复制产生两份物品。
4. 清空 keeper 的旧战斗 AI 运行时状态；按复制后的 `skillLevels` 调用 `SkillRuntimeCalibrator.rebuild(keeper, target)` 重建持久技能效果；若 keeper 是 `TamableAnimal`，重新应用 `combatMode`。
5. 若 keeper 的行囊容量随技能等级变化，调用 `target.resizePouchToLevel()`，并用 `PouchDrop.dropStacks(keeper, overflow)` 处理溢出。
6. 复制、技能重建、战斗模式应用或容量重算任一失败：返回 `CLEANUP_FAILED`，不更新档案、不删除任何实体。
7. 建议在 `FurkinData` 增加内部 `copyCoreFrom(FurkinData source)`，让字段清单集中在能力对象内；禁止直接复制整个 `serializeNBT()`，以免把行囊或旧 AI 运行时状态覆盖到 keeper。

### 6.2 装备槽

对 `discard` 实体的四个盔甲槽逐一处理：

1. `stack = discard.getItemBySlot(slot)`。
2. 若 `stack.isEmpty()`，继续。
3. 若 keeper 对应槽为空：
   - `keeper.setItemSlot(slot, stack.copy())`。
   - 成功写入后清空 discard 槽。
4. 若 keeper 对应槽非空：
   - 使用 `Containers.dropItemStack(keeper.getLevel(), keeper.getX(), keeper.getY(), keeper.getZ(), stack.copy())` 在 keeper 脚下掉落。
   - 成功调用后清空 discard 槽。
5. `dropItemStack` 抛异常时，不清理源槽，终止整次修复并返回 `CLEANUP_FAILED`；该方法没有 boolean 返回值。

使用的槽位映射必须复用现有 `MobEquipmentContainer.slotFor(index)` 或 `EquipmentSlots` 中的顺序，不能手写头/胸/腿/脚顺序。

### 6.3 行囊

1. 读取 `discardData.getPouch()`。
2. 从高槽位到低槽位遍历，避免搬运过程中的容器变化影响下标。
3. 对每个非空栈：
   - `ItemStack remainder = keeperData.getPouch().addItem(stack.copy())`。
   - 若 `remainder` 非空，使用 `PouchDrop.dropStacks(keeper, List.of(remainder))`。
   - 只有加入/drop 返回值确认成功后才清空源槽。
4. 搬运结束后，源行囊必须为空。
5. 若 keeper 行囊容量为 0，所有物品走丢弃路径，不尝试扩容。

### 6.4 清理重复实体

只有所有丢弃候选的核心数据步骤、装备和行囊都成功处理后才执行：

实际实现顺序：

1. 确认阶段 A 已完成核心数据复制，keeper 的核心字段与 canonical 一致；keeper 的旧战斗运行时状态已清理并完成技能重建。
2. 确认阶段 A 已让所有 discard 行囊为空。
3. 确认装备搬运阶段已清空所有 discard 盔甲槽。
4. 更新档案 canonical 到 keeper；如果此步失败，禁止进入删除阶段。
5. 清除目标和战斗 AI。
6. 再次清空 discard 行囊和装备槽作为幂等防线。
7. 调用 `discard()`。
8. 不使用 `LivingEntity#die(...)`，避免死亡掉落、死亡事件和魂石路径被误触发。

## 7. 异常、重试与日志

### 7.1 失败返回

阶段 A 任一核心数据复制或物品搬运失败：

- 不修改档案 canonical UUID。
- 不删除任何重复实体。
- 已经成功复制/转移的内容可保持已处理状态；已清空的物品源槽不得在重试时再次复制。
- 日志必须记录已复制的核心字段、已转移物品和失败阶段；重试必须幂等。
- 若无法保证重试安全，直接中止并提示管理员先备份和人工处理。

阶段 B 删除失败：

- 档案已经指向 keeper，不能回滚到已删除的旧 canonical。
- 返回 `CLEANUP_FAILED`，保留未删除者，提示管理员用同一 keeper 重新执行。
- 日志必须列出 keeper、未删除实体和失败阶段。

### 7.2 日志

成功日志：

```text
Furkin duplicate repair complete: companion=<id>, keeper=<uuid>,
removed=<uuid,uuid>, operator=<player>
```

失败日志：

```text
Furkin duplicate repair failed: companion=<id>, keeper=<uuid>,
stage=<DATA|EQUIPMENT|POUCH|POSTCONDITION>, cause=<exception>
```

### 7.3 崩溃恢复

P1 不承诺跨服务器崩溃的事务原子性。为防止崩溃后误操作：

- `choose` 单次执行尽量在同一个服务端 tick 内完成。
- 修复前写 INFO 快照日志，列出所有候选及物品计数。
- 阶段 A 完成后、阶段 B 删除前再次记录 keeper UUID；若崩溃后重新发现重复实体，不自动继续，管理员重新运行 `repair list` 后决定。
- 不在 P1 引入持久化 repair journal；如果实际测试发现跨 tick/跨崩溃风险不可接受，再单独立项。

## 8. 验证夹具

推荐增加一个**临时**、仅开发环境启用的夹具入口，验证后删除：

```text
/furkin debug duplicate <canonical_uuid> <companion_id>
```

夹具行为：

- 在玩家附近生成同物种实体。
- 给它写入相同 `companionId` 和 owner。
- 不加入档案，不改变 canonical。
- 允许设置空装备/空行囊和有装备/行囊两种模式。

夹具不得进入正式提交；测试证据写入本目录 WP 文档。

## 9. P1 完成定义

- [x] UUID 不匹配实体不会自动抢绑档案。
- [x] `repair list` 只读、输出可复现、无区块加载。
- [x] `repair choose` 必须显式提供保留 UUID。
- [x] 旧 canonical 未加载时返回 `CANONICAL_NOT_LOADED`，不把档案改指向其他实体。
- [x] keeper 不是当前 canonical 时，核心运行时数据在删除前复制；等级 / 经验 / 技能 / 战斗模式 / 冷却不回退。
- [x] 装备和行囊在删除重复实体前完成转移或掉落。
- [x] canonical 更新发生在所有核心数据与物品处理成功后、删除重复实体前；任一步失败不删除源实体、不改 canonical。
- [x] 修复后只剩唯一已加载 canonical。
- [ ] 普通召唤、传送、入世、死亡、收回路径无行为回归。
- [x] `compileJava`、`build`、`runServer` 及日志检查通过。


## 10. 失败注入与重试验收记录

- 日期：2026-09-27。
- 一次性夹具 `FURKIN_FIXTURE_P1_FAILURE=1` 使用临时世界 `furkin_p1_failure_20260927`，通过真实 `FurkinDuplicateRepair.choose(...)` 执行 P1-06、P1-07、P1-12、P1-14 场景。
- `FURKIN_FIXTURE_P1_FAILURE_EMPTY_OK`：空装备、空行囊重复体安全清理，canonical 成功切换到 keeper。
- `FURKIN_FIXTURE_P1_FAILURE_EQUIPMENT_OK`：装备槽写入异常返回 `CLEANUP_FAILED`，两个实体和 canonical 档案均保持，物品总数 1。
- `FURKIN_FIXTURE_P1_FAILURE_PARTIAL_OK`：第一次搬运中途失败，第二次重试成功；`totalBefore=2 totalAfter=2`，无重复、无丢失。
- `FURKIN_FIXTURE_P1_FAILURE_CORE_OK`：技能重建异常第一次安全失败，第二次成功；keeper 最终保留等级 17 / 经验 42 / 技能点 5 / `AGGRESSIVE` / cooldown `12345`。
- 原始日志：`D:\frukin_dev\_research\p1_failure_20260927.log`；干净回归日志：`D:\frukin_dev\_research\p1_failure_clean_20260927.log`。
- 收尾：夹具和临时世界已删除，`server.properties` 已恢复；`clean build` 通过，jar 无 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (21.399s)`，日志无 Furkin 专属 ERROR / FATAL。

## 11. 命令、权限、反向修复与幂等验收记录

- 日期：2026-09-27。
- 环境：Minecraft 1.19.2 / Forge 43.2.0 / Java 17，临时服务端夹具；环境变量 `FURKIN_FIXTURE_P1_COMMAND=1`，临时世界 `furkin_p1_command_20260927`。
- P1-03：真实 Brigadier dispatcher 执行 `repair list`；权限 2 可见 3 条候选输出，诊断前后状态等价，权限 1 被拒；缺少 keeper UUID 与非法 UUID 均未进入修复路径。
- P1-04：keeper 为当前 canonical 时，重复体装备与行囊先转移，再清理重复体；canonical UUID 不变，最终只剩一个已加载候选。
- P1-08：同一 keeper 连续两次 `choose` 均返回 `OK`；第二次 `discards=none`，状态快照不变，不重复搬运、不误删 canonical。
- P1-10：canonical 未加载时返回 `CANONICAL_NOT_LOADED`；重复体保留，档案 UUID 不变。
- 第一次夹具运行在 P1-08 尝试读取已被合法删除的旧 canonical，属于夹具观察对象写错；改为观察 keeper 与档案件后全组通过，产品代码未修改。
- 原始日志：`D:\frukin_dev\_research\p1_command_20260927.log`；干净回归日志：`D:\frukin_dev\_research\p1_command_clean_20260927.log`。
- 收尾：夹具源码、临时世界和 `server.properties` 已清理；`clean build` 通过，jar 无 fixture / `internal.debug`；无夹具 `runServer` 达到 `Done (24.177s)`，无夹具标记和 Furkin 专属 ERROR / FATAL。该启动烟测在 `Done` 后由批处理终止，未记录为正常停服。