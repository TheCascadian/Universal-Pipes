package com.thecascadian.universalpipes.gametest;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.Connection;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.block.PipeInteractions;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.PipeNetworks;
import com.thecascadian.universalpipes.core.Status;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.filter.Expr;
import com.thecascadian.universalpipes.filter.FilterSet;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTests only; no JUnit. Covers transfer, remainder handling, filters,
 * stock limits, in place upgrades, the expression engine, discovery without
 * stack overflow on a 7200 pipe chain, and the 10000 pipe benchmark.
 */
@GameTestHolder(UniversalPipes.MODID)
@PrefixGameTestTemplate(false)
public final class PipeGameTests {

    private static final String TEMPLATE = UniversalPipes.MODID + ":empty";
    private static final int FAST_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CHAIN_LENGTH = 7200;
    private static final int CHAIN_WIDTH = 30;
    private static final int CHAIN_DEPTH = 30;
    private static final int GRID = 100;
    private static final int BENCH_WARMUP = 80;
    private static final int BENCH_TICKS = 200;
    private static final int BENCH_TIMEOUT = 600;
    private static final int STACK = 64;
    private static final int CHEST_SLOTS = 27;
    private static final int DESTINATION_ROW = 4;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int SETTLE_TICKS = 60;

    private PipeGameTests() {
    }

