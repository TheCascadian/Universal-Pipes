package com.thecascadian.universalpipes.block;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.PipeNetworks;
import com.thecascadian.universalpipes.core.Targets;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Wrench, upgrade and styling behaviour. It is handled on the right click event rather
 * than in Item#useOn so that one code path serves this mod's wrench, any item in
 * the common wrench tag, and sneaking: the event fires before vanilla decides
 * whether a sneaking player's block interaction is skipped, which Item#useOn
 * cannot guarantee. Every world change is gated by mayInteract and, for
 * removal, by the standard break event.
 */
@EventBusSubscriber(modid = UniversalPipes.MODID)
public final class PipeInteractions {

    private static final TagKey<Item> WRENCH_TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));
    private static final int BREAK_EFFECT = 2001;
    private static final int ANVIL_COST = 1;
    private static final int PARTICLE_COUNT = 3;
    private static final int PARTICLE_LIMIT = 16;
    private static final double PARTICLE_SPREAD = 0.25;
    private static final float SOUND_VOLUME = 1.0F;
    private static final float SOUND_PITCH = 1.0F;

    private PipeInteractions() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock))
            return;
        ItemStack stack = event.getItemStack();
        boolean wrench = stack.is(RegistryHandler.PIPE_WRENCH.get()) || stack.is(WRENCH_TAG);
        boolean upgrade = stack.getItem() instanceof PipeUpgrade;
        boolean dye = stack.getItem() instanceof DyeItem;
        if (!wrench && !upgrade && !dye)
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        Player player = event.getEntity();
        if (!(level instanceof ServerLevel server) || !level.mayInteract(player, pos))
            return;
        if (dye) {
            tint(server, pos, player, stack);
        } else if (wrench) {
            if (player.isShiftKeyDown())
                dismantle(server, pos, state, player);
            else
                cycle(server, pos, PipeBlock.faceAt(state, pos, event.getHitVec().getLocation(),
                        event.getHitVec().getDirection()));
        } else {
            upgrade(server, pos, player, stack, player.isShiftKeyDown());
        }
    }

    /** Dyeing in place; it creates the block entity that carries the look and spends one dye. */
    private static void tint(ServerLevel level, BlockPos pos, Player player, ItemStack stack) {
        Appearance next = styled(appearanceAt(level, pos), stack);
        if (next == null || next.equals(appearanceAt(level, pos)))
            return;
        PipeBlock.entityFor(level, pos).setAppearance(next);
        consume(player, stack);
    }

    private static Appearance appearanceAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PipeEntity pipe ? pipe.appearance() : Appearance.NONE;
    }

    /**
     * The appearance a given item applies, or null when it applies none. Dye sets
     * the tint, a glow ink sac the glow, and a block from the material tag the
     * material. All of them are vanilla items, so styling adds no item to the mod.
     */
    private static Appearance styled(Appearance base, ItemStack item) {
        if (item.getItem() instanceof DyeItem dye)
            return base.withTint(dye.getDyeColor().getTextureDiffuseColor() & Appearance.RGB_MASK);
        if (item.is(Items.GLOW_INK_SAC))
            return PipesConfig.glowAllowed() ? base.withGlow(true) : null;
        if (item.getItem() instanceof BlockItem block) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block.getBlock());
            return Appearance.allowed(id) ? base.withMaterial(Optional.of(id)) : null;
        }
        return null;
    }

    /**
     * Anvil styling covers a whole stack at one material per pipe, since the
     * anvil always consumes the full left stack and a partial result would
     * destroy the remainder.
     */
    @SubscribeEvent
    public static void onAnvil(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (!left.is(RegistryHandler.PIPE_ITEM.get()) || right.getCount() < left.getCount())
            return;
        PipeEntity.Settings current = left.getOrDefault(RegistryHandler.SETTINGS.get(), PipeEntity.EMPTY_SETTINGS);
        Appearance next = styled(current.appearance(), right);
        if (next == null || next.equals(current.appearance()))
            return;
        ItemStack output = left.copy();
        output.set(RegistryHandler.SETTINGS.get(),
                new PipeEntity.Settings(current.disabledMask(), current.faces(), next));
        event.setOutput(output);
        event.setCost(ANVIL_COST);
        event.setMaterialCost(left.getCount());
    }

    /** Connected, then disconnected, then extract, then connected again. */
    private static void cycle(ServerLevel level, BlockPos pos, Direction face) {
        PipeEntity pipe = PipeBlock.entityFor(level, pos);
        if (pipe.isExtract(face)) {
            pipe.setExtract(face, null);
        } else if (pipe.isDisabled(face)) {
            pipe.setDisabled(face, false);
            boolean pipeNeighbour = level.getBlockState(pos.relative(face)).getBlock() instanceof PipeBlock;
            if (!pipeNeighbour && Targets.connectable(level, pos, face))
                pipe.setExtract(face, EndpointConfig.fromDefaults());
        } else {
            pipe.setDisabled(face, true);
        }
        PipeBlock.refresh(level, pos);
        level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH);
    }

    private static void dismantle(ServerLevel level, BlockPos pos, BlockState state, Player player) {
        if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled())
            return;
        List<ItemStack> drops = PipeBlock.dropsFor(state, level.getBlockEntity(pos));
        level.levelEvent(BREAK_EFFECT, pos, Block.getId(state));
        level.removeBlock(pos, false);
        if (player.getAbilities().instabuild)
            return;
        for (ItemStack drop : drops) {
            if (!player.getInventory().add(drop))
                Block.popResource(level, pos, drop);
        }
    }

    /** Whether a pipe at the given tier may take an upgrade of the target tier. */
    public static boolean canUpgrade(int current, int target) {
        return target > current && (PipesConfig.tierSkipping() || target == current + 1);
    }

    private static void upgrade(ServerLevel level, BlockPos pos, Player player, ItemStack stack, boolean segment) {
        int target = PipeUpgrade.tierOf(stack);
        if (!segment) {
            if (applyUpgrade(level, pos, target, 0))
                consume(player, stack);
            else
                player.displayClientMessage(Component.translatable("message.universal_pipes.cannot_upgrade"), true);
            return;
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(pos);
        queue.add(pos);
        int visited = 0;
        int upgraded = 0;
        int limit = PipesConfig.upgradeMaxBlocks();
        while (!queue.isEmpty() && visited < limit && (!stack.isEmpty() || player.getAbilities().instabuild)) {
            BlockPos current = queue.poll();
            visited++;
            if (applyUpgrade(level, current, target, upgraded)) {
                consume(player, stack);
                upgraded++;
            }
            BlockState state = level.getBlockState(current);
            for (Direction face : Direction.values()) {
                BlockPos next = current.relative(face);
                if (state.getValue(PipeBlock.FACES.get(face)) == Connection.CONNECTED
                        && level.getBlockState(next).getBlock() instanceof PipeBlock && seen.add(next))
                    queue.add(next);
            }
        }
        if (upgraded == 0)
            player.displayClientMessage(Component.translatable("message.universal_pipes.cannot_upgrade"), true);
    }

    /** The block entity is untouched because only the tier property changes. */
    private static boolean applyUpgrade(ServerLevel level, BlockPos pos, int target, int particlesSoFar) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock) || !canUpgrade(state.getValue(PipeBlock.TIER), target))
            return false;
        level.setBlock(pos, state.setValue(PipeBlock.TIER, target), Block.UPDATE_ALL);
        PipeNetworks.bump(level);
        if (particlesSoFar < PARTICLE_LIMIT) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    PARTICLE_COUNT, PARTICLE_SPREAD, PARTICLE_SPREAD, PARTICLE_SPREAD, 0.0);
        }
        level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH);
        return true;
    }

    private static void consume(Player player, ItemStack stack) {
        if (!player.getAbilities().instabuild)
            stack.shrink(1);
    }
}
