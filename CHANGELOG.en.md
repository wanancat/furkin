# Changelog

All notable changes to **Furkin** are documented in this file.

---

## Versioning

This project follows the **Forge-recommended version format**:

```text
MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH
```

| Segment | Meaning | Incremented when |
|---|---|---|
| `MCVERSION` | Target Minecraft version | Always matches the Minecraft version |
| `MAJORMOD` | Mod major | Removes items, removes or changes existing mechanics, or upgrades the Minecraft version |
| `MAJORAPI` | API major | Breaking API changes: changes enum ordering or variables, changes method return types, or removes public methods entirely |
| `MINOR` | Minor | Adds items, adds new mechanics, or deprecates public methods |
| `PATCH` | Patch | Fixes bugs |

**Two consequences:**

1. **The version number communicates API compatibility.** Third parties only need to compare the `MAJORAPI` segment. It is queryable at runtime via `FurkinApi.getApiVersion()`.
2. **All API changes are collected under a dedicated `### API Changes` section.** Breaking changes are called out explicitly.

> Reference: Forge documentation, "Versioning" -- the `MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH` format distinguishes world incompatibilities from API incompatibilities.

---

## [1.19.2-0.0.2.0] - 2026-09-25

### Fixed

- **Skill refunds and schema validation** -- Respec now refunds the amount actually paid per level, and hot-changing a skill's `cost` no longer rewrites past payments. Legacy saves without a payment ledger migrate from the current definition once; deleted definitions fall back to `cost=1` with a warning. Loading rejects non-positive `cost`, invalid `maxLevel` / `tier` / `requiresLevel`, and missing or unreachable `requires` / `requiresLevel` / `levelGate` references, preventing malformed skill data from creating or consuming skill points incorrectly.
- **Skill hot-reload consistency** -- After `/reload`, loaded companions now clear and rebuild their `furkin:attribute` modifiers from the current tree, preventing duplicate or stale bonuses when a skill is deleted, retargeted, or rebalanced; entities unloaded during the reload are calibrated on join. `bleeding_bite` keeps live semantics: new DPS applies immediately, existing duration is preserved, and damage stops if the definition becomes invalid.
- **Client packet isolation** -- Shared network packets no longer directly contain `Minecraft`, client screen classes, or local capability writes. Five client callbacks now go through `FurkinClientPacketHandler` behind `DistExecutor` client-only dispatch. Packet fields and structure are unchanged.
- **Localized command feedback** -- `furkin` command feedback for summoning, listing, unbinding, renaming, XP, combat mode, skills, inspection, and pouches now uses translation keys. English and Chinese key sets stay in sync, while dynamic names, counts, UUIDs, and skill IDs are passed as arguments.
- **Save and skill data hardening** -- Invalid `FurkinState` / `FurkinCombatMode` values no longer make entity or archive deserialization throw. Bad archive entries are skipped individually with a warning, without taking down the rest of the save. Duplicate skill IDs now log both sources and keep the first definition instead of silently overriding it.

---

## [1.19.2-0.0.1.0] - 2026-09-24

**Minecraft 1.19.2 port** -- Migrates the build target to Minecraft 1.19.2 / Forge 43.x while preserving the feature set of `1.20.1-0.0.1.0`.

### Changed

- Target runtime changed to Minecraft `1.19.2` and Forge `43.2.0`; mappings now use `official 1.19.2`, and the resource pack format is `9`.
- Client GUIs were ported from 1.20.1 `GuiGraphics` to 1.19.2 `PoseStack`.
- Adapted entity/world accessors, command messages, buttons and `EditBox`, scroll widgets, the creative tab, and world rendering-stage APIs for 1.19.2.

### Fixed

- Adjusted button texture sizing, skill-row spacing, initial mouse-wheel focus, scroll-position restoration, and long-name truncation to preserve the 1.20.1 UI behavior.
- After a successful summon or teleport, the existing Furkin Record refresh path updates the screen in place instead of reopening it.

