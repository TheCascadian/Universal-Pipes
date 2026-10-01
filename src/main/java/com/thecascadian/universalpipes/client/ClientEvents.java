package com.thecascadian.universalpipes.client;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Tooltips are where the mod explains itself: tier speed, how to style a pipe, and what a wrench carries. */
@EventBusSubscriber(modid = UniversalPipes.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    private static Component line(String key, Object... arguments) {
        return Component.translatable("tooltip." + UniversalPipes.MODID + "." + key, arguments)
                .withStyle(ChatFormatting.GRAY);
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.is(RegistryHandler.PIPE_ITEM.get())) {
            PipeData.TierSpec spec = PipeData.tier(stack.getOrDefault(RegistryHandler.TIER_COMPONENT.get(), 1));
            event.getToolTip().add(line("throughput", spec.perSecond(spec.itemsPerOp()),
                    spec.perSecond(spec.fluidPerOp()), spec.energyPerSecond()));
            stack.getOrDefault(RegistryHandler.SETTINGS.get(), PipeEntity.EMPTY_SETTINGS).appearance().material()
                    .map(BuiltInRegistries.BLOCK::get)
                    .ifPresent(block -> event.getToolTip().add(line("material", block.getName())));
            event.getToolTip().add(line("styling"));
            event.getToolTip().add(line("styling.clear"));
        } else if (stack.getItem() instanceof PipeUpgrade) {
            PipeData.TierSpec spec = PipeData.tier(PipeUpgrade.tierOf(stack));
            event.getToolTip().add(line("throughput", spec.perSecond(spec.itemsPerOp()),
                    spec.perSecond(spec.fluidPerOp()), spec.energyPerSecond()));
        } else if (stack.is(RegistryHandler.PIPE_WRENCH.get()) && stack.has(RegistryHandler.SETTINGS.get())) {
            event.getToolTip().add(line("copied"));
        }
    }
}
