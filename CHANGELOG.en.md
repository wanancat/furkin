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

## [1.19.2-0.0.4.0] - 2026-09-28

### Added

- **Owner dimension follow** -- When the owner performs a real dimension change (nether portal, End portal, cross-dimension `/tp` / `/execute in`), companions that are in the departure dimension, within radius, already loaded, and owned by the player follow to the target dimension; sitting companions stand up and follow on arrival. Implemented as a pre-travel snapshot plus a server-tick completion check, so pets are never moved inside the travel event itself: a cancelled travel, a third destination, or an expired snapshot never produce a wrong teleport. Only already-loaded entities are handled -- no chunk tickets, no cold-chunk loading, no rebuilds, no archive copies. The vanilla End "credits return" (End exit portal -> credits -> respawn) is out of scope and does not follow. A landing is accepted only when its chunk is loaded, a standable support exists within 2 blocks below, and the destination volume contains no tracked hazard; if the owner is falling over a deep pit or lava, that companion stays in the source dimension instead of risking a drop.
- **Owner dimension follow server config** -- `furkin-server.toml` now exposes `ownerDimensionFollowEnabled` (default `true`) and `ownerDimensionFollowRadius` (default `16`, range 1-64, 3D Euclidean radius from the departure position to the companion center).
- **Explicit remote summon takes priority** -- If the same companion already has an active remote-summon pending for the same player at snapshot or execution time, owner-follow yields read-only: it never moves the pet, cancels the pending, or rewrites its terminal state. `remotePendingAtArm` also prevents a late catch-up follow after the pending fails or is cancelled, avoiding a pet that reappears right after a failure message.

### API Changes

- No breaking API changes; the public API and packet structure are unchanged, `PROTOCOL_VERSION` remains `"2"`, and no save schema field was added.

### Validation

- `compileJava` and `build` pass; the produced `build/libs/furkin-1.19.2-0.0.4.0.jar` expands `mods.toml` to version `1.19.2-0.0.4.0` (pure ASCII) and contains no `internal.debug` / fixture classes.
- `runServer` reached `Done`; an RCON `stop` completed `Saving players`, `Saving worlds`, and all three dimension saves, after which Gradle reported `BUILD SUCCESSFUL` and exited with code 0. No `FATAL` was logged; the only `ERROR` lines are the vanilla/Forge `TagLoader` tag noise already present in earlier `runServer` logs.
- `runClient` reached resource loading / the title screen with `ERROR=0` and `FATAL=0`.
- Static audit: the follow path adds no `LivingTickEvent`, `getChunkFuture`, `addRegionTicket`, or `setChunkForced`; `PROTOCOL_VERSION` stays `"2"` and no public API, save-schema, or packet changes were made.
- A one-off server fixture covered F-04 through F-29 and finished with `FURKIN_FIXTURE_ODF_OK checks=52 failures=0`, covering radius boundaries, cancellation/third-dimension/timeout, cold/unloaded entities, other-owner/wild/recalled/dead/duplicate filtering, sitting pets, partial failure, the historical landing fallback (the old fixture assertion is obsolete after F-19 was reopened), remote-summon pending yielding, post-pending failure, canonical reuse for an explicit summon after follow, and shutdown cleanup. Fixed-prefix log: `D:\frukin_dev\_research\owner_dimension_follow_fixture_20260928.log`; fixture source, temporary run world, and fixture markers were removed, and the final jar contains no fixture classes.
- Server performance: a fixture-free 30-minute idle run stayed at 20 TPS with overall mean tick time 0.859-0.910 ms. Stress fixtures covered radius 16/64, `activeLimit=3/20`, 80/150 nearby wild entities, and 12 same-tick players: cold radius-16/20-companion P95 was 19.979 ms; hot P95 was 4.854 ms with a 6.538 ms max; default-three P95 was 1.410 ms; radius-64 P95 was 5.883 ms; 12x3 P95 was 7.027 ms. These measurements were collected before the F-19 support-search fix; that fix only adds constant-bounded block reads and collision checks to dimension-landing selection. Logs: `D:\frukin_dev\_research\odf_perf_30m_20260928.txt` and `D:\frukin_dev\_research\odf_perf_stress_20260928.log`. Fixture source and temporary worlds were removed. These server measurements do not by themselves establish real-client frame-rate or multi-client network fanout; those were measured separately below.
- Focused landing fixture: a temporary `OdfLandingFixture` called `findSafeLanding(...)` directly across 5 branches and finished with `FURKIN_FIXTURE_ODF_LANDING_OK checks=5 failures=0`, covering normal ground, unsupported drop beyond 2 blocks, a target body inside lava, a stone slab support, and a target body inside fire. Log: `D:\frukin_dev\_research\odf_landing_fixture_20260928.log`; the fixture source was removed.
- Real-client portal matrix executed: Overworld <-> Nether round trips passed, including the all-three `moved=3 failed=0 skipped=0` run; sitting-follow passed, and explicit remote-summon pending interplay was partially verified with no cancellation or duplicate entity. The End credits return passed the negative out-of-scope expectation. F-19 landing safety is reopened: in the 2026-09-28 17:37 real-client run, the owner fell from a Nether portal and all three companions logged `moved=3` before dying in lava about 3 seconds later because the old implementation did not validate support below the destination. The current implementation searches up to 2 blocks downward for support and rejects unsupported/hazardous destination volumes; the focused server fixture passes. Real-client revalidation also passes: a normal safe landing moved all three pets without deaths, while a deterministic cross-dimension `/tp` to Nether `y=250` recorded `NO_SAFE_LANDING` for all three and finished with `moved=0 failed=3`, with no new `Furkin teleported` entries.
- Final-code retest after the F-19 support-search fix: default-three P95 `1.861 ms`, 12x3 same-tick P95 `8.558 ms`, and radius-16/activeLimit-20 cold P95 `23.352 ms`; the default `activeLimit` remains 3.
- F-26 is complete: the server state machine and real-client failure feedback both passed; `OdfAlpha` received the localized cancellation message. Stable-window real-client FPS was 60/60 p50/p95, and the two-client fanout measured 3 x 207 bytes with `chunkTrackers=2`, plus one `PlayerEvent.StartTracking` delivery per companion and client. Residual boundary: the alternate F-16/F-25 timing branch remains marked partially verified; production hardware/view-distance and long-run public-network traffic are not extrapolated from this environment.
## [1.19.2-0.0.3.0] - 2026-09-27

