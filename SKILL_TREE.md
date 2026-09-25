# 技能树说明 · Furkin Skill Tree

> 中文 / English（双语同文）· Bilingual

---

## 一、简要介绍 / Overview

**中文**

技能树分为**主干**与**物种分支**两部分：主干技能是所有绒亲都可学习的通用能力，物种分支则提供猫、狗各自独有的专长。每升一级获得 1 点技能点，可用于解锁新技能或升级已有技能；技能**解锁后即自动生效**，无需额外开关或装备槽位。

部分技能还会解锁**随身行囊**——一个随伙伴同行的物品背包，产出类技能（如藏骨本能、捕鱼天赋）生成的物品会直接存入其中。

**English**

The skill tree is split into a **core trunk** and **species branches**: trunk skills are universal abilities every furkin can learn, while species branches offer unique specialties for cats and dogs respectively. You earn 1 skill point per level, which can be used to unlock new skills or upgrade existing ones. Skills **activate automatically** once unlocked — no toggles or equipment slots needed.

Certain skills also unlock the **Travel Pouch** — a carry-along inventory for your companion, where items produced by "production" skills (e.g. Bone Harvest, Fish Harvest) are stored directly.

---

## 二、技能树总览 / Skill Tree Overview

### 主干 / Core Trunk（所有绒亲 / all furkin）

**中文**

| 技能 ID                        | 中文名  | 最大等级 | 等级效果                     | 前置             |
| ---------------------------- | ---- | ---- | ------------------------ | -------------- |
| `furkin:sturdy_constitution` | 健壮体魄 | 5    | 每级 +2 生命上限               | —              |
| `furkin:sharp_fang`          | 利齿尖牙 | 5    | 每级 +0.5 攻击力              | —              |
| `furkin:thick_fur`           | 坚韧绒甲 | 5    | 每级 +1 护甲                 | —              |
| `furkin:travel_pouch`        | 随身行囊 | 3    | 每级 +9 格（9 → 18 → 27）     | —              |
| `furkin:forager`             | 拾荒本能 | 1    | 自动拾取附近掉落物进行囊（半径 8）       | `travel_pouch` |
| `furkin:self_feeder`         | 低血进食 | 1    | 血量 <30% 自动食用行囊食物（冷却 10s） | `travel_pouch` |

**English**

| Skill ID                     | Name                | Max Level | Effect per Level                                         | Requires       |
| ---------------------------- | ------------------- | --------- | -------------------------------------------------------- | -------------- |
| `furkin:sturdy_constitution` | Sturdy Constitution | 5         | +2 Max Health per level                                  | —              |
| `furkin:sharp_fang`          | Sharp Fang          | 5         | +0.5 Attack per level                                    | —              |
| `furkin:thick_fur`           | Tough Fur Armor     | 5         | +1 Armor per level                                       | —              |
| `furkin:travel_pouch`        | Travel Pouch        | 3         | +9 pouch slots per level (9 → 18 → 27)                   | —              |
| `furkin:forager`             | Forager             | 1         | Auto-collect nearby item drops into the pouch (radius 8) | `travel_pouch` |
| `furkin:self_feeder`         | Self Feeder         | 1         | Auto-eat pouch food when HP <30% (10s cooldown)          | `travel_pouch` |

### 猫分支 / Cat Branch（`furkin:cat`）

**中文**

| 技能 ID                 | 中文名  | 最大等级 | 等级效果                        | 前置             |
| --------------------- | ---- | ---- | --------------------------- | -------------- |
| `furkin:nimble_grace` | 灵巧身法 | 3    | 每级 +8% 闪避概率                 | —              |
| `furkin:nine_lives`   | 九命猫  | 1    | 受到致命伤害时触发不死图腾效果（冷却10分钟） | `nimble_grace` Lv.3 |
| `furkin:fish_harvest` | 捕鱼天赋 | 3    | 每 60 / 40 / 30s 凭空产 1 生鱼进背包（仅在场时计时） | `travel_pouch` |
| `furkin:night_watch`  | 守夜者  | 1    | 夜晚在主人 16 格内时，使主人获得夜视效果 | —              |

