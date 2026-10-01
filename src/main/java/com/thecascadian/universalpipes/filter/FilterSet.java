package com.thecascadian.universalpipes.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One data model behind both filter views. The simple view edits the mode flag,
 * the list type and the entries. The advanced view edits the ordered rule list.
 * A filter with nothing in its active list is inactive and allows everything,
 * so a fresh endpoint never blocks transfers until the player configures it.
 */
public record FilterSet(boolean advanced, boolean whitelist, List<String> entries, boolean firstMatch,
        List<Rule> rules) {

    public static final int DENIED = -1;
    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static final FilterSet EMPTY = new FilterSet(false, true, List.of(), true, List.of());

    public record Rule(String expression, boolean allow, List<BlockPos> scope, int limit, boolean enabled) {

        public static final Codec<Rule> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("expression").forGetter(Rule::expression),
                Codec.BOOL.optionalFieldOf("allow", true).forGetter(Rule::allow),
                BlockPos.CODEC.listOf().optionalFieldOf("scope", List.of()).forGetter(Rule::scope),
                Codec.INT.optionalFieldOf("limit", 0).forGetter(Rule::limit),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Rule::enabled)).apply(i, Rule::new));

        boolean covers(BlockPos destination) {
            return scope.isEmpty() || scope.contains(destination);
        }
    }

    public static final Codec<FilterSet> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("advanced", false).forGetter(FilterSet::advanced),
            Codec.BOOL.optionalFieldOf("whitelist", true).forGetter(FilterSet::whitelist),
            Codec.STRING.listOf().optionalFieldOf("entries", List.of()).forGetter(FilterSet::entries),
            Codec.BOOL.optionalFieldOf("first_match", true).forGetter(FilterSet::firstMatch),
            Rule.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(FilterSet::rules))
            .apply(i, FilterSet::new));

    /**
     * Returns DENIED, or the most units that may move to the destination in one
     * operation (UNLIMITED when the matching rule sets no limit).
     */
    public int limitFor(Expr.Subject subject, BlockPos destination) {
        return advanced ? advancedLimit(subject, destination) : simpleLimit(subject);
    }

    private int simpleLimit(Expr.Subject subject) {
        if (entries.isEmpty())
            return UNLIMITED;
        boolean listed = entries.contains(subject.id().toString());
        return listed == whitelist ? UNLIMITED : DENIED;
    }

    private int advancedLimit(Expr.Subject subject, BlockPos destination) {
        if (rules.isEmpty())
            return UNLIMITED;
        int best = DENIED;
        for (Rule rule : rules) {
            if (!rule.enabled() || !rule.covers(destination) || !Expr.matches(rule.expression(), subject))
                continue;
            if (!rule.allow())
                return DENIED;
            int cap = rule.limit() <= 0 ? UNLIMITED : rule.limit();
            if (firstMatch)
                return cap;
            best = Math.max(best, cap);
        }
        return best;
    }
}