### Added

- **Contract health precondition** -- Contracts now check `Enemy > NeutralMob > other` classification before accepting a target: Enemy defaults to 30% or 4 HP, neutral mobs to 50% or 8 HP, and other species are unlimited by default. Initiation and confirmation share the same server-authoritative check; a too-healthy target gets a localized threshold message and cancels the vanilla interaction, while invalid health fails silently. Forge multipart entities resolve to their parent first.

- **True remote summon** -- When a summoned companion's chunk is unloaded, Furkin adds a bounded temporary chunk ticket at the recorded dimension/position, re-resolves the same entity by its canonical UUID after the chunks load, and teleports it. Load failure, timeout, duplicate entities, or state changes fail safely without rebuilding, copying the archive, or clearing the recorded location. Defaults: radius 1 (3x3), timeout 600 ticks, with per-player and global request limits.
- **Duplicate companion recovery** -- Added `/furkin repair list <pet_id>` and `/furkin repair choose <pet_id> <keep_entity_uuid>`: the keeper receives the equipment/pouch items it is missing (conflicts drop at its feet) before duplicates are removed; the canonical join guard no longer treats an entity with a different UUID as the archived companion.
- **Remote summon server config** -- `furkin-server.toml` now exposes `remoteSummonEnabled`, `remoteSummonTicketRadius`, `remoteSummonTimeoutTicks`, `remoteSummonMaxPendingPerPlayer`, `remoteSummonMaxPendingGlobal`, and `remoteSummonCooldownTicks`; defaults and ranges are documented in the remote-summon feature documentation under docs/remote-summon-1.19.2/. Disabling only rejects the remote-loading path for an already-summoned but unloaded companion; loaded-entity teleport and legal rebuild still work.

### Fixed

- **No more equipment loss in unloaded chunks** -- When a companion is `summoned=true` but missing from the runtime entity index, the summon path now fails safely instead of flipping the record to `summoned=false`, clearing its identity/location, or rebuilding a second entity from an old snapshot, so live equipment and pouch contents on the original entity are no longer lost.
- **No rebuild while an identity is loaded** -- The loaded-identity index now tracks all contracted entities by `companionId`. When `summoned=false` and any same-identity entity is still loaded (including an orphan/duplicate with no canonical UUID or a different UUID), rebuild is rejected with `DUPLICATE_CONFLICT`; legal rebuild remains unchanged when none is loaded.
- **Asynchronous remote-summon feedback** -- The companion record and `/furkin summon` now share the server-side remote-summon service: pending requests get localized feedback, the record button shows a disabled loading state, every non-pending terminal state refreshes the list, and failures / timeouts / duplicate conflicts report their reason without a false success.

### API Changes

- No breaking API changes; the public API and packet structure are unchanged, and `PROTOCOL_VERSION` remains `"2"`.

### Validation

- A one-off server fixture covered 33 scenarios with `pass=33 fail=0`; vanilla EnderDragon multipart event targets passed parent resolution, contract, feeding, panel, and recall paths under 1.19.2 / Forge 43.2.0.
- `clean build` succeeded; a clean `runServer` reached `Done`, a clean `runClient` loaded resources and started, and the final JAR contains no `internal.debug` / fixture classes.

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
