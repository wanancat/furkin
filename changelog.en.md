# Changelog

All notable changes to **Furkin (绒亲)** are documented in this file.

---

## Versioning

This project follows the **Forge-recommended version format**:

```
MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH
```

| Segment | Meaning | Incremented when |
|---|---|---|
| `MCVERSION` | Target Minecraft version | Always matches the MC version |
| `MAJORMOD` | Mod major version | Removing items / changing or removing existing mechanics / bumping the MC version |
| `MAJORAPI` | **API major version** | ⭐ **Breaking API change**: reordering enums or variables, changing a method's return type, or removing a public method entirely |
| `MINOR` | Minor version | Adding items / adding new mechanics / deprecating public methods |
| `PATCH` | Patch | Bug fixes |

**Two consequences**:

1. **The version number alone tells you API compatibility** —— third parties only need to compare the `MAJORAPI` segment. It is queryable at runtime via `FurkinApi.getApiVersion()`.
2. **All API changes are collected under a dedicated `#### API Changes` section** —— breaking ones are called out explicitly.

> Reference: Forge documentation *Versioning* —— `MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH` distinguishes world-incompatible changes from API-incompatible changes.

---

## [1.20.1-0.0.3.0] - 2026-09-25

**Added**

- **Configurable contract health gates** —— Registered species now use `Enemy > NeutralMob > other` classification, with configurable percentage and absolute health gates. Either branch may pass: Enemy and NeutralMob default to `30%` or `4` health, while other targets default to `100%` with the absolute branch disabled. The six values live in the server `furkin-server.toml`. Both the naming request and confirmation revalidate the gate, so regenerating above it before confirmation is rejected.
- **Multipart entity interaction compatibility** —— When an entity interaction targets a Forge `PartEntity`, Furkin resolves the public `getParent()` entity. Multipart creatures such as the Twilight Forest Hydra, whose main entity is not directly pickable, can now pass the health gate and complete contract, dismiss and resummon flows.

**Notes**

- Only `HEALTH_TOO_HIGH` shows the health message and cancels the vanilla interaction; existing failures such as unregistered species, wrong ownership or being out of reach are not newly cancelled.
- Non-`TamableAnimal` enemies currently guarantee archive, dismiss and resummon flows only; following, ceasefire, owner defence and aggressive targeting are not yet guaranteed.
- No public API changes were made, `MAJORAPI` remains `0`, and the network protocol remains `2`.

---

## [1.20.1-0.0.2.0] - 2026-09-25

**Fixed**

- **Immediate record refresh after summon** —— After a successful summon from the Furkin Record, the list now immediately shows the green "Summoned" state and refreshes the right-side dismiss and combat-mode actions without reopening the screen.

- **Command feedback localization** —— `/furkin` command success, failure, status and quantity feedback now uses translation keys and follows the client language; dynamic UUIDs, names and counts remain translation arguments.
- **Contract confirmation authority** —— Contract confirmation now requires a matching server-issued pending session and revalidates species, ownership, target identity, distance and the exact main-hand stack; stale, forged or replayed requests cannot create an archive or consume an item.
- **Skill refunds and schema validation** —— Respec now refunds the amount actually paid per level, and hot-changing a skill's `cost` no longer rewrites past payments. Loading rejects non-positive `cost`, invalid `maxLevel` / `tier` / `requiresLevel`, preventing malformed skill data from creating or consuming skill points incorrectly.
- **Skill hot-reload consistency** —— After `/reload`, loaded companions now clear and rebuild their `furkin:attribute` modifiers from the current tree, preventing duplicate or stale bonuses when a skill is deleted, retargeted, or rebalanced; entities unloaded during the reload are calibrated on join. `bleeding_bite` keeps live semantics: new DPS applies immediately, existing duration is preserved, and damage stops if the definition becomes invalid.
- **Unbind cleanup** —— Unbinding now drops and clears the pouch and four equipment slots, restores displaced AI, vanilla default drop chances, skill effects, cooldowns, feeding and sitting state; the archive is removed only after cleanup succeeds.
- **Unresolved companion recovery** —— If a summoned companion cannot currently be resolved, regular unbinding no longer removes its archive. A confirmed force-unbind can be used instead; cleanup completes when the entity next enters the world and is retried if it fails.
- **Targeted lookup** —— Archives now store the entity UUID and dimension, so unbinding uses targeted index lookup without loading chunks, scanning all entities, or adding tick polling.
- **Network protocol** —— Force-unbind adds request/result packets and raises the protocol version to 2; clients and servers must use the same protocol version.
- **Cross-dimension archive** —— Companion archives now use one server-level overworld instance. Legacy per-dimension archives are merged on first read, so viewing, summoning, retracting and reviving across dimensions no longer resolve against the wrong world state, and the active limit is enforced server-wide.

