package com.thecascadian.universalpipes.filter;

import com.thecascadian.universalpipes.data.PipeData;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filter expression language shared by items and fluids.
 *
 * <pre>
 * expr  := or ;  or := and (('|' | 'or') and)* ;  and := not (('&amp;' | 'and') not)*
 * not   := ('!' | 'not') not | '(' expr ')' | atom
 * atom  := id | '#'tag | '@'namespace | '~'text | '?enchanted' | '?damaged'
 *        | '?durability=lo..hi' | '?has=component' | '?eq=component=snbt'
 * </pre>
 *
 * A regular-expression atom was rejected: it cannot be bounded in cost on the
 * server thread, and every pattern it would express is covered by the atoms
 * above. The grammar is parsed once per expression text, and results are cached
 * per item type and component set, both caches dropped on any data reload.
 */
public final class Expr {

    /** The view of a stack that atoms are evaluated against. */
    public interface Subject {
        ResourceLocation id();

        boolean inTag(ResourceLocation tag);

        String name();

        boolean enchanted();

        boolean damaged();

        int durabilityLeft();

        DataComponentMap components();

        Object typeKey();

        DataComponentPatch patch();
    }

    private enum Kind { ID, TAG, NAMESPACE, NAME, ENCHANTED, DAMAGED, DURABILITY, HAS, EQ }

    private sealed interface Node permits And, Or, Not, Atom {
    }

    private record And(List<Node> parts) implements Node {
    }

    private record Or(List<Node> parts) implements Node {
    }

    private record Not(Node inner) implements Node {
    }

    private record Atom(Kind kind, String text, ResourceLocation location, int low, int high) implements Node {
    }

    private record CacheKey(Expr expr, Object type, DataComponentPatch patch) {
    }

