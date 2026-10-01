# Universal Pipes

![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-blue)
![NeoForge 21.1.219+](https://img.shields.io/badge/NeoForge-21.1.219%2B-orange)
![License MIT](https://img.shields.io/badge/License-MIT-green)

A single configurable pipe block that moves items, fluids and energy between adjacent inventories, with five upgradeable tiers and per-face filters.

## Features

- One pipe block for items, fluids and energy. Each extraction face enables the transport types it needs.
- Five tiers, upgraded in place without losing settings.
- Per-face endpoints: each face of a pipe touching an inventory can be connected, disconnected or set to extract.
- Simple filters (whitelist or blacklist of item ids) and advanced filters (ordered rules with an expression language, per-rule limits and target scopes).
- Redstone modes: always, powered, unpowered.
- Distribution modes: round robin, nearest, furthest, random, priority.
- Stock limits: keep a number of units in the source, stop inserting at a number in the destination.
- Bridging: tier 4 pipes join across a short air gap.
- Appearance: dye tint, glow, and a material block, applied to a whole pipe line in one click.
- Dye channels: pipes of different dye colours do not connect, so parallel lines can touch.
- Comparator output that reflects the state of the pipe's busiest face.
- Network inspector and settings copy through the wrench.
- Datapack-driven tiers, defaults and limits, reloadable without a restart.
- `/upipes` commands for statistics, profiling and forcing a network rebuild.

## Install

Requirements: Minecraft 1.21.1 and NeoForge 21.1.219 or newer (NeoForge version range as built from `gradle.properties`). Java 21.

Place the mod jar in the `mods` folder of the client and of the server. The mod has no other dependencies. No recipe viewer integration is included.

## Quick start

1. Craft pipes. Eight iron ingots in two rows of three (gap in the middle row) give eight pipes. Craft a Pipe Wrench from iron ingots as shown in the recipe table.
2. Place a chest, then a line of pipes, then a second chest, so the pipes touch both chests. Pipes connect to adjacent blocks that expose an item, fluid or energy capability.
3. Use the wrench on the face of the first pipe that touches the source chest. Each click advances the face through this cycle: connected, disconnected, extract, and back to connected. Two clicks therefore turn a connected face into an extract face. If the neighbour is another pipe, the extract step is skipped and the face goes from disconnected to connected.
4. Right-click the extract face with an empty hand to open the pipe screen. A new extract face moves items only, with redstone set to always and round robin distribution. Use the Items, Fluids and Energy tabs to change what is moved, and the Settings, Filters and Look views to configure the face.
5. Upgrade a pipe by right-clicking it with a Pipe Upgrade. Sneak while doing so to upgrade the whole connected line, up to the configured limit. Tier 1 moves 5 items per second, tier 5 moves 1280.

Other wrench actions: sneak and use it on an extract face to copy that face's settings (the next face created with the same wrench starts from the copy). Sneak and use it on any other face to dismantle the pipe. Sneak with an empty hand to see a one-line summary of the pipe line. Any item in the `c:tools/wrench` tag behaves as the wrench.

## Recipes

Ingot ingredients are item tags, so any mod's ingot of the same kind works.

| Result | Pattern (rows) | Ingredients |
| --- | --- | --- |
| 8 Pipe | `III`, blank, `III` | I = `c:ingots/iron` |
| Pipe Wrench | `I I`, `III`, ` I ` | I = `c:ingots/iron` |
| Pipe Upgrade, tier 2 | ` I `, `IRI`, ` I ` | I = `c:ingots/copper`, R = redstone |
| Pipe Upgrade, tier 3 | ` I `, `IRI`, ` I ` | I = `c:ingots/gold`, R = redstone |
| Pipe Upgrade, tier 4 | ` I `, `IRI`, ` I ` | I = `c:ingots/netherite`, R = redstone |

There is no crafting recipe for the tier 5 upgrade. It is available from the creative tab, or with `/give @s universal_pipes:pipe_upgrade[universal_pipes:tier=5]`.

## Tiers

Values come from the bundled `tiers.json`. Per-second rates are the per-operation amount multiplied by 20 and divided by the interval in ticks. Energy is stated per tick and multiplied by 20.

| Tier | Items per op | Interval (ticks) | Items/s | Fluid mB per op | mB/s | FE/tick | FE/s | Filter slots | Max rules |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 4 | 5 | 50 | 250 | 128 | 2,560 | 3 | 2 |
| 2 | 4 | 4 | 20 | 200 | 1,000 | 512 | 10,240 | 5 | 4 |
| 3 | 16 | 2 | 160 | 800 | 8,000 | 2,048 | 40,960 | 7 | 8 |
| 4 | 32 | 1 | 640 | 2,000 | 40,000 | 8,192 | 163,840 | 9 | 16 |
| 5 | 64 | 1 | 1,280 | 8,000 | 160,000 | 32,768 | 655,360 | 9 | 32 |

Upgrades may skip tiers unless `allow_tier_skipping` is off. With `tier_feature_gating` on, fluids, energy, advanced filters and stock limits need the minimum tier set in `limits.json`. Bridging always needs its minimum tier (default 4).

## Filters

Each transport type on an extract face has its own filter. Energy has no filter. A filter with nothing in its active list allows everything.

**Simple mode.** A list of item ids. Whitelist allows only listed ids, blacklist allows everything except listed ids. Entries are matched exactly against the full id (`minecraft:cobblestone`). The list holds as many entries as the tier has filter slots.

**Advanced mode.** An ordered list of rules, up to the tier's maximum. Each rule has an expression, a verdict (allow or deny), an optional per-operation limit (zero means no limit), an optional scope (specific target inventories, empty means any target) and an enabled flag. Disabled rules and rules whose scope does not include the destination are skipped. In advanced mode the whitelist and blacklist toggle is not used; the verdicts decide.

- First match: the first enabled, in-scope rule whose expression matches decides. Deny stops with a refusal. Allow returns its limit.
- All match: every enabled, in-scope rule is checked. Any matching deny refuses the stack. Otherwise the largest limit among matching allow rules applies.
- If rules exist and none matches, the stack is refused. If no rules exist, everything is allowed.
- An expression that does not compile matches nothing.

### Expression syntax

```
expr  := or
or    := and (('|' | 'or') and)*
and   := not (('&' | 'and') not)*
not   := ('!' | 'not') not | '(' expr ')' | atom
atom  := id | '#'tag | '@'namespace | '~'text | '?enchanted' | '?damaged'
       | '?durability=lo..hi' | '?has=component' | '?eq=component=snbt'
```

| Atom | Meaning |
| --- | --- |
| `id` | Exact item or fluid id. A missing namespace means `minecraft`. |
| `#tag` | Member of the tag. A missing namespace means `minecraft`. |
| `@namespace` | Any id from that namespace. Compared case-sensitively. |
| `~text` | Display name contains the text, ignoring case. Use quotes for spaces: `~"iron ingot"`. |
| `?enchanted` | The stack has enchantments. |
| `?damaged` | The stack has taken damage. |
| `?durability=lo..hi` | Remaining durability within the range. Items without durability never match. |
| `?has=component` | The stack carries the data component. |
| `?eq=component=snbt` | The component's encoded value equals the given text. |

Precedence from tightest to loosest: `!`, `&`, `|`. Brackets override it. Operators and word forms are case-insensitive, ids and tags are lowercased. The length and nesting depth are limited by `limits.json` (128 characters and depth 6 by default). Each entry in a simple filter is limited to 64 characters. There is no regular expression atom.

### Examples

| Goal | Expression |
| --- | --- |
| One exact item | `diamond` |
| Everything except cobblestone | `!cobblestone` (as an allow rule) |
| Any ore, using the common tag | `#c:ores` |
| Items from one mod only | `@create` |
| Only enchanted swords and pickaxes | `?enchanted & (#minecraft:swords \| #minecraft:pickaxes)` |
| Damaged tools with little life left | `?damaged & ?durability=1..100` |
| Anything with "ingot" in its name, except iron | `~ingot & !iron_ingot` |
| Raw materials from vanilla, no logs | `@minecraft & #c:raw_materials & !#minecraft:logs` |
| Words instead of symbols | `not cobblestone and not dirt` |

Fluids use the same grammar. `?enchanted`, `?damaged` and `?durability` never match fluids.

## Configuration

Server options are in `universal_pipes-server.toml` (per world), the client option in `universal_pipes-client.toml`. All options except `command_permission_level` take effect live.

| Option | Default | Range | Meaning |
| --- | --- | --- | --- |
| `entity_targets_enabled` | `false` | | Allow entities that expose item, fluid or energy capabilities as targets. |
| `tier_feature_gating` | `false` | | Require the minimum tiers from the feature table in `limits.json`. |
| `network_upgrade_max_blocks` | `64` | 1 to 4096 | Maximum pipes changed by one sneak-use of a Pipe Upgrade. |
| `allow_bridging` | `true` | | Allow pipes of the bridging tier to join across a short air gap. |
| `allow_materials` | `true` | | Allow blocks from the materials tag to restyle pipes. |
| `material_blocklist` | empty | list of block ids | Block ids that may never be used as a pipe material, on top of the tag. |
| `dye_channels` | `true` | | Pipes of different dye colours do not connect. |
| `paint_max_blocks` | `64` | 1 to 4096 | Maximum pipes styled by one use of a dye, glow ink sac, material block or water bucket. |
| `max_network_nodes` | `512` | 16 to 32768 | Maximum pipes visited when one endpoint discovers its network. |
| `tick_budget_ms` | `35.0` | 1.0 to 50.0 | Endpoints defer work while the mean server tick time exceeds this many milliseconds. |
| `idle_backoff_max_ticks` | `80` | 4 to 1200 | Longest idle interval in ticks for an endpoint with nothing to move. |
| `rebuild_budget_per_tick` | `4` | 1 to 256 | Network discoveries allowed per level per tick. |
| `refund_on_dismantle` | `true` | | Dismantling a pipe returns the spent Pipe Upgrade. |
| `allow_tier_skipping` | `true` | | Allow a Pipe Upgrade to skip tiers. |
| `allow_glow` | `true` | | Allow pipes to be made luminous. |
| `command_permission_level` | `2` | 0 to 4 | Permission level required for `/upipes`. Applies to commands registered after the next world load. |
| `status_refresh_ticks` (client) | `10` | 1 to 200 | Client ticks between status line refreshes in the pipe screen. |

### Datapack

Three JSON files are read from `data/<namespace>/universal_pipes/` and reload with `/reload`. A file from a namespace other than `universal_pipes` replaces the bundled one. Every value is clamped on load and a missing value falls back to the built-in default.

`tiers.json` holds an array `tiers` of five objects, in tier order, with these keys and clamps: `items_per_operation` (1 to 4096), `interval_ticks` (1 to 1200), `fluid_mb_per_operation` (1 to 1000000), `energy_per_tick` (1 to 100000000), `filter_slots` (0 to 9), `max_rules` (0 to 64).

`limits.json` keys: `max_expression_length` (8 to 512, default 128), `max_expression_depth` (1 to 16, default 6), `max_scope_targets` (1 to 64, default 16), `max_stock` (1 to 100000000, default 1000000), `bridge_gap` (1 to 8, default 2), and an object `min_tier` with `fluids`, `energy`, `advanced_filters`, `stock_limits` (each 1 to 5, default 1) and `bridging` (1 to 5, default 4).

`defaults.json` sets what a newly created extract face starts with: `redstone` (`ignore`, `on` or `off`), `distribution` (`round_robin`, `nearest_first`, `furthest_first`, `random` or `priority`), and the booleans `items` (default true), `fluids` and `energy` (default false).

Tags:

| Tag | Type | Effect |
| --- | --- | --- |
| `universal_pipes:non_connectable` | block | Pipes never connect to these blocks. |
| `universal_pipes:non_transferable` | item | These items are never moved. |
| `universal_pipes:non_transferable` | fluid | These fluids are never moved. |
| `universal_pipes:materials` | block | Blocks that may restyle a pipe as a material. |

## Commands

`/upipes` requires the permission level in `command_permission_level` (default 2).

| Command | Effect |
| --- | --- |
| `/upipes stats` | Per dimension: endpoints, epoch, discoveries, deferred discoveries and mean cost in ms per tick. |
| `/upipes profile [count]` | The slowest extraction faces by mean microseconds per attempt. Count is 1 to 50, default 10. |
| `/upipes rebuild` | Invalidates every network topology and wakes all endpoints. They rediscover within the rebuild budget. |

## Building from source

Requires JDK 21.

```
./gradlew build            # jar in build/libs
./gradlew runClient
./gradlew runServer
./gradlew test             # unit tests
./gradlew runGameTestServer
```

`runGameTestServer` runs the GameTests in `PipeGameTests` and exits.

## Contributing

Open an issue before large changes. Keep changes small, match the surrounding code style, and run the unit tests and GameTests before submitting. Translations are described in `docs/TRANSLATING.md`.

## License

MIT. See `LICENSE`.
