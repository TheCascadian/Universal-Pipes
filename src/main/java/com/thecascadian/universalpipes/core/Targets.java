package com.thecascadian.universalpipes.core;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.config.PipesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

import java.util.List;

/**
 * Capability lookups with the documented fallbacks. Sided capabilities are
 * always queried with the side of the target that the pipe touches. Vanilla
 * containers are wrapped only when the block exposes no item capability, and
 * entities are consulted only while the server toggle is on.
 */
public final class Targets {

    public static final TagKey<Block> NON_CONNECTABLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "non_connectable"));

    private Targets() {
    }

    /** True when the block (or an entity, if enabled) next to the face offers any supported capability. */
    public static boolean connectable(Level level, BlockPos pipe, Direction face) {
        BlockPos pos = pipe.relative(face);
        Direction side = face.getOpposite();
        if (level.getBlockState(pos).is(NON_CONNECTABLE))
            return false;
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null
                || level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null
                || level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null
                || level.getBlockEntity(pos) instanceof Container
                || !entities(level, pos).isEmpty() && hasEntityCapability(level, pos, side);
    }

    public static IItemHandler fallbackItems(Level level, BlockPos pos, Direction side) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof WorldlyContainer worldly)
            return new SidedInvWrapper(worldly, side);
        if (entity instanceof Container container)
            return new InvWrapper(container);
        for (Entity candidate : entities(level, pos)) {
            IItemHandler handler = candidate.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, side);
            if (handler != null)
                return handler;
        }
        return null;
    }

    public static IFluidHandler fallbackFluids(Level level, BlockPos pos, Direction side) {
        return firstEntity(level, pos, Capabilities.FluidHandler.ENTITY, side);
    }

    public static IEnergyStorage fallbackEnergy(Level level, BlockPos pos, Direction side) {
        return firstEntity(level, pos, Capabilities.EnergyStorage.ENTITY, side);
    }

    private static boolean hasEntityCapability(Level level, BlockPos pos, Direction side) {
        return fallbackItems(level, pos, side) != null || fallbackFluids(level, pos, side) != null
                || fallbackEnergy(level, pos, side) != null;
    }

    private static <T> T firstEntity(Level level, BlockPos pos, EntityCapability<T, Direction> capability,
            Direction side) {
        for (Entity candidate : entities(level, pos)) {
            T handler = candidate.getCapability(capability, side);
            if (handler != null)
                return handler;
        }
        return null;
    }

    private static List<Entity> entities(Level level, BlockPos pos) {
        return PipesConfig.entityTargets() ? level.getEntitiesOfClass(Entity.class, new AABB(pos)) : List.of();
    }
}
