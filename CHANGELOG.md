# Changelog

All notable changes to **Furkin (绒亲)** are documented in this file.

本文件记录 Furkin（绒亲）的所有重要变更。

---

## 版本规范 / Versioning

本项目采用 **Forge 官方推荐的版本号格式**：

This project follows the **Forge-recommended version format**:

```
MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH
```

| 段 / Segment | 含义 / Meaning | 何时递增 / Incremented when |
|---|---|---|
| `MCVERSION` | 适用的 Minecraft 版本 / target Minecraft version | 始终与 MC 版本一致 |
| `MAJORMOD` | 模组主版本 / mod major | 删物品 / 改删既有机制 / 升 MC 版本 |
| `MAJORAPI` | **API 主版本 / API major** | ⭐ **破坏性 API 变更**：改枚举顺序或变量、改方法返回类型、整体移除 public 方法 |
| `MINOR` | 次版本 / minor | 加物品 / 加新机制 / 废弃 public 方法 |
| `PATCH` | 修订 / patch | 修 bug |

**派生两件事 / Two consequences**：

1. **读版本号就能判断 API 兼容性** —— 第三方只需比较 `MAJORAPI` 段。
   程序内可用 `FurkinApi.getApiVersion()` 查询该段的值。
   *Third parties only need to compare the `MAJORAPI` segment; it is queryable at runtime via `FurkinApi.getApiVersion()`.*
2. **本文件以 `### API Changes` 专节标记所有 API 变更** —— 破坏性的会在条目里明写。
   *All API changes are collected under a dedicated `### API Changes` section; breaking ones are called out explicitly.*

> 参考来源 / Reference：Forge 文档《Versioning》——
> `MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH` 能「区分世界不兼容与 API 不兼容的改动」。

---

## [1.19.2-0.0.2.0] - 2026-09-25

### Fixed / 修复

- **技能退款与数据校验 / Skill refunds and schema validation** —— 洗点现按每级实际支付成本退款，热改技能 `cost` 不会重写已支付金额；旧档缺少实付表时按当前定义一次性迁移，已删除定义按 `cost=1` 兜底并告警。加载期拒绝非正 `cost`、非法 `maxLevel` / `tier` / `requiresLevel`，以及悬空或不可达的 `requires` / `requiresLevel` / `levelGate` 引用，避免非法技能数据制造或吞掉技能点。
  *Respec now refunds the amount actually paid per level, and hot-changing a skill's `cost` no longer rewrites past payments. Legacy saves without a payment ledger migrate from the current definition once; deleted definitions fall back to `cost=1` with a warning. Loading rejects non-positive `cost`, invalid `maxLevel` / `tier` / `requiresLevel`, and missing or unreachable `requires` / `requiresLevel` / `levelGate` references, preventing malformed skill data from creating or consuming skill points incorrectly.*
- **技能热重载一致性 / Skill hot-reload consistency** —— `/reload` 后会按当前技能树清理并重建已加载绒亲的 `furkin:attribute` modifier，删除技能、切换属性目标或修改数值不会留下重复/失效加成；重载时未加载实体在入世时校准。`bleeding_bite` 保持实时语义：立即采用新 DPS，不重写已施加持续时间，定义失效后停止伤害并自然到期。
  *After `/reload`, loaded companions now clear and rebuild their `furkin:attribute` modifiers from the current tree, preventing duplicate or stale bonuses when a skill is deleted, retargeted, or rebalanced; entities unloaded during the reload are calibrated on join. `bleeding_bite` keeps live semantics: new DPS applies immediately, existing duration is preserved, and damage stops if the definition becomes invalid.*
- **客户端网络包隔离 / Client packet isolation** —— 共享网络包不再直接承载 `Minecraft`、客户端界面和本地 capability 写入；五条客户端回调统一下沉到 `FurkinClientPacketHandler`，由 `DistExecutor` 仅在客户端分发。协议字段和包结构不变。
  *Shared network packets no longer directly contain `Minecraft`, client screen classes, or local capability writes. Five client callbacks now go through `FurkinClientPacketHandler` behind `DistExecutor` client-only dispatch. Packet fields and structure are unchanged.*
- **命令回执本地化 / Localized command feedback** —— `furkin` 命令的召唤、列表、解绑、改名、加经验、模式、技能、查看和行囊回执改为翻译键；中英文键集合同步，动态名称、数量、UUID 和技能 ID 作为参数传递。
  *`furkin` command feedback for summoning, listing, unbinding, renaming, XP, combat mode, skills, inspection, and pouches now uses translation keys. English and Chinese key sets stay in sync, while dynamic names, counts, UUIDs, and skill IDs are passed as arguments.*
