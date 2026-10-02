package com.thecascadian.universalpipes.item;

import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class PipeUpgrade extends Item {

    public static final int DEFAULT_TIER = 2;

    public PipeUpgrade(Properties properties) {
        super(properties);
    }

    public static int tierOf(ItemStack stack) {
        return stack.getOrDefault(RegistryHandler.TIER_COMPONENT.get(), DEFAULT_TIER);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.universalpipes.pipe_upgrade.tiered", tierOf(stack));
    }

    public static ItemStack create(int tier) {
        ItemStack stack = new ItemStack(RegistryHandler.PIPE_UPGRADE.get());
        stack.set(RegistryHandler.TIER_COMPONENT.get(), tier);
        return stack;
    }
}
