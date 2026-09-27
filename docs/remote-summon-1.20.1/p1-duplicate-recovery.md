# P1：重复实体恢复与 canonical 守卫（1.20.1）

- 状态：代码已落地（工作树叠加在 `a9870714` 之上）；2026-09-28 已由 `repair`（34 项）、`commands`（21 项）、`orphan`（20 项）、`restart`（9 项）夹具覆盖普通修复、装备 / 行囊 / 技能故障注入与重试、幂等、未加载 canonical 拒绝、两阶段重启，全部 0 失败
- 依赖：P0 完成
- 风险：高；涉及实体删除、物品搬运和核心运行时数据复制，操作前必须备份世界
- 参考：`D:\frukin_dev\frukin_1_19_2\docs\remote-summon-1.19.2\p1-duplicate-recovery.md`
- 主要文件：`FurkinEntityLocator.java`、`FurkinDuplicateRegistry.java`、`FurkinDuplicateRepair.java`、`FurkinData.java`、`CommonEvents.java`、`FurkinCommand.java`、中英文 lang

## 1. 背景

P0 只能阻止未来继续制造重复实体，不能修复已经存在的重复状态。实际存档可能出现：

- canonical（档案 `entity_uuid`）仍在旧区块，带真实装备和行囊。
- 重建实体在玩家附近，带旧档案快照。
- 两个实体都曾入世，当前 `CommonEvents` 会按“最新入世者”覆盖档案定位。

1.20.1 当前入世代码：

```java
if (entry != null && entry.isSummoned()
        && (!entity.getUUID().equals(entry.getEntityUuid())
        || !serverLevel.dimension().equals(entry.getEntityDimension()))) {
    entry.setEntityLocation(entity);
    archive.putEntry(entry);
}
```

只要 UUID 或维度不同，任意同身份实体就能抢改 canonical，导致传送、解绑、命名和战斗模式作用对象不确定。

## 2. P1 目标

1. `companionId` 仍是档案主键，`entity_uuid` 是唯一 canonical 实体 UUID。
2. UUID 不匹配的入世实体不能自动抢改档案。
3. 增加只读诊断，列出同 `companionId` 的当前已加载候选。
4. 增加显式修复流程，由管理员指定 keeper。
5. 删除重复实体前先搬运核心数据和物品；无法放入 keeper 的物品掉落到 keeper 脚下。
6. 不自动选择、不自动删除、不凭空复制物品。
7. 修复完成后同 `companionId` 在当前已加载世界中最多只剩 keeper。

## 3. canonical 定义

- 档案 `entity_uuid` 非空时，它只指向 canonical。
- `entity_uuid == null` 不能让普通入世事件自动采用任意实体。
- 维度只是定位元数据；UUID 相同的实体换维度后，可以刷新 `entity_dimension`。
- `summoned=false` 时如果 canonical UUID 仍加载，它是需要修复的孤儿/冲突，不允许重建第二只。
- 其它同 `companionId` 实体一律视为 duplicate/candidate，除非 `repair choose` 已明确将其设为 keeper。

## 4. 入世守卫

> 独立复查补正（D-34）：登记与 canonical 位置刷新必须覆盖所有已契约 `LivingEntity`，不能放在 `TamableAnimal` 分支之后；`TamableAnimal` 只额外承担战斗 AI 重建。

在 `CommonEvents.onEntityJoinLevel(...)` 中按以下决策表处理：

| 档案状态 | 入世实体 | 动作 |
|---|---|---|
| `entry == null` | 任意 | 沿用非档案逻辑 |
| `entry.isAlive() == false` | 任意 | 不按存活队友刷新，沿用死亡处理 |
| `summoned=false` | 任意 | 不改为已召唤；登记为孤儿 candidate 供诊断 |
| `entityUuid=null` | 任意 | 不自动采用；WARN 记录后等待修复 |
| UUID 相同 | 可刷新维度和位置 |
| UUID 不同 | 不刷新 canonical；WARN；加入内存 duplicate registry |

重复日志建议固定格式：

```text
Furkin duplicate companion join: companion=<id>, canonical=<uuid>, incoming=<uuid>,
canonical_dimension=<dim>, incoming_dimension=<dim>, incoming_pos=<x,y,z>
```

字段缺失写 `none`。不得因为日志格式化或 capability 缺失抛异常。

## 5. 定位器扩展

在 `FurkinEntityLocator` 增加：

```java
@Nullable
LivingEntity findLoadedByRecordedUuid(MinecraftServer server, FurkinArchiveEntry entry)

List<LivingEntity> findAllLoaded(MinecraftServer server, FurkinArchiveEntry entry)
```

边界：

- `findLoadedByRecordedUuid`：只查已加载索引，不要求 `summoned=true`；用于 P0/P1 的孤儿检测。
- `findAllLoaded`：只查当前已加载维度，允许一次性遍历 `ServerLevel#getEntities().getAll()`；仅服务 `repair list` / `repair choose`，禁止进入召唤、传送、入世、tick 热路径。
- `findAllLoaded` 结果 canonical 优先，其余按实体 UUID 字符串排序，输出可复现。
- 两个方法都不加载区块、都不修改档案、都不删除实体。

## 6. 重复注册表

新增只存在内存的 `FurkinDuplicateRegistry`：

```java
onEntityJoin(LivingEntity entity, FurkinArchiveEntry entry)
onEntityLeave(LivingEntity entity)
onCompanionCleared(UUID companionId, UUID entityUuid)
hasLoadedDuplicate(MinecraftServer server, FurkinArchiveEntry entry)
clear(MinecraftServer server)
clearCompanion(UUID companionId)
```

约束：

