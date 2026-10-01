package com.thecascadian.universalpipes.client;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.guide.GuideContent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The guide book. A chapter list sits on the left and the chosen chapter is wrapped
 * and split into pages on the right, with page buttons and mouse wheel paging.
 */
public class GuideScreen extends Screen {

    private static final int WIDTH = 300;
    private static final int HEIGHT = 196;
    private static final int LIST_WIDTH = 104;
    private static final int LINE_HEIGHT = 10;
    private static final int PAGE_LINES = 15;

    private static final int PAPER = 0xFFE9DCBC;
    private static final int PAPER_EDGE = 0xFF8C6B3F;
    private static final int LIST_BG = 0xFF5B4327;
    private static final int TEXT = 0xFF2B2015;

    private final List<GuideContent.Chapter> chapters;
    private List<List<FormattedCharSequence>> pages = List.of();
    private int chapter;
    private int page;
    private Button previous;
    private Button next;

    public GuideScreen(List<GuideContent.Chapter> chapters) {
        super(Component.translatable("item." + UniversalPipes.MODID + ".guide"));
        this.chapters = chapters;
    }

    private int left() {
        return (width - WIDTH) / 2;
    }

    private int top() {
        return (height - HEIGHT) / 2;
    }

    @Override
    protected void init() {
        int x = left() + 6;
        int buttonHeight = Math.min(16, (HEIGHT - 12) / Math.max(1, chapters.size()));
        for (int i = 0; i < chapters.size(); i++) {
            int index = i;
            addRenderableWidget(Button.builder(chapters.get(i).title(), button -> select(index))
                    .bounds(x, top() + 6 + i * buttonHeight, LIST_WIDTH - 10, buttonHeight - 1).build());
        }
        int bottom = top() + HEIGHT - 22;
        int pageX = left() + LIST_WIDTH + 6;
        previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> turn(-1))
                .bounds(pageX, bottom, 22, 16).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), button -> turn(1))
                .bounds(left() + WIDTH - 28, bottom, 22, 16).build());
        select(chapter);
    }

    private void select(int index) {
        chapter = Math.max(0, Math.min(index, chapters.size() - 1));
        page = 0;
        paginate();
        updateButtons();
    }

    private void paginate() {
        int textWidth = WIDTH - LIST_WIDTH - 24;
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component paragraph : chapters.get(chapter).paragraphs()) {
            lines.addAll(font.split(paragraph, textWidth));
            lines.add(FormattedCharSequence.EMPTY);
        }
        List<List<FormattedCharSequence>> result = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += PAGE_LINES) {
            result.add(lines.subList(start, Math.min(lines.size(), start + PAGE_LINES)));
        }
        if (result.isEmpty())
            result.add(List.of());
        pages = result;
    }

    private void turn(int delta) {
        int target = Math.max(0, Math.min(page + delta, pages.size() - 1));
        if (target != page) {
            page = target;
            updateButtons();
        }
    }

    private void updateButtons() {
        if (previous != null)
            previous.active = page > 0;
        if (next != null)
            next.active = page < pages.size() - 1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        turn(scrollY < 0 ? 1 : -1);
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = left();
        int top = top();

        graphics.fill(left - 2, top - 2, left + WIDTH + 2, top + HEIGHT + 2, PAPER_EDGE);
        graphics.fill(left, top, left + LIST_WIDTH, top + HEIGHT, LIST_BG);
        graphics.fill(left + LIST_WIDTH, top, left + WIDTH, top + HEIGHT, PAPER);

        int textX = left + LIST_WIDTH + 8;
        int y = top + 8;
        for (FormattedCharSequence line : pages.get(page)) {
            graphics.drawString(font, line, textX, y, TEXT, false);
            y += LINE_HEIGHT;
        }

        Component counter = Component.literal((page + 1) + " / " + pages.size());
        graphics.drawString(font, counter, left + LIST_WIDTH + (WIDTH - LIST_WIDTH) / 2 - font.width(counter) / 2,
                top + HEIGHT - 18, TEXT, false);
    }
}
