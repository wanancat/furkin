# Furkin (绒亲)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)

![Forge](https://img.shields.io/badge/Forge-47.2.0-orange)

![License](https://img.shields.io/badge/License-MIT-green)

A lightweight **companion pet framework** for Minecraft Forge. Contract vanilla animals into
growing, skill-tree'd, equippable, revivable companions — and expose a public API so other mods
can bring their own creatures in as companions too.

> 中文说明见 [README.zh-CN.md](./README.zh-CN.md)。

---

## Features

- **Contract** — turn any registered species into a companion with a single item, no prior taming required.
- **Skill tree** — a shared trunk plus species-specific branches (combat for dogs, scouting for cats).
- **Equipment** — companions wear vanilla armor (all 4 slots), third-party armor works with zero config.
- **Revival** — a soulstone-based revive system that preserves all growth.

## Dependencies

| Requirement     | Version |
| --------------- | ------- |
| Minecraft       | 1.20.1  |
| Minecraft Forge | 47.2.0  |
| Java            | 17      |

**Zero hard dependencies.** No required library mods.

## Installation

1. Install [Minecraft Forge 1.20.1](https://files.minecraftforge.net) (47.2.0 or later).
2. Download the latest `.jar` from the releases.
3. Drop it into your `.minecraft/mods` folder.

## Usage

Craft a **Furkin Contract** and right-click any valid animal to bond it as your companion.
Craft a **Furkin Record** to manage your companions (summon, recall, skill points, equipment).

> Detailed in-game guide coming with the 1.0 release.

## For Mod Developers

Furkin is designed as a **predecessor mod**: depend on it and register your own species,
skills, and effects through the public API (`com.wanancat.furkin.api`).

```java
// Register a species
FurkinApi.registerSpecies(EntityType.WOLF, ...);
```

Any registered species works — a zombie, even, if you find it fluffy enough. :)

See the API package for the full surface. A standalone example mod will be provided.

## Building from Source

```bash
# Requires JDK 17
./gradlew build
```

The mod jar is produced at `build/libs/furkin-<version>.jar`.

To run the development client or server:

```bash
./gradlew runClient
./gradlew runServer
```

## License

[MIT](./LICENSE.txt). Free to use, modify, and redistribute — including in modpacks and as a dependency.

## Credits

- **Author**: wanancat
- Built on the Minecraft Forge modding framework.