- 只在服务端线程读写。
- `hasLoadedDuplicate` 只检查登记 UUID 是否仍在已加载索引；不得退化成全实体扫描。
- canonical UUID 永远从重复判定中排除。
- 注册表不持久化、不参与 canonical 决策、不自动删除实体。
- 死亡、解绑、强制解绑、实体离场和停服时清理登记。
- P2 只调用只读查询，不放任何区块加载逻辑。

## 7. 修复入口

只做 OP 命令，不增加 GUI 或网络包：

```text
/furkin repair list <companion_id>
/furkin repair choose <companion_id> <keep_entity_uuid>            # 预演（默认，只读）
/furkin repair choose <companion_id> <keep_entity_uuid> confirm    # 执行
```

权限与归属：

- 命令权限等级 2。
- 服务层仍要求调用者是档案主人；OP 身份不自动绕过 owner 校验。
- 校验顺序冻结为 档案存在 → owner → summoned → alive（D-28），`repair list` 与 `repair choose` 完全一致。
- 参数先解析为合法 UUID；无效 UUID 属于命令层参数错误。

`repair list` 至少输出：

- companion ID 与当前 canonical UUID。
- 每个已加载候选的实体 UUID、是否 canonical、维度、坐标。
- 装备槽数量 / 总数、行囊物品数、等级、经验、技能点、capability companion ID。
- 列表只读，调用前后档案和实体状态一致。

`repair choose` 必须显式指定 keeper。核心行为见 [P1 执行契约](p1-execution-contract.md)。

## 8. 已经产生重复体时的操作流程

修复前必须备份世界；随后：

1. 停止继续点击召唤，避免制造更多重建实体。
2. 尽量加载原实体和候选实体所在区块。
3. 执行 `/furkin repair list <companion_id>`。
4. 比较候选的装备、行囊、等级、技能和位置，人工判断 keeper。
5. 无法判断时不要执行 `choose`，先保留现场。
6. 先执行 `/furkin repair choose <companion_id> <keep_uuid>` 预演，核对计划中的 canonical / keeper、将删除实体、装备与行囊搬运数量。
7. 计划无误后再执行 `/furkin repair choose <companion_id> <keep_uuid> confirm`。
8. 检查 keeper 的等级、经验、技能、装备、行囊和档案指向。名字不由 `repair` 改写（D-25）；`syncArchiveCore` 不写名字。
9. 重载世界或切换维度，确认重复实体不会重新抢绑。
10. 原 canonical 无法加载时，P1 只能诊断已加载候选，不能直接重写 region 文件。

## 9. 验收标准

- [x] UUID 不匹配的入世实体不会抢改档案定位（代码路径 + 静态审计）。
- [x] `summoned=false` 且同身份实体仍加载时，拒绝重建并返回明确冲突（代码路径）。
- [x] `repair list` 只读且输出完整候选（代码路径）。
- [x] `repair choose` 默认只预演；不带 `confirm` 时不做任何搬运 / 删除 / 档案写入（代码路径）。
- [x] 预演与执行复用同一段前置校验与计划构建，`PREVIEWED` 不等于 `OK`（代码路径）。
- [x] `repair choose` 在核心数据和物品搬运成功后才删除重复实体（代码路径）。
- [x] keeper 与 canonical 不同时，keeper 获得 canonical 的等级、经验、技能、战斗模式、冷却等核心数据（代码路径）。
- [x] 装备 / 行囊搬运失败时不丢失物品，原 canonical 和重复实体保留，重试可收敛（夹具 `repair`：`pouch failure returns CLEANUP_FAILED` / `keeps source item count` / `restores keeper item count` / `keeps archive canonical` / `retry succeeds` / `retry preserves total item count`）。
- [x] keeper 槽位已有装备时，不覆盖已有物品；重复体装备掉落（代码路径）。
- [x] 修复后当前已加载世界只剩 keeper，档案 `entity_uuid` 指向 keeper（p0p1 夹具）。
- [x] 服务端重启后档案仍指向正确 UUID，重复实体不会再次抢绑（夹具 `restart`：`retains archive pointer for repaired companion`（keeper=`id(5013)`）/ `loads repaired keeper` / `does not load removed canonical`（canonical=`id(4013)`）；keeper 随出生区块加载属正常行为）。
- [x] `repair` 不修改 keeper / 档案的名字字段（代码路径）。
- [x] keeper 的 AI 通过 `combatMode.applyTo(tamable)` 重建，没有使用 `clearCombatAiState()`（代码路径 + 静态审计）。
- [x] `compileJava`、`build`、`runServer` 通过；夹具日志无新增 Furkin `ERROR` / `FATAL`。

## 10. 实施记录

- 工作树：`mc1.20.1` / `a9870714` 基线，当前增量未提交。
- 已落地：`FurkinDuplicateRegistry`、`FurkinDuplicateRepair`、`FurkinEntityLocator.findAllLoaded`、`FurkinData.copyCoreFrom`、入世 / 离场 / 停服清理、`repair list|choose [confirm]` 命令与中英键。
- 代码口径：`repair choose` 默认只调用 `plan(...)`；只有带 `confirm` 才调用 `choose(...)`。keeper AI 使用 `setTarget(null)` + `combatMode.applyTo(...)`。
- 已通过夹具：p0p1 覆盖 duplicate registry、preview/confirm、删除 canonical / 保留 keeper、档案重定向、核心字段复制、普通路径物品精确守恒。
- 未覆盖边界：装备同槽冲突已由 `repair equipment conflict keeps keeper item` + `drops displaced item without loss` 覆盖；技能重建 `CLEANUP_FAILED` 注入由 `skill rebuild failure returns CLEANUP_FAILED` + `keeps both entities` + `retry succeeds` 覆盖。仍未逐字段 diff 等级 / 经验 / 技能 / 战斗模式 / 冷却，也未跑真实客户端命令输出（P1-08 / P2-27）。