    public static final int MAX_ENTRY_LENGTH = 64;
    private static final int PARSE_CACHE = 256;
    private static final int RESULT_CACHE = 8192;
    private static final String DEFAULT_NAMESPACE = "minecraft";
    private static final Map<String, Object> PARSED = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > PARSE_CACHE;
        }
    };
    private static final Map<CacheKey, Boolean> RESULTS = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, Boolean> eldest) {
            return size() > RESULT_CACHE;
        }
    };

    private final Node root;
    private final String source;

    private Expr(Node root, String source) {
        this.root = root;
        this.source = source;
    }

    public static void clearCaches() {
        synchronized (PARSED) {
            PARSED.clear();
        }
        synchronized (RESULTS) {
            RESULTS.clear();
        }
    }

    /** Returns the compiled expression, or the error text when the source is invalid. */
    public static Object compile(String source) {
        synchronized (PARSED) {
            Object cached = PARSED.get(source);
            if (cached != null)
                return cached;
        }
        Object result;
        try {
            result = new Parser(source, PipeData.limits().maxExpressionLength(), PipeData.limits().maxExpressionDepth())
                    .parse();
        } catch (IllegalArgumentException invalid) {
            result = String.valueOf(invalid.getMessage());
        }
        synchronized (PARSED) {
            PARSED.put(source, result);
        }
        return result;
    }

    public static boolean isValid(String source) {
        return compile(source) instanceof Expr;
    }

    /** An expression that does not compile matches nothing. */
    public static boolean matches(String source, Subject subject) {
        return compile(source) instanceof Expr expr && expr.test(subject);
    }

    private boolean test(Subject subject) {
        CacheKey key = new CacheKey(this, subject.typeKey(), subject.patch());
        synchronized (RESULTS) {
            Boolean cached = RESULTS.get(key);
            if (cached != null)
                return cached;
        }
        boolean result = evaluate(root, subject);
        synchronized (RESULTS) {
            RESULTS.put(key, result);
        }
        return result;
    }

    @Override
    public String toString() {
        return source;
    }

    private static boolean evaluate(Node node, Subject subject) {
        return switch (node) {
            case And and -> and.parts().stream().allMatch(part -> evaluate(part, subject));
            case Or or -> or.parts().stream().anyMatch(part -> evaluate(part, subject));
            case Not not -> !evaluate(not.inner(), subject);
            case Atom atom -> evaluateAtom(atom, subject);
        };
    }

    private static boolean evaluateAtom(Atom atom, Subject subject) {
        return switch (atom.kind()) {
            case ID -> subject.id().equals(atom.location());
            case TAG -> subject.inTag(atom.location());
            case NAMESPACE -> subject.id().getNamespace().equals(atom.text());
            case NAME -> subject.name().toLowerCase(Locale.ROOT).contains(atom.text());
            case ENCHANTED -> subject.enchanted();
            case DAMAGED -> subject.damaged();
            case DURABILITY -> subject.durabilityLeft() >= atom.low() && subject.durabilityLeft() <= atom.high();
            case HAS -> hasComponent(subject.components(), atom.location());
            case EQ -> atom.text().equals(componentSnbt(subject.components(), atom.location()));
        };
    }

    private static boolean hasComponent(DataComponentMap map, ResourceLocation id) {
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
        return type != null && map.has(type);
    }

    private static String componentSnbt(DataComponentMap map, ResourceLocation id) {
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
        return type == null ? null : encode(map, type);
    }

    private static <T> String encode(DataComponentMap map, DataComponentType<T> type) {
        T value = map.get(type);
        if (value == null || type.codec() == null)
            return null;
        return type.codec().encodeStart(NbtOps.INSTANCE, value).result().map(Object::toString).orElse(null);
    }

    public static Subject subject(ItemStack stack) {
        return new Subject() {
            @Override
            public ResourceLocation id() {
                return BuiltInRegistries.ITEM.getKey(stack.getItem());
            }

            @Override
            public boolean inTag(ResourceLocation tag) {
                return stack.is(TagKey.create(Registries.ITEM, tag));
            }

            @Override
            public String name() {
                return stack.getHoverName().getString();
            }

            @Override
            public boolean enchanted() {
                return stack.isEnchanted();
            }

            @Override
            public boolean damaged() {
                return stack.isDamaged();
            }

            @Override
            public int durabilityLeft() {
                return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : -1;
            }

            @Override
            public DataComponentMap components() {
                return stack.getComponents();
            }

            @Override
            public Object typeKey() {
                return stack.getItem();
            }

            @Override
            public DataComponentPatch patch() {
                return stack.getComponentsPatch();
            }
        };
    }

    public static Subject subject(FluidStack stack) {
        return new Subject() {
            @Override
            public ResourceLocation id() {
                return BuiltInRegistries.FLUID.getKey(stack.getFluid());
            }

            @Override
            public boolean inTag(ResourceLocation tag) {
                return stack.getFluid().is(TagKey.create(Registries.FLUID, tag));
            }

            @Override
            public String name() {
                return stack.getHoverName().getString();
            }

            @Override
            public boolean enchanted() {
                return false;
            }

            @Override
            public boolean damaged() {
                return false;
            }

            @Override
            public int durabilityLeft() {
                return -1;
            }

            @Override
            public DataComponentMap components() {
                return stack.getComponents();
            }

            @Override
            public Object typeKey() {
                return stack.getFluid();
            }

            @Override
            public DataComponentPatch patch() {
                return stack.getComponentsPatch();
            }
        };
    }

    private static final class Parser {
        private final String text;
        private final int maxDepth;
        private int index;

        Parser(String text, int maxLength, int maxDepth) {
            if (text.length() > maxLength)
                throw new IllegalArgumentException("Expression is longer than " + maxLength + " characters");
            this.text = text;
            this.maxDepth = maxDepth;
        }

        Expr parse() {
            Node node = parseOr(0);
            skipSpace();
            if (index < text.length())
                throw new IllegalArgumentException("Unexpected text at position " + index);
            return new Expr(node, text);
        }

        private Node parseOr(int depth) {
            List<Node> parts = new ArrayList<>();
            parts.add(parseAnd(depth));
            while (consumeOperator('|', "or"))
                parts.add(parseAnd(depth));
            return parts.size() == 1 ? parts.get(0) : new Or(parts);
        }

        private Node parseAnd(int depth) {
            List<Node> parts = new ArrayList<>();
            parts.add(parseNot(depth));
            while (consumeOperator('&', "and"))
                parts.add(parseNot(depth));
            return parts.size() == 1 ? parts.get(0) : new And(parts);
        }

        private Node parseNot(int depth) {
            skipSpace();
            if (consumeOperator('!', "not")) {
                return new Not(parseNot(checkDepth(depth + 1)));
            }
            if (peek() == '(') {
                index++;
                Node inner = parseOr(checkDepth(depth + 1));
                skipSpace();
                if (peek() != ')')
                    throw new IllegalArgumentException("Missing closing parenthesis");
                index++;
                return inner;
            }
            return parseAtom();
        }

        private int checkDepth(int depth) {
            if (depth > maxDepth)
                throw new IllegalArgumentException("Nesting deeper than " + maxDepth);
            return depth;
        }

        private boolean consumeOperator(char symbol, String word) {
            skipSpace();
            if (peek() == symbol) {
                index++;
                return true;
            }
            int end = index + word.length();
            if (text.regionMatches(true, index, word, 0, word.length())
                    && (end >= text.length() || Character.isWhitespace(text.charAt(end)) || text.charAt(end) == '(')) {
                index = end;
                return true;
            }
            return false;
        }

        private Node parseAtom() {
            skipSpace();
            char first = peek();
            if (first == '#')
                return locationAtom(Kind.TAG, 1);
            if (first == '@') {
                index++;
                return new Atom(Kind.NAMESPACE, readWord(), null, 0, 0);
            }
            if (first == '~') {
                index++;
                String needle = readValue().toLowerCase(Locale.ROOT);
                if (needle.isEmpty())
                    throw new IllegalArgumentException("Empty name text");
                return new Atom(Kind.NAME, needle, null, 0, 0);
            }
            if (first == '?')
                return parseQuery();
            return locationAtom(Kind.ID, 0);
        }

        private Node locationAtom(Kind kind, int skip) {
            index += skip;
            return new Atom(kind, "", location(readWord()), 0, 0);
        }

        private Node parseQuery() {
            index++;
            int start = index;
            while (index < text.length() && Character.isLetter(text.charAt(index)))
                index++;
            String key = text.substring(start, index);
            String value = "";
            if (peek() == '=') {
                index++;
                value = readValue();
            }
            switch (key) {
                case "enchanted":
                    return new Atom(Kind.ENCHANTED, "", null, 0, 0);
                case "damaged":
                    return new Atom(Kind.DAMAGED, "", null, 0, 0);
                case "durability": {
                    int split = value.indexOf("..");
                    if (split < 0)
                        throw new IllegalArgumentException("Durability needs lo..hi");
                    try {
                        return new Atom(Kind.DURABILITY, "", null, Integer.parseInt(value.substring(0, split)),
                                Integer.parseInt(value.substring(split + 2)));
                    } catch (NumberFormatException bad) {
                        throw new IllegalArgumentException("Durability range is not a number");
                    }
                }
                case "has":
                    return new Atom(Kind.HAS, "", location(value), 0, 0);
                case "eq": {
                    int split = value.indexOf('=');
                    if (split < 0)
                        throw new IllegalArgumentException("Component match needs component=snbt");
                    return new Atom(Kind.EQ, value.substring(split + 1), location(value.substring(0, split)), 0, 0);
                }
                default:
                    throw new IllegalArgumentException("Unknown query " + key);
            }
        }

        private ResourceLocation location(String raw) {
            String value = raw.indexOf(':') < 0 ? DEFAULT_NAMESPACE + ":" + raw : raw;
            ResourceLocation parsed = ResourceLocation.tryParse(value.toLowerCase(Locale.ROOT));
            if (parsed == null)
                throw new IllegalArgumentException("Not a valid identifier: " + raw);
            return parsed;
        }

        private String readWord() {
            if (peek() == '"')
                return readValue();
            int start = index;
            while (index < text.length() && !isDelimiter(text.charAt(index)))
                index++;
            if (start == index)
                throw new IllegalArgumentException("Expected a value at position " + index);
            return text.substring(start, index);
        }

        private String readValue() {
            if (peek() != '"')
                return readWord();
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < text.length() && text.charAt(index) != '"') {
                if (text.charAt(index) == '\\' && index + 1 < text.length())
                    index++;
                builder.append(text.charAt(index++));
            }
            if (index >= text.length())
                throw new IllegalArgumentException("Unterminated quotation");
            index++;
            return builder.toString();
        }

        private static boolean isDelimiter(char c) {
            return Character.isWhitespace(c) || c == ')' || c == '(' || c == '&' || c == '|';
        }

        private void skipSpace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index)))
                index++;
        }

        private char peek() {
            return index < text.length() ? text.charAt(index) : '\0';
        }
    }
}
