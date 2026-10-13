# KyouTycoon

Standalone Minecraft Java Edition Tycoon plugin for Paper. Core technology: Java 21, Gradle, Paper API and SQLite. All factory machines use vanilla blocks and all resources use vanilla materials; the plugin does not require a paid tycoon plugin, custom item pack or resource pack.

## Requirements

- Java 21
- Paper for Minecraft 1.21.x
- Gradle 8.14 or a compatible Gradle 8.x installation
- Network access on the first build so Gradle can download dependencies

## Build and test

```bash
gradle --no-daemon clean test build
```

The same command runs in GitHub Actions on every push and pull request. It validates the YAML files, compiles the plugin and runs JUnit tests.

Output JAR:

`build/libs/KyouTycoon-1.0.0.jar`

## Install

1. Run the build command above.
2. Copy `build/libs/KyouTycoon-1.0.0.jar` into the Paper server's `plugins/` directory.
3. Start the server once. KyouTycoon creates its default configuration files and SQLite database.
4. The default Tycoon world is `tycoon_world`. If `world.enabled` is true and the world doesn't exist, it is generated automatically.

The plugin never deletes a pre-existing world. Back up the world folder and `plugins/KyouTycoon/kyoutycoon.db` before upgrades.

## Main settings

Configuration files are in `plugins/KyouTycoon/`:

- `config.yml`: world/plot dimensions, protection, starting balance, offline production, storage, energy, market, company, progression and performance.
- `machines.yml`: machine definitions, input/output materials, energy costs and upgrade levels.
- `recipes.yml`: vanilla input/output recipes.
- `shops.yml`: prices used by the market.
- `research.yml`: research costs, prerequisites and production bonuses.
- `contracts.yml`: timed production requirements and rewards.
- `messages.yml`: player-facing messages.
- `gui.yml`: inventory menu titles and sizes.

All items and block materials are vanilla. A machine uses a vanilla block selected in `machines.yml`; the owner registers it with `/tycoon machine register <machine_id>` while looking at the block. The block remains a normal vanilla block until explicitly registered.

## World and plots

Default world name: `tycoon_world`. It uses a flat generator without structures. Default layout is 10 columns by 10 rows (100 plots), each 100 × 100 blocks, with a 7-block road between plots. The grid is centered on zero and stored plot bounds are inclusive. A compact spawn hub sits over the central road crossing.

A new player is given a plot automatically when `plot.starting_claim: true`; otherwise use `/plot claim`.

## Commands

| Command | Purpose |
| --- | --- |
| `/tycoon` | Open the main menu |
| `/tycoon help` | Show player command help |
| `/tycoon reload` | Reload plugin configuration |
| `/tycoon warehouse` | Show stored vanilla materials |
| `/tycoon deposit <MATERIAL> <amount>` | Move matching items from inventory to warehouse |
| `/tycoon withdraw <MATERIAL> <amount>` | Move items from warehouse to inventory |
| `/tycoon machine register <machine_id>` | Register the targeted matching vanilla block |
| `/plot claim` | Claim the next available plot |
| `/plot home` | Teleport to the owned plot |
| `/plot info` | Show plot level and machine count |
| `/plot upgrade` | Upgrade plot level |
| `/balance` | Show current balance |
| `/pay <player> <amount>` | Transfer money to an online player |
| `/baltop` | Show cached balance ranking |
| `/market` | List market prices |
| `/market sell <MATERIAL> <amount>` | Sell warehouse stock |
| `/company create <name>` | Create a company |
| `/company info` | Show company details |
| `/company members` | Show company membership |
| `/contract` | Show configured contract IDs |
| `/research` | Show configured research IDs |
| `/prestige` | Attempt prestige if the configured value requirement is met |
| `/tyadmin world create` | Create the world if it does not exist |
| `/tyadmin reload` | Reload configuration |
| `/tyadmin debug` | Show basic runtime counts |

## Permissions

- `kyoutycoon.player`: basic plugin commands (default: everyone)
- `kyoutycoon.plot`: plot commands (default: everyone)
- `kyoutycoon.company`: company commands (default: everyone)
- `kyoutycoon.admin`: administration commands (default: operator)
- `kyoutycoon.admin.reload`: reload permission (default: operator)
- `kyoutycoon.admin.debug`: debug permission (default: operator)

## SQLite and persistence

The plugin stores player balances, plot ownership, machine ownership and coordinates, production progress and timestamps in SQLite. Additional tables support warehouses, energy, companies and company membership, employees, research, timed contracts, achievements, transactions, market prices and prestige. Machine processing is batched; it does not create one scheduler per machine. Periodic saves use a single-thread database executor.

## Offline production

The boolean is intentionally defined exactly as follows:

```yaml
offline_machine:
  player_offline: false
  maximum_offline_time: 21600
```

- `false`: the owner can be offline while machines continue processing elapsed time. Catch-up is capped at six hours by default.
- `true`: machines pause while the owner is offline and no offline cycles are credited.

The production calculator has automated tests for both modes and the offline cap.

## GUI

`/tycoon` opens the inventory main menu. Plot, machine, warehouse, energy and market pages are interactive. The market GUI sells one unit per click; use the market sell command for a larger amount.

## Build validation

GitHub Actions runs Java 21 + Gradle, parses all resource YAML files and executes `clean test build`. Check the [Actions page](https://github.com/Yuri-Neko/KyouTycoon/actions) for the latest result. Tests cover plot bounds/road gaps, offline production semantics and progression transactions.

## Troubleshooting

- **Plugin doesn't enable:** confirm the server is Paper with Java 21 and inspect the startup error before deleting any data.
- **SQLite cannot open:** check write permission for the plugin directory and whether another process has locked the database.
- **World wasn't generated:** confirm `world.enabled: true` and inspect server logs; existing worlds are not regenerated.
- **Machine doesn't process:** ensure the targeted block matches a machine ID in `machines.yml`, register it, provide configured input in warehouse, and check energy and storage state.
- **Market sale fails:** the material needs a configured `sell` price and sufficient warehouse stock.
- **Offline cycles stop:** verify `offline_machine.player_offline`; `true` pauses offline production by design.
- **Build fails:** run `gradle --version`, verify Java 21 is active, then run `gradle --no-daemon clean test build` and inspect the first compiler/test error.

## Development notes

The codebase is being modularized in ordered implementation parts following the original plan. Before adding another feature, run the CI build/tests and correct the first failing stage. Don't treat a successful compile as proof of in-game behavior; Paper-server smoke testing is still needed for world generation, inventories, protection and actual tick behavior.
