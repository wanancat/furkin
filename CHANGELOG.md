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
2. **本文件以 `#### API Changes` 专节标记所有 API 变更** —— 破坏性的会在条目里明写。
   *All API changes are collected under a dedicated `#### API Changes` section; breaking ones are called out explicitly.*

> 参考来源 / Reference：Forge 文档《Versioning》——
> `MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH` 能「区分世界不兼容与 API 不兼容的改动」。

---

## [Unreleased]

**修复 / Fixed**

- **命令回执本地化 / Command feedback localization** —— `/furkin` 命令的固定成功、失败、状态和数量回执现使用翻译键，随客户端中英文语言切换；动态 UUID、名称和数量继续作为翻译参数注入。
  *Fixed `/furkin` command success, failure, status and quantity feedback now uses translation keys and follows the client language; dynamic UUIDs, names and counts remain translation arguments.*

- **技能退款与数据校验 / Skill refunds and schema validation** —— 洗点现按每级实际支付成本退款，热改技能 `cost` 不会重写已支付金额；加载期拒绝非正 `cost`、非法 `maxLevel` / `tier` / `requiresLevel`，避免非法技能数据制造或吞掉技能点。
  *Respec now refunds the amount actually paid per level, and hot-changing a skill's `cost` no longer rewrites past payments. Loading rejects non-positive `cost`, invalid `maxLevel` / `tier` / `requiresLevel`, preventing malformed skill data from creating or consuming skill points incorrectly.*
- **技能热重载一致性 / Skill hot-reload consistency** —— `/reload` 后会按当前技能树清理并重建已加载绒亲的 `furkin:attribute` modifier，删除技能、切换属性目标或修改数值不会留下重复/失效加成；重载时未加载实体在入世时校准。`bleeding_bite` 保持实时语义：立即采用新 DPS，不重写已施加时长，定义失效后停止伤害并自然到期。
  *After `/reload`, loaded companions now clear and rebuild their `furkin:attribute` modifiers from the current tree, preventing duplicate or stale bonuses when a skill is deleted, retargeted, or rebalanced; entities unloaded during the reload are calibrated on join. `bleeding_bite` keeps live semantics: new DPS applies immediately, existing duration is preserved, and damage stops if the definition becomes invalid.*
- **解绑清理 / Unbind cleanup** —— 解绑现在会掉落并清空行囊与四件装备，恢复被接管的 AI、原版默认掉率、技能效果、冷却、进食和坐姿等状态；只有全部清理成功后才删除档案。
  *Unbinding now drops and clears the pouch and four equipment slots, restores displaced AI, vanilla default drop chances, skill effects, cooldowns, feeding and sitting state; the archive is removed only after cleanup succeeds.*
- **不可解析实体自救 / Unresolved companion recovery** —— 已召唤实体暂时无法定位时，常规解绑不再删档；玩家可二次确认强制解绑，原实体以后入世时再完成清理，失败会保留墓碑并重试。
  *If a summoned companion cannot currently be resolved, regular unbinding no longer removes its archive. A confirmed force-unbind can be used instead; cleanup completes when the entity next enters the world and is retried if it fails.*
- **定向定位 / Targeted lookup** —— 档案记录实体 UUID 与维度，解绑只做定向索引查询，不加载区块、不扫描全服实体、不产生 tick 轮询。
  *Archives now store the entity UUID and dimension, so unbinding uses targeted index lookup without loading chunks, scanning all entities, or adding tick polling.*
- **网络协议 / Network protocol** —— 强制解绑新增请求/结果包，协议版本提升到 2；客户端与服务端需使用同一协议版本。
  *Force-unbind adds request/result packets and raises the protocol version to 2; clients and servers must use the same protocol version.*
- **跨维度档案 / Cross-dimension archive** —— 绒亲档案统一为服务器级主世界实例，旧版本按维度分裂的档案会在首次读取时合并；跨维度查看、召唤、收回和复活不再命中原维度之外的错误状态，活跃上限按整服统一计算。
  *Companion archives now use one server-level overworld instance. Legacy per-dimension archives are merged on first read, so viewing, summoning, retracting and reviving across dimensions no longer resolve against the wrong world state, and the active limit is enforced server-wide.*

---

## [1.20.1-0.0.1.1] - 2026-09-24

**修复 / Fixed**

- **流血效果图标 / Bleeding effect icon** —— `furkin:bleeding` 效果此前缺失 `textures/mob_effect/bleeding.png`，HUD 会报缺纹理告警（不崩溃）。现随包提供原版「瞬间伤害」(`instant_damage`) 的红色裂心图标 `assets/furkin/textures/mob_effect/bleeding.png`（18×18），经 `mob_effects` 图集自动缝入 `furkin:mob_effect/bleeding` 精灵，与 `0xB22222` 红色主题色一致。纯资源改动，无 Java 改动。
  *The bleeding effect lacked its icon texture (`furkin:textures/mob_effect/bleeding.png`), emitting a missing-texture warning on the HUD (no crash). It now ships vanilla `instant_damage`'s red cracked-heart icon as `assets/furkin/textures/mob_effect/bleeding.png` (18×18), auto-stitched into the `furkin:mob_effect/bleeding` sprite via the `mob_effects` atlas, matching the `0xB22222` red theme. Resource-only change, no Java change.*

---

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