### API Changes

- The seven public API types and their signatures are unchanged; this is a Minecraft/Forge platform port, not a breaking public API change.
- `1.19.2-0.0.1.0` and `1.20.1-0.0.1.0` are separate artifacts per Minecraft line; third parties should treat `MCVERSION` as the load-compatibility boundary.

### Validation

- `compileJava` passed with JDK 17.0.2 and zero javac errors.
- The client starts and enters a single-player world; the integrated server saves and exits cleanly without a crash report.
- WP9d GUI regression tests D1-D9 passed, covering contract naming, the Furkin Record, renaming, skills, equipment, the Travel Pouch, and revival flows.
- The dedicated-server smoke test reaches `Done`; graceful shutdown and save verification for the dedicated server remain outstanding.

### Known Issues

- Minecraft 1.20.1 worlds are not guaranteed to load directly in 1.19.2; back up and verify separately before crossing versions.

## [1.20.1-0.0.1.0] - 2026-09-22

**Initial complete version** -- All functionality from M0 through M5 is complete.

### Added

- **Contract system** -- Contract vanilla cats and dogs into companions: contract item, naming screen, and archive persistence.
- **Companion management** -- Summon, retract, release, and rename; overhead status icon; lost and fallen state tracking.
- **Growth and skill tree** -- Level curve, skill points, and 13 skills (6 trunk + 4 cat + 3 dog); skill data is JSON-driven and hot-reloadable.
- **Travel Pouch** -- A carry-along inventory sized by the `travel_pouch` skill level; handles shrinking, reclaiming, and death drops.
- **Equipment system** -- Four equipment slots (head, chest, legs, and feet), archived with the companion, with paired death snapshots and drop-chance suppression.
- **Revival system** -- Soulstone (fire-resistant and bound by `companion_id`), a 12-flower plus wool revival ritual, and an in-record "reacquire soulstone" fallback (1 diamond and a 600-second per-pet cooldown).
- **Furkin panel** -- A container screen with Skill, Pouch, and Equipment tabs; the Skill tab shows a live attribute area with a hover breakdown.
- **Record attribute area** -- Companion attributes displayed inside the Furkin Record.
- **Command layer** -- A `/furkin` command tree covering summon, archive, skills, and other operations.
- **Custom creative tab** -- A dedicated Furkin creative tab so all four items are searchable in creative mode.
- **Configuration** -- Client and server TOML configs, including balance values.
- **Public API** -- The `com.wanancat.furkin.api` package for third parties to register species and react to level-ups.

### API Changes

**This version is the first public API release.**

- **Added the public API package `com.wanancat.furkin.api`** with seven public types:
  - `FurkinApi` -- Static entry point: `registerSpecies()`, `isRegistered()`, `getSpecies()`, and `getApiVersion()`
  - `api.companion.FurkinSpecies` -- Species identifier
  - `api.companion.FurkinSpeciesRegistry` -- Species registry
  - `api.companion.IFurkin` -- Read-only companion query interface
  - `api.skill.FurkinSkillEffectType` -- Custom skill effect type
  - `api.skill.FurkinSkillEffectTypeRegistry` -- Effect type registry
  - `api.event.FurkinLevelUpEvent` -- Level-up event (cancellable, not overridable)
- **The `MAJORAPI` segment is currently `0`** -- This signals that the API is not yet declared stable; it will advance to `1` at the first stable release.
- **Boundary note:** Everything outside the `api` package -- especially `com.wanancat.furkin.internal.*` -- is an implementation detail, may change at any time, and carries no compatibility promise. No separate API jar is published, so this boundary is a convention rather than a compile-time constraint; depend only on the `api` package.

### Notes

- Balance values are frozen at their initial set; later versions may adjust them.
- The four items (Furkin Contract, Furkin Record, Respec Potion, and Furkin Soulstone) appear only in Furkin's own creative tab, not in any vanilla tab.
