# P0：未解析实体安全失败（1.20.1）

- 状态：代码已落地（工作树叠加在 `a9870714` 之上）；2026-09-28 已由 `reload` / `nbt` / `orphan` / `commands` / `cold` / `restart` 六组夹具覆盖 P0-01 ~ P0-09 的核心路径，全部 0 失败；仅“同一实例内卸载→失败→回载”串联与真实客户端交互仍待补
- 依赖：无；必须先于 P1/P2 完成
- 优先级：最高；阻断复制实体与实时物品丢失
- 参考：`D:\frukin_dev\frukin_1_19_2\docs\remote-summon-1.19.2\p0-safe-failure.md`
- 基线：`mc1.20.1@5ad0924`；下列行号只用于定位，实施时按符号名检索
- 主要文件：`internal/contract/FurkinCompanionManager.java`、`internal/contract/FurkinEntityLocator.java`、`internal/network/RequestSummonPacket.java`、`internal/command/FurkinCommand.java`、中英文 lang

## 1. 当前 1.20.1 根因

### 1.1 分流入口把两类失败压成一个

`FurkinCompanionManager.summonOrTeleport(ServerPlayer, UUID)`（基线 `FurkinCompanionManager.java:88`）当前实现：

```java
if (entry.isSummoned()) {
    boolean teleported = teleportToOwner(player, companionId);
    return teleported ? SummonResult.TELEPORTED : SummonResult.REBUILD_FAILED;
}
```

`teleportToOwner` 返回 `false` 时一律折叠为 `REBUILD_FAILED`，调用方无法区分“索引未命中”和“跨维度传送失败”。

### 1.2 失败路径改写档案

`FurkinCompanionManager.teleportToOwner(...)`（基线 `:498`）在 `target == null` 分支（基线 `:524`）：

```java
LivingEntity target = FurkinEntityLocator.locate(player.getServer(), entry);
if (target == null) {
    entry.setSummoned(false);
    entry.clearEntityLocation();
    archive.putEntry(entry);
    FurkinMod.LOGGER.warn("Furkin teleport: entity missing for id={}, marked dismissed", companionId);
    return false;
}
```

`FurkinEntityLocator.locate(...)` 只查已加载实体索引（`ServerLevel#getEntity(UUID)` / 分维度回退），不加载区块。因此区块一旦卸载，一次普通召唤就被误写成“已收回”。下一次点击进入 `rebuildCompanion(...)` 重建分支时，旧实体仍在旧区块，实时装备和行囊不在档案快照里，通常会生成一只缺少当前物品的第二只实体。

### 1.3 当前 `setSummoned(false)` / `clearEntityLocation()` 全量审计

基线 `5ad0924` 的全部调用点：

| 位置 | 行 | 语义 | P0 处置 |
|---|---:|---|---|
| `FurkinCompanionManager.dismiss` | 193 / 194 | 成功收回，实体已 `discard()` | 保留 |
| `CommonEvents.onLivingDeath`（死亡侧写） | 433 / 434 | 实体随死亡移除 | 保留 |
| `FurkinCompanionManager.teleportToOwner` 未解析分支 | 525 / 526 | 把“未解析”误当“已收回” | **删除**，改为只读失败 |

P0 完成后，`setSummoned(false)` 只允许出现在上表前两处以及 P1 管理员修复路径。任何新增调用点必须在实施记录里给出理由。

## 2. P0 目标

当 `summoned=true` 但目标无法从当前已加载实体索引解析时：

- 不改 `summoned`。
- 不清 `entity_uuid` / `entity_dimension` / 未来 `entity_pos`。
- 不重建、不复活、不调用解绑清理。
- 返回明确的 `ENTITY_UNRESOLVED` 或 `DIMENSION_CHANGE_FAILED`。
- 只有请求开始时确认 `summoned=false`，才允许进入合法的快照重建。
- `summoned=false` 但已加载同 UUID / 同身份实体时同样安全失败，不得重建第二只。

该阶段不加载区块，只把“未解析”从“已收回”中分离出来。

## 3. 精确结果类型

