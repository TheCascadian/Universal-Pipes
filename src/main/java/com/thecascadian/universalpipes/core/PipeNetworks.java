package com.thecascadian.universalpipes.core;

import com.thecascadian.universalpipes.block.Connection;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.config.PipesConfig;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.data.PipeData;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Transient, per level network bookkeeping. Nothing here is saved.
 *
 * Invalidation uses one counter per level instead of per network ids: any pipe
 * placement, removal, connection change or neighbour change bumps the epoch,
 * and each endpoint compares its cached topology against it before use. The
 * alternative of tracking network membership would need merge and split logic
 * for every edit; the counter costs one discovery per affected endpoint, which
 * the per tick rebuild budget spreads out.
 */
public final class PipeNetworks {

    /** A place where items can be delivered: the block next to a pipe face. */
    public record Destination(BlockPos pipe, Direction face, BlockPos target, int distance) {
    }

    public record Topology(long epoch, List<Destination> destinations, boolean hadUnloaded, boolean truncated) {
    }

    /** Per level counters and the set of loaded endpoints, used by the commands. */
    public static final class LevelState {
        private long epoch;
        private long rebuilds;
        private long deferredRebuilds;
        private long budgetTick = Long.MIN_VALUE;
        private int usedBudget;
        private long tickNanos;
        private double averageNanos;
        private final Set<Object> endpoints = Collections.newSetFromMap(new IdentityHashMap<>());

        public long epoch() {
            return epoch;
        }

        public long rebuilds() {
            return rebuilds;
        }

        public long deferredRebuilds() {
            return deferredRebuilds;
        }

        public double averageMillis() {
            return averageNanos / 1_000_000.0;
        }

        public Set<Object> endpoints() {
            return endpoints;
        }
    }

    private static final double NANOS_SMOOTHING = 0.05;
    private static final Map<Level, LevelState> STATES = new WeakHashMap<>();

    private PipeNetworks() {
    }

    public static synchronized LevelState state(Level level) {
        return STATES.computeIfAbsent(level, key -> new LevelState());
    }

    public static synchronized List<LevelState> allStates() {
        return new ArrayList<>(STATES.values());
    }

    public static synchronized void clear() {
        STATES.clear();
    }

    public static long epoch(Level level) {
        return state(level).epoch;
    }

    public static void bump(Level level) {
        if (!level.isClientSide)
            state(level).epoch++;
    }

    /** Takes one discovery from this tick's budget; false means retry next tick. */
    public static boolean tryConsumeRebuild(ServerLevel level) {
        LevelState state = state(level);
        long now = level.getGameTime();
        if (state.budgetTick != now) {
            state.budgetTick = now;
            state.usedBudget = 0;
        }
        if (state.usedBudget >= PipesConfig.rebuildBudget()) {
            state.deferredRebuilds++;
            return false;
        }
        state.usedBudget++;
        state.rebuilds++;
        return true;
    }

    public static void recordNanos(Level level, long nanos) {
        state(level).tickNanos += nanos;
    }

    public static void endTick(Level level) {
        LevelState state = state(level);
        state.averageNanos += (state.tickNanos - state.averageNanos) * NANOS_SMOOTHING;
        state.tickNanos = 0;
    }

    /**
     * Breadth first discovery with an explicit queue. Recursion was rejected
     * because a network of several thousand pipes would overflow the stack.
     * Only chunks that are already loaded are entered, so discovery never
     * loads or forces a chunk and an unloaded destination is simply absent
     * until a later topology change finds it.
     */
    public static Topology discover(ServerLevel level, BlockPos start, Direction sourceFace, long epoch) {
        int max = PipesConfig.maxNetworkNodes();
        Long2IntOpenHashMap distance = new Long2IntOpenHashMap();
        distance.defaultReturnValue(-1);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        List<Destination> destinations = new ArrayList<>();
        BlockPos sourceTarget = start.relative(sourceFace);
        boolean unloaded = false;
        boolean truncated = false;

        distance.put(start.asLong(), 0);
        queue.enqueue(start.asLong());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            long packed = queue.dequeueLong();
            cursor.set(packed);
            BlockPos pipePos = cursor.immutable();
            BlockState state = level.getBlockState(pipePos);
            if (!(state.getBlock() instanceof PipeBlock))
                continue;
            int depth = distance.get(packed);
            for (Direction face : Direction.values()) {
                if (state.getValue(PipeBlock.FACES.get(face)) != Connection.CONNECTED)
                    continue;
                BlockPos next = pipePos.relative(face);
                if (!level.hasChunkAt(next)) {
                    unloaded = true;
                    continue;
                }
                if (level.getBlockState(next).getBlock() instanceof PipeBlock) {
                    if (distance.containsKey(next.asLong()))
                        continue;
                    if (distance.size() >= max) {
                        truncated = true;
                        continue;
                    }
                    distance.put(next.asLong(), depth + 1);
                    queue.enqueue(next.asLong());
                } else if (!next.equals(sourceTarget)) {
                    destinations.add(new Destination(pipePos, face, next, depth));
                }
            }
        }
        return new Topology(epoch, List.copyOf(destinations), unloaded, truncated);
    }

    /** Lifecycle hooks: data reload registration, per tick cost smoothing and cleanup on shutdown. */
    @EventBusSubscriber(modid = UniversalPipes.MODID)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onAddReloadListeners(AddReloadListenerEvent event) {
            event.addListener(PipeData.LISTENER);
        }

        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            if (event.getLevel() instanceof ServerLevel level)
                endTick(level);
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            clear();
        }
    }
}
