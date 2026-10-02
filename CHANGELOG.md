# Changelog

All notable changes to Universal Pipes are recorded here, oldest first within each version.

## 0.0.1 (pre-release)

Development history from the first commit on 2026-09-30 to 2026-10-01. Targets Minecraft 1.21.1 and NeoForge 21.1.219.

### Project setup (2026-09-30)

- Created the repository with an MIT license.
- Added the Gradle build scaffold, the NeoForge mod metadata template and the Gradle wrapper.
- Fixed `.gitattributes`.

### Core blocks and items (2026-09-30)

- Registered the Pipe block, the Pipe Wrench and the Pipe Upgrade item, with models, blockstate, textures and a creative tab.
- Added the mod configuration, datapack loading for tiers, defaults and limits, and the filter engine.
- Added pipe networks, per-face endpoints, the pipe menu and screen, network payloads, the `/upipes` commands and pipe interactions.
- Added crafting recipes for the pipe, the wrench and the tier 2 to 4 upgrades.
- Added GameTests, a benchmark and an empty test structure.
- Fixed listener initialisation order, endpoint registration and item tinting.

### Pipe appearance (2026-09-30 to 2026-10-01)

- Added per-pipe appearance: dye tint, collar accent, material sprite and glow.
- Synced pipe looks to clients and added a texture for each tier.
- Simplified the pipe screen and labelled the look view.
- Gave pipe model faces explicit UV regions.
- Fixed the model refresh call and vertex stride, and restored the endpoint configuration accessors on the pipe entity.
- Whole pipe lines can be styled in the world with one click, and a water bucket clears a look.
- Added a settings screen.
- Advanced filter rules are deleted with a click.
- Dye colour became a connection channel, so parallel pipe lines of different colours can touch.

### Gameplay features (2026-10-01)

- Enforced tier gating through the datapack feature table.
- Added the network inspector (sneak with an empty hand), wrench settings copy, bridging across a short air gap for tier 4 pipes, channel crossing, comparator output, tooltips and further configuration options.
- Added translations for the configuration screen.

### Tier 5, JEI and the guide book (2026-10-01)

- Added the tier 5 upgrade recipe, tier names and a wrench tag.
- Added an optional JEI plugin.
- Replaced the Patchouli guide with an in-game guide book ported from the Universal Ore Processing framework. Its tier chapter is built from the loaded datapack.

### Documentation, tests and tooling (2026-10-01)

- Wrote the README covering recipes, tiers, filters, configuration and commands.
- Added JUnit tests for the filter expression parser and filter sets.
- Added a translation guide (`docs/TRANSLATING.md`) and a language file checker (`tools/check_lang.py`).
- Fixed the unit test mod binding in `build.gradle`, which previously failed project evaluation.
- Removed a GitHub Actions workflow that had been added, so builds and checks run locally only.
