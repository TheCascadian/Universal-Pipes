package com.thecascadian.universalpipes.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.filter.Expr;
import com.thecascadian.universalpipes.filter.FilterSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything a player can configure on one extraction face. It is an immutable
 * value so that the screen, the network payload, the clipboard text and the
 * saved data all use the same codec, and so that the server can sanitize a
 * received copy without mutating anything it already holds.
 */
public record EndpointConfig(Redstone redstone, Distribution distribution, List<BlockPos> priority,
        Transport items, Transport fluids, Transport energy, boolean crossChannels) {

    public enum Redstone implements StringRepresentable {
        IGNORE, ON, OFF;

        public static final Codec<Redstone> CODEC = StringRepresentable.fromEnum(Redstone::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Distribution implements StringRepresentable {
        ROUND_ROBIN, NEAREST_FIRST, FURTHEST_FIRST, RANDOM, PRIORITY;

        public static final Codec<Distribution> CODEC = StringRepresentable.fromEnum(Distribution::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Per transport type settings. The stock limits are units for items and
     * millibuckets for fluids, and percentages for energy. Zero disables a limit.
     */
    public record Transport(boolean enabled, int keepInSource, int stopAtDestination, FilterSet filter) {

        public static final Codec<Transport> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("enabled", false).forGetter(Transport::enabled),
                Codec.INT.optionalFieldOf("keep_in_source", 0).forGetter(Transport::keepInSource),
                Codec.INT.optionalFieldOf("stop_at_destination", 0).forGetter(Transport::stopAtDestination),
                FilterSet.CODEC.optionalFieldOf("filter", FilterSet.EMPTY).forGetter(Transport::filter))
                .apply(i, Transport::new));

        public Transport withEnabled(boolean value) {
            return new Transport(value, keepInSource, stopAtDestination, filter);
        }

        public Transport withKeep(int value) {
            return new Transport(enabled, value, stopAtDestination, filter);
        }

        public Transport withStop(int value) {
            return new Transport(enabled, keepInSource, value, filter);
        }

        public Transport withFilter(FilterSet value) {
            return new Transport(enabled, keepInSource, stopAtDestination, value);
        }
    }

    public static final Codec<EndpointConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Redstone.CODEC.optionalFieldOf("redstone", Redstone.IGNORE).forGetter(EndpointConfig::redstone),
            Distribution.CODEC.optionalFieldOf("distribution", Distribution.ROUND_ROBIN)
                    .forGetter(EndpointConfig::distribution),
            BlockPos.CODEC.listOf().optionalFieldOf("priority", List.of()).forGetter(EndpointConfig::priority),
            Transport.CODEC.optionalFieldOf("items", new Transport(true, 0, 0, FilterSet.EMPTY))
                    .forGetter(EndpointConfig::items),
            Transport.CODEC.optionalFieldOf("fluids", new Transport(false, 0, 0, FilterSet.EMPTY))
                    .forGetter(EndpointConfig::fluids),
            Transport.CODEC.optionalFieldOf("energy", new Transport(false, 0, 0, FilterSet.EMPTY))
                    .forGetter(EndpointConfig::energy),
            Codec.BOOL.optionalFieldOf("cross_channels", false).forGetter(EndpointConfig::crossChannels))
            .apply(i, EndpointConfig::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, EndpointConfig> STREAM_CODEC = ByteBufCodecs
            .fromCodecWithRegistries(CODEC);

    /** Builds a fresh configuration from the datapack defaults. */
    public static EndpointConfig fromDefaults() {
        PipeData.Defaults defaults = PipeData.defaults();
        return new EndpointConfig(
                parse(Redstone.values(), defaults.redstone(), Redstone.IGNORE),
                parse(Distribution.values(), defaults.distribution(), Distribution.ROUND_ROBIN),
                List.of(),
                new Transport(defaults.items(), 0, 0, FilterSet.EMPTY),
                new Transport(defaults.fluids(), 0, 0, FilterSet.EMPTY),
                new Transport(defaults.energy(), 0, 0, FilterSet.EMPTY), false);
    }

    private static <E extends Enum<E> & StringRepresentable> E parse(E[] values, String name, E fallback) {
        for (E value : values) {
            if (value.getSerializedName().equals(name))
                return value;
        }
        return fallback;
    }

    public EndpointConfig withRedstone(Redstone value) {
        return new EndpointConfig(value, distribution, priority, items, fluids, energy, crossChannels);
    }

    public EndpointConfig withDistribution(Distribution value) {
        return new EndpointConfig(redstone, value, priority, items, fluids, energy, crossChannels);
    }

    public EndpointConfig withCrossChannels(boolean value) {
        return new EndpointConfig(redstone, distribution, priority, items, fluids, energy, value);
    }

    public EndpointConfig withPriority(List<BlockPos> value) {
        return new EndpointConfig(redstone, distribution, value, items, fluids, energy, crossChannels);
    }

    public EndpointConfig withItems(Transport value) {
        return new EndpointConfig(redstone, distribution, priority, value, fluids, energy, crossChannels);
    }

    public EndpointConfig withFluids(Transport value) {
        return new EndpointConfig(redstone, distribution, priority, items, value, energy, crossChannels);
    }

    public EndpointConfig withEnergy(Transport value) {
        return new EndpointConfig(redstone, distribution, priority, items, fluids, value, crossChannels);
    }

    public Transport transport(TransportType type) {
        return switch (type) {
            case ITEM -> items;
            case FLUID -> fluids;
            case ENERGY -> energy;
        };
    }

    public EndpointConfig withTransport(TransportType type, Transport value) {
        return switch (type) {
            case ITEM -> withItems(value);
            case FLUID -> withFluids(value);
            case ENERGY -> withEnergy(value);
        };
    }

    /**
     * Returns a copy that obeys the datapack limits for the given tier: lists are
     * truncated, numbers clamped and over-long expressions cut. Server side
     * validation applies this to every received copy rather than trusting the
     * client. Invalid expressions are kept, because an expression that does not
     * compile simply matches nothing and the player can see and fix it.
     */
    public EndpointConfig sanitize(int tier) {
        PipeData.Limits limits = PipeData.limits();
        PipeData.TierSpec spec = PipeData.tier(tier);
        List<BlockPos> order = priority.size() > limits.maxScopeTargets()
                ? new ArrayList<>(priority.subList(0, limits.maxScopeTargets()))
                : priority;
        return new EndpointConfig(redstone, distribution, order,
                sanitize(items, limits, spec, false),
                sanitize(fluids, limits, spec, false),
                sanitize(energy, limits, spec, true), crossChannels);
    }

    /**
     * The configuration as it acts at a given tier when feature gating is on. It is
     * applied when running and never written back, so a pipe that is upgraded later
     * regains what the player had configured instead of losing it.
     */
    public EndpointConfig gated(int tier) {
        PipeData.Limits limits = PipeData.limits();
        boolean advanced = tier >= limits.minTierAdvancedFilters();
        boolean stock = tier >= limits.minTierStockLimits();
        return new EndpointConfig(redstone, distribution, priority, gated(items, advanced, stock),
                gated(fluids, advanced, stock), gated(energy, advanced, stock), crossChannels);
    }

    private static Transport gated(Transport transport, boolean advanced, boolean stock) {
        FilterSet filter = transport.filter();
        if (!advanced && filter.advanced())
            filter = new FilterSet(false, filter.whitelist(), filter.entries(), filter.firstMatch(), List.of());
        else if (!stock) {
            List<FilterSet.Rule> rules = new ArrayList<>();
            for (FilterSet.Rule rule : filter.rules())
                rules.add(new FilterSet.Rule(rule.expression(), rule.allow(), rule.scope(), 0, rule.enabled()));
            filter = new FilterSet(filter.advanced(), filter.whitelist(), filter.entries(), filter.firstMatch(), rules);
        }
        return stock ? new Transport(transport.enabled(), transport.keepInSource(), transport.stopAtDestination(), filter)
                : new Transport(transport.enabled(), 0, 0, filter);
    }

    private static Transport sanitize(Transport transport, PipeData.Limits limits, PipeData.TierSpec spec,
            boolean percent) {
        int ceiling = percent ? 100 : limits.maxStock();
        return new Transport(transport.enabled(), clamp(transport.keepInSource(), ceiling),
                clamp(transport.stopAtDestination(), ceiling), sanitize(transport.filter(), limits, spec));
    }

    private static FilterSet sanitize(FilterSet filter, PipeData.Limits limits, PipeData.TierSpec spec) {
        List<String> entries = new ArrayList<>();
        for (String entry : filter.entries()) {
            if (entries.size() >= spec.filterSlots())
                break;
            if (entry.length() <= Expr.MAX_ENTRY_LENGTH)
                entries.add(entry);
        }
        List<FilterSet.Rule> rules = new ArrayList<>();
        for (FilterSet.Rule rule : filter.rules()) {
            if (rules.size() >= spec.maxRules())
                break;
            String expression = rule.expression().length() > limits.maxExpressionLength()
                    ? rule.expression().substring(0, limits.maxExpressionLength())
                    : rule.expression();
            List<BlockPos> scope = rule.scope().size() > limits.maxScopeTargets()
                    ? new ArrayList<>(rule.scope().subList(0, limits.maxScopeTargets()))
                    : rule.scope();
            rules.add(new FilterSet.Rule(expression, rule.allow(), scope, clamp(rule.limit(), limits.maxStock()),
                    rule.enabled()));
        }
        return new FilterSet(filter.advanced(), filter.whitelist(), entries, filter.firstMatch(), rules);
    }

    private static int clamp(int value, int ceiling) {
        return Math.max(0, Math.min(ceiling, value));
    }
}