**English**

| Skill ID              | Name         | Max Level | Effect per Level                                      | Requires       |
| --------------------- | ------------ | --------- | ----------------------------------------------------- | -------------- |
| `furkin:nimble_grace` | Nimble Grace | 3         | +8% dodge chance per level                            | —              |
| `furkin:nine_lives`   | Nine Lives   | 1         | Triggers the Totem of Undying effect on a fatal blow (10 min cooldown) | `nimble_grace` Lv.3 |
| `furkin:fish_harvest` | Fish Harvest | 3         | Produce 1 raw fish into the pouch every 60 / 40 / 30s (ticks only while summoned) | `travel_pouch` |
| `furkin:night_watch`  | Night Watch  | 1         | Grants the owner Night Vision when within 16 blocks at night | —              |

### 狗分支 / Dog Branch（`furkin:dog`）

**中文**

| 技能 ID                  | 中文名  | 最大等级 | 等级效果                                        | 前置             |
| ---------------------- | ---- | ---- | ------------------------------------------- | -------------- |
| `furkin:bleeding_bite` | 流血撕咬 | 3    | 攻击附加流血效果：每秒 1/2/3 点伤害，持续 4 秒（亡灵免疫） | `sharp_fang`（等级跟随）   |
| `furkin:pack_tactics`  | 群猎战术 | 3    | 附近每存在1只友方狗队友 +10% / +15% / +20% 攻击（最多叠 3 层） | `sharp_fang`（等级跟随）   |
| `furkin:bone_harvest`  | 藏骨本能 | 3    | 每 60 / 40 / 30s 凭空产 1 骨头进背包（仅在场时计时）                 | `travel_pouch` |

**English**

| Skill ID               | Name          | Max Level | Effect per Level                                                                                        | Requires       |
| ---------------------- | ------------- | --------- | ------------------------------------------------------------------------------------------------------- | -------------- |
| `furkin:bleeding_bite` | Bleeding Bite | 3         | Attacks apply Bleeding effect: 1/2/3 damage per second for 4 seconds (undead immune) | `sharp_fang` (level follows)   |
| `furkin:pack_tactics`  | Pack Tactics  | 3         | +10% / +15% / +20% Attack per nearby canine ally (stacks up to 3)                                       | `sharp_fang` (level follows)   |
| `furkin:bone_harvest`  | Bone Harvest  | 3         | Produce 1 bone into the pouch every 60 / 40 / 30s (ticks only while summoned)                                                       | `travel_pouch` |

### 待定技能 / Planned（未定稿，暂未开放 / not yet finalized）

**中文**

| 技能 ID                  | 中文名  | 最大等级 | 简介                         |
| ---------------------- | ---- | ---- | -------------------------- |
| `furkin:heal_master`   | 疗愈之息 | 3    | 光环：对半径内友方周期回血              |
| `furkin:guardian`      | 守护之誓 | 3    | 主人受击时分担 20% / 40% / 60% 伤害 |
| `furkin:mount`         | 乘骑同行 | 1    | 可骑乘（作坐骑）                   |
| `furkin:swift_paws`    | 迅捷之爪 | 3    | 每级 +5% 移速                  |
| `furkin:loyal_stay`    | 不离不弃 | 1    | 主人濒死时挡一次致命伤害               |
| `furkin:treasure_nose` | 寻宝鼻  | 3    | 击杀 / 挖矿掉落翻倍                |
| `furkin:warm_body`     | 暖绒暖身 | 3    | 主人靠近获回血                    |

**English**

