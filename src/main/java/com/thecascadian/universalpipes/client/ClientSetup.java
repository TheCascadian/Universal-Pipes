package com.thecascadian.universalpipes.client;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Pipe textures are grayscale; the look, or else the tier's vanilla map colour, is the tint. */
@EventBusSubscriber(modid = UniversalPipes.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private static final MapColor[] TIER_COLORS = { MapColor.WOOD, MapColor.METAL, MapColor.GOLD, MapColor.DIAMOND,
            MapColor.COLOR_PURPLE };
    private static final int NO_TINT = -1;
    private static final int OPAQUE = 0xFF000000;
    private static final int BODY_INDEX = 0;
    private static final int ACCENT_INDEX = 1;
    private static final String PIPE_MODEL = "pipe";
    private static final String INVENTORY_VARIANT = "inventory";

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onScreens(RegisterMenuScreensEvent event) {
        event.register(RegistryHandler.PIPE_MENU.get(), PipeScreen::new);
    }

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> {
            Appearance appearance = level != null && pos != null && level.getBlockEntity(pos) instanceof PipeEntity pipe
                    ? pipe.appearance() : Appearance.NONE;
            return tint(appearance, tintIndex, state.getValue(PipeBlock.TIER));
        }, RegistryHandler.PIPE.get());
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tint(
                stack.getOrDefault(RegistryHandler.SETTINGS.get(), PipeEntity.EMPTY_SETTINGS).appearance(), tintIndex,
                stack.getOrDefault(RegistryHandler.TIER_COMPONENT.get(), 1)), RegistryHandler.PIPE_ITEM.get());
    }

    /** Wraps every in-world pipe model; the inventory model is left alone because it has no block entity. */
    @SubscribeEvent
    public static void onBaked(ModelEvent.ModifyBakingResult event) {
        PipeModel.clearSprites();
        event.getModels().replaceAll((id, model) -> id.id().getNamespace().equals(UniversalPipes.MODID)
                && id.id().getPath().equals(PIPE_MODEL) && !id.variant().equals(INVENTORY_VARIANT)
                        ? new PipeModel(model) : model);
    }

    /**
     * Tint index 0 colours the body and index 1 the collars. An unset accent
     * follows the body; with a material and no dye the sprite shows unmodified,
     * and with neither the tier colour applies.
     */
    private static int tint(Appearance appearance, int tintIndex, int tier) {
        if (tintIndex != BODY_INDEX && tintIndex != ACCENT_INDEX)
            return NO_TINT;
        int chosen = tintIndex == ACCENT_INDEX && appearance.accent() != Appearance.UNSET ? appearance.accent()
                : appearance.tint();
        if (chosen != Appearance.UNSET)
            return chosen | OPAQUE;
        return appearance.material().isPresent() ? NO_TINT : color(tier);
    }

    private static int color(int tier) {
        return TIER_COLORS[Math.max(1, Math.min(tier, TIER_COLORS.length)) - 1].col | 0xFF000000;
    }
}
