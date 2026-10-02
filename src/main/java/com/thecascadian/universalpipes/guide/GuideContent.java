package com.thecascadian.universalpipes.guide;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of the in-game guide book. Most chapters are fixed lang text, and the
 * tier chapter is assembled from the loaded tier data, so the book always
 * matches the datapack in use.
 */
public final class GuideContent {

    /**
     * One chapter: a title and paragraphs that the screen wraps and paginates,
     * followed by one page per crafting recipe.
     */
    public record Chapter(Component title, List<Component> paragraphs, List<Recipe> recipes) {
        public Chapter(Component title, List<Component> paragraphs) {
            this(title, paragraphs, List.of());
        }
    }

    /** A shaped recipe for display: nine grid cells (empty stacks are blank), its result and a caption. */
    public record Recipe(List<ItemStack> grid, ItemStack result, Component name, Component caption) {
    }

    private static final String KEY = "guide." + UniversalPipes.MODID + ".";
    private static final String[] SIMPLE = { "intro", "placing", "wrench", "endpoints", "filters", "appearance",
            "commands" };
    private static final int[] SIMPLE_PARAGRAPHS = { 3, 3, 3, 3, 3, 3, 2 };

    private GuideContent() {
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    private static Component heading(Component title) {
        return title.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    public static List<Chapter> build() {
        List<Chapter> chapters = new ArrayList<>();
        chapters.add(recipes());
        for (int i = 0; i < SIMPLE.length; i++) {
            List<Component> paragraphs = new ArrayList<>();
            for (int p = 1; p <= SIMPLE_PARAGRAPHS[i]; p++)
                paragraphs.add(text(SIMPLE[i] + "." + p));
            chapters.add(new Chapter(text("chapter." + SIMPLE[i]), paragraphs));
            if (i == 3)
                chapters.add(tiers());
        }
        return chapters;
    }

    /**
     * The recipes are drawn from the items themselves, so the book needs no
     * artwork. Each ring recipe uses the ingot of its tier around redstone.
     */
    private static Chapter recipes() {
        ItemStack none = ItemStack.EMPTY;
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        List<Recipe> recipes = new ArrayList<>();
        recipes.add(new Recipe(List.of(iron, iron, iron, none, none, none, iron, iron, iron),
                new ItemStack(RegistryHandler.PIPE_ITEM.get(), 8), text("recipes.pipe.name"), text("recipes.pipe")));
        recipes.add(new Recipe(List.of(iron, none, iron, iron, iron, iron, none, iron, none),
                new ItemStack(RegistryHandler.PIPE_WRENCH.get()), text("recipes.wrench.name"),
                text("recipes.wrench")));
        ItemStack[] rings = { new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.GOLD_INGOT),
                new ItemStack(Items.NETHERITE_INGOT), new ItemStack(Items.NETHERITE_BLOCK) };
        for (int i = 0; i < rings.length; i++) {
            int tier = i + 2;
            ItemStack centre = tier == 5 ? new ItemStack(Items.NETHER_STAR) : redstone;
            ItemStack ring = rings[i];
            recipes.add(new Recipe(List.of(none, ring, none, ring, centre, ring, none, ring, none),
                    PipeUpgrade.create(tier), text("recipes.upgrade.name", tier), text("recipes.upgrade", tier)));
        }
        recipes.add(new Recipe(List.of(new ItemStack(Items.BOOK), iron, none, none, none, none, none, none, none),
                new ItemStack(RegistryHandler.GUIDE_BOOK.get()), text("recipes.guide.name"), text("recipes.guide")));
        List<Component> intro = List.of(text("recipes.intro"));
        return new Chapter(text("chapter.recipes"), intro, recipes);
    }

    private static Chapter tiers() {
        List<Component> paragraphs = new ArrayList<>();
        paragraphs.add(text("tiers.1"));
        paragraphs.add(text("tiers.2"));
        for (int tier = 1; tier <= PipeData.TIER_COUNT; tier++) {
            PipeData.TierSpec spec = PipeData.tier(tier);
            paragraphs.add(heading(text("tiers.name", tier)));
            paragraphs.add(text("tiers.items", spec.itemsPerOp(), spec.perSecond(spec.itemsPerOp())));
            paragraphs.add(text("tiers.fluids", spec.fluidPerOp(), spec.perSecond(spec.fluidPerOp())));
            paragraphs.add(text("tiers.energy", spec.energyPerSecond()));
            paragraphs.add(text("tiers.filters", spec.filterSlots(), spec.maxRules()));
        }
        return new Chapter(text("chapter.tiers"), paragraphs);
    }
}
