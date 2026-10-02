package com.thecascadian.universalpipes.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.filter.Expr;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reloadable tuning data. Three JSON files are read from
 * data/&lt;namespace&gt;/universalpipes/: tiers, defaults and limits. A file from
 * a namespace other than this mod's wins over the bundled one. Every value is
 * clamped on load, and a missing value falls back to the built-in default, so a
 * partial or malformed file can never produce an unusable tier.
 */
public final class PipeData extends SimpleJsonResourceReloadListener {

    public static final int TIER_COUNT = 5;
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "universalpipes";
    private static final String TIERS = "tiers";
    private static final String DEFAULTS = "defaults";
    private static final String LIMITS = "limits";

    private static final int[] ITEMS_PER_OP = { 1, 4, 16, 32, 64 };
    private static final int[] INTERVAL_TICKS = { 4, 4, 2, 1, 1 };
    private static final int[] FLUID_PER_OP = { 50, 200, 800, 2000, 8000 };
    private static final int[] ENERGY_PER_TICK = { 128, 512, 2048, 8192, 32768 };
    private static final int[] FILTER_SLOTS = { 3, 5, 7, 9, 9 };
    private static final int[] MAX_RULES = { 2, 4, 8, 16, 32 };

    private static final int MAX_ITEMS_PER_OP = 4096;
    private static final int MAX_INTERVAL = 1200;
    private static final int MAX_FLUID_PER_OP = 1_000_000;
    private static final int MAX_ENERGY_PER_TICK = 100_000_000;
    private static final int SIMPLE_SLOTS_CAP = 9;
    private static final int RULES_CAP = 64;
    private static final int EXPRESSION_LENGTH_CAP = 512;
    private static final int EXPRESSION_DEPTH_CAP = 16;
    private static final int SCOPE_CAP = 64;
    private static final int STOCK_CAP = 100_000_000;
    private static final int DEFAULT_EXPRESSION_LENGTH = 128;
    private static final int DEFAULT_EXPRESSION_DEPTH = 6;
    private static final int DEFAULT_SCOPE = 16;
    private static final int DEFAULT_STOCK_CAP = 1_000_000;
    private static final int DEFAULT_BRIDGE_TIER = 4;
    private static final int DEFAULT_BRIDGE_GAP = 2;
    private static final int BRIDGE_GAP_CAP = 8;

    private static volatile Snapshot current = Snapshot.parse(new JsonObject());

    public static final PipeData LISTENER = new PipeData();

    public record TierSpec(int itemsPerOp, int intervalTicks, int fluidPerOp, int energyPerTick, int filterSlots,
            int maxRules) {

        private static final int TICKS_PER_SECOND = 20;

        /** Rate of a per operation amount in units per second at this tier's interval. */
        public long perSecond(int amount) {
            return (long) amount * TICKS_PER_SECOND / intervalTicks;
        }

        /** Energy is already stated per tick, so only the tick rate applies. */
        public long energyPerSecond() {
            return (long) energyPerTick * TICKS_PER_SECOND;
        }
    }

    /** Feature table: the tier a feature needs once tier_feature_gating is on. */
    public record Limits(int maxExpressionLength, int maxExpressionDepth, int maxScopeTargets, int maxStock,
            int minTierFluids, int minTierEnergy, int minTierAdvancedFilters, int minTierStockLimits,
            int minTierBridging, int bridgeGap) {
    }

    public record Defaults(String redstone, String distribution, boolean items, boolean fluids, boolean energy) {
    }

    public record Snapshot(JsonObject raw, List<TierSpec> tiers, Limits limits, Defaults defaults) {

