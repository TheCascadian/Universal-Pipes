package com.thecascadian.universalpipes.filter;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilterSetTest {

    private static final BlockPos HERE = new BlockPos(0, 0, 0);
    private static final BlockPos THERE = new BlockPos(5, 0, 0);
    private static final TestSubject STONE = TestSubject.of("minecraft:stone");
    private static final TestSubject DIRT = TestSubject.of("minecraft:dirt");
    private static final TestSubject MODDED = TestSubject.of("mod:thing");

    private static FilterSet.Rule rule(String expression, boolean allow) {
        return new FilterSet.Rule(expression, allow, List.of(), 0, true);
    }

    private static FilterSet.Rule limited(String expression, int limit) {
        return new FilterSet.Rule(expression, true, List.of(), limit, true);
    }

    private static FilterSet advanced(boolean firstMatch, FilterSet.Rule... rules) {
        return new FilterSet(true, true, List.of(), firstMatch, List.of(rules));
    }

    @Test
    void denialConstantIsNegative() {
        assertEquals(-1, FilterSet.DENIED);
    }

    @Test
    void emptyFilterAllowsEverything() {
        assertEquals(FilterSet.UNLIMITED, FilterSet.EMPTY.limitFor(STONE, HERE));
    }

    @Test
    void simpleWhitelistAllowsOnlyListedIds() {
        FilterSet filter = new FilterSet(false, true, List.of("minecraft:stone"), true, List.of());
        assertEquals(FilterSet.UNLIMITED, filter.limitFor(STONE, HERE));
        assertEquals(FilterSet.DENIED, filter.limitFor(DIRT, HERE));
    }

    @Test
    void simpleBlacklistDeniesOnlyListedIds() {
        FilterSet filter = new FilterSet(false, false, List.of("minecraft:stone"), true, List.of());
        assertEquals(FilterSet.DENIED, filter.limitFor(STONE, HERE));
        assertEquals(FilterSet.UNLIMITED, filter.limitFor(DIRT, HERE));
    }

    @Test
    void simpleListWithoutEntriesIsInactiveInBothModes() {
        assertEquals(FilterSet.UNLIMITED, new FilterSet(false, true, List.of(), true, List.of()).limitFor(STONE, HERE));
        assertEquals(FilterSet.UNLIMITED, new FilterSet(false, false, List.of(), true, List.of()).limitFor(STONE, HERE));
    }

    @Test
    void simpleModeIgnoresRulesAndAdvancedModeIgnoresEntries() {
        FilterSet simple = new FilterSet(false, true, List.of("minecraft:stone"), true,
                List.of(rule("dirt", false)));
        assertEquals(FilterSet.UNLIMITED, simple.limitFor(STONE, HERE));
        assertEquals(FilterSet.DENIED, simple.limitFor(DIRT, HERE));
        FilterSet advanced = new FilterSet(true, true, List.of("minecraft:stone"), true, List.of(rule("dirt", true)));
        assertEquals(FilterSet.DENIED, advanced.limitFor(STONE, HERE));
        assertEquals(FilterSet.UNLIMITED, advanced.limitFor(DIRT, HERE));
    }

    @Test
    void advancedWithoutRulesAllowsEverything() {
        assertEquals(FilterSet.UNLIMITED, advanced(true).limitFor(STONE, HERE));
    }

    @Test
    void advancedRefusesWhenNoRuleMatches() {
        assertEquals(FilterSet.DENIED, advanced(true, rule("dirt", true)).limitFor(STONE, HERE));
        assertEquals(FilterSet.DENIED, advanced(false, rule("dirt", true)).limitFor(STONE, HERE));
    }

    @Test
    void ruleLimitIsReturnedAndZeroMeansUnlimited() {
        assertEquals(8, advanced(true, limited("stone", 8)).limitFor(STONE, HERE));
        assertEquals(FilterSet.UNLIMITED, advanced(true, limited("stone", 0)).limitFor(STONE, HERE));
    }

    @Test
    void firstMatchTakesTheFirstMatchingRule() {
        TestSubject subject = MODDED.withEnchanted(true);
        assertEquals(4, advanced(true, limited("?enchanted", 4), rule("@mod", false)).limitFor(subject, HERE));
        assertEquals(FilterSet.DENIED, advanced(true, rule("@mod", false), limited("?enchanted", 4))
                .limitFor(subject, HERE));
    }

    @Test
    void allMatchLetsAnyMatchingDenyWin() {
        TestSubject subject = MODDED.withEnchanted(true);
        assertEquals(FilterSet.DENIED, advanced(false, limited("?enchanted", 4), rule("@mod", false))
                .limitFor(subject, HERE));
        assertEquals(FilterSet.DENIED, advanced(false, rule("@mod", false), limited("?enchanted", 4))
                .limitFor(subject, HERE));
    }

    @Test
    void allMatchTakesTheLargestLimitFirstMatchTheFirst() {
        TestSubject subject = STONE.withEnchanted(true).withDamaged(true);
        FilterSet.Rule small = limited("?enchanted", 4);
        FilterSet.Rule large = limited("?damaged", 10);
        assertEquals(10, advanced(false, small, large).limitFor(subject, HERE));
        assertEquals(4, advanced(true, small, large).limitFor(subject, HERE));
    }

    @Test
    void allMatchWithUnlimitedRuleIsUnlimited() {
        TestSubject subject = STONE.withEnchanted(true);
        assertEquals(FilterSet.UNLIMITED, advanced(false, limited("?enchanted", 4), limited("stone", 0))
                .limitFor(subject, HERE));
    }

    @Test
    void disabledRulesAreSkipped() {
        FilterSet.Rule off = new FilterSet.Rule("@mod", false, List.of(), 0, false);
        assertEquals(FilterSet.UNLIMITED, advanced(true, off, rule("@mod", true)).limitFor(MODDED, HERE));
    }

    @Test
    void scopeRestrictsARuleToChosenTargets() {
        FilterSet.Rule scoped = new FilterSet.Rule("stone", true, List.of(HERE), 0, true);
        FilterSet filter = advanced(true, scoped);
        assertEquals(FilterSet.UNLIMITED, filter.limitFor(STONE, HERE));
        assertEquals(FilterSet.DENIED, filter.limitFor(STONE, THERE));
    }

    @Test
    void scopedDenyOnlyAppliesInScope() {
        FilterSet.Rule scopedDeny = new FilterSet.Rule("stone", false, List.of(THERE), 0, true);
        FilterSet filter = advanced(true, scopedDeny, rule("stone", true));
        assertEquals(FilterSet.DENIED, filter.limitFor(STONE, THERE));
        assertEquals(FilterSet.UNLIMITED, filter.limitFor(STONE, HERE));
    }

    @Test
    void emptyScopeCoversEveryTarget() {
        FilterSet filter = advanced(true, rule("stone", true));
        assertEquals(FilterSet.UNLIMITED, filter.limitFor(STONE, HERE));
        assertEquals(FilterSet.UNLIMITED, filter.limitFor(STONE, THERE));
    }

    @Test
    void invalidExpressionMatchesNothing() {
        assertEquals(FilterSet.DENIED, advanced(true, rule("((", true)).limitFor(STONE, HERE));
        assertEquals(FilterSet.UNLIMITED, advanced(true, rule("((", false), rule("stone", true)).limitFor(STONE, HERE));
    }
}
