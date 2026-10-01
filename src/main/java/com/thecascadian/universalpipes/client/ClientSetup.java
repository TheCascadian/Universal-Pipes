package com.thecascadian.universalpipes.client;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Tier textures are authored per tier; dyes from the appearance are the only tint. */
@EventBusSubscriber(modid = UniversalPipes.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private static final int NO_TINT = -1;
    private static final int OPAQUE = 0xFF000000;
    private static final int BODY_INDEX = 0;
    private static final int ACCENT_INDEX = 1;
    private static final ResourceLocation TIER_PROPERTY = ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID,
            "tier");
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
            return tint(appearance, tintIndex);
        }, RegistryHandler.PIPE.get());
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tint(
                stack.getOrDefault(RegistryHandler.SETTINGS.get(), PipeEntity.EMPTY_SETTINGS).appearance(), tintIndex),
                RegistryHandler.PIPE_ITEM.get());
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
     * The tier is shown by its own texture, so tinting is only ever a dye:
     * index 0 colours the body and index 1 the collars, and an unset accent
     * follows the body.
     */
    private static int tint(Appearance appearance, int tintIndex) {
        if (tintIndex != BODY_INDEX && tintIndex != ACCENT_INDEX)
            return NO_TINT;
        int chosen = tintIndex == ACCENT_INDEX && appearance.accent() != Appearance.UNSET ? appearance.accent()
                : appearance.tint();
        return chosen == Appearance.UNSET ? NO_TINT : chosen | OPAQUE;
    }

    /**
     * Item models pick their tier texture through a predicate, as an item model
     * cannot read components itself. The value is the tier as a fraction of the
     * maximum because the property function clamps its result to the range 0 to 1.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(RegistryHandler.PIPE_ITEM.get(), TIER_PROPERTY,
                    (stack, level, entity, seed) -> stack.getOrDefault(RegistryHandler.TIER_COMPONENT.get(), 1)
                            / (float) PipeBlock.MAX_TIER);
            ItemProperties.register(RegistryHandler.PIPE_UPGRADE.get(), TIER_PROPERTY,
                    (stack, level, entity, seed) -> PipeUpgrade.tierOf(stack) / (float) PipeBlock.MAX_TIER);
        });
    }
}