在 `FurkinCompanionManager` 中新增 / 调整（该类型位于 `internal` 包，不属于 `com.wanancat.furkin.api`，不影响公开 API 版本）：

```text
SummonResult
  SUMMONED
  TELEPORTED
  NOT_FOUND
  NOT_OWNER
  NOT_ALIVE
  ACTIVE_LIMIT
  ENTITY_UNRESOLVED
  DUPLICATE_CONFLICT          // 由 P1 接入；P0 可先保留常量与分支
  DIMENSION_CHANGE_FAILED
  REBUILD_FAILED

TeleportResult
  TELEPORTED
  ENTITY_UNRESOLVED
  DIMENSION_CHANGE_FAILED
```

`SummonResult` 新增常量追加在 `ACTIVE_LIMIT` 之后、`REBUILD_FAILED` 之前，保持“失败类集中在尾部”的可读性；不要插入到 `SUMMONED/TELEPORTED` 之间。

映射规则：

| `teleportToOwner` 返回 | `summonOrTeleport` 返回 |
|---|---|
| `TELEPORTED` | `TELEPORTED` |
| `ENTITY_UNRESOLVED` | `ENTITY_UNRESOLVED` |
| `DIMENSION_CHANGE_FAILED` | `DIMENSION_CHANGE_FAILED` |

约束：

- 已加载 canonical 传送成功才返回 `TELEPORTED`。
- 不能在 `summonOrTeleport` 内“传送失败后自动重建”。
- `teleportToOwner` 对 `entry == null` / owner 不匹配 / `alive=false` / `summoned=false` 返回 `ENTITY_UNRESOLVED` 只作为内部兜底；`summonOrTeleport` 必须在调用前先做这些校验，不要依赖该兜底产出用户可见结果。

## 4. 代码改动清单（按文件）

### 4.1 `FurkinCompanionManager.java`

1. 新增 `TeleportResult`，把 `teleportToOwner(...)` 返回类型从 `boolean` 改为 `TeleportResult`。
2. `target == null` 时只记录 WARN，然后返回 `ENTITY_UNRESOLVED`。日志字段固定为 `id / entity / dimension / reason`，`reason` 由只读诊断函数给出（见 4.5）。
3. 删除该分支中的 `setSummoned(false)`、`clearEntityLocation()`、`archive.putEntry(...)`。
4. 抽出一个只做“已解析实体传送”的公共方法，供 P2 复用（P0 可以先就地保留，P2.2 再抽）：

   ```java
   public static TeleportResult teleportLoadedEntity(ServerPlayer player,
                                                     FurkinArchiveEntry entry,
                                                     LivingEntity target)
   ```

   保持现有语义：同维度 `target.teleportTo(...)`；跨维度 `target.changeDimension(serverLevel, new FixedTeleporter(...))`，返回值不是 `LivingEntity` 时返回 `DIMENSION_CHANGE_FAILED`；成功后 `setEntityLocation(relocated)`、`archive.putEntry(entry)`、清 `setOrderedToSit(false)` / `setInSittingPose(false)`、发送 `SyncFurkinDataPacket`、记录 `Furkin teleported` INFO。
5. `summonOrTeleport(...)`：`entry.isSummoned()` 分支按第 3 节映射结果；不再出现 `boolean` 中转。
6. 未召唤分支在进入 `rebuildCompanion(...)` 前增加**顺序固定**的两道只读守卫：

   ```text
   1) FurkinEntityLocator.findLoadedByRecordedUuid(server, entry) != null
        -> logLoadedButNotSummoned(...) ; return SummonResult.ENTITY_UNRESOLVED
   2) FurkinDuplicateRegistry.hasLoadedDuplicate(server, entry)
        -> logLoadedDuplicate(...) ; return SummonResult.DUPLICATE_CONFLICT
   ```

   - 守卫必须在 **active limit 校验之前**执行：命中冲突时不应因为“名额已满”而返回误导性的 `ACTIVE_LIMIT`。
   - 两道守卫都只查运行时索引，不加载区块。
   - 本工作树 P1 已引入 `FurkinDuplicateRegistry`；第 2 步与第 1 步按顺序落地。
