package com.thecascadian.universalpipes.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Fourteen scalars only. Everything tunable per tier or per feature lives in
 * reloadable datapack JSON instead, so servers can change it without a restart.
 * Reads fall back to the default while the spec is not yet loaded, which is the
 * case in GameTests and during early world setup.
 */
public final class PipesConfig {

    private static final int DEFAULT_UPGRADE_BLOCKS = 64;
    private static final int DEFAULT_PAINT_BLOCKS = 64;
    private static final int DEFAULT_MAX_NODES = 512;
    private static final double DEFAULT_TICK_BUDGET_MS = 35.0;
    private static final int DEFAULT_BACKOFF_CEILING = 80;
    private static final int DEFAULT_REBUILD_BUDGET = 4;
    private static final int DEFAULT_COMMAND_LEVEL = 2;
    private static final int DEFAULT_STATUS_REFRESH = 10;

    private static final ModConfigSpec.Builder SERVER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENTITY_TARGETS = SERVER
            .comment("Allow pipes to use entities that expose item, fluid or energy capabilities as targets. Live.")
            .define("entity_targets_enabled", false);
    private static final ModConfigSpec.BooleanValue TIER_GATING = SERVER
            .comment("Require the minimum tiers from the feature table in the datapack. Live.")
            .define("tier_feature_gating", false);
    private static final ModConfigSpec.BooleanValue ADVANCED_FILTERS = SERVER
            .comment("Show and allow the advanced rule-based filter mode. Off by default; the simple filter is always available. Live.")
            .define("advanced_filters", false);
    private static final ModConfigSpec.IntValue UPGRADE_BLOCKS = SERVER
            .comment("Maximum pipes changed by one sneak-use of a Pipe Upgrade. Live.")
            .defineInRange("network_upgrade_max_blocks", DEFAULT_UPGRADE_BLOCKS, 1, 4096);
    private static final ModConfigSpec.BooleanValue BRIDGING = SERVER
            .comment("Allow pipes of the bridging tier to join across a short air gap. Live.")
            .define("allow_bridging", true);
    private static final ModConfigSpec.BooleanValue MATERIALS = SERVER
            .comment("Allow blocks from the materials tag to restyle pipes. Live.")
            .define("allow_materials", true);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> MATERIAL_BLOCKLIST = SERVER
            .comment("Block ids that may never be used as a pipe material, on top of the datapack tag. Live.")
            .defineListAllowEmpty("material_blocklist", () -> List.of(), () -> "minecraft:stone",
                    value -> value instanceof String);
    private static final ModConfigSpec.BooleanValue DYE_CHANNELS = SERVER
            .comment("Pipes of different dye colours do not connect, so parallel lines can touch. Live.")
            .define("dye_channels", true);
    private static final ModConfigSpec.IntValue PAINT_BLOCKS = SERVER
            .comment("Maximum pipes styled by one use of a dye, glow ink sac, material block or water bucket. Live.")
            .defineInRange("paint_max_blocks", DEFAULT_PAINT_BLOCKS, 1, 4096);
    private static final ModConfigSpec.IntValue MAX_NODES = SERVER
            .comment("Maximum pipes visited when one endpoint discovers its network. Live.")
            .defineInRange("max_network_nodes", DEFAULT_MAX_NODES, 16, 32768);
    private static final ModConfigSpec.DoubleValue TICK_BUDGET = SERVER
            .comment("Endpoints defer work while the mean server tick time exceeds this many milliseconds. Live.")
            .defineInRange("tick_budget_ms", DEFAULT_TICK_BUDGET_MS, 1.0, 50.0);
    private static final ModConfigSpec.IntValue BACKOFF_CEILING = SERVER
            .comment("Longest idle interval in ticks for an endpoint that has nothing to move. Live.")
            .defineInRange("idle_backoff_max_ticks", DEFAULT_BACKOFF_CEILING, 4, 1200);
    private static final ModConfigSpec.IntValue REBUILD_BUDGET = SERVER
            .comment("Network discoveries allowed per level per tick. Live.")
            .defineInRange("rebuild_budget_per_tick", DEFAULT_REBUILD_BUDGET, 1, 256);
    private static final ModConfigSpec.BooleanValue REFUND = SERVER
            .comment("Dismantling a pipe returns the spent Pipe Upgrade. Live.")
            .define("refund_on_dismantle", true);
    private static final ModConfigSpec.BooleanValue TIER_SKIPPING = SERVER
            .comment("Allow a Pipe Upgrade to skip tiers. Live.")
            .define("allow_tier_skipping", true);
    private static final ModConfigSpec.BooleanValue GLOW = SERVER
            .comment("Allow pipes to be made luminous in the appearance tab. Live.")
            .define("allow_glow", true);
    private static final ModConfigSpec.BooleanValue GIVE_GUIDE = SERVER
            .comment("Give every player one Pipes Guide book the first time they join a world. Live.")
            .define("give_guide_on_first_join", true);
    private static final ModConfigSpec.IntValue COMMAND_LEVEL = SERVER
            .comment("Permission level required for /upipes. Applies to commands registered after the next world load.")
            .defineInRange("command_permission_level", DEFAULT_COMMAND_LEVEL, 0, 4);

    private static final ModConfigSpec.IntValue STATUS_REFRESH = CLIENT
            .comment("Client ticks between status line refreshes in the pipe screen. Live.")
            .defineInRange("status_refresh_ticks", DEFAULT_STATUS_REFRESH, 1, 200);

    public static final ModConfigSpec SERVER_SPEC = SERVER.build();
    public static final ModConfigSpec CLIENT_SPEC = CLIENT.build();

    private PipesConfig() {
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    public static boolean giveGuide() {
        return read(GIVE_GUIDE);
    }

    public static boolean entityTargets() {
        return read(ENTITY_TARGETS);
    }

    public static boolean advancedFilters() {
        return read(ADVANCED_FILTERS);
    }

    public static boolean tierGating() {
        return read(TIER_GATING);
    }

    public static boolean bridgingAllowed() {
        return read(BRIDGING);
    }

    public static boolean materialAllowed(String id) {
        return read(MATERIALS) && !read(MATERIAL_BLOCKLIST).contains(id);
    }

    public static boolean dyeChannels() {
        return read(DYE_CHANNELS);
    }

    public static int paintMaxBlocks() {
        return read(PAINT_BLOCKS);
    }

    public static int upgradeMaxBlocks() {
        return read(UPGRADE_BLOCKS);
    }

    public static int maxNetworkNodes() {
        return read(MAX_NODES);
    }

    public static double tickBudgetMs() {
        return read(TICK_BUDGET);
    }

    public static int idleBackoffMax() {
        return read(BACKOFF_CEILING);
    }

    public static int rebuildBudget() {
        return read(REBUILD_BUDGET);
    }

    public static boolean refundOnDismantle() {
        return read(REFUND);
    }

    public static boolean tierSkipping() {
        return read(TIER_SKIPPING);
    }

    public static boolean glowAllowed() {
        return read(GLOW);
    }

    public static int commandLevel() {
        return read(COMMAND_LEVEL);
    }

    public static int statusRefreshTicks() {
        return read(STATUS_REFRESH);
    }
}