- **存档与技能数据加固 / Save and skill data hardening** —— 非法 `FurkinState` / `FurkinCombatMode` 不再让实体或档案反序列化抛异常；损坏档案条目会被单条跳过并告警，不影响同档其它宠物；技能同 ID 重复定义会记录双方来源并保留首个定义，不再静默覆盖。
  *Invalid `FurkinState` / `FurkinCombatMode` values no longer make entity or archive deserialization throw. Bad archive entries are skipped individually with a warning, without taking down the rest of the save. Duplicate skill IDs now log both sources and keep the first definition instead of silently overriding it.*

---

## [1.19.2-0.0.1.0] - 2026-09-24

**Minecraft 1.19.2 移植版 / Minecraft 1.19.2 port** —— 在保留 `1.20.1-0.0.1.0` 功能范围的前提下，将构建目标迁移到 Minecraft 1.19.2 / Forge 43.x。
*Migrates the build target to Minecraft 1.19.2 / Forge 43.x while preserving the feature set of `1.20.1-0.0.1.0`.*

### Changed

- 目标运行环境改为 Minecraft `1.19.2`、Forge `43.2.0`；映射改为 `official 1.19.2`，资源包格式改为 `9`。
  *Target runtime changed to Minecraft `1.19.2` and Forge `43.2.0`; mappings now use `official 1.19.2`, and the resource pack format is `9`.*
- 客户端 GUI 从 1.20.1 的 `GuiGraphics` 移植到 1.19.2 的 `PoseStack`。
  *Client GUIs were ported from 1.20.1 `GuiGraphics` to 1.19.2 `PoseStack`.*
- 适配 1.19.2 的实体/世界访问方法、命令消息、按钮与 `EditBox`、滚动控件、创造模式标签页和世界渲染阶段 API。
  *Adapted entity/world accessors, command messages, buttons and `EditBox`, scroll widgets, the creative tab, and world rendering-stage APIs for 1.19.2.*

### Fixed

- 调整按钮贴图尺寸、技能列表行距、首次滚轮聚焦、列表滚动位置恢复与长名字截断，保持 1.20.1 的界面行为。
  *Adjusted button texture sizing, skill-row spacing, initial mouse-wheel focus, scroll-position restoration, and long-name truncation to preserve the 1.20.1 UI behavior.*
- 召唤或传送成功后复用现有绒亲录刷新逻辑，界面就地更新而不重新打开。
  *After a successful summon or teleport, the existing record refresh path updates the screen in place instead of reopening it.*

### API Changes

- 公开 API 的 7 个类型及其签名保持不变；本次为 Minecraft/Forge 平台迁移，不是 public API 破坏性变更。
  *The seven public API types and their signatures are unchanged; this is a Minecraft/Forge platform port, not a breaking public API change.*
- `1.19.2-0.0.1.0` 与 `1.20.1-0.0.1.0` 是按 Minecraft 主线分开的构建产物；第三方应将 `MCVERSION` 视为加载兼容边界。
  *`1.19.2-0.0.1.0` and `1.20.1-0.0.1.0` are separate artifacts per Minecraft line; third parties should treat `MCVERSION` as the load-compatibility boundary.*

### Validation

- 使用 JDK 17.0.2 执行 `compileJava` 通过，javac 错误为 0。
  *`compileJava` passed with JDK 17.0.2 and zero javac errors.*
- 客户端可启动并进入单人世界；集成服务端可保存世界并正常退出，未产生崩溃报告。
  *The client starts and enters a single-player world; the integrated server saves and exits cleanly without a crash report.*
- WP9d GUI 回归 D1–D9 全部通过，覆盖契约命名、绒亲录、改名、技能、装备、行囊和复活流程。
  *WP9d GUI regression D1–D9 passed, covering contract naming, the record, renaming, skills, equipment, the pouch, and revival flows.*
- 专用服务端冒烟测试可启动至 `Done`；专用服务端正常关闭与保存验证仍待补做。
  *The dedicated-server smoke test reaches `Done`; graceful shutdown and save verification for the dedicated server remain outstanding.*

### Known Issues

- 不保证 1.20.1 世界存档可直接在 1.19.2 中加载；跨版本使用前应备份并单独验证。
  *Minecraft 1.20.1 worlds are not guaranteed to load directly in 1.19.2; back up and verify separately before crossing versions.*

## [1.20.1-0.0.1.0] - 2026-09-22

**首个完整版本 / Initial complete version** —— M0 至 M5 全部功能完成。
*All functionality from M0 through M5 is complete.*

### Added

- **契约系统 / Contract system** —— 将原版猫、狗契约成伙伴：契约物品、命名界面、档案持久化。
  *Contract vanilla cats and dogs into companions: contract item, naming screen, archive persistence.*