7. `rebuildCompanion(...)` 内保留**最后一道**同样的两道守卫，位置在读取 `entry.getSpecies()` 之前。调用方守卫不能替代最终写入前的保护。
8. 审计所有 `setSummoned(false)` / `clearEntityLocation()`，只允许成功 `dismiss(...)`、死亡侧写和明确的管理员修复。

### 4.2 `FurkinEntityLocator.java`

P0 需要 `findLoadedByRecordedUuid(MinecraftServer, FurkinArchiveEntry)`：

- 只查已加载维度索引，不要求 `summoned=true`。
- 语义是“按档案记录的 canonical UUID 找已加载实体”。

若 P0 阶段暂时不引入该方法，可用等价的只读查询替代，但必须在实施记录中说明替代方式，并在 P1 收敛到该方法签名。`locate(...)` 保持“已加载 canonical 定位”原语义不变。

### 4.3 `RequestSummonPacket.java`

- 保留一个 `UUID` 的编解码格式和方向，不改包 ID。
- 本工作树按 P0+P1+P2 同包实施：`RequestSummonPacket` 最终调用 `RemoteSummonService.request(...)`，`RemoteSummonService` 再复用 `FurkinCompanionManager` 的安全失败与公共传送路径。
- 绒亲录 `ENTITY_UNRESOLVED` 直接使用终态键 `furkin.msg.remote_summon_unresolved`（D-29）；不引入、不保留过渡键。
- 失败文案后按需调用 `FurkinRecordItem.refreshRecordList(player)` 结束客户端在途态；服务端档案不得被改写。
- 不再使用笼统 `furkin.msg.summon_failed` 覆盖可区分的失败原因。

### 4.4 `FurkinCommand.java`

- `/furkin summon <id>` 与绒亲录共用同一个服务端分流入口（P0 阶段即 `summonOrTeleport`）。
- `ENTITY_UNRESOLVED` → `furkin.command.summon.entity_unresolved`；`DIMENSION_CHANGE_FAILED` → `furkin.command.summon.dimension_change_failed`。
- 不因一次不可解析而修改档案。
- 现有 `default -> furkin.command.summon.failed` 分支保留给真正的内部错误（`INVALID_STATE` / `REBUILD_FAILED` 等）。

### 4.5 只读诊断日志

P0 现行实现有两类可检索诊断日志，不是同一个格式化函数：

1. `teleportToOwner` 的未解析分支：

```text
Furkin remote resolve failed: id=<companionId>, entity=<canonicalUuid|none>, dimension=<dimension|none>, reason=<reason>
```

`resolveFailureReason(...)` 的 `reason` 只取以下四值：

- `missing-entity-uuid`：`entityUuid == null`。
- `missing-entity-dimension`：`entityDimension == null`。
- `dimension-unavailable`：记录维度不是当前服务器的已注册维度。
- `loaded-index-miss`：维度有效，但当前已加载实体索引没有命中 canonical UUID。

2. 未召唤 / 重复守卫在拒绝重建时另有带 `player` 的日志：

```text
Furkin remote resolve failed: ..., reason=loaded-but-not-summoned, player=<playerUuid>
Furkin remote resolve failed: ..., reason=loaded-duplicate, player=<playerUuid>
```

入世监听本身还分别输出 `Furkin companion join without canonical`、`Furkin duplicate companion join` 和 DEBUG 级 `Furkin companion join while archive not summoned`；不要把这三条非 `Furkin remote resolve failed` 前缀的日志写成同一组 reason 取值。

日志格式化不得因为字段为 null 抛异常。

### 4.6 语言文件

`en_us.json` 与 `zh_cn.json` 必须同步，键集合完全一致：