| Skill ID               | Name          | Max Level | Summary                                             |
| ---------------------- | ------------- | --------- | --------------------------------------------------- |
| `furkin:heal_master`   | Healing Aura  | 3         | Aura: periodically heals nearby allies              |
| `furkin:guardian`      | Guardian      | 3         | Absorb 20% / 40% / 60% of damage taken by the owner |
| `furkin:mount`         | Mount         | 1         | Can be ridden as a mount                            |
| `furkin:swift_paws`    | Swift Paws    | 3         | +5% movement speed per level                        |
| `furkin:loyal_stay`    | Loyal Stay    | 1         | Block a fatal blow when the owner is near death     |
| `furkin:treasure_nose` | Treasure Nose | 3         | Double drops from kills / mining                    |
| `furkin:warm_body`     | Warm Body     | 3         | Owner gains Regeneration when nearby                |

> 待定技能仍在设计中，具体效果与归属可能在后续版本调整或变更。

> Planned skills are still under design; their exact effects and placement may change in future versions.

---

## 三、技能数据结构 / Skill Data Structure

技能的配置以**数据驱动**方式进行，每条技能对应一份 JSON 配置（`data/furkin/skills/<skill_id>.json`）。以下为可配置字段。

Skills are **data-driven**; each skill maps to one JSON config file (`data/furkin/skills/<skill_id>.json`). The configurable fields are listed below.

### 顶层字段 / Top-level Fields

**中文**

| 字段            | 类型    | 默认                     | 含义                                    |
| ------------- | ----- | ---------------------- | ------------------------------------- |
| `id`          | 字符串   | 文件名                    | 技能标识（含命名空间，如 `furkin:sharp_fang`）     |
| `name`        | 字符串   | `furkin.skill.<id 路径>` | 显示名本地化 key                            |
| `description` | 字符串   | `name + ".desc"`       | 描述本地化 key                             |
| `tier`        | 整数    | 1                      | 层级（树上位置，越大越靠后）；面板顺序：先主干、后物种分支，组内按 tier 升序                        |
| `requires`    | 字符串数组 | 空                      | 前置技能 id 列表                            |
| `requiresLevel` | 对象  | 空                      | 前置等级要求：前置技能 id → 该前置必须达到的最低等级（如 `{"furkin:nimble_grace": 3}`） |
| `levelGate`   | 字符串   | 无                      | 等级门限：指向某前置技能，本技能可升到的最高等级 = 该前置技能当前等级 |
| `maxLevel`    | 整数    | 1                      | 可升级级数；`1`=单级、`-1`=无限、正整数=多级           |
| `cost`        | 整数    | 1                      | 每级消耗技能点                               |
| `species`     | 字符串数组 | 空                      | 归属物种（空=全物种；`furkin:cat`/`furkin:dog`） |
| `effects`     | 数组    | 空                      | 效果列表（每项 = `type` + `params`）          |

**English**

| Field         | Type     | Default                  | Meaning                                                       |
| ------------- | -------- | ------------------------ | ------------------------------------------------------------- |
| `id`          | string   | file name                | Skill identifier (namespaced, e.g. `furkin:sharp_fang`)       |
| `name`        | string   | `furkin.skill.<id path>` | Display-name localization key                                 |
| `description` | string   | `name + ".desc"`         | Description localization key                                  |
| `tier`        | int      | 1                        | Tier (tree position; higher = later). Panel order: trunk first, then species branches; ascending tier within a group |
| `requires`    | string[] | empty                    | Prerequisite skill id list                                    |
| `requiresLevel` | object | empty                    | Required prerequisite levels: prerequisite skill id → minimum level it must reach (e.g. `{"furkin:nimble_grace": 3}`) |
| `levelGate`   | string   | none                     | Level gate: points to a prerequisite; this skill's max reachable level = that prerequisite's current level |
| `maxLevel`    | int      | 1                        | Max level; `1`=single, `-1`=unlimited, positive = multi-level |
| `cost`        | int      | 1                        | Skill-point cost per level                                    |
| `species`     | string[] | empty                    | Species (empty = all; `furkin:cat` / `furkin:dog`)            |
| `effects`     | array    | empty                    | Effect list (each entry = `type` + `params`)                  |

**数据约束 / Data constraints**

- `tier` 必须 ≥ 1；`maxLevel` 必须是 `-1` 或正整数；`cost` 必须 > 0。
  *`tier` must be >= 1; `maxLevel` must be -1 or a positive integer; `cost` must be > 0.*
