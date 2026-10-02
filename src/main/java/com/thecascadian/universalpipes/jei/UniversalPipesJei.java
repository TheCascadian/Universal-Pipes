package com.thecascadian.universalpipes.jei;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Keeps the tiered upgrades apart in JEI and documents the three items. */
@JeiPlugin
public class UniversalPipesJei implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "jei");

    /** Subtype is the tier component, so each tier is its own ingredient. */
    private static final ISubtypeInterpreter<ItemStack> BY_TIER = new ISubtypeInterpreter<>() {
        @Override
        public Object getSubtypeData(ItemStack stack, UidContext context) {
            return tier(stack);
        }

        @Override
        public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
            return Integer.toString(tier(stack));
        }

        private int tier(ItemStack stack) {
            return stack.getOrDefault(RegistryHandler.TIER_COMPONENT.get(), 1);
        }
    };

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(RegistryHandler.PIPE_ITEM.get(), BY_TIER);
        registration.registerSubtypeInterpreter(RegistryHandler.PIPE_UPGRADE.get(), BY_TIER);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(RegistryHandler.PIPE_ITEM.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
                Component.translatable("jei.universalpipes.info.pipe"));
        registration.addIngredientInfo(RegistryHandler.PIPE_WRENCH.get().getDefaultInstance(),
                VanillaTypes.ITEM_STACK, Component.translatable("jei.universalpipes.info.wrench"));
        List<ItemStack> upgrades = new ArrayList<>();
        for (int tier = PipeUpgrade.DEFAULT_TIER; tier <= PipeBlock.MAX_TIER; tier++)
            upgrades.add(PipeUpgrade.create(tier));
        registration.addIngredientInfo(upgrades, VanillaTypes.ITEM_STACK,
                Component.translatable("jei.universalpipes.info.upgrade"));
    }
}