| 键 | 引入阶段 | 使用入口 | 说明 |
|---|---|---|---|
| `furkin.msg.remote_summon_unresolved` | P0/P2 终态 | 绒亲录 `RequestSummonPacket` | “目标可能仍在未加载区块，本次未召唤，档案未改变” |
| `furkin.msg.summon_dimension_change_failed` | P0 | 绒亲录 `RequestSummonPacket` | 跨维度传送失败，档案未改变；P2 继续复用 |
| `furkin.command.summon.entity_unresolved` | P0 | `/furkin summon` | 带 `%s` 短 id；P2 继续复用 |
| `furkin.command.summon.dimension_change_failed` | P0 | `/furkin summon` | 带 `%s` 短 id；P2 继续复用 |

本工作树直接使用终态键 `furkin.msg.remote_summon_unresolved`；旧过渡键 `furkin.msg.summon_entity_unresolved` 从未进入 1.20.1 lang。

## 5. 不变量

P0 完成后必须保持：

1. `summoned=true` 是“已召唤”标记，不因一次索引未命中而翻转。
2. `entity_uuid` 是 canonical 身份的持久锚点，不在普通失败路径清空。
3. `summoned=false` 不是“可以无条件重建”：仍有已加载同身份实体时必须安全失败。
4. 只有成功的 `dismiss`、死亡侧写或管理员修复可以产生 `summoned=true -> false`。
5. 任何 P0 失败路径都不产生新实体、不掉落物品、不调用 `FurkinUnbindCleanup`。
6. `RequestSummonPacket` 线格式、包 ID、方向、`PROTOCOL_VERSION` 均不变。

## 6. 失败矩阵（P0 范围）

| 前置状态 | 已加载 canonical | 同身份重复体 | 期望结果 | 档案 | 新实体 |
|---|---|---|---|---|---|
| `summoned=true` | 是 | 否 | `TELEPORTED` | 刷新位置 | 否 |
| `summoned=true` | 否 | 否 | `ENTITY_UNRESOLVED` | 不变 | 否 |
| `summoned=true` | 是 | 是 | 阻塞 / P1 冲突 | 不变 | 否 |
| `summoned=true`，跨维度 `changeDimension` 返回非生物 | 是 | 否 | `DIMENSION_CHANGE_FAILED` | 不变 | 否 |
| `summoned=false` | 是（同 UUID） | 否 | `ENTITY_UNRESOLVED` | 不变 | 否 |
| `summoned=false` | 否 | 是 | `DUPLICATE_CONFLICT`（P1 接入后） | 不变 | 否 |
| `summoned=false` | 否 | 否 | `SUMMONED`（受 active limit） | 正常重建 | 是 |

## 7. 验证要求

### 7.1 静态检查

```powershell
rg -n "setSummoned\(false\)|clearEntityLocation\(\)|ENTITY_UNRESOLVED|DUPLICATE_CONFLICT|DIMENSION_CHANGE_FAILED|setEntityLocation|findLoadedByRecordedUuid" src/main/java
rg -n "summon_entity_unresolved|summon_dimension_change_failed|command.summon.entity_unresolved|command.summon.dimension_change_failed" src/main/resources
.\gradlew.bat compileJava --console=plain
```

检查：

- `teleportToOwner` 未解析分支没有任何档案写操作。
- `summonOrTeleport` 不会在同一次请求内先失败再重建。
- 未召唤守卫顺序为 `findLoadedByRecordedUuid` → `hasLoadedDuplicate` → active limit。
- 重建入口有最后一道守卫。
- `RequestSummonPacket` 线格式未变，`FurkinNetwork` 包 ID 和 `PROTOCOL_VERSION` 未改。
- 4 个 P0 键在中英文两侧都存在且拼写一致。

### 7.2 运行期场景

至少覆盖：

1. 契约绒亲、穿戴装备、放入行囊物品，记录 `summoned=true` 与 canonical UUID。
2. 让目标区块卸载，连续两次点击召唤。
3. 两次都返回未解析失败；`summoned=true`、UUID / 维度 / 位置不变；没有第二只实体；档案 NBT 前后一致。
4. 回载原区块，确认原实体、装备和行囊保持，且仍能用同一 UUID 传送。
5. `dismiss -> summon` 正常重建，行为不回退。
6. `summoned=false` 且 canonical UUID 仍加载时，拒绝重建第二只实体。
7. 跨维度 `changeDimension` 失败只返回 `DIMENSION_CHANGE_FAILED`，不触碰档案。
8. 命令与绒亲录对同一场景给出各自键的明确文案，且档案一致。

