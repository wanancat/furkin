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
| `furkin:nine_lives`   | 九命猫  | 1    | 受到致命伤害免死一次，保留 1 血（冷却 10 分钟） | `nimble_grace` |
| `furkin:fish_harvest` | 捕鱼天赋 | 3    | 每 60 / 40 / 30s 凭空产 1 生鱼进背包 | `travel_pouch` |
| `furkin:night_watch`  | 守夜者  | 1    | 夜晚靠近主人时使其获得夜视效果             | —              |

**English**

| Skill ID              | Name         | Max Level | Effect per Level                                      | Requires       |
| --------------------- | ------------ | --------- | ----------------------------------------------------- | -------------- |
| `furkin:nimble_grace` | Nimble Grace | 3         | +8% dodge chance per level                            | —              |
| `furkin:nine_lives`   | Nine Lives   | 1         | Survive a fatal blow at 1 HP (10 min cooldown)        | `nimble_grace` |
| `furkin:fish_harvest` | Fish Harvest | 3         | Produce 1 raw fish into the pouch every 60 / 40 / 30s | `travel_pouch` |
| `furkin:night_watch`  | Night Watch  | 1         | Night Vision near the owner at night                  | —              |

### 狗分支 / Dog Branch（`furkin:dog`）

**中文**

| 技能 ID                  | 中文名  | 最大等级 | 等级效果                                        | 前置             |
| ---------------------- | ---- | ---- | ------------------------------------------- | -------------- |
| `furkin:bleeding_bite` | 流血撕咬 | 3    | 攻击附加流血效果：每秒 1/2/3 点伤害，持续 4 秒（亡灵免疫） | `sharp_fang`（等级跟随）   |
| `furkin:pack_tactics`  | 群猎战术 | 3    | 附近每存在1只友方狗队友 +10% / +15% / +20% 攻击（最多叠 3 层） | `sharp_fang`   |
| `furkin:bone_harvest`  | 藏骨本能 | 3    | 每 60 / 40 / 30s 凭空产 1 骨头进背包                 | `travel_pouch` |

**English**

| Skill ID               | Name          | Max Level | Effect per Level                                                                                        | Requires       |
| ---------------------- | ------------- | --------- | ------------------------------------------------------------------------------------------------------- | -------------- |
| `furkin:bleeding_bite` | Bleeding Bite | 3         | Attacks apply Bleeding effect: 1/2/3 damage per second for 4 seconds (undead immune) | `sharp_fang` (level follows)   |
| `furkin:pack_tactics`  | Pack Tactics  | 3         | +10% / +15% / +20% Attack per nearby canine ally (stacks up to 3)                                       | `sharp_fang`   |
| `furkin:bone_harvest`  | Bone Harvest  | 3         | Produce 1 bone into the pouch every 60 / 40 / 30s                                                       | `travel_pouch` |

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
| `tier`        | 整数    | 1                      | 层级（树上位置，越大越靠后）                        |
| `requires`    | 字符串数组 | 空                      | 前置技能 id 列表                            |
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
| `tier`        | int      | 1                        | Tier (tree position; higher = later)                          |
| `requires`    | string[] | empty                    | Prerequisite skill id list                                    |
| `levelGate`   | string   | none                     | Level gate: points to a prerequisite; this skill's max reachable level = that prerequisite's current level |
| `maxLevel`    | int      | 1                        | Max level; `1`=single, `-1`=unlimited, positive = multi-level |
| `cost`        | int      | 1                        | Skill-point cost per level                                    |
| `species`     | string[] | empty                    | Species (empty = all; `furkin:cat` / `furkin:dog`)            |
| `effects`     | array    | empty                    | Effect list (each entry = `type` + `params`)                  |

### 效果项结构 / Effect Entry

每条效果项为 `{ "type": "...", "params": {...} }`，其中 `type` 指向已注册的效果类型：

Each effect entry is `{ "type": "...", "params": {...} }`, where `type` refers to a registered effect type:

**中文**

| type          | 说明          |


| ------------- | ----------- |  
| `attribute`   | 属性修正（修改属性值） |  
| `ability`     | 主动技能        |  
| `passive`     | 被动技能        |  
| `interaction` | 交互技能        |

**English**

| type          | Meaning            |
| ------------- | ------------------ |
| `attribute`   | Attribute modifier |
| `ability`     | Active ability     |
| `passive`     | Passive skill      |
| `interaction` | Interaction skill  |

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

---

## 四、备注 / Notes

- 技能点、背包格数、各数值均可通过配置调整。
- 本模组仍处于开发阶段，技能效果与数值可能随版本迭代调整。
- Skill points, pouch size, and all values are configurable.
- This mod is still under development; skill effects and values may be adjusted across versions.
