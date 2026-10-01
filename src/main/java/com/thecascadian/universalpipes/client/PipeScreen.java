package com.thecascadian.universalpipes.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.Appearance;
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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * The one pipe screen: 176 pixels wide, 18 pixel slots, the standard inventory
 * position, extended in height only. Three views (settings, filters, look)
 * share one row of tabs, and each view is a few rows of controls. Nothing needs
 * a slot to be picked up first: clicking an item in the inventory adds it to the
 * filter or sets the material, and a carried item may be dropped on the panel.
 * The client holds a working copy of the configuration and sends the whole
 * value after each edit; the server sanitizes it and is the only authority.
 */
public class PipeScreen extends AbstractContainerScreen<PipeMenu> {

    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "panel");
    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "slot");

    private static final int TITLE = 0xFF404040;
    private static final int POSITIVE = 0xFF006600;
    private static final int NEGATIVE = 0xFFAA0000;
    private static final int DENY = 0xFF8A4B00;
    private static final int SECONDARY = 0xFF555555;
    private static final int DISABLED = 0xFF6E6E6E;
    private static final int ROW_FILL = 0xFF8B8B8B;
    private static final int ROW_SELECTED = 0xFFDADADA;
    private static final int OPAQUE = 0xFF000000;

    private static final int WIDTH = 176;
    private static final int BASE_HEIGHT = 166;
    private static final int LEFT = 8;
    private static final int FULL_WIDTH = 160;
    private static final int HALF_WIDTH = 78;
    private static final int RIGHT = 90;
    private static final int TAB_WIDTH = 52;
    private static final int TAB_STEP = 54;
    private static final int BUTTON_HEIGHT = 14;
    private static final int ROW_TABS = 16;
    private static final int ROW_1 = 34;
    private static final int ROW_2 = 52;
    private static final int ROW_3 = 70;
    private static final int ROW_4 = 88;
    private static final int ROW_5 = 106;
    private static final int SLOT_SIZE = 18;
    private static final int GHOST_COLUMNS = 9;
    private static final int TEXT_INSET = 3;
    private static final int TEXT_DROP = 4;

    private static final int RULE_ROWS = 3;
    private static final int PICKER_ROWS = 6;
    private static final int LIST_ROW_HEIGHT = 12;
    private static final int RULE_BUTTONS_Y = 108;
    private static final int RULE_EXPRESSION_Y = 126;
    private static final int RULE_CONTROLS_Y = 144;
    private static final int PICKER_BUTTONS_Y = 128;
    private static final int RULE_BUTTON_WIDTH = 38;
    private static final int RULE_BUTTON_STEP = 40;
    private static final int VERDICT_WIDTH = 50;
    private static final int SCOPE_WIDTH = 56;
    private static final int LIMIT_WIDTH = 50;
    private static final int CONTROL_GAP = 2;
    private static final int NUMBER_MAX_LENGTH = 9;
    private static final int CLIPBOARD_LIMIT = 12000;

    private static final int SWATCH_SIZE = 16;
    private static final int SWATCH_STEP = 20;
    private static final int SWATCH_COLUMNS = 8;
    private static final int LOOK_PAINT_Y = 78;
    private static final int LOOK_MATERIAL_Y = 98;
    private static final int LOOK_GLOW_Y = 122;
    private static final int MATERIAL_LABEL = 22;
    private static final int CLEAR_WIDTH = 44;
    private static final DyeColor[] DYES = DyeColor.values();

    private enum Picker { NONE, PRIORITY, SCOPE }

    private enum View { SETTINGS, FILTERS, LOOK }

    private TransportType tab = TransportType.ITEM;
    private View view = View.SETTINGS;
    private Picker picker = Picker.NONE;
    private boolean accentTarget;
    private EndpointConfig config;
    private Appearance look;
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
        this.look = menu.appearance();
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

    private void send() {
        PacketDistributor.sendToServer(new Payloads.ConfigUpdate(config, look));
    }

    private void edit(UnaryOperator<EndpointConfig> change) {
        config = change.apply(config);
        send();
    }

    private void editLook(UnaryOperator<Appearance> change) {
        look = change.apply(look);
        send();
    }

    private void editTransport(UnaryOperator<EndpointConfig.Transport> change) {
        edit(current -> current.withTransport(tab, change.apply(current.transport(tab))));
    }

    private void editFilter(UnaryOperator<FilterSet> change) {
        editTransport(transport -> transport.withFilter(change.apply(transport.filter())));
    }

    private static Component text(String key) {
        return Component.translatable("gui." + UniversalPipes.MODID + "." + key);
    }

    private int x(int offset) {
        return leftPos + offset;
    }

    private int y(int offset) {
        return topPos + offset;
    }

    private void rebuild() {
        clearWidgets();
        for (View target : View.values())
            addRenderableWidget(viewButton(target));
        if (picker != Picker.NONE) {
            addPickerWidgets();
            return;
        }
        switch (view) {
            case SETTINGS -> addSettingWidgets();
            case FILTERS -> addFilterWidgets();
            case LOOK -> addLookWidgets();
        }
    }

    private Button viewButton(View target) {
        Button button = Button.builder(text("view." + target.name().toLowerCase(Locale.ROOT)), pressed -> {
            view = target;
            picker = Picker.NONE;
            rebuild();
        }).bounds(x(LEFT + target.ordinal() * TAB_STEP), y(ROW_TABS), TAB_WIDTH, BUTTON_HEIGHT).build();
        button.active = view != target;
        return button;
    }

    private CycleButton<TransportType> typeCycle() {
        return CycleButton.builder((TransportType type) -> Component.translatable(type.translationKey()))
                .withValues(TransportType.values()).withInitialValue(tab).displayOnlyValue()
                .create(x(LEFT), y(ROW_1), HALF_WIDTH, BUTTON_HEIGHT, text("type"), (button, value) -> {
                    tab = value;
                    selectedRule = -1;
                    scroll = 0;
                    rebuild();
                });
    }

    private void addSettingWidgets() {
        EndpointConfig.Transport transport = config.transport(tab);
        addRenderableWidget(typeCycle());
        addRenderableWidget(CycleButton.booleanBuilder(text("transfer.on"), text("transfer.off")).displayOnlyValue()
                .withInitialValue(transport.enabled()).create(x(RIGHT), y(ROW_1), HALF_WIDTH, BUTTON_HEIGHT,
                        text("transfer"), (button, value) -> editTransport(t -> t.withEnabled(value))));
        addRenderableWidget(CycleButton.builder((EndpointConfig.Redstone value) -> text("redstone." + value.getSerializedName()))
                .withValues(EndpointConfig.Redstone.values()).withInitialValue(config.redstone()).displayOnlyValue()
                .withTooltip(value -> Tooltip.create(text("redstone.tooltip")))
                .create(x(LEFT), y(ROW_2), HALF_WIDTH, BUTTON_HEIGHT, text("redstone"),
                        (button, value) -> edit(c -> c.withRedstone(value))));
        addRenderableWidget(CycleButton.builder((EndpointConfig.Distribution value) -> text("distribution." + value.getSerializedName()))
                .withValues(EndpointConfig.Distribution.values()).withInitialValue(config.distribution()).displayOnlyValue()
                .withTooltip(value -> Tooltip.create(text("distribution.tooltip")))
                .create(x(RIGHT), y(ROW_2), HALF_WIDTH, BUTTON_HEIGHT, text("distribution"), (button, value) -> {
                    edit(c -> c.withDistribution(value));
                    rebuild();
                }));
        addRenderableWidget(numberBox(LEFT, ROW_3, transport.keepInSource(), "keep",
                value -> editTransport(t -> t.withKeep(value))));
        addRenderableWidget(numberBox(RIGHT, ROW_3, transport.stopAtDestination(), "stop",
                value -> editTransport(t -> t.withStop(value))));
        addRenderableWidget(Button.builder(text("copy"), pressed -> copy())
                .bounds(x(LEFT), y(ROW_4), HALF_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(text("paste"), pressed -> paste())
                .bounds(x(RIGHT), y(ROW_4), HALF_WIDTH, BUTTON_HEIGHT).build());
        if (config.distribution() == EndpointConfig.Distribution.PRIORITY) {
            addRenderableWidget(Button.builder(text("order"), pressed -> {
                picker = Picker.PRIORITY;
                scroll = 0;
                rebuild();
            }).bounds(x(LEFT), y(ROW_5), FULL_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    private EditBox numberBox(int offset, int row, int initial, String key, IntConsumer onChange) {
        EditBox box = new EditBox(font, x(offset), y(row), HALF_WIDTH, BUTTON_HEIGHT, text(key));
        box.setMaxLength(NUMBER_MAX_LENGTH);
        box.setFilter(value -> value.chars().allMatch(Character::isDigit));
        box.setHint(text(key + ".hint"));
        box.setValue(initial == 0 ? "" : Integer.toString(initial));
        box.setTooltip(Tooltip.create(text(key + ".tooltip")));
        box.setResponder(value -> onChange.accept(value.isEmpty() ? 0 : Integer.parseInt(value)));
        return box;
    }

    private void addFilterWidgets() {
        addRenderableWidget(typeCycle());
        if (tab == TransportType.ENERGY)
            return;
        FilterSet filter = config.transport(tab).filter();
        addRenderableWidget(Button.builder(text(filter.advanced() ? "mode.advanced" : "mode.simple"), pressed -> {
            editFilter(f -> new FilterSet(!f.advanced(), f.whitelist(), f.entries(), f.firstMatch(), f.rules()));
            selectedRule = -1;
            rebuild();
        }).bounds(x(RIGHT), y(ROW_1), HALF_WIDTH, BUTTON_HEIGHT).build());
        if (!filter.advanced()) {
            addRenderableWidget(Button.builder(text(filter.whitelist() ? "whitelist" : "blacklist"), pressed -> {
                editFilter(f -> new FilterSet(f.advanced(), !f.whitelist(), f.entries(), f.firstMatch(), f.rules()));
                rebuild();
            }).bounds(x(LEFT), y(ROW_2), FULL_WIDTH, BUTTON_HEIGHT).build());
            return;
        }
        addRenderableWidget(Button.builder(text(filter.firstMatch() ? "first_match" : "all_match"), pressed -> {
            editFilter(f -> new FilterSet(f.advanced(), f.whitelist(), f.entries(), !f.firstMatch(), f.rules()));
            rebuild();
        }).bounds(x(LEFT), y(ROW_2), FULL_WIDTH, BUTTON_HEIGHT).build());
        addRuleEditor(filter);
    }

    private void addRuleEditor(FilterSet filter) {
        addRuleButton(0, "rule.add", pressed -> addRule(""));
        addRuleButton(1, "rule.remove", pressed -> {
            if (selectedRule < 0 || selectedRule >= filter.rules().size())
                return;
            List<FilterSet.Rule> rules = new ArrayList<>(filter.rules());
            rules.remove(selectedRule);
            selectedRule = Math.min(selectedRule, rules.size() - 1);
            editFilter(f -> withRules(f, rules));
            rebuild();
        });
        addRuleButton(2, "rule.up", pressed -> moveRule(filter, -1));
        addRuleButton(3, "rule.down", pressed -> moveRule(filter, 1));
        if (selectedRule < 0 || selectedRule >= filter.rules().size())
            return;
        FilterSet.Rule rule = filter.rules().get(selectedRule);
        int index = selectedRule;
        EditBox expression = new EditBox(font, x(LEFT), y(RULE_EXPRESSION_Y), FULL_WIDTH, BUTTON_HEIGHT, text("expression"));
        expression.setMaxLength(PipeData.limits().maxExpressionLength());
        expression.setHint(text("expression.hint"));
        expression.setValue(rule.expression());
        expression.setTooltip(Tooltip.create(text("expression.tooltip")));
        expression.setResponder(value -> updateRule(index, r -> new FilterSet.Rule(value, r.allow(), r.scope(), r.limit(), r.enabled())));
        addRenderableWidget(expression);
        addRenderableWidget(CycleButton.booleanBuilder(text("rule.allow"), text("rule.deny")).displayOnlyValue()
                .withInitialValue(rule.allow()).create(x(LEFT), y(RULE_CONTROLS_Y), VERDICT_WIDTH, BUTTON_HEIGHT,
                        text("rule.verdict"), (button, value) -> updateRule(index,
                                r -> new FilterSet.Rule(r.expression(), value, r.scope(), r.limit(), r.enabled()))));
        Component scope = rule.scope().isEmpty() ? text("scope.any")
                : Component.translatable("gui." + UniversalPipes.MODID + ".scope.chosen", rule.scope().size());
        addRenderableWidget(Button.builder(scope, pressed -> {
            picker = Picker.SCOPE;
            scroll = 0;
            rebuild();
        }).bounds(x(LEFT + VERDICT_WIDTH + CONTROL_GAP), y(RULE_CONTROLS_Y), SCOPE_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(text("scope.tooltip"))).build());
        EditBox limit = numberBox(LEFT + VERDICT_WIDTH + SCOPE_WIDTH + 2 * CONTROL_GAP, RULE_CONTROLS_Y, rule.limit(),
                "limit", value -> updateRule(index,
                        r -> new FilterSet.Rule(r.expression(), r.allow(), r.scope(), value, r.enabled())));
        limit.setWidth(LIMIT_WIDTH);
        addRenderableWidget(limit);
    }

    private void addRuleButton(int column, String key, Button.OnPress action) {
        addRenderableWidget(Button.builder(text(key), action)
                .bounds(x(LEFT + column * RULE_BUTTON_STEP), y(RULE_BUTTONS_Y), RULE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addPickerWidgets() {
        boolean scope = picker == Picker.SCOPE;
        if (scope) {
            addRenderableWidget(Button.builder(text("scope.any"), pressed -> {
                updateRule(selectedRule, r -> new FilterSet.Rule(r.expression(), r.allow(), List.of(), r.limit(), r.enabled()));
                picker = Picker.NONE;
                rebuild();
            }).bounds(x(LEFT), y(PICKER_BUTTONS_Y), HALF_WIDTH, BUTTON_HEIGHT).build());
        }
        addRenderableWidget(Button.builder(text("done"), pressed -> {
            picker = Picker.NONE;
            rebuild();
        }).bounds(x(scope ? RIGHT : LEFT), y(PICKER_BUTTONS_Y), scope ? HALF_WIDTH : FULL_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addLookWidgets() {
        addRenderableWidget(CycleButton.booleanBuilder(text("paint.collar"), text("paint.body")).displayOnlyValue()
                .withInitialValue(accentTarget).create(x(LEFT), y(LOOK_PAINT_Y), HALF_WIDTH, BUTTON_HEIGHT,
                        text("paint"), (button, value) -> accentTarget = value));
        addRenderableWidget(Button.builder(text("clear_color"), pressed -> editLook(
                current -> accentTarget ? current.withAccent(Appearance.UNSET) : current.withTint(Appearance.UNSET)))
                .bounds(x(RIGHT), y(LOOK_PAINT_Y), HALF_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(text("clear"), pressed -> editLook(current -> current.withMaterial(Optional.empty())))
                .bounds(x(LEFT + FULL_WIDTH - CLEAR_WIDTH), y(LOOK_MATERIAL_Y + 2), CLEAR_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(CycleButton.onOffBuilder(look.glow()).displayOnlyValue().create(x(LEFT), y(LOOK_GLOW_Y),
                HALF_WIDTH, BUTTON_HEIGHT, text("glow"), (button, value) -> editLook(current -> current.withGlow(value))));
        addRenderableWidget(Button.builder(text("reset_look"), pressed -> {
            editLook(current -> Appearance.NONE);
            rebuild();
        }).bounds(x(RIGHT), y(LOOK_GLOW_Y), HALF_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addRule(String expression) {
        FilterSet filter = config.transport(tab).filter();
        if (filter.rules().size() >= PipeData.tier(menu.tier()).maxRules())
            return;
        List<FilterSet.Rule> rules = new ArrayList<>(filter.rules());
        rules.add(new FilterSet.Rule(expression, true, List.of(), 0, true));
        selectedRule = rules.size() - 1;
        editFilter(f -> withRules(f, rules));
        rebuild();
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
            if (index < 0 || index >= f.rules().size())
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

    private String idOf(ItemStack stack) {
        if (tab == TransportType.FLUID) {
            return FluidUtil.getFluidContained(stack).map(fluid -> BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString())
                    .orElse(null);
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private boolean simpleFilterShown() {
        return view == View.FILTERS && picker == Picker.NONE && tab != TransportType.ENERGY
                && !config.transport(tab).filter().advanced();
    }

    private boolean advancedFilterShown() {
        return view == View.FILTERS && picker == Picker.NONE && tab != TransportType.ENERGY
                && config.transport(tab).filter().advanced();
    }

    /**
     * Uses a stack as input for the current view: a filter entry or rule in the
     * filter view, the material in the look view. Returns whether it was taken,
     * so that the caller leaves the stack alone and no pickup happens.
     */
    private boolean absorb(ItemStack stack) {
        if (stack.isEmpty() || picker != Picker.NONE)
            return false;
        return switch (view) {
            case FILTERS -> addFilter(stack);
            case LOOK -> setMaterial(stack);
            default -> false;
        };
    }

    private boolean addFilter(ItemStack stack) {
        if (tab == TransportType.ENERGY)
            return false;
        String id = idOf(stack);
        if (id == null)
            return false;
        FilterSet filter = config.transport(tab).filter();
        if (filter.advanced()) {
            if (filter.rules().stream().noneMatch(rule -> rule.expression().equals(id)))
                addRule(id);
            return true;
        }
        if (!filter.entries().contains(id) && filter.entries().size() < PipeData.tier(menu.tier()).filterSlots()) {
            editFilter(f -> {
                List<String> entries = new ArrayList<>(f.entries());
                entries.add(id);
                return new FilterSet(f.advanced(), f.whitelist(), entries, f.firstMatch(), f.rules());
            });
        }
        return true;
    }

    private boolean setMaterial(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem block))
            return false;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block.getBlock());
        if (Appearance.allowed(id))
            editLook(current -> current.withMaterial(Optional.of(id)));
        return true;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null && slot.hasItem() && absorb(slot.getItem()))
            return;
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    private boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= x(LEFT) && mouseX < x(LEFT + FULL_WIDTH) && mouseY >= y(ROW_1)
                && mouseY < y(RULE_CONTROLS_Y + BUTTON_HEIGHT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;
        if (picker != Picker.NONE)
            return clickDestination(mouseX, mouseY);
        if (view == View.LOOK && clickSwatch(mouseX, mouseY))
            return true;
        if (simpleFilterShown() && menu.getCarried().isEmpty() && clickGhost(mouseX, mouseY))
            return true;
        if (advancedFilterShown() && clickRuleRow(mouseX, mouseY, button))
            return true;
        return inPanel(mouseX, mouseY) && absorb(menu.getCarried());
    }

    private int ghostX(int index) {
        return x(LEFT + index * SLOT_SIZE);
    }

    private int ghostY() {
        return y(ROW_4);
    }

    /** With an empty hand, a click on an entry removes it. */
    private boolean clickGhost(double mouseX, double mouseY) {
        List<String> entries = config.transport(tab).filter().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (mouseX < ghostX(i) || mouseX >= ghostX(i) + SLOT_SIZE || mouseY < ghostY() || mouseY >= ghostY() + SLOT_SIZE)
                continue;
            int index = i;
            editFilter(f -> {
                List<String> remaining = new ArrayList<>(f.entries());
                remaining.remove(index);
                return new FilterSet(f.advanced(), f.whitelist(), remaining, f.firstMatch(), f.rules());
            });
            return true;
        }
        return false;
    }

    private boolean clickSwatch(double mouseX, double mouseY) {
        for (int i = 0; i < DYES.length; i++) {
            if (mouseX >= swatchX(i) && mouseX < swatchX(i) + SWATCH_SIZE && mouseY >= swatchY(i)
                    && mouseY < swatchY(i) + SWATCH_SIZE) {
                int color = rgb(DYES[i]);
                editLook(current -> accentTarget ? current.withAccent(color) : current.withTint(color));
                return true;
            }
        }
        return false;
    }

    /** Left click selects a rule; right click switches it on or off. */
    private boolean clickRuleRow(double mouseX, double mouseY, int button) {
        List<FilterSet.Rule> rules = config.transport(tab).filter().rules();
        for (int row = 0; row < RULE_ROWS; row++) {
            int index = scroll + row;
            int rowY = y(ROW_3 + row * LIST_ROW_HEIGHT);
            if (index >= rules.size() || mouseX < x(LEFT) || mouseX >= x(LEFT + FULL_WIDTH) || mouseY < rowY
                    || mouseY >= rowY + LIST_ROW_HEIGHT)
                continue;
            if (button == 1)
                updateRule(index, r -> new FilterSet.Rule(r.expression(), r.allow(), r.scope(), r.limit(), !r.enabled()));
            selectedRule = index;
            rebuild();
            return true;
        }
        return false;
    }

    private boolean clickDestination(double mouseX, double mouseY) {
        List<BlockPos> targets = menu.destinations();
        for (int row = 0; row < PICKER_ROWS; row++) {
            int index = scroll + row;
            int rowY = y(ROW_2 + row * LIST_ROW_HEIGHT);
            if (index >= targets.size() || mouseX < x(LEFT) || mouseX >= x(LEFT + FULL_WIDTH) || mouseY < rowY
                    || mouseY >= rowY + LIST_ROW_HEIGHT)
                continue;
            BlockPos target = targets.get(index);
            if (picker == Picker.PRIORITY)
                edit(c -> c.withPriority(toggled(c.priority(), target)));
            else
                updateRule(selectedRule, r -> new FilterSet.Rule(r.expression(), r.allow(), toggled(r.scope(), target),
                        r.limit(), r.enabled()));
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
        int rows = picker != Picker.NONE ? PICKER_ROWS : RULE_ROWS;
        scroll = Math.max(0, Math.min(Math.max(0, size - rows), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    private int swatchX(int index) {
        return x(LEFT + (index % SWATCH_COLUMNS) * SWATCH_STEP);
    }

    private int swatchY(int index) {
        return y(ROW_1 + (index / SWATCH_COLUMNS) * SWATCH_STEP);
    }

    private static int rgb(DyeColor dye) {
        return dye.getTextureDiffuseColor() & Appearance.RGB_MASK;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blitSprite(PANEL, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots)
            graphics.blitSprite(SLOT, leftPos + slot.x - 1, topPos + slot.y - 1, SLOT_SIZE, SLOT_SIZE);
        if (picker != Picker.NONE)
            drawDestinations(graphics);
        else if (view == View.LOOK)
            drawLook(graphics);
        else if (simpleFilterShown())
            drawGhosts(graphics);
        else if (advancedFilterShown())
            drawRules(graphics);
    }

    private void drawLook(GuiGraphics graphics) {
        int selected = accentTarget ? look.accent() : look.tint();
        for (int i = 0; i < DYES.length; i++) {
            int swatchX = swatchX(i);
            int swatchY = swatchY(i);
            boolean chosen = selected == rgb(DYES[i]);
            graphics.fill(swatchX - 1, swatchY - 1, swatchX + SWATCH_SIZE + 1, swatchY + SWATCH_SIZE + 1,
                    chosen ? TITLE : ROW_FILL);
            graphics.fill(swatchX, swatchY, swatchX + SWATCH_SIZE, swatchY + SWATCH_SIZE, rgb(DYES[i]) | OPAQUE);
        }
        graphics.blitSprite(SLOT, x(LEFT), y(LOOK_MATERIAL_Y), SLOT_SIZE, SLOT_SIZE);
        look.material().map(BuiltInRegistries.BLOCK::get).ifPresent(block -> graphics.renderFakeItem(
                new ItemStack(block), x(LEFT) + 1, y(LOOK_MATERIAL_Y) + 1));
    }

    private void drawGhosts(GuiGraphics graphics) {
        List<String> entries = config.transport(tab).filter().entries();
        int slots = Math.min(PipeData.tier(menu.tier()).filterSlots(), GHOST_COLUMNS);
        for (int i = 0; i < slots; i++) {
            graphics.blitSprite(SLOT, ghostX(i), ghostY(), SLOT_SIZE, SLOT_SIZE);
            if (i < entries.size())
                graphics.renderFakeItem(displayStack(entries.get(i)), ghostX(i) + 1, ghostY() + 1);
        }
    }

    private void drawRules(GuiGraphics graphics) {
        List<FilterSet.Rule> rules = config.transport(tab).filter().rules();
        for (int row = 0; row < RULE_ROWS; row++) {
            int index = scroll + row;
            int rowY = y(ROW_3 + row * LIST_ROW_HEIGHT);
            graphics.fill(x(LEFT), rowY, x(LEFT + FULL_WIDTH), rowY + LIST_ROW_HEIGHT - 1,
                    index == selectedRule ? ROW_SELECTED : ROW_FILL);
            if (index >= rules.size())
                continue;
            FilterSet.Rule rule = rules.get(index);
            int color = !rule.enabled() ? DISABLED : !Expr.isValid(rule.expression()) ? NEGATIVE
                    : rule.allow() ? POSITIVE : DENY;
            String marker = !rule.enabled() ? "x " : rule.allow() ? "+ " : "- ";
            graphics.drawString(font, font.plainSubstrByWidth(marker + rule.expression(), FULL_WIDTH - 2 * TEXT_INSET),
                    x(LEFT) + TEXT_INSET, rowY + 2, color, false);
        }
    }

    private void drawDestinations(GuiGraphics graphics) {
        List<BlockPos> targets = menu.destinations();
        List<BlockPos> chosen = picker == Picker.PRIORITY ? config.priority()
                : selectedRule >= 0 && selectedRule < config.transport(tab).filter().rules().size()
                        ? config.transport(tab).filter().rules().get(selectedRule).scope()
                        : List.of();
        for (int row = 0; row < PICKER_ROWS; row++) {
            int index = scroll + row;
            int rowY = y(ROW_2 + row * LIST_ROW_HEIGHT);
            graphics.fill(x(LEFT), rowY, x(LEFT + FULL_WIDTH), rowY + LIST_ROW_HEIGHT - 1, ROW_FILL);
            if (index >= targets.size())
                continue;
            BlockPos target = targets.get(index);
            int rank = chosen.indexOf(target);
            String label = (rank >= 0 ? (picker == Picker.PRIORITY ? (rank + 1) + ". " : "+ ") : "  ")
                    + target.getX() + " " + target.getY() + " " + target.getZ();
            graphics.drawString(font, label, x(LEFT) + TEXT_INSET, rowY + 2, rank >= 0 ? POSITIVE : TITLE, false);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TITLE, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TITLE, false);
        Component status = Component.translatable(shownStatus.translationKey());
        graphics.drawString(font, status, LEFT + FULL_WIDTH - font.width(status), titleLabelY, statusColor(), false);
        if (picker != Picker.NONE) {
            graphics.drawString(font, text(picker == Picker.PRIORITY ? "order.hint" : "scope.hint"), LEFT,
                    ROW_1 + TEXT_DROP, SECONDARY, false);
        } else if (view == View.LOOK) {
            Component name = look.material().map(BuiltInRegistries.BLOCK::get).map(block -> block.getName())
                    .orElse(text("hint.material").copy());
            graphics.drawString(font, name, LEFT + MATERIAL_LABEL, LOOK_MATERIAL_Y + 5,
                    look.material().isPresent() ? TITLE : SECONDARY, false);
        } else if (view == View.FILTERS && tab == TransportType.ENERGY) {
            graphics.drawString(font, text("energy.nofilter"), LEFT, ROW_2 + TEXT_DROP, SECONDARY, false);
        } else if (simpleFilterShown()) {
            graphics.drawString(font, text("hint.add"), LEFT, ROW_3 + TEXT_DROP, SECONDARY, false);
            graphics.drawString(font, Component.translatable("gui.universal_pipes.count",
                    config.transport(tab).filter().entries().size(), PipeData.tier(menu.tier()).filterSlots()), LEFT,
                    ROW_5 + TEXT_DROP, SECONDARY, false);
        }
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