        static Snapshot parse(JsonObject root) {
            JsonObject tiersRoot = object(root, TIERS);
            JsonArray array = tiersRoot.has(TIERS) && tiersRoot.get(TIERS).isJsonArray()
                    ? tiersRoot.getAsJsonArray(TIERS)
                    : new JsonArray();
            List<TierSpec> tiers = new ArrayList<>();
            for (int i = 0; i < TIER_COUNT; i++) {
                JsonObject entry = i < array.size() && array.get(i).isJsonObject() ? array.get(i).getAsJsonObject()
                        : new JsonObject();
                tiers.add(new TierSpec(
                        number(entry, "items_per_operation", ITEMS_PER_OP[i], 1, MAX_ITEMS_PER_OP),
                        number(entry, "interval_ticks", INTERVAL_TICKS[i], 1, MAX_INTERVAL),
                        number(entry, "fluid_mb_per_operation", FLUID_PER_OP[i], 1, MAX_FLUID_PER_OP),
                        number(entry, "energy_per_tick", ENERGY_PER_TICK[i], 1, MAX_ENERGY_PER_TICK),
                        number(entry, "filter_slots", FILTER_SLOTS[i], 0, SIMPLE_SLOTS_CAP),
                        number(entry, "max_rules", MAX_RULES[i], 0, RULES_CAP)));
            }
            JsonObject limitsRoot = object(root, LIMITS);
            JsonObject minTier = object(limitsRoot, "min_tier");
            Limits limits = new Limits(
                    number(limitsRoot, "max_expression_length", DEFAULT_EXPRESSION_LENGTH, 8, EXPRESSION_LENGTH_CAP),
                    number(limitsRoot, "max_expression_depth", DEFAULT_EXPRESSION_DEPTH, 1, EXPRESSION_DEPTH_CAP),
                    number(limitsRoot, "max_scope_targets", DEFAULT_SCOPE, 1, SCOPE_CAP),
                    number(limitsRoot, "max_stock", DEFAULT_STOCK_CAP, 1, STOCK_CAP),
                    number(minTier, "fluids", 1, 1, TIER_COUNT),
                    number(minTier, "energy", 1, 1, TIER_COUNT),
                    number(minTier, "advanced_filters", 1, 1, TIER_COUNT),
                    number(minTier, "stock_limits", 1, 1, TIER_COUNT),
                    number(minTier, "bridging", DEFAULT_BRIDGE_TIER, 1, TIER_COUNT),
                    number(limitsRoot, "bridge_gap", DEFAULT_BRIDGE_GAP, 1, BRIDGE_GAP_CAP));
            JsonObject defaultsRoot = object(root, DEFAULTS);
            Defaults defaults = new Defaults(
                    text(defaultsRoot, "redstone", "ignore"),
                    text(defaultsRoot, "distribution", "round_robin"),
                    flag(defaultsRoot, "items", true),
                    flag(defaultsRoot, "fluids", false),
                    flag(defaultsRoot, "energy", false));
            return new Snapshot(root, List.copyOf(tiers), limits, defaults);
        }
    }

    private PipeData() {
        super(GSON, DIRECTORY);
    }

    public static Snapshot current() {
        return current;
    }

    public static TierSpec tier(int tier) {
        return current.tiers().get(Math.max(1, Math.min(tier, TIER_COUNT)) - 1);
    }

    public static Limits limits() {
        return current.limits();
    }

    public static Defaults defaults() {
        return current.defaults();
    }

    /** Applies the JSON a server sent, using the same parser and clamps as the local reload. */
    public static void applySynced(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed.isJsonObject())
            install(parsed.getAsJsonObject());
    }

    public static String toSyncJson() {
        return current.raw().toString();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        JsonObject root = new JsonObject();
        for (String name : List.of(TIERS, DEFAULTS, LIMITS)) {
            JsonElement chosen = null;
            for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
                if (!file.getKey().getPath().equals(name))
                    continue;
                if (chosen == null || !file.getKey().getNamespace().equals(UniversalPipes.MODID))
                    chosen = file.getValue();
            }
            if (chosen != null && chosen.isJsonObject())
                root.add(name, chosen);
        }
        install(root);
    }

    private static void install(JsonObject root) {
        current = Snapshot.parse(root);
        Expr.clearCaches();
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static int number(JsonObject object, String key, int fallback, int min, int max) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isNumber())
            return fallback;
        return Math.max(min, Math.min(max, object.get(key).getAsInt()));
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static boolean flag(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isBoolean()
                ? object.get(key).getAsBoolean()
                : fallback;
    }
}
