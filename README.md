# KyouTycoon

Standalone Minecraft Java Edition Tycoon plugin for Paper + Java 21 + Gradle + SQLite. It uses vanilla blocks/items only and does not require Vault, WorldEdit, PlotSquared, ItemsAdder, Oraxen, MythicMobs or Citizens.

## Build

```bash
./gradlew build
```

Windows:

```powershell
gradlew.bat build
```

Output: `build/libs/KyouTycoon-1.0.0.jar`

## Install

1. Build the project.
2. Copy the JAR to `plugins/`.
3. Start Paper.
4. KyouTycoon creates `tycoon_world` and `kyoutycoon.db` automatically.

## Commands

- `/tycoon`
- `/tycoon reload`
- `/plot claim`
- `/plot home`
- `/plot info`
- `/plot upgrade`
- `/balance`
- `/pay <player> <amount>`
- `/baltop`
- `/company create <name>`
- `/company members`
- `/company info`
- `/market`
- `/contract`
- `/research`
- `/prestige`
- `/tyadmin world create`
- `/tyadmin reload`
- `/tyadmin debug`

## World

Default: `tycoon_world`, flat, peaceful, no structures and no mob spawning. Default is 10 x 10 plots, each 100 x 100 blocks, separated by exactly 7-block roads. The road pattern is STONE / STONE_BRICKS / STONE_BRICKS / WHITE_CONCRETE / STONE_BRICKS / STONE_BRICKS / STONE.

## Offline production

```yaml
offline_machine:
  player_offline: false
```

- `false`: player offline -> machines remain ON and production is calculated from `last_processed`.
- `true`: player offline -> machines are OFF and offline production is not calculated.

Offline time is capped by `maximum_offline_time`.

## Machine persistence

Machines are stored in SQLite with UUID, owner UUID, world, coordinates, type, level, state, progress, created time and `last_processed`. Production uses one batch scheduler rather than one scheduler per machine.

## Database

SQLite tables include players, plots, warehouses, machines, companies, company_members, employees, research, contracts, achievements, transactions, market_prices and prestige.

## Configuration files

- `config.yml`
- `machines.yml`
- `recipes.yml`
- `shops.yml`
- `research.yml`
- `contracts.yml`
- `messages.yml`
- `gui.yml`

## Notes

The project intentionally keeps the core implementation compact and modular enough to build as a standalone plugin. Vanilla blocks are used as machine identities; machine location/type/owner are persisted in SQLite, so no resource pack or custom item is required.