### 7.3 证据要求

- 档案前后快照（`/data get` 或直接读 `furkin_archive` NBT）。
- 已加载实体计数（同一 `companionId`）。
- 诊断日志命中 `Furkin remote resolve failed` 且 `reason` 与场景一致。
- `compileJava`、`build`、`runServer` 原始日志；涉及客户端文案时补 `runClient`。

## 8. 验收标准

- [x] 未加载实体不会触发 `summoned=false`（代码路径 + 静态审计）。
- [x] 未加载实体不会触发 `clearEntityLocation()`（代码路径 + 静态审计）。
- [x] 第二次点击不会产生新实体（代码路径 + 静态审计）。
- [x] 原实体回载后装备和行囊保持，UUID 不变（夹具 `cold`：`keeps archive uuid` / `keeps equipment count` / `keeps pouch item count`；夹具 `restart`：`loads repaired keeper`）。
- [x] 正常 `dismiss -> summon` 仍能按档案快照恢复（夹具 `orphan`：`dismiss-ok summon rebuilds` / `creates entity` / `preserves snapshot entity uuid` / `keeps equipment count`；按快照恢复实体 UUID 是 D-37 的预期口径）。
- [x] `summoned=false` 但同 UUID 实体已加载时不会重建第二只实体（代码路径 + 静态审计）。
- [x] `summoned=false` 但同身份其它实体已加载时返回 `DUPLICATE_CONFLICT`（P1 代码已接入）。
- [x] 跨维度失败返回 `DIMENSION_CHANGE_FAILED` 且档案不变（代码路径 + 静态审计）。
- [x] `setSummoned(false)` 调用点与第 1.3 节审计表一致（当前仅有 `dismiss` 与死亡侧写）。
- [x] `RequestSummonPacket` 线格式未改变，`PROTOCOL_VERSION` 仍为 `2`。
- [x] 4 个 P0 语言键在中英文两侧齐全，且旧过渡键不存在。
- [x] `compileJava`、`build`、`runServer` 通过；最终去夹具 `runServer` 到达 `Done`，日志无 Furkin 专属 ERROR / FATAL。
- [ ] 真实客户端绒亲录在途态与交互仍未验证（P2-25 / P2-26 / P2-39）；服务端生命周期取消已由 `cold` / `stop-pending` 覆盖。

## 9. 实施记录

- 提交：`mc1.20.1` / `a9870714` 基线；本次 0.0.3.0 增量提交为 `39633b8`，changelog 提交为 `c22b036`，均已推送至 `origin/mc1.20.1`，工作树干净。
- 代码文件：`FurkinCompanionManager`、`FurkinEntityLocator`、`FurkinDuplicateRegistry`、`CommonEvents`、`RequestSummonPacket`、`FurkinCommand`、两份 lang。
- 编译 / 构建证据：2026-09-28 `build` 成功（`furkin-1.20.1-0.0.3.0.jar`）；去夹具 `runServer` 到达 `Done (2.592s)`，详细状态见本目录 `verification-matrix.md`。
- 已通过夹具（2026-09-28 全部 0 失败）：`reload` 13 项（未解析只读失败、两次失败无实体、档案 NBT 不变）、`nbt` 16 项（serializeNBT 前后逐字节一致、缺/坏 `entity_pos`）、`orphan` 20 项（`dismiss` 清 UUID、重复冲突拒绝、按快照重建）、`commands` 21 项（命令与绒亲录同语义、各自键）、`cold` 45 项、`restart` 9 项。
- 未覆盖边界：同一实例内“卸载 → 失败两次 → 回载原区块”串联（现由 `reload` / `cold` / `restart` 分段覆盖）、真实客户端绒亲录交互。落盘 NBT diff、真实 `dismiss` 生命周期、命令 / 绒亲录同场景、服务端故障注入已补齐。
