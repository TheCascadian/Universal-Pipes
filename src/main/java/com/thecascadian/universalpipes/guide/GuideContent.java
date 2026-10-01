package com.thecascadian.universalpipes.guide;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.data.PipeData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of the in-game guide book. Most chapters are fixed lang text, and the
 * tier chapter is assembled from the loaded tier data, so the book always
 * matches the datapack in use.
 */
public final class GuideContent {

    /** One chapter: a title and paragraphs that the screen wraps and paginates. */
    public record Chapter(Component title, List<Component> paragraphs) {
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