    private static BlockState pipeState(int tier) {
        return RegistryHandler.PIPE.get().defaultBlockState().setValue(PipeBlock.TIER, tier);
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item))
                total += container.getItem(slot).getCount();
        }
        return total;
    }

    private static Container container(GameTestHelper helper, BlockPos relative) {
        return (Container) helper.getBlockEntity(relative);
    }

    /** Chest at x=1, pipes x=2..4 (tier 1), chest at x=6, extraction on the first pipe facing west. */
    private static PipeEntity buildLine(GameTestHelper helper, EndpointConfig config) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        for (int x = 2; x <= 4; x++)
            helper.setBlock(new BlockPos(x, 1, 1), pipeState(1));
        BlockPos first = helper.absolutePos(new BlockPos(2, 1, 1));
        ServerLevel level = helper.getLevel();
        PipeEntity pipe = PipeBlock.entityFor(level, first);
        pipe.setExtract(Direction.WEST, config);
        PipeBlock.refresh(level, first);
        return (PipeEntity) level.getBlockEntity(first);
    }

    @GameTest(template = TEMPLATE)
    public static void itemsMoveThroughPipes(GameTestHelper helper) {
        buildLine(helper, EndpointConfig.fromDefaults());
        Container source = container(helper, new BlockPos(1, 1, 1));
        source.setItem(0, new ItemStack(Items.DIRT, 16));
        helper.runAfterDelay(SETTLE_TICKS * 2L, () -> {
            Container destination = container(helper, new BlockPos(5, 1, 1));
            int moved = count(destination, Items.DIRT);
            int remaining = count(source, Items.DIRT);
            if (moved <= 0)
                helper.fail("No items reached the destination");
            else if (moved + remaining != 16)
                helper.fail("Items were created or lost: moved " + moved + " remaining " + remaining);
            else
                helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void refusedItemsStayInSource(GameTestHelper helper) {
        PipeEntity pipe = buildLine(helper, EndpointConfig.fromDefaults());
        Container source = container(helper, new BlockPos(1, 1, 1));
        Container destination = container(helper, new BlockPos(5, 1, 1));
        source.setItem(0, new ItemStack(Items.DIRT, 16));
        for (int slot = 0; slot < CHEST_SLOTS; slot++)
            destination.setItem(slot, new ItemStack(Items.STONE, STACK));
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            if (count(source, Items.DIRT) != 16)
                helper.fail("Refused items left the source");
            else if (pipe.status(Direction.WEST) != Status.DESTINATION_FULL)
                helper.fail("Status was " + pipe.status(Direction.WEST));
            else
                helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void blacklistBlocksItem(GameTestHelper helper) {
        EndpointConfig base = EndpointConfig.fromDefaults();
        FilterSet blacklist = new FilterSet(false, false, List.of("minecraft:dirt"), true, List.of());
        PipeEntity pipe = buildLine(helper, base.withItems(base.items().withFilter(blacklist)));
        container(helper, new BlockPos(1, 1, 1)).setItem(0, new ItemStack(Items.DIRT, 8));
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            if (count(container(helper, new BlockPos(5, 1, 1)), Items.DIRT) != 0)
                helper.fail("Blacklisted item was moved");
            else if (pipe.status(Direction.WEST) != Status.FILTERED_OUT)
                helper.fail("Status was " + pipe.status(Direction.WEST));
            else
                helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void keepInSourceLimitHolds(GameTestHelper helper) {
        int keep = 10;
        EndpointConfig base = EndpointConfig.fromDefaults();
        buildLine(helper, base.withItems(base.items().withKeep(keep)));
        Container source = container(helper, new BlockPos(1, 1, 1));
        source.setItem(0, new ItemStack(Items.DIRT, 16));
        helper.runAfterDelay(SETTLE_TICKS * 3L, () -> {
            if (count(source, Items.DIRT) != keep)
                helper.fail("Source holds " + count(source, Items.DIRT) + " instead of " + keep);
            else
                helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void upgradeKeepsEntityAndSettings(GameTestHelper helper) {
        EndpointConfig base = EndpointConfig.fromDefaults();
        FilterSet filter = new FilterSet(false, false, List.of("minecraft:dirt"), true, List.of());
        buildLine(helper, base.withItems(base.items().withFilter(filter)));
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 1));
        ServerLevel level = helper.getLevel();
        if (!PipeInteractions.canUpgrade(1, 3)) {
            helper.fail("Tier skipping is expected to be allowed by default");
            return;
        }
        level.setBlock(pos, level.getBlockState(pos).setValue(PipeBlock.TIER, 3), Block.UPDATE_ALL);
        PipeEntity pipe = (PipeEntity) level.getBlockEntity(pos);
        if (pipe == null || !pipe.isExtract(Direction.WEST))
            helper.fail("Block entity or extraction face was lost");
        else if (!pipe.config(Direction.WEST).items().filter().entries().equals(List.of("minecraft:dirt")))
            helper.fail("Filter was lost");
        else
            helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void pipeDoesNotConnectToPlainBlocks(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 1, 1), pipeState(1));
        BlockState state = helper.getBlockState(new BlockPos(2, 1, 1));
        if (state.getValue(PipeBlock.FACES.get(Direction.WEST)) != Connection.NONE)
            helper.fail("Pipe connected to a block without capabilities");
        else
            helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void pipesConnectToEachOther(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), pipeState(1));
        helper.setBlock(new BlockPos(2, 1, 1), pipeState(1));
        helper.runAfterDelay(1, () -> {
            BlockState state = helper.getBlockState(new BlockPos(1, 1, 1));
            if (state.getValue(PipeBlock.FACES.get(Direction.EAST)) != Connection.CONNECTED)
                helper.fail("Adjacent pipes did not connect");
            else
                helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void expressionEngine(GameTestHelper helper) {
        Expr.Subject log = Expr.subject(new ItemStack(Items.OAK_LOG));
        Expr.Subject sword = Expr.subject(new ItemStack(Items.DIAMOND_SWORD));
        boolean ok = Expr.matches("#minecraft:logs & !?enchanted", log)
                && Expr.matches("@minecraft", sword)
                && Expr.matches("~\"oak\" | minecraft:dirt", log)
                && !Expr.matches("minecraft:dirt or ?damaged", log)
                && Expr.matches("?durability=1..2000", sword)
                && Expr.matches("!(minecraft:dirt | minecraft:stone)", log);
        if (!ok) {
            helper.fail("Expression evaluation gave a wrong result");
            return;
        }
        String tooDeep = "(".repeat(PipeData.limits().maxExpressionDepth() + 1) + "minecraft:dirt"
                + ")".repeat(PipeData.limits().maxExpressionDepth() + 1);
        if (Expr.isValid("(minecraft:dirt") || Expr.isValid("") || Expr.isValid(tooDeep))
            helper.fail("An invalid expression compiled");
        else
            helper.succeed();
    }

    /** A serpentine chain forces a breadth first depth of 7200, which would overflow a recursive walk. */
    @GameTest(template = TEMPLATE, timeoutTicks = BENCH_TIMEOUT)
    public static void discoveryHandlesLongChain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 2, 0));
        List<BlockPos> path = serpentine(origin);
        for (int i = 0; i < path.size(); i++) {
            BlockState state = pipeState(1);
            if (i > 0)
                state = state.setValue(PipeBlock.FACES.get(direction(path.get(i), path.get(i - 1))), Connection.CONNECTED);
            if (i + 1 < path.size())
                state = state.setValue(PipeBlock.FACES.get(direction(path.get(i), path.get(i + 1))), Connection.CONNECTED);
            level.setBlock(path.get(i), state, FAST_FLAGS);
        }
        BlockPos last = path.get(path.size() - 1);
        level.setBlock(last.above(), Blocks.CHEST.defaultBlockState(), FAST_FLAGS);
        level.setBlock(last, level.getBlockState(last).setValue(PipeBlock.FACES.get(Direction.UP), Connection.CONNECTED),
                FAST_FLAGS);
        PipeNetworks.Topology topology = PipeNetworks.discover(level, path.get(0), Direction.WEST, 0L, CHAIN_LENGTH + 1);
        if (topology.destinations().size() != 1 || topology.destinations().get(0).distance() != CHAIN_LENGTH - 1)
            helper.fail("Chain discovery found " + topology.destinations().size() + " destinations");
        else
            helper.succeed();
    }

    private static List<BlockPos> serpentine(BlockPos origin) {
        List<BlockPos> path = new java.util.ArrayList<>(CHAIN_LENGTH);
        int layers = CHAIN_LENGTH / (CHAIN_WIDTH * CHAIN_DEPTH);
        for (int y = 0; y < layers; y++) {
            for (int z = 0; z < CHAIN_DEPTH; z++) {
                boolean forward = (y * CHAIN_DEPTH + z) % 2 == 0;
                for (int i = 0; i < CHAIN_WIDTH; i++) {
                    int x = forward ? i : CHAIN_WIDTH - 1 - i;
                    path.add(origin.offset(x, y, z));
                }
            }
        }
        return path;
    }

    private static Direction direction(BlockPos from, BlockPos to) {
        return Direction.fromDelta(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    /**
     * Ten thousand pipes, one hundred active endpoints. The endpoints sit on
     * the first row, each fed by a chest of cobblestone and reaching destination
     * chests on the fifth row. Results are logged, never asserted, because the
     * figures depend on the machine.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = BENCH_TIMEOUT)
    public static void benchmarkTenThousandPipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 2, 2));
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                BlockState state = pipeState(1);
                if (x > 0)
                    state = state.setValue(PipeBlock.FACES.get(Direction.WEST), Connection.CONNECTED);
                if (x < GRID - 1)
                    state = state.setValue(PipeBlock.FACES.get(Direction.EAST), Connection.CONNECTED);
                if (z > 0)
                    state = state.setValue(PipeBlock.FACES.get(Direction.NORTH), Connection.CONNECTED);
                if (z < GRID - 1)
                    state = state.setValue(PipeBlock.FACES.get(Direction.SOUTH), Connection.CONNECTED);
                if (z == DESTINATION_ROW)
                    state = state.setValue(PipeBlock.FACES.get(Direction.UP), Connection.CONNECTED);
                if (z == 0)
                    state = state.setValue(PipeBlock.FACES.get(Direction.NORTH), Connection.ENDPOINT);
                level.setBlock(origin.offset(x, 0, z), state, FAST_FLAGS);
            }
        }
        for (int x = 0; x < GRID; x++) {
            BlockPos pipe = origin.offset(x, 0, 0);
            level.setBlock(pipe.north(), Blocks.CHEST.defaultBlockState(), FAST_FLAGS);
            Container source = (Container) level.getBlockEntity(pipe.north());
            for (int slot = 0; slot < CHEST_SLOTS; slot++)
                source.setItem(slot, new ItemStack(Items.COBBLESTONE, STACK));
            level.setBlock(origin.offset(x, 1, DESTINATION_ROW), Blocks.CHEST.defaultBlockState(), FAST_FLAGS);
            PipeEntity entity = PipeBlock.entityFor(level, pipe);
            entity.setExtract(Direction.NORTH, EndpointConfig.fromDefaults());
        }
        helper.runAfterDelay(BENCH_WARMUP, () -> {
            long startNanos = System.nanoTime();
            helper.runAfterDelay(BENCH_TICKS, () -> {
                double wallMillis = (System.nanoTime() - startNanos) / (double) NANOS_PER_MILLI / BENCH_TICKS;
                PipeNetworks.LevelState state = PipeNetworks.state(level);
                UniversalPipes.LOGGER.info(
                        "[benchmark] {} pipes, {} endpoints: mod cost {} ms per tick, wall {} ms per tick over {} ticks, discoveries {}",
                        GRID * GRID, GRID, String.format("%.4f", state.averageMillis()),
                        String.format("%.4f", wallMillis), BENCH_TICKS, state.rebuilds());
                helper.succeed();
            });
        });
    }
}
