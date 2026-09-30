package com.thecascadian.universalpipes.client;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Pipe textures are grayscale; the tier picks a vanilla map colour as the tint. */
@EventBusSubscriber(modid = UniversalPipes.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private static final MapColor[] TIER_COLORS = { MapColor.WOOD, MapColor.METAL, MapColor.GOLD, MapColor.DIAMOND,
            MapColor.COLOR_PURPLE };
    private static final int NO_TINT = -1;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onScreens(RegisterMenuScreensEvent event) {
        event.register(RegistryHandler.PIPE_MENU.get(), PipeScreen::new);
    }

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> tintIndex == 0 ? color(state.getValue(PipeBlock.TIER)) : NO_TINT,
                RegistryHandler.PIPE.get());
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tintIndex == 0 ? color(stack.getOrDefault(RegistryHandler.TIER_COMPONENT.get(), 1)) : NO_TINT, RegistryHandler.PIPE_ITEM.get());
    }

    private static int color(int tier) {
        return TIER_COLORS[Math.max(1, Math.min(tier, TIER_COLORS.length)) - 1].col | 0xFF000000;
    }
}