- **伴侣管理 / Companion management** —— 召唤 / 收回 / 解除契约 / 改名；头顶状态图标；丢失与死亡状态追踪。
  *Summon / retract / release / rename; overhead status icon; lost & fallen state tracking.*
- **成长与技能树 / Growth & skill tree** —— 升级曲线、技能点、13 条技能（主干 6 + 猫 4 + 狗 3）；技能数据驱动（JSON，`/reload` 即时生效）。
  *Level curve, skill points, 13 skills (6 trunk + 4 cat + 3 dog); skill data is JSON-driven and hot-reloadable.*
- **随身行囊 / Travel pouch** —— 随伙伴同行的背包，格数随 `travel_pouch` 技能等级派生；缩容 / 回收 / 死亡掉落处理。
  *Carry-along inventory sized by the `travel_pouch` skill level; handles shrinking, reclaiming, and death drops.*
- **装备系统 / Equipment system** —— 4 格装备槽（头 / 胸 / 腿 / 脚），入档与死亡快照，`setDropChance(0)` 配对关闭掉落。
  *Four equipment slots (head/chest/legs/feet), archived with the companion, paired death snapshot and drop-chance suppression.*
- **复活系统 / Revival system** —— 魂石（防火，绑定 `companion_id`）、12 朵花 + 羊毛复活仪式、绒亲录内「重获魂石」兜底（1 钻石 + 600s 每宠物冷却）。
  *Soulstone (fire-resistant, bound by `companion_id`), a 12-flower + wool revival ritual, and an in-record "reacquire soulstone" fallback (1 diamond + 600s per-pet cooldown).*
- **绒亲面板 / Furkin panel** —— 容器屏，技能 / 行囊 / 装备三页签；技能页当前属性区（三行抬头 + 悬停全属性明细）。
  *Container screen with Skill / Pouch / Equipment tabs; the skill tab shows a live attribute area with a hover breakdown.*
- **绒亲录属性区 / Record attribute area** —— 档案界面内展示伙伴属性。
  *Companion attributes displayed inside the record screen.*
- **命令层 / Command layer** —— `/furkin` 命令树，覆盖召唤、档案、技能等调试与操作入口。
  *A `/furkin` command tree covering summon, archive, skills and other operations.*
- **自建创造标签页 / Custom creative tab** —— 绒亲品牌 tab，解决四件物品在创造搜索中搜不到的问题。
  *A dedicated Furkin creative tab so all four items are searchable in creative.*
- **配置项 / Configuration** —— 客户端与服务端 TOML 配置（含平衡数值）。
  *Client and server TOML configs, including balance values.*
- **公开 API / Public API** —— `com.wanancat.furkin.api` 包，供第三方注册物种与响应升级事件。
  *The `com.wanancat.furkin.api` package for third parties to register species and react to level-ups.*

### API Changes

⭐ **本版本为 API 首次发布 / First public API release.**

- **新增公开 API 包 `com.wanancat.furkin.api`**，含 7 个公开类型：
  *New public API package `com.wanancat.furkin.api` with 7 public types:*
  - `FurkinApi` —— 静态入口：`registerSpecies()` / `isRegistered()` / `getSpecies()` / `getApiVersion()`
  - `api.companion.FurkinSpecies` —— 物种标识
  - `api.companion.FurkinSpeciesRegistry` —— 物种注册表
  - `api.companion.IFurkin` —— 伴侣只读查询接口
  - `api.skill.FurkinSkillEffectType` —— 自定义技能效果类型
  - `api.skill.FurkinSkillEffectTypeRegistry` —— 效果类型注册表
  - `api.event.FurkinLevelUpEvent` —— 升级事件（可取消，不可覆写）
- **`MAJORAPI` 段当前为 `0`** —— 表示 API 尚未承诺稳定，首次正式发布时将进位为 `1`。
  *`MAJORAPI` is currently `0`, signalling that the API is not yet declared stable.*
- ⚠️ **边界说明 / Boundary note**：`api` 包以外的所有 furkin 类型（尤其
  `com.wanancat.furkin.internal.*`）均为**内部实现**，结构随时可能变更，**不在兼容承诺范围内**。
  本版本**不发布独立的 api jar**，故该边界是**约定**而非编译期约束 —— 请自行只依赖 `api` 包。
  *Everything outside the `api` package — especially `internal` — is implementation detail and carries no
  compatibility promise. No separate api jar is published, so this boundary is a convention, not a compile-time
  enforcement.*

### Notes

- 平衡数值为**初版冻结值**，后续版本可能调整。
  *Balance values are frozen at their initial set; later versions may adjust them.*
- ⚠️ 四件物品（契约 / 绒亲录 / 洗点药水 / 魂石）**未挂任何原版创造标签页**，仅出现在自建 furkin tab 中。
  *The four items appear only in Furkin's own creative tab, not in any vanilla tab.*
