package com.thecascadian.universalpipes.block;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.PipeNetworks;
import com.thecascadian.universalpipes.core.Status;
import com.thecascadian.universalpipes.core.Targets;
import com.thecascadian.universalpipes.data.PipeData;
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
import net.minecraft.world.InteractionHand;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final Direction CLIPBOARD_FACE = Direction.UP;
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
        Player player = event.getEntity();
        boolean restyle = restyles(stack, player);
        boolean inspect = stack.isEmpty() && player.isShiftKeyDown() && event.getHand() == InteractionHand.MAIN_HAND;
        if (!wrench && !upgrade && !restyle && !inspect)
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel server) || !level.mayInteract(player, pos))
            return;
        if (inspect) {
            inspect(server, pos, player);
        } else if (restyle) {
            paint(server, pos, player, stack);
        } else if (wrench) {
            Direction face = PipeBlock.faceAt(state, pos, event.getHitVec().getLocation(),
                    event.getHitVec().getDirection());
            if (!player.isShiftKeyDown())
                cycle(server, pos, face, stack);
            else if (level.getBlockEntity(pos) instanceof PipeEntity pipe && pipe.isExtract(face))
                copy(player, stack, pipe.config(face));
            else
                dismantle(server, pos, state, player);
        } else {
            upgrade(server, pos, player, stack, player.isShiftKeyDown());
        }
    }

    /**
     * Styling needs no screen: dye, glow ink and water act on a click, and a
     * material block needs sneaking so that placing a block against a pipe stays
     * the default for every other block.
     */
    private static boolean restyles(ItemStack stack, Player player) {
        return stack.getItem() instanceof DyeItem || stack.is(Items.GLOW_INK_SAC) || stack.is(Items.WATER_BUCKET)
                || player.isShiftKeyDown() && stack.getItem() instanceof BlockItem block
                        && Appearance.allowed(BuiltInRegistries.BLOCK.getKey(block.getBlock()));
    }

    /**
     * Styles every pipe on the connected line, nearest first, up to the
     * configured limit. A line with one entry and one exit is styled uniformly,
     * walking from the entry and giving every pipe the entry's look, and spends one item per pipe actually changed. A water
     * bucket removes the whole look at no cost, so undoing a mistake is one click.
     */
    private static void paint(ServerLevel level, BlockPos start, Player player, ItemStack stack) {
        boolean clear = stack.is(Items.WATER_BUCKET);
        List<BlockPos> line = line(level, start, PipesConfig.maxNetworkNodes());
        BlockPos entry = soleEntry(level, line);
        Appearance uniform = null;
        if (entry != null) {
            line = line(level, entry, PipesConfig.paintMaxBlocks());
            uniform = clear ? Appearance.NONE : styled(appearanceAt(level, entry), stack);
        } else {
            line = line(level, start, PipesConfig.paintMaxBlocks());
        }
        int changed = 0;
        for (BlockPos current : line) {
            Appearance before = appearanceAt(level, current);
            Appearance next = uniform != null ? uniform : clear ? Appearance.NONE : styled(before, stack);
            if (next == null || next.equals(before))
                continue;
            if (!clear && stack.isEmpty() && !player.getAbilities().instabuild)
                break;
            PipeBlock.entityFor(level, current).setAppearance(next);
            if (clear)
                PipeBlock.refresh(level, current);
            else
                consume(player, stack);
            changed++;
        }
        if (changed > 0)
            level.playSound(null, start, clear ? SoundEvents.BUCKET_EMPTY : SoundEvents.DYE_USE, SoundSource.BLOCKS,
                    SOUND_VOLUME, SOUND_PITCH);
        player.displayClientMessage(Component.translatable("message.universalpipes.styled", changed), true);
    }

    /**
     * Keeps a line with one entry and one exit looking like its entry, texture
     * included. Called when a pipe is placed or an extract face changes, so a line
     * stays uniform without repainting. Costs nothing and does nothing for any
     * other kind of line.
     */
    static void harmonise(ServerLevel level, BlockPos pos) {
        List<BlockPos> line = line(level, pos, PipesConfig.maxNetworkNodes());
        BlockPos entry = soleEntry(level, line);
        if (entry == null)
            return;
        Appearance look = appearanceAt(level, entry);
        for (BlockPos current : line) {
            if (appearanceAt(level, current).equals(look))
                continue;
            PipeBlock.entityFor(level, current).setAppearance(look);
            PipeBlock.refresh(level, current);
        }
    }

    /**
     * The entry pipe when the line has exactly one entry (an extract face) and one
     * exit (any other endpoint face), otherwise null. Such a line is styled as a
     * whole from its entry, so every pipe ends up with the entry's look.
     */
    private static BlockPos soleEntry(ServerLevel level, List<BlockPos> line) {
        BlockPos entry = null;
        int entries = 0;
        int exits = 0;
        for (BlockPos pos : line) {
            if (!(level.getBlockEntity(pos) instanceof PipeEntity pipe))
                continue;
            BlockState state = level.getBlockState(pos);
            for (Direction face : Direction.values()) {
                if (state.getValue(PipeBlock.FACES.get(face)) != Connection.ENDPOINT)
                    continue;
                if (pipe.isExtract(face)) {
                    entries++;
                    entry = pos;
                } else {
                    exits++;
                }
            }
        }
        return entries == 1 && exits == 1 ? entry : null;
    }

    /**
     * The connected line is collected before anything changes, because recolouring
     * a pipe may disconnect it from its neighbours and would cut the walk short.
     */
    private static List<BlockPos> line(ServerLevel level, BlockPos start, int limit) {
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> order = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && order.size() < limit) {
            BlockPos current = queue.poll();
            order.add(current);
            BlockState state = level.getBlockState(current);
            for (Direction face : Direction.values()) {
                BlockPos neighbour = current.relative(face);
                if (state.getValue(PipeBlock.FACES.get(face)) == Connection.CONNECTED
                        && level.getBlockState(neighbour).getBlock() instanceof PipeBlock && seen.add(neighbour))
                    queue.add(neighbour);
            }
        }
        return order;
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
    private static void cycle(ServerLevel level, BlockPos pos, Direction face, ItemStack wrench) {
        PipeEntity pipe = PipeBlock.entityFor(level, pos);
        if (pipe.isExtract(face)) {
            pipe.setExtract(face, null);
        } else if (pipe.isDisabled(face)) {
            pipe.setDisabled(face, false);
            boolean pipeNeighbour = level.getBlockState(pos.relative(face)).getBlock() instanceof PipeBlock;
            if (!pipeNeighbour && Targets.connectable(level, pos, face))
                pipe.setExtract(face, copied(wrench, pipe.tier()));
        } else {
            pipe.setDisabled(face, true);
        }
        PipeBlock.refresh(level, pos);
        harmonise(level, pos);
        level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH);
    }

    /**
     * Copied settings ride on the wrench in the existing settings component, so no
     * item or component type is added. The component is a map by face, and the
     * copy always uses one fixed face as its slot.
     */
    private static void copy(Player player, ItemStack wrench, EndpointConfig config) {
        wrench.set(RegistryHandler.SETTINGS.get(),
                new PipeEntity.Settings(0, Map.of(CLIPBOARD_FACE, config), Appearance.NONE));
        player.displayClientMessage(Component.translatable("message.universalpipes.copied"), true);
    }

    /** A new endpoint starts from the copied settings when the wrench holds some, and from the defaults otherwise. */
    private static EndpointConfig copied(ItemStack wrench, int tier) {
        PipeEntity.Settings held = wrench.get(RegistryHandler.SETTINGS.get());
        EndpointConfig config = held == null ? null : held.faces().get(CLIPBOARD_FACE);
        return config == null ? EndpointConfig.fromDefaults() : config.sanitize(tier);
    }

    /** Pipe count, endpoints, the weakest tier and the state of the first active face, as one line. */
    private static void inspect(ServerLevel level, BlockPos start, Player player) {
        List<BlockPos> line = line(level, start, PipesConfig.maxNetworkNodes());
        int endpoints = 0;
        int weakest = PipeData.TIER_COUNT;
        Status shown = Status.IDLE;
        for (BlockPos pos : line) {
            weakest = Math.min(weakest, level.getBlockState(pos).getValue(PipeBlock.TIER));
            if (!(level.getBlockEntity(pos) instanceof PipeEntity pipe))
                continue;
            for (Direction face : Direction.values()) {
                if (!pipe.isExtract(face))
                    continue;
                endpoints++;
                if (shown == Status.IDLE)
                    shown = pipe.status(face);
            }
        }
        player.displayClientMessage(Component.translatable("message.universalpipes.inspect", line.size(), endpoints,
                weakest, Component.translatable(shown.translationKey())), true);
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
                player.displayClientMessage(Component.translatable("message.universalpipes.cannot_upgrade"), true);
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
            player.displayClientMessage(Component.translatable("message.universalpipes.cannot_upgrade"), true);
    }

    /** The block entity is untouched because only the tier property changes. */
    private static boolean applyUpgrade(ServerLevel level, BlockPos pos, int target, int particlesSoFar) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock) || !canUpgrade(state.getValue(PipeBlock.TIER), target))
            return false;
        level.setBlock(pos, state.setValue(PipeBlock.TIER, target), Block.UPDATE_ALL);
        PipeBlock.refresh(level, pos);
        PipeBlock.notifyBridgeNeighbours(level, pos);
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
