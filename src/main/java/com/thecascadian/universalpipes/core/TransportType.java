package com.thecascadian.universalpipes.core;

import com.thecascadian.universalpipes.UniversalPipes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * The transport abstraction. Chemicals and mod specific resources are out of
 * scope for this mod, but every transfer path is keyed by this type so a new
 * kind needs one constant and one handler, not a new network.
 */
public enum TransportType {
    ITEM("items"),
    FLUID("fluids"),
    ENERGY("energy");

    public static final TagKey<Item> NON_TRANSFERABLE_ITEM = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "non_transferable"));
    public static final TagKey<Fluid> NON_TRANSFERABLE_FLUID = TagKey.create(Registries.FLUID,
            ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "non_transferable"));

    private final String translationKey;

    TransportType(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return "gui." + UniversalPipes.MODID + ".tab." + translationKey;
    }
}