- `requiresLevel` 的每个值必须 > 0；非法技能定义会在加载期被拒绝并记录 WARN。
  *Every `requiresLevel` value must be > 0; invalid skill definitions are rejected at load time with a WARN.*
- 技能点退款按每级实际支付成本累计；热改 `cost` 只影响后续加点，不重写已支付部分。
  *Skill-point refunds use the cumulative amount actually paid per level. Hot-changing `cost` affects future unlocks only; it does not rewrite previously paid amounts.*

### 效果项结构 / Effect Entry

每条效果项为 `{ "type": "...", "params": {...} }`，其中 `type` 指向已注册的效果类型：

Each effect entry is `{ "type": "...", "params": {...} }`, where `type` refers to a registered effect type:

**中文**

| type                 | 说明                                    |
| -------------------- | ------------------------------------- |
| `furkin:attribute`   | 属性修正（修改属性值）                           |
| `furkin:ability`     | 主动技能（**占位**：已注册，尚未实现）                 |
| `furkin:passive`     | 被动技能                                  |
| `furkin:interaction` | 交互技能（**占位**：已注册，尚未实现）                 |

> `ability` 与 `interaction` 目前只是**类型骨架** —— JSON 引用它们不会报错，但 `apply` / `remove`
> 是空操作，技能不会产生任何效果。具体能力 / 交互技能留待后续批次实现。

**English**

| type                 | Meaning                                                        |
| -------------------- | -------------------------------------------------------------- |
| `furkin:attribute`   | Attribute modifier                                             |
| `furkin:ability`     | Active ability (**placeholder**: registered, not implemented)   |
| `furkin:passive`     | Passive skill                                                  |
| `furkin:interaction` | Interaction skill (**placeholder**: registered, not implemented) |

> `ability` and `interaction` are **type skeletons** only — referencing them in JSON is accepted,
> but `apply` / `remove` are no-ops, so the skill does nothing. Concrete abilities / interactions
> come later.

`params` 为自由 JSON 对象，由对应效果实现解析。以 `attribute` 为例：

`params` is a free-form JSON object parsed by the corresponding effect implementation. For `attribute`:

**中文**

| params 字段   | 含义                                              | 默认       |
| ----------- | ----------------------------------------------- | -------- |
| `attribute` | 属性注册名（如 `minecraft:generic.max_health`）         | 必填       |
| `amount`    | 每级加成量（实际值 = amount × 等级）                        | 0.0      |
| `operation` | `addition` / `multiply_base` / `multiply_total` | addition |

**English**

| params Field | Meaning                                                       | Default  |
| ------------ | ------------------------------------------------------------- | -------- |
| `attribute`  | Attribute registry name (e.g. `minecraft:generic.max_health`) | required |
| `amount`     | Per-level amount (actual value = amount × level)              | 0.0      |
| `operation`  | `addition` / `multiply_base` / `multiply_total`               | addition |

`passive` 类型的 `params` 多一个 `trigger`（触发位点）字段，并按技能各带一个配置块 ——
块名说的是「这个技能做什么」，块里的字段就是它的全部数值：

For `passive`, `params` carries an extra `trigger` field plus exactly one config block per skill.
The block name says what the skill does; its fields are all of its values:

**中文**

| params 字段 | 含义                                            | 取值                                        |
| --------- | --------------------------------------------- | ----------------------------------------- |
| `trigger` | 触发位点（**必填**；缺省或与期望不符时该块被忽略，技能静默不生效）          | `tick`（周期）/ `attack`（攻击时）/ `hurt`（受击时） |
| 配置块       | 该技能的数值块，块名见下表                                 | 下列块名之一                                    |

