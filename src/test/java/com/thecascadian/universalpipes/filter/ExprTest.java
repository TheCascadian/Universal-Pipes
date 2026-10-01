package com.thecascadian.universalpipes.filter;

import com.thecascadian.universalpipes.data.PipeData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprTest {

    private static final TestSubject STONE = TestSubject.of("minecraft:stone");
    private static final TestSubject MODDED = TestSubject.of("mod:thing");

    @AfterEach
    void restoreDefaults() {
        PipeData.applySynced("{}");
    }

    private static void limits(int length, int depth) {
        PipeData.applySynced("{\"limits\":{\"max_expression_length\":" + length + ",\"max_expression_depth\":" + depth
                + "}}");
    }

    @Test
    void idMatchesExactlyAndDefaultsToMinecraftNamespace() {
        assertTrue(Expr.matches("minecraft:stone", STONE));
        assertTrue(Expr.matches("stone", STONE));
        assertFalse(Expr.matches("dirt", STONE));
        assertFalse(Expr.matches("other:stone", STONE));
        assertTrue(Expr.matches("mod:thing", MODDED));
    }

    @Test
    void tagMatchesMembership() {
        TestSubject log = TestSubject.of("minecraft:oak_log").withTag("minecraft:logs");
        assertTrue(Expr.matches("#minecraft:logs", log));
        assertTrue(Expr.matches("#logs", log));
        assertFalse(Expr.matches("#minecraft:planks", log));
        assertFalse(Expr.matches("#minecraft:logs", STONE));
    }

    @Test
    void namespaceMatchesAndIsCaseSensitive() {
        assertTrue(Expr.matches("@mod", MODDED));
        assertFalse(Expr.matches("@minecraft", MODDED));
        assertTrue(Expr.matches("@minecraft", STONE));
        assertFalse(Expr.matches("@Mod", MODDED));
    }

    @Test
    void nameIsASubstringMatchIgnoringCase() {
        TestSubject ingot = TestSubject.of("minecraft:iron_ingot").withName("Iron Ingot");
        assertTrue(Expr.matches("~iron", ingot));
        assertTrue(Expr.matches("~IRON", ingot));
        assertTrue(Expr.matches("~\"iron ingot\"", ingot));
        assertFalse(Expr.matches("~gold", ingot));
        assertFalse(Expr.isValid("~\"\""));
        assertFalse(Expr.isValid("~"));
    }

    @Test
    void enchantedAndDamagedQueries() {
        assertTrue(Expr.matches("?enchanted", STONE.withEnchanted(true)));
        assertFalse(Expr.matches("?enchanted", STONE));
        assertTrue(Expr.matches("?damaged", STONE.withDamaged(true)));
        assertFalse(Expr.matches("?damaged", STONE));
    }

    @Test
    void durabilityRange() {
        assertTrue(Expr.matches("?durability=1..100", STONE.withDurability(50)));
        assertTrue(Expr.matches("?durability=1..100", STONE.withDurability(100)));
        assertFalse(Expr.matches("?durability=1..100", STONE.withDurability(150)));
        assertFalse(Expr.matches("?durability=1..100", STONE.withDurability(-1)));
        assertFalse(Expr.isValid("?durability=5"));
        assertFalse(Expr.isValid("?durability=a..b"));
        assertFalse(Expr.isValid("?durability"));
    }

    @Test
    void componentQueriesCompile() {
        assertTrue(Expr.isValid("?has=minecraft:enchantments"));
        assertTrue(Expr.isValid("?eq=minecraft:damage=5"));
        assertFalse(Expr.isValid("?eq=minecraft:damage"));
        assertFalse(Expr.isValid("?bogus"));
    }

    @Test
    void andBindsTighterThanOr() {
        TestSubject subject = STONE.withEnchanted(true);
        assertTrue(Expr.matches("?enchanted | ?damaged & @mod", subject));
        assertFalse(Expr.matches("(?enchanted | ?damaged) & @mod", subject));
    }

    @Test
    void notBindsTighterThanAnd() {
        assertFalse(Expr.matches("!?enchanted & ?damaged", STONE));
        assertTrue(Expr.matches("!(?enchanted & ?damaged)", STONE));
    }

    @Test
    void wordOperatorsAreEquivalentToSymbols() {
        TestSubject subject = STONE.withEnchanted(true);
        assertTrue(Expr.matches("?enchanted and not ?damaged", subject));
        assertTrue(Expr.matches("?damaged OR ?enchanted", subject));
        assertFalse(Expr.matches("NOT ?enchanted", subject));
    }

    @Test
    void operatorWordsInsideIdsAreNotOperators() {
        assertTrue(Expr.matches("orange_dye", TestSubject.of("minecraft:orange_dye")));
        assertTrue(Expr.matches("andesite", TestSubject.of("minecraft:andesite")));
        assertTrue(Expr.matches("notch_apple", TestSubject.of("minecraft:notch_apple")));
    }

    @Test
    void brackets() {
        assertTrue(Expr.matches("(stone)", STONE));
        assertTrue(Expr.matches("((stone))", STONE));
        assertFalse(Expr.isValid("(stone"));
        assertFalse(Expr.isValid("stone)"));
        assertFalse(Expr.isValid("()"));
    }

    @Test
    void caseIsIgnoredForIdsTagsAndOperators() {
        assertTrue(Expr.matches("MINECRAFT:STONE", STONE));
        TestSubject log = TestSubject.of("minecraft:oak_log").withTag("minecraft:logs");
        assertTrue(Expr.matches("#MINECRAFT:LOGS", log));
    }

    @Test
    void lengthLimitFromLimitsJson() {
        limits(16, 6);
        assertTrue(Expr.isValid("x".repeat(16)));
        assertFalse(Expr.isValid("x".repeat(17)));
    }

    @Test
    void depthLimitFromLimitsJson() {
        limits(128, 2);
        assertTrue(Expr.isValid("((stone))"));
        assertFalse(Expr.isValid("(((stone)))"));
        assertTrue(Expr.isValid("!!stone"));
        assertFalse(Expr.isValid("!!!stone"));
    }

    @Test
    void defaultDepthLimitIsSix() {
        assertTrue(Expr.isValid("(".repeat(6) + "stone" + ")".repeat(6)));
        assertFalse(Expr.isValid("(".repeat(7) + "stone" + ")".repeat(7)));
    }

    @Test
    void emptyAndMalformedInputFailsCleanly() {
        String[] bad = { "", "   ", "&", "|", "!", "stone &", "| stone", "stone stone", "#", "@", "a$b", "~\"open",
                "(", ")", "stone &&", "?" };
        for (String source : bad) {
            assertDoesNotThrow(() -> Expr.compile(source), source);
            assertFalse(Expr.isValid(source), source);
            assertFalse(Expr.matches(source, STONE), source);
            assertInstanceOf(String.class, Expr.compile(source), source);
        }
    }

    @Test
    void randomInputNeverThrows() {
        String alphabet = "ab:#@~?!&|() \"\\=.1,or and not enchanted";
        Random random = new Random(12345);
        for (int i = 0; i < 5000; i++) {
            StringBuilder builder = new StringBuilder();
            int length = random.nextInt(40);
            for (int j = 0; j < length; j++)
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String source = builder.toString();
            assertDoesNotThrow(() -> Expr.matches(source, STONE), source);
        }
    }
}
