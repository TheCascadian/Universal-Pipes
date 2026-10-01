package com.thecascadian.universalpipes.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.PipeNetworks;
import com.thecascadian.universalpipes.core.Status;
import com.thecascadian.universalpipes.core.Targets;
import com.thecascadian.universalpipes.core.Transfer;
import com.thecascadian.universalpipes.core.TransportType;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.net.Payloads;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Exists only on pipes that carry state: an extraction face, or a face the
 * wrench disabled. Passive pipes have no block entity and never tick. Saved
 * state is one codec used for the world and for the item data component; all
 * runtime caches are transient and rebuilt on demand.
 */
public class PipeEntity extends BlockEntity {

    /** Persisted form, also the body of the data component. */
    public record Settings(int disabledMask, Map<Direction, EndpointConfig> faces, Appearance appearance) {
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("disabled", 0).forGetter(Settings::disabledMask),
                Codec.unboundedMap(Direction.CODEC, EndpointConfig.CODEC).optionalFieldOf("faces", Map.of())
                        .forGetter(Settings::faces),
                Appearance.CODEC.optionalFieldOf("appearance", Appearance.NONE).forGetter(Settings::appearance))
                .apply(i, Settings::new));
    }

    public static final Settings EMPTY_SETTINGS = new Settings(0, Map.of(), Appearance.NONE);

    /** Read by the baked model wrapper to swap the sprite of a pipe that has a material. */
    public static final ModelProperty<Appearance> APPEARANCE_PROPERTY = new ModelProperty<>();
    private static final int GLOW_LIGHT = 7;

    /** Transient per face scheduling and cache state. */
    private static final class Runtime {
        long nextRun;
        int backoff;
        Status status = Status.IDLE;
        PipeNetworks.Topology topology;
        List<Transfer.Dest> destinations = List.of();
        List<Transfer.Dest> ordered;
        int orderKey;
        int roundRobin;
        boolean lossLogged;
        double averageNanos;
        BlockCapabilityCache<IItemHandler, Direction> items;
        BlockCapabilityCache<IFluidHandler, Direction> fluids;
        BlockCapabilityCache<IEnergyStorage, Direction> energy;
    }

    /** Snapshot of one face for the profile command. */
    public record FaceProfile(BlockPos pos, Direction face, int tier, double averageMicros, Status status) {
    }

    private static final int HASH_SALT = 31;
    private static final double NANOS_PER_MICRO = 1000.0;
    private static final double AVERAGE_SMOOTHING = 0.1;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private int disabledMask;
    private Appearance appearance = Appearance.NONE;
    private boolean registered;
    private final Map<Direction, EndpointConfig> faces = new EnumMap<>(Direction.class);
    private final Map<Direction, Runtime> runtimes = new EnumMap<>(Direction.class);

    public PipeEntity(BlockPos pos, BlockState state) {
        super(RegistryHandler.PIPE_ENTITY.get(), pos, state);
    }

    public int tier() {
        return getBlockState().getValue(PipeBlock.TIER);
    }

    public boolean isDisabled(Direction face) {
        return (disabledMask & (1 << face.ordinal())) != 0;
    }

    public void setDisabled(Direction face, boolean disabled) {
        int bit = 1 << face.ordinal();
        disabledMask = disabled ? disabledMask | bit : disabledMask & ~bit;
        changed();
    }

    public boolean isExtract(Direction face) {
        return faces.containsKey(face);
    }

    public EndpointConfig config(Direction face) {
        return faces.get(face);
    }

    public void setExtract(Direction face, EndpointConfig config) {
        if (config == null) {
            faces.remove(face);
            runtimes.remove(face);
        } else {
            faces.put(face, config);
        }
        changed();
    }

    /** Applies a validated configuration and wakes the face so the change acts at once. */
    public void updateConfig(Direction face, EndpointConfig config) {
        if (!faces.containsKey(face))
            return;
        faces.put(face, config);
        Runtime runtime = runtimes.get(face);
        if (runtime != null) {
            runtime.ordered = null;
            runtime.nextRun = 0L;
        }
        setChanged();
    }

    public boolean isEmptyState() {
        return disabledMask == 0 && faces.isEmpty() && appearance.equals(Appearance.NONE);
    }

    public Appearance appearance() {
        return appearance;
    }

    /**
     * The client has no block entity for a pipe by itself: chunk loading creates
     * one through newBlockEntity, which returns null so that passive pipes stay
     * free of entities on the server. A look is therefore mirrored to the
     * clients tracking the chunk by an explicit payload, and the client creates
     * its own entity when that payload arrives. Glow is read from the entity, so
     * a light recheck is queued as well.
     */
    public void setAppearance(Appearance value) {
        if (appearance.equals(value))
            return;
        boolean recolored = appearance.tint() != value.tint();
        appearance = value;
        setChanged();
        if (level == null)
            return;
        if (level.isClientSide) {
            if (level.getModelDataManager() != null)
                level.getModelDataManager().requestRefresh(this);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        } else {
            level.getLightEngine().checkBlock(worldPosition);
            syncAppearance();
            if (recolored)
                PipeBlock.refresh(level, worldPosition);
        }
    }

    public void syncAppearance() {
        if (level instanceof ServerLevel server)
            PacketDistributor.sendToPlayersTrackingChunk(server, new ChunkPos(worldPosition),
                    new Payloads.AppearanceSync(Map.of(worldPosition, appearance)));
    }

    public int lightEmission() {
        return appearance.glow() ? GLOW_LIGHT : 0;
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(APPEARANCE_PROPERTY, appearance).build();
    }

    public Status status(Direction face) {
        Runtime runtime = runtimes.get(face);
        return runtime == null ? Status.IDLE : runtime.status;
    }

    public List<BlockPos> destinations(Direction face) {
        if (!(level instanceof ServerLevel server))
            return List.of();
        PipeNetworks.Topology topology = PipeNetworks.discover(server, worldPosition, face, PipeNetworks.epoch(server),
                crossChannels(face));
        List<BlockPos> result = new ArrayList<>();
        for (PipeNetworks.Destination destination : topology.destinations()) {
            if (!result.contains(destination.target()))
                result.add(destination.target());
        }
        return result;
    }

    private boolean crossChannels(Direction face) {
        EndpointConfig config = faces.get(face);
        return config != null && config.crossChannels();
    }

    /** The strongest comparator signal over the faces, so one working face is enough to read as active. */
    public int signal() {
        int strongest = 0;
        for (Runtime runtime : runtimes.values())
            strongest = Math.max(strongest, runtime.status.signal());
        return strongest;
    }

    public void wakeAll() {
        for (Runtime runtime : runtimes.values())
            runtime.nextRun = 0L;
    }

    public List<FaceProfile> profile() {
        List<FaceProfile> result = new ArrayList<>();
        for (Map.Entry<Direction, Runtime> entry : runtimes.entrySet()) {
            Runtime runtime = entry.getValue();
            result.add(new FaceProfile(worldPosition, entry.getKey(), tier(), runtime.averageNanos / NANOS_PER_MICRO,
                    runtime.status));
        }
        return result;
    }

    private void changed() {
        setChanged();
        if (level != null) {
            PipeNetworks.bump(level);
            wakeAll();
        }
    }

    public Settings settings() {
        return new Settings(disabledMask, Map.copyOf(faces), appearance);
    }

    private void load(Settings settings) {
        disabledMask = settings.disabledMask();
        faces.clear();
        faces.putAll(settings.faces());
        appearance = settings.appearance();
        runtimes.clear();
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        builder.set(RegistryHandler.SETTINGS.get(), settings());
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        Settings stored = input.get(RegistryHandler.SETTINGS.get());
        if (stored != null)
            load(stored);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        Settings.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), settings()).result()
                .ifPresent(encoded -> tag.put("settings", encoded));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("settings"))
            Settings.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get("settings")).result()
                    .ifPresent(this::load);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        register();
    }

    private void register() {
        if (!registered && level != null && !level.isClientSide) {
            PipeNetworks.state(level).endpoints().add(this);
            registered = true;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (registered && level != null) {
            PipeNetworks.state(level).endpoints().remove(this);
            registered = false;
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PipeEntity pipe) {
        if (pipe.faces.isEmpty() || !(level instanceof ServerLevel server))
            return;
        pipe.register();
        long now = level.getGameTime();
        for (Map.Entry<Direction, EndpointConfig> entry : pipe.faces.entrySet()) {
            Runtime runtime = pipe.runtimes.get(entry.getKey());
            if (runtime == null) {
                runtime = new Runtime();
                runtime.backoff = PipeData.tier(pipe.tier()).intervalTicks();
                runtime.nextRun = now + stagger(pos, entry.getKey(), runtime.backoff);
                pipe.runtimes.put(entry.getKey(), runtime);
            }
            if (now < runtime.nextRun)
                continue;
            long started = System.nanoTime();
            int signal = pipe.signal();
            pipe.process(server, entry.getKey(), entry.getValue(), runtime, now);
            if (pipe.signal() != signal)
                server.updateNeighbourForOutputSignal(pos, state.getBlock());
            long spent = System.nanoTime() - started;
            runtime.averageNanos += (spent - runtime.averageNanos) * AVERAGE_SMOOTHING;
            PipeNetworks.recordNanos(level, spent);
        }
    }

    /** Spreads first runs over the interval so endpoints placed together do not all act on one tick. */
    private static int stagger(BlockPos pos, Direction face, int interval) {
        return Math.floorMod(pos.hashCode() * HASH_SALT + face.ordinal(), Math.max(1, interval));
    }

    private void process(ServerLevel level, Direction face, EndpointConfig config, Runtime runtime, long now) {
        PipeData.TierSpec spec = PipeData.tier(tier());
        config = PipesConfig.tierGating() ? config.gated(tier()) : config;
        if (level.getServer().getAverageTickTimeNanos() / NANOS_PER_MILLI > PipesConfig.tickBudgetMs()) {
            idle(runtime, now, spec, Status.BUDGET_DEFERRED);
            return;
        }
        if (!redstoneAllows(level, config)) {
            idle(runtime, now, spec, Status.REDSTONE_OFF);
            return;
        }
        long epoch = PipeNetworks.epoch(level);
        if (runtime.topology == null || runtime.topology.epoch() != epoch) {
            if (!PipeNetworks.tryConsumeRebuild(level)) {
                runtime.status = Status.BUDGET_DEFERRED;
                runtime.nextRun = now + 1;
                return;
            }
            PipeNetworks.Topology topology = PipeNetworks.discover(level, worldPosition, face, epoch,
                    config.crossChannels());
            runtime.topology = topology;
            runtime.destinations = topology.destinations().stream()
                    .map(destination -> new Transfer.Dest(destination,
                            () -> !isRemoved() && runtime.topology == topology, () -> runtime.nextRun = 0L))
                    .toList();
            runtime.ordered = null;
        }
        if (runtime.destinations.isEmpty()) {
            idle(runtime, now, spec, runtime.topology.hadUnloaded() ? Status.UNLOADED_TARGET : Status.NO_DESTINATION);
            return;
        }

        Transfer.Tally tally = new Transfer.Tally();
        List<Transfer.Dest> order = order(runtime, config, level);
        BlockPos sourcePos = worldPosition.relative(face);
        Direction side = face.getOpposite();
        if (config.items().enabled() && allowed(TransportType.ITEM))
            runItems(level, runtime, config, spec, order, tally, sourcePos, side);
        if (config.fluids().enabled() && allowed(TransportType.FLUID))
            runFluids(level, runtime, config, spec, order, tally, sourcePos, side);
        if (config.energy().enabled() && allowed(TransportType.ENERGY))
            runEnergy(level, runtime, config, spec, order, tally, sourcePos, side);

        if (tally.lost && !runtime.lossLogged) {
            runtime.lossLogged = true;
            UniversalPipes.LOGGER.warn(
                    "Pipe at {} face {} could not return a remainder to its source; the remainder was dropped or lost",
                    worldPosition, face);
        }
        if (tally.moved > 0) {
            runtime.status = Status.TRANSFERRED;
            runtime.backoff = spec.intervalTicks();
            runtime.nextRun = now + spec.intervalTicks();
            return;
        }
        Status reason = tally.filtered && !tally.full ? Status.FILTERED_OUT
                : tally.full ? Status.DESTINATION_FULL
                : tally.unloaded ? Status.UNLOADED_TARGET : Status.IDLE;
        idle(runtime, now, spec, reason);
    }

    private void runItems(ServerLevel level, Runtime runtime, EndpointConfig config, PipeData.TierSpec spec,
            List<Transfer.Dest> order, Transfer.Tally tally, BlockPos sourcePos, Direction side) {
        if (runtime.items == null)
            runtime.items = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, sourcePos, side,
                    () -> !isRemoved(), () -> runtime.nextRun = 0L);
        IItemHandler source = runtime.items.getCapability();
        if (source == null)
            source = Targets.fallbackItems(level, sourcePos, side);
        if (source != null)
            Transfer.items(level, sourcePos, source, config.items(), spec.itemsPerOp(), order, tally);
    }

    private void runFluids(ServerLevel level, Runtime runtime, EndpointConfig config, PipeData.TierSpec spec,
            List<Transfer.Dest> order, Transfer.Tally tally, BlockPos sourcePos, Direction side) {
        if (runtime.fluids == null)
            runtime.fluids = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, sourcePos, side,
                    () -> !isRemoved(), () -> runtime.nextRun = 0L);
        IFluidHandler source = runtime.fluids.getCapability();
        if (source == null)
            source = Targets.fallbackFluids(level, sourcePos, side);
        if (source != null)
            Transfer.fluids(level, source, config.fluids(), spec.fluidPerOp(), order, tally);
    }

    private void runEnergy(ServerLevel level, Runtime runtime, EndpointConfig config, PipeData.TierSpec spec,
            List<Transfer.Dest> order, Transfer.Tally tally, BlockPos sourcePos, Direction side) {
        if (runtime.energy == null)
            runtime.energy = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, sourcePos, side,
                    () -> !isRemoved(), () -> runtime.nextRun = 0L);
        IEnergyStorage source = runtime.energy.getCapability();
        if (source == null)
            source = Targets.fallbackEnergy(level, sourcePos, side);
        if (source != null) {
            int budget = (int) Math.min(Integer.MAX_VALUE, (long) spec.energyPerTick() * spec.intervalTicks());
            Transfer.energy(level, source, config.energy(), budget, order, tally);
        }
    }

    /** Fluids and energy honour the optional minimum tier from the datapack feature table. */
    private boolean allowed(TransportType type) {
        if (!PipesConfig.tierGating())
            return true;
        PipeData.Limits limits = PipeData.limits();
        return switch (type) {
            case ITEM -> true;
            case FLUID -> tier() >= limits.minTierFluids();
            case ENERGY -> tier() >= limits.minTierEnergy();
        };
    }

    private boolean redstoneAllows(ServerLevel level, EndpointConfig config) {
        boolean powered = level.hasNeighborSignal(worldPosition);
        return switch (config.redstone()) {
            case IGNORE -> true;
            case ON -> powered;
            case OFF -> !powered;
        };
    }

    /** Idle faces double their wait up to the configured ceiling; any activity or wake resets it. */
    private static void idle(Runtime runtime, long now, PipeData.TierSpec spec, Status status) {
        runtime.status = status;
        runtime.backoff = Math.min(Math.max(runtime.backoff * 2, spec.intervalTicks()), PipesConfig.idleBackoffMax());
        runtime.nextRun = now + runtime.backoff;
    }

    /**
     * Deterministic orders are cached on the topology. Round robin and random
     * are cheap permutations of the cached list applied per operation.
     */
    private static List<Transfer.Dest> order(Runtime runtime, EndpointConfig config, ServerLevel level) {
        int key = config.distribution().ordinal() * HASH_SALT + config.priority().hashCode();
        if (runtime.ordered == null || runtime.orderKey != key) {
            List<Transfer.Dest> sorted = new ArrayList<>(runtime.destinations);
            switch (config.distribution()) {
                case NEAREST_FIRST -> sorted.sort(Comparator.comparingInt(dest -> dest.destination().distance()));
                case FURTHEST_FIRST -> sorted.sort(
                        Comparator.comparingInt((Transfer.Dest dest) -> dest.destination().distance()).reversed());
                case PRIORITY -> sorted.sort(Comparator.comparingInt(dest -> rank(config.priority(), dest.target())));
                default -> {
                }
            }
            runtime.ordered = sorted;
            runtime.orderKey = key;
        }
        List<Transfer.Dest> base = runtime.ordered;
        if (base.size() < 2)
            return base;
        if (config.distribution() == EndpointConfig.Distribution.ROUND_ROBIN) {
            List<Transfer.Dest> rotated = new ArrayList<>(base.size());
            int start = Math.floorMod(runtime.roundRobin++, base.size());
            for (int i = 0; i < base.size(); i++)
                rotated.add(base.get((start + i) % base.size()));
            return rotated;
        }
        if (config.distribution() == EndpointConfig.Distribution.RANDOM) {
            List<Transfer.Dest> shuffled = new ArrayList<>(base);
            RandomSource random = level.random;
            for (int i = shuffled.size() - 1; i > 0; i--)
                Collections.swap(shuffled, i, random.nextInt(i + 1));
            return shuffled;
        }
        return base;
    }

    private static int rank(List<BlockPos> priority, BlockPos target) {
        int index = priority.indexOf(target);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