| 块名              | 所属技能                               | 字段                                                                 |
| --------------- | ---------------------------------- | ------------------------------------------------------------------ |
| `harvest`       | `fish_harvest` / `bone_harvest`    | `interval[]`（各等级间隔，tick）、`items[]`（物品池）、`count`（每次份数，选填，默认 1）        |
| `forager`       | `forager`                          | `radius`（拾取半径，格）                                                 |
| `feeder`        | `self_feeder`                      | `threshold`（开吃血量比例）、`cooldown`（两次进食间隔，tick）                      |
| `dodge`         | `nimble_grace`                     | `chancePerLevel`（每级闪避概率）                                         |
| `nine_lives`    | `nine_lives`                       | `cooldownTicks`（免死冷却，tick）                                        |
| `night_watch`   | `night_watch`                      | `radius`（生效半径，格）、`durationTicks`（每次刷新的夜视时长，tick）                  |
| `pack_tactics`  | `pack_tactics`                     | `bonusPerStack[]`（各等级每层加成）、`maxStacks`（叠层上限）、`radius`（计数半径，格）  |
| `bleeding_bite` | `bleeding_bite`                    | `durationTicks`（每次施加的流血时长，tick）、`damagePerSecond[]`（各等级每秒伤害）      |

时间单位一律用 **tick**（20 tick = 1 秒），与产出 / 进食 / 免死保持一致。
字段缺失或越界时**该技能整体禁用**，并在服务端日志打 WARN —— 不会静默截断、也不会退回默认值。两处例外：
`harvest` 的 `count` 选填（缺省为 1）；`items` 中的单个未知物品 id **只跳过该候选**，池空了才禁用。
另，`interval` 项数少于技能等级数时 **取最后一项**（合法简写，不是越界）——`maxLevel 3` 配 `interval[1200]`
等价于每级都是 1200。

**English**

| params Field | Meaning                                                                                            | Value                                                                        |
| ------------ | -------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `trigger`    | Trigger site (**required**; if absent or mismatched the block is ignored and the skill does nothing) | `tick` (periodic) / `attack` (on attacking) / `hurt` (on damaged)              |
| config block | The skill's value block; see the table below                                                        | one of the block names below                                                 |

| Block           | Skill                            | Fields                                                                                              |
| --------------- | -------------------------------- | --------------------------------------------------------------------------------------------------- |
| `harvest`       | `fish_harvest` / `bone_harvest`  | `interval[]` (per-level interval, tick), `items[]` (item pool), `count` (per yield, optional, default 1) |
| `forager`       | `forager`                        | `radius` (pickup radius, blocks)                                                                     |
| `feeder`        | `self_feeder`                    | `threshold` (health ratio to start eating), `cooldown` (between two meals, tick)                     |
| `dodge`         | `nimble_grace`                   | `chancePerLevel` (dodge chance per level)                                                            |
| `nine_lives`    | `nine_lives`                     | `cooldownTicks` (death-defy cooldown, tick)                                                          |
| `night_watch`   | `night_watch`                    | `radius` (effect radius, blocks), `durationTicks` (night-vision duration per refresh, tick)          |
| `pack_tactics`  | `pack_tactics`                   | `bonusPerStack[]` (per-level, per-stack bonus), `maxStacks` (stack cap), `radius` (counting radius, blocks) |
| `bleeding_bite` | `bleeding_bite`                  | `durationTicks` (bleed duration per hit, tick), `damagePerSecond[]` (damage per second per level)   |

All durations are in **ticks** (20 ticks = 1 second), consistent with harvest / feeder / nine-lives.
A missing or out-of-range field disables the whole skill and logs a WARN on the server —
values are never silently clamped or defaulted. Two exceptions: `harvest`'s `count` is optional
(defaults to 1); a single unknown item id in `items` only skips that candidate (the skill is disabled
only if the pool ends up empty). Also, when `interval` has fewer entries than the skill's levels the
**last entry is reused** (a legal shorthand, not an out-of-range error) — `maxLevel 3` with
`interval[1200]` means 1200 ticks at every level.

---

## 四、备注 / Notes

- 本模组仍处于开发阶段，技能效果与数值可能随版本迭代调整。
- This mod is still under development; skill effects and values may be adjusted across versions.