---

## [1.20.1-0.0.1.1] - 2026-09-24

**Fixed**

- **Bleeding effect icon** —— The `furkin:bleeding` effect previously lacked its icon texture (`furkin:textures/mob_effect/bleeding.png`), emitting a missing-texture warning on the HUD (no crash). The mod now ships vanilla `instant_damage`'s red cracked-heart icon as `assets/furkin/textures/mob_effect/bleeding.png` (18×18), auto-stitched into the `furkin:mob_effect/bleeding` sprite via the `mob_effects` atlas, matching the `0xB22222` red theme. Resource-only change, no Java change.

---

## [1.20.1-0.0.1.0] - 2026-09-22

**Initial complete version** —— All functionality from M0 through M5 is complete.

### Added

- **Contract system** —— Contract vanilla cats and dogs into companions: contract item, naming screen, archive persistence.
- **Companion management** —— Summon / retract / release / rename; overhead status icon; lost & fallen state tracking.
- **Growth & skill tree** —— Level curve, skill points, 13 skills (6 trunk + 4 cat + 3 dog); skill data is JSON-driven and hot-reloadable via `/reload`.
- **Travel pouch** —— A carry-along inventory sized by the `travel_pouch` skill level; handles shrinking, reclaiming, and death drops.
- **Equipment system** —— Four equipment slots (head / chest / legs / feet), archived with the companion, paired death snapshot and drop-chance suppression (`setDropChance(0)`).
- **Revival system** —— Soulstone (fire-resistant, bound by `companion_id`), a 12-flower + wool revival ritual, and an in-record "reacquire soulstone" fallback (1 diamond + 600s per-pet cooldown).
- **Furkin panel** —— A container screen with Skill / Pouch / Equipment tabs; the skill tab shows a live attribute area with a hover breakdown.
- **Record attribute area** —— Companion attributes displayed inside the record screen.
- **Command layer** —— A `/furkin` command tree covering summon, archive, skills and other operations.
- **Custom creative tab** —— A dedicated Furkin creative tab so all four items are searchable in creative.
- **Configuration** —— Client and server TOML configs, including balance values.
- **Public API** —— The `com.wanancat.furkin.api` package for third parties to register species and react to level-ups.

### API Changes

⭐ **First public API release.**

- **New public API package `com.wanancat.furkin.api`** with 7 public types:
  - `FurkinApi` —— Static entry point: `registerSpecies()` / `isRegistered()` / `getSpecies()` / `getApiVersion()`
  - `api.companion.FurkinSpecies` —— Species identifier
  - `api.companion.FurkinSpeciesRegistry` —— Species registry
  - `api.companion.IFurkin` —— Read-only companion query interface
  - `api.skill.FurkinSkillEffectType` —— Custom skill effect type
  - `api.skill.FurkinSkillEffectTypeRegistry` —— Effect type registry
  - `api.event.FurkinLevelUpEvent` —— Level-up event (cancellable, non-overridable)
- **`MAJORAPI` is currently `0`** —— signalling that the API is not yet declared stable; it will advance to `1` upon the first formal release.
- ⚠️ **Boundary note**: Everything outside the `api` package — especially `com.wanancat.furkin.internal.*` — is implementation detail and carries no compatibility promise. No separate api jar is published, so this boundary is a convention, not a compile-time enforcement —— depend only on the `api` package.

### Notes

- Balance values are frozen at their initial set; later versions may adjust them.
- ⚠️ The four items (contract / record / respec potion / soulstone) are **not attached to any vanilla creative tab** and appear only in Furkin's own creative tab.
