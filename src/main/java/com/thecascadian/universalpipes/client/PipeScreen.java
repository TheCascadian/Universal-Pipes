package com.thecascadian.universalpipes.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.Status;
import com.thecascadian.universalpipes.core.TransportType;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.filter.Expr;
import com.thecascadian.universalpipes.filter.FilterSet;
import com.thecascadian.universalpipes.menu.PipeMenu;
import com.thecascadian.universalpipes.net.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The one pipe screen: 176 pixels wide, 18 pixel slots, the standard inventory
 * position, extended in height only. Tabs choose the transport type, the two
 * view buttons choose between settings and filters. The client holds a working
 * copy of the configuration and sends the whole value after each edit; the
 * server sanitizes it and is the only authority.
 */
public class PipeScreen extends AbstractContainerScreen<PipeMenu> {

    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "panel");
    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "slot");

    private static final int TITLE = 0xFF404040;
    private static final int POSITIVE = 0xFF55FF55;
    private static final int NEGATIVE = 0xFFFF5555;
    private static final int VALUE = 0xFFFFAA00;
    private static final int SECONDARY = 0xFFAAAAAA;
    private static final int MUTED = 0xFF555555;
    private static final int SLOT_FILL = 0xFF8B8B8B;

    private static final int WIDTH = 176;
    private static final int BASE_HEIGHT = 166;
    private static final int LEFT = 8;
    private static final int FULL_WIDTH = 160;
    private static final int HALF_WIDTH = 76;
    private static final int RIGHT = 92;
    private static final int TAB_WIDTH = 52;
    private static final int TAB_STEP = 54;
    private static final int BUTTON_HEIGHT = 14;
    private static final int ROW_TABS = 18;
    private static final int ROW_VIEWS = 34;
    private static final int ROW_CONTENT = 54;
    private static final int SLOT_SIZE = 18;
    private static final int GHOST_COLUMNS = 9;
    private static final int RULE_ROWS = 3;
    private static final int ROW_HEIGHT = 12;
    private static final int ROW_LIST_Y = 70;
    private static final int NUMBER_MAX_LENGTH = 9;
    private static final int RULE_BUTTON_WIDTH = 38;
    private static final int RULE_BUTTON_STEP = 40;
    private static final int ROW_STATUS = 73;
    private static final int ROW_CYCLES = 84;
    private static final int ROW_LABELS = 103;
    private static final int ROW_BOXES = 113;
    private static final int ROW_COPY = 131;
    private static final int ROW_ORDER = 147;
    private static final int PRESENT_LIMIT_SLACK = 6;
    private static final int CLIPBOARD_LIMIT = 12000;

    private enum Picker { NONE, PRIORITY, SCOPE }

    private TransportType tab = TransportType.ITEM;
    private boolean filtersView;
    private Picker picker = Picker.NONE;
    private EndpointConfig config;
    private int selectedRule = -1;
    private int scroll;
    private Status shownStatus = Status.IDLE;
    private int refreshCounter;

    public PipeScreen(PipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = BASE_HEIGHT + PipeMenu.EXTENSION;
        this.inventoryLabelY = BASE_HEIGHT - 94 + PipeMenu.EXTENSION;
        this.config = menu.config();
    }

    @Override
    protected void init() {
        super.init();
        rebuild();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (++refreshCounter >= PipesConfig.statusRefreshTicks()) {
            refreshCounter = 0;
            shownStatus = menu.status();
        }
    }

    private void edit(UnaryOperator<EndpointConfig> change) {
        config = change.apply(config);
        PacketDistributor.sendToServer(new Payloads.ConfigUpdate(config));
    }

    private void editTransport(UnaryOperator<EndpointConfig.Transport> change) {
        edit(current -> current.withTransport(tab, change.apply(current.transport(tab))));
    }

    private void editFilter(UnaryOperator<FilterSet> change) {
        editTransport(transport -> transport.withFilter(change.apply(transport.filter())));
    }

    private void rebuild() {
        clearWidgets();
        for (TransportType type : TransportType.values()) {
            Button button = Button.builder(Component.translatable(type.translationKey()), pressed -> {
                tab = type;
                picker = Picker.NONE;
                selectedRule = -1;
                rebuild();
            }).bounds(leftPos + LEFT + type.ordinal() * TAB_STEP, topPos + ROW_TABS, TAB_WIDTH, BUTTON_HEIGHT).build();
            button.active = type != tab;
            addRenderableWidget(button);
        }
        addRenderableWidget(viewButton("view.settings", LEFT, false));
        addRenderableWidget(viewButton("view.filters", RIGHT, true));
        if (filtersView)
            addFilterWidgets();
        else
            addSettingWidgets();
    }

    private Button viewButton(String key, int x, boolean filters) {
        Button button = Button.builder(Component.translatable("gui." + UniversalPipes.MODID + "." + key), pressed -> {
            filtersView = filters;
            picker = Picker.NONE;
            rebuild();
        }).bounds(leftPos + x, topPos + ROW_VIEWS, HALF_WIDTH, BUTTON_HEIGHT).build();
        button.active = filtersView != filters;
        return button;
    }

    private void addSettingWidgets() {
        if (picker == Picker.PRIORITY) {
            addRenderableWidget(Button.builder(text("done"), pressed -> {
                picker = Picker.NONE;
                rebuild();
            }).bounds(leftPos + LEFT, topPos + ROW_LIST_Y + RULE_ROWS * ROW_HEIGHT + 2, FULL_WIDTH, BUTTON_HEIGHT)
                    .build());
            return;
        }
        EndpointConfig.Transport transport = config.transport(tab);
        addRenderableWidget(CycleButton.onOffBuilder(transport.enabled()).create(leftPos + LEFT, topPos + ROW_CONTENT,
                FULL_WIDTH, BUTTON_HEIGHT + 2, text("enabled"), (button, value) -> editTransport(t -> t.withEnabled(value))));
        addRenderableWidget(CycleButton.builder((EndpointConfig.Redstone value) -> text("redstone." + value.getSerializedName()))
                .withValues(EndpointConfig.Redstone.values()).withInitialValue(config.redstone())
                .create(leftPos + LEFT, topPos + ROW_CYCLES, HALF_WIDTH, BUTTON_HEIGHT + 2, text("redstone"),
                        (button, value) -> edit(c -> c.withRedstone(value))));
        addRenderableWidget(CycleButton.builder((EndpointConfig.Distribution value) -> text("distribution." + value.getSerializedName()))
                .withValues(EndpointConfig.Distribution.values()).withInitialValue(config.distribution())
                .create(leftPos + RIGHT, topPos + ROW_CYCLES, HALF_WIDTH, BUTTON_HEIGHT + 2, text("distribution"),
                        (button, value) -> {
                            edit(c -> c.withDistribution(value));
                            rebuild();
                        }));
        addRenderableWidget(numberBox(LEFT, ROW_BOXES, transport.keepInSource(), "keep",
                value -> editTransport(t -> t.withKeep(value))));
        addRenderableWidget(numberBox(RIGHT, ROW_BOXES, transport.stopAtDestination(), "stop",
                value -> editTransport(t -> t.withStop(value))));
        addRenderableWidget(Button.builder(text("copy"), pressed -> copy())
                .bounds(leftPos + LEFT, topPos + ROW_COPY, HALF_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(text("paste"), pressed -> paste())
                .bounds(leftPos + RIGHT, topPos + ROW_COPY, HALF_WIDTH, BUTTON_HEIGHT).build());
        if (config.distribution() == EndpointConfig.Distribution.PRIORITY) {
            addRenderableWidget(Button.builder(text("order"), pressed -> {
                picker = Picker.PRIORITY;
                scroll = 0;
                rebuild();
            }).bounds(leftPos + LEFT, topPos + ROW_ORDER, FULL_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    private EditBox numberBox(int x, int y, int initial, String key, java.util.function.IntConsumer onChange) {
        EditBox box = new EditBox(font, leftPos + x, topPos + y, HALF_WIDTH, BUTTON_HEIGHT, text(key));
        box.setMaxLength(NUMBER_MAX_LENGTH);
        box.setFilter(value -> value.chars().allMatch(Character::isDigit));
        box.setValue(Integer.toString(initial));
        box.setTooltip(Tooltip.create(text(key + ".tooltip")));
        box.setResponder(value -> onChange.accept(value.isEmpty() ? 0 : Integer.parseInt(value)));
        return box;
    }

    private void addFilterWidgets() {
        if (tab == TransportType.ENERGY)
            return;
        FilterSet filter = config.transport(tab).filter();
        addRenderableWidget(Button.builder(text(filter.advanced() ? "mode.advanced" : "mode.simple"), pressed -> {
            editFilter(f -> new FilterSet(!f.advanced(), f.whitelist(), f.entries(), f.firstMatch(), f.rules()));
            picker = Picker.NONE;
            rebuild();
        }).bounds(leftPos + LEFT, topPos + ROW_CONTENT, HALF_WIDTH, BUTTON_HEIGHT).build());
        if (!filter.advanced()) {
            addRenderableWidget(Button.builder(text(filter.whitelist() ? "whitelist" : "blacklist"), pressed -> {
                editFilter(f -> new FilterSet(f.advanced(), !f.whitelist(), f.entries(), f.firstMatch(), f.rules()));
                rebuild();
            }).bounds(leftPos + RIGHT, topPos + ROW_CONTENT, HALF_WIDTH, BUTTON_HEIGHT).build());
            return;
        }
        addRenderableWidget(Button.builder(text(filter.firstMatch() ? "first_match" : "all_match"), pressed -> {
            editFilter(f -> new FilterSet(f.advanced(), f.whitelist(), f.entries(), !f.firstMatch(), f.rules()));
            rebuild();
        }).bounds(leftPos + RIGHT, topPos + ROW_CONTENT, HALF_WIDTH, BUTTON_HEIGHT).build());
        addRuleEditor(filter);
    }

    private void addRuleEditor(FilterSet filter) {
        int buttonY = topPos + ROW_LIST_Y + RULE_ROWS * ROW_HEIGHT + 2;
        addRuleButton(0, buttonY, "rule.add", pressed -> {
            if (filter.rules().size() >= PipeData.tier(menu.tier()).maxRules())
                return;
            List<FilterSet.Rule> rules = new ArrayList<>(filter.rules());
            rules.add(new FilterSet.Rule("", true, List.of(), 0, true));
            selectedRule = rules.size() - 1;
            editFilter(f -> withRules(f, rules));
            rebuild();
        });
        addRuleButton(1, buttonY, "rule.remove", pressed -> {
            if (selectedRule < 0 || selectedRule >= filter.rules().size())
                return;
            List<FilterSet.Rule> rules = new ArrayList<>(filter.rules());
            rules.remove(selectedRule);
            selectedRule = Math.min(selectedRule, rules.size() - 1);
            editFilter(f -> withRules(f, rules));
            rebuild();
        });
        addRuleButton(2, buttonY, "rule.up", pressed -> moveRule(filter, -1));
        addRuleButton(3, buttonY, "rule.down", pressed -> moveRule(filter, 1));
        if (selectedRule < 0 || selectedRule >= filter.rules().size())
            return;
        FilterSet.Rule rule = filter.rules().get(selectedRule);
        int editorY = buttonY + BUTTON_HEIGHT + 2;
        EditBox expression = new EditBox(font, leftPos + LEFT, editorY, FULL_WIDTH, BUTTON_HEIGHT, text("expression"));
        expression.setMaxLength(PipeData.limits().maxExpressionLength());
        expression.setValue(rule.expression());
        expression.setTooltip(Tooltip.create(text("expression.tooltip")));
        int index = selectedRule;
        expression.setResponder(value -> updateRule(index, r -> new FilterSet.Rule(value, r.allow(), r.scope(), r.limit(), r.enabled())));
        addRenderableWidget(expression);
        int rowY = editorY + BUTTON_HEIGHT + 2;
        addRenderableWidget(CycleButton.booleanBuilder(text("rule.allow"), text("rule.deny")).withInitialValue(rule.allow())
                .create(leftPos + LEFT, rowY, 50, BUTTON_HEIGHT, text("rule.verdict"),
                        (button, value) -> updateRule(index, r -> new FilterSet.Rule(r.expression(), value, r.scope(), r.limit(), r.enabled()))));
        addRenderableWidget(CycleButton.booleanBuilder(text("rule.chosen"), text("rule.any")).withInitialValue(!rule.scope().isEmpty())
                .create(leftPos + LEFT + 52, rowY, 50, BUTTON_HEIGHT, text("rule.scope"), (button, value) -> {
                    picker = value ? Picker.SCOPE : Picker.NONE;
                    if (!value)
                        updateRule(index, r -> new FilterSet.Rule(r.expression(), r.allow(), List.of(), r.limit(), r.enabled()));
                    rebuild();
                }));
        addRenderableWidget(CycleButton.onOffBuilder(rule.enabled()).create(leftPos + LEFT + 104, rowY, 56,
                BUTTON_HEIGHT, text("rule.enabled"),
                (button, value) -> updateRule(index, r -> new FilterSet.Rule(r.expression(), r.allow(), r.scope(), r.limit(), value))));
        EditBox limit = new EditBox(font, leftPos + LEFT, rowY + BUTTON_HEIGHT + 2, 50, BUTTON_HEIGHT, text("rule.limit"));
        limit.setMaxLength(NUMBER_MAX_LENGTH);
        limit.setFilter(value -> value.chars().allMatch(Character::isDigit));
        limit.setValue(Integer.toString(rule.limit()));
        limit.setTooltip(Tooltip.create(text("rule.limit.tooltip")));
        limit.setResponder(value -> updateRule(index, r -> new FilterSet.Rule(r.expression(), r.allow(), r.scope(),
                value.isEmpty() ? 0 : Integer.parseInt(value), r.enabled())));
        addRenderableWidget(limit);
        addRenderableWidget(Button.builder(text("rule.targets"), pressed -> {
            picker = picker == Picker.SCOPE ? Picker.NONE : Picker.SCOPE;
            scroll = 0;
            rebuild();
        }).bounds(leftPos + LEFT + 52, rowY + BUTTON_HEIGHT + 2, 108, BUTTON_HEIGHT).build());
    }

    private void addRuleButton(int column, int y, String key, Button.OnPress action) {
        addRenderableWidget(Button.builder(text(key), action)
                .bounds(leftPos + LEFT + column * RULE_BUTTON_STEP, y, RULE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void moveRule(FilterSet filter, int delta) {
        int target = selectedRule + delta;
        if (selectedRule < 0 || target < 0 || target >= filter.rules().size())
            return;
        List<FilterSet.Rule> rules = new ArrayList<>(filter.rules());
        FilterSet.Rule moved = rules.remove(selectedRule);
        rules.add(target, moved);
        selectedRule = target;
        editFilter(f -> withRules(f, rules));
        rebuild();
    }

    private void updateRule(int index, UnaryOperator<FilterSet.Rule> change) {
        editFilter(f -> {
            if (index >= f.rules().size())
                return f;
            List<FilterSet.Rule> rules = new ArrayList<>(f.rules());
            rules.set(index, change.apply(rules.get(index)));
            return withRules(f, rules);
        });
    }

    private static FilterSet withRules(FilterSet f, List<FilterSet.Rule> rules) {
        return new FilterSet(f.advanced(), f.whitelist(), f.entries(), f.firstMatch(), rules);
    }

    private void copy() {
        EndpointConfig.CODEC.encodeStart(JsonOps.INSTANCE, config).result()
                .ifPresent(json -> minecraft.keyboardHandler.setClipboard(json.toString()));
    }

    private void paste() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text.length() > CLIPBOARD_LIMIT)
            return;
        try {
            JsonElement json = JsonParser.parseString(text);
            EndpointConfig.CODEC.parse(JsonOps.INSTANCE, json).result().ifPresent(parsed -> {
                edit(current -> parsed.sanitize(menu.tier()));
                rebuild();
            });
        } catch (RuntimeException notJson) {
            // Clipboard text that is not a configuration is ignored.
        }
    }

    private static Component text(String key) {
        return Component.translatable("gui." + UniversalPipes.MODID + "." + key);
    }

    private int ghostX(int index) {
        return leftPos + LEFT + index * SLOT_SIZE;
    }

    private int ghostY() {
        return topPos + ROW_CONTENT + 26;
    }

    private ItemStack displayStack(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null)
            return ItemStack.EMPTY;
        if (tab == TransportType.FLUID) {
            Fluid fluid = BuiltInRegistries.FLUID.get(location);
            Item bucket = fluid.getBucket();
            return bucket == null ? ItemStack.EMPTY : new ItemStack(bucket);
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(location));
    }

    private String idOf(ItemStack carried) {
        if (tab == TransportType.FLUID) {
            return FluidUtil.getFluidContained(carried).map(fluid -> BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString())
                    .orElse(null);
        }
        return BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
    }

    private boolean simpleFilterShown() {
        return filtersView && tab != TransportType.ENERGY && !config.transport(tab).filter().advanced();
    }

    private boolean advancedFilterShown() {
        return filtersView && tab != TransportType.ENERGY && config.transport(tab).filter().advanced();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;
        if (simpleFilterShown() && clickGhost(mouseX, mouseY, button))
            return true;
        if (advancedFilterShown() && picker == Picker.NONE && clickRuleRow(mouseX, mouseY))
            return true;
        return picker != Picker.NONE && clickDestination(mouseX, mouseY);
    }

    private boolean clickGhost(double mouseX, double mouseY, int button) {
        int slots = PipeData.tier(menu.tier()).filterSlots();
        for (int i = 0; i < Math.min(slots, GHOST_COLUMNS); i++) {
            if (mouseX < ghostX(i) || mouseX >= ghostX(i) + SLOT_SIZE || mouseY < ghostY() || mouseY >= ghostY() + SLOT_SIZE)
                continue;
            ItemStack carried = menu.getCarried();
            int index = i;
            if (carried.isEmpty() || button != 0) {
                editFilter(f -> {
                    List<String> entries = new ArrayList<>(f.entries());
                    if (index < entries.size())
                        entries.remove(index);
                    return new FilterSet(f.advanced(), f.whitelist(), entries, f.firstMatch(), f.rules());
                });
            } else {
                String id = idOf(carried);
                if (id != null) {
                    editFilter(f -> {
                        List<String> entries = new ArrayList<>(f.entries());
                        if (!entries.contains(id)) {
                            if (index < entries.size())
                                entries.set(index, id);
                            else
                                entries.add(id);
                        }
                        return new FilterSet(f.advanced(), f.whitelist(), entries, f.firstMatch(), f.rules());
                    });
                }
            }
            return true;
        }
        return false;
    }

    private boolean clickRuleRow(double mouseX, double mouseY) {
        List<FilterSet.Rule> rules = config.transport(tab).filter().rules();
        for (int row = 0; row < RULE_ROWS; row++) {
            int index = scroll + row;
            int y = topPos + ROW_LIST_Y + row * ROW_HEIGHT;
            if (index < rules.size() && mouseX >= leftPos + LEFT && mouseX < leftPos + LEFT + FULL_WIDTH && mouseY >= y
                    && mouseY < y + ROW_HEIGHT) {
                selectedRule = index;
                rebuild();
                return true;
            }
        }
        return false;
    }

    private boolean clickDestination(double mouseX, double mouseY) {
        List<BlockPos> targets = menu.destinations();
        for (int row = 0; row < RULE_ROWS; row++) {
            int index = scroll + row;
            int y = topPos + ROW_LIST_Y + row * ROW_HEIGHT;
            if (index >= targets.size() || mouseX < leftPos + LEFT || mouseX >= leftPos + LEFT + FULL_WIDTH || mouseY < y
                    || mouseY >= y + ROW_HEIGHT)
                continue;
            BlockPos target = targets.get(index);
            if (picker == Picker.PRIORITY) {
                edit(c -> c.withPriority(toggled(c.priority(), target)));
            } else if (selectedRule >= 0) {
                updateRule(selectedRule, r -> new FilterSet.Rule(r.expression(), r.allow(), toggled(r.scope(), target),
                        r.limit(), r.enabled()));
            }
            return true;
        }
        return false;
    }

    private static List<BlockPos> toggled(List<BlockPos> list, BlockPos target) {
        List<BlockPos> result = new ArrayList<>(list);
        if (!result.remove(target))
            result.add(target);
        return result;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int size = picker != Picker.NONE ? menu.destinations().size() : config.transport(tab).filter().rules().size();
        scroll = Math.max(0, Math.min(Math.max(0, size - RULE_ROWS), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blitSprite(PANEL, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots)
            graphics.blitSprite(SLOT, leftPos + slot.x - 1, topPos + slot.y - 1, SLOT_SIZE, SLOT_SIZE);
        if (simpleFilterShown())
            drawGhosts(graphics);
        if (picker != Picker.NONE)
            drawDestinations(graphics);
        else if (advancedFilterShown())
            drawRules(graphics);
    }

    private void drawGhosts(GuiGraphics graphics) {
        List<String> entries = config.transport(tab).filter().entries();
        int slots = Math.min(PipeData.tier(menu.tier()).filterSlots(), GHOST_COLUMNS);
        for (int i = 0; i < GHOST_COLUMNS; i++) {
            graphics.blitSprite(SLOT, ghostX(i), ghostY(), SLOT_SIZE, SLOT_SIZE);
            if (i >= slots) {
                graphics.fill(ghostX(i) + 1, ghostY() + 1, ghostX(i) + SLOT_SIZE - 1, ghostY() + SLOT_SIZE - 1, MUTED);
            } else if (i < entries.size()) {
                graphics.renderFakeItem(displayStack(entries.get(i)), ghostX(i) + 1, ghostY() + 1);
            }
        }
    }

    private void drawRules(GuiGraphics graphics) {
        List<FilterSet.Rule> rules = config.transport(tab).filter().rules();
        for (int row = 0; row < RULE_ROWS; row++) {
            int index = scroll + row;
            int y = topPos + ROW_LIST_Y + row * ROW_HEIGHT;
            graphics.fill(leftPos + LEFT, y, leftPos + LEFT + FULL_WIDTH, y + ROW_HEIGHT - 1, SLOT_FILL);
            if (index >= rules.size())
                continue;
            FilterSet.Rule rule = rules.get(index);
            int color = !rule.enabled() ? MUTED : !Expr.isValid(rule.expression()) ? NEGATIVE : rule.allow() ? POSITIVE : VALUE;
            String label = (rule.allow() ? "+ " : "- ") + rule.expression();
            graphics.drawString(font, font.plainSubstrByWidth(label, FULL_WIDTH - PRESENT_LIMIT_SLACK), leftPos + LEFT + 3,
                    y + 2, index == selectedRule ? TITLE : color, true);
        }
    }

    private void drawDestinations(GuiGraphics graphics) {
        List<BlockPos> targets = menu.destinations();
        List<BlockPos> chosen = picker == Picker.PRIORITY ? config.priority()
                : selectedRule >= 0 && selectedRule < config.transport(tab).filter().rules().size()
                        ? config.transport(tab).filter().rules().get(selectedRule).scope()
                        : List.of();
        for (int row = 0; row < RULE_ROWS; row++) {
            int index = scroll + row;
            int y = topPos + ROW_LIST_Y + row * ROW_HEIGHT;
            graphics.fill(leftPos + LEFT, y, leftPos + LEFT + FULL_WIDTH, y + ROW_HEIGHT - 1, SLOT_FILL);
            if (index >= targets.size())
                continue;
            BlockPos target = targets.get(index);
            int rank = chosen.indexOf(target);
            String label = (rank >= 0 ? (picker == Picker.PRIORITY ? (rank + 1) + ". " : "+ ") : "  ")
                    + target.getX() + " " + target.getY() + " " + target.getZ();
            graphics.drawString(font, label, leftPos + LEFT + 3, y + 2, rank >= 0 ? POSITIVE : SECONDARY, true);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TITLE, true);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TITLE, true);
        if (!filtersView && picker == Picker.PRIORITY) {
            graphics.drawString(font, text("order.hint"), LEFT, ROW_CONTENT + 4, SECONDARY, true);
        } else if (!filtersView) {
            graphics.drawString(font, text("keep"), LEFT, ROW_LABELS, SECONDARY, true);
            graphics.drawString(font, text("stop"), RIGHT, ROW_LABELS, SECONDARY, true);
            graphics.drawString(font, statusLine(), LEFT, ROW_STATUS, statusColor(), true);
        } else if (tab == TransportType.ENERGY) {
            graphics.drawString(font, text("energy.nofilter"), LEFT, ROW_CONTENT, MUTED, true);
        } else if (simpleFilterShown()) {
            int limit = PipeData.tier(menu.tier()).filterSlots();
            graphics.drawString(font, Component.translatable("gui.universal_pipes.slots", limit), LEFT, ROW_CONTENT + 18,
                    SECONDARY, true);
        } else if (picker == Picker.NONE) {
            int max = PipeData.tier(menu.tier()).maxRules();
            graphics.drawString(font, Component.translatable("gui.universal_pipes.rules",
                    config.transport(tab).filter().rules().size(), max), LEFT, ROW_CONTENT + 18, SECONDARY, true);
        }
    }

    private Component statusLine() {
        return Component.translatable(shownStatus.translationKey());
    }

    private int statusColor() {
        return switch (shownStatus) {
            case TRANSFERRED -> POSITIVE;
            case IDLE -> SECONDARY;
            default -> NEGATIVE;
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (simpleFilterShown()) {
            List<String> entries = config.transport(tab).filter().entries();
            for (int i = 0; i < Math.min(entries.size(), GHOST_COLUMNS); i++) {
                if (mouseX >= ghostX(i) && mouseX < ghostX(i) + SLOT_SIZE && mouseY >= ghostY()
                        && mouseY < ghostY() + SLOT_SIZE)
                    graphics.renderTooltip(font, displayStack(entries.get(i)), mouseX, mouseY);
            }
        }
        renderTooltip(graphics, mouseX, mouseY);
    }
}
