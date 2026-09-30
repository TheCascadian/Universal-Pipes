package com.thecascadian.universalpipes.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * One block for all tiers. Tier and six three-state faces give 5 * 3^6 = 3645
 * states, well under the vanilla budget, and keep passive pipes free of block
 * entities. The alternative of one block per tier would multiply registry
 * entries and break in-place upgrades.
 */
public class PipeBlock extends Block {

    public static final int MAX_TIER = 5;
    public static final IntegerProperty TIER = IntegerProperty.create("tier", 1, MAX_TIER);

    public static final Map<Direction, EnumProperty<Connection>> FACES = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.values())
            FACES.put(direction, EnumProperty.create(direction.getSerializedName(), Connection.class));
    }

    private static final double CORE_MIN = 5.0;
    private static final double CORE_MAX = 11.0;
    private static final double ARM_LENGTH = 5.0;
    private static final VoxelShape CORE = Block.box(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);

    static {
        ARMS.put(Direction.NORTH, Block.box(CORE_MIN, CORE_MIN, 0, CORE_MAX, CORE_MAX, ARM_LENGTH));
        ARMS.put(Direction.SOUTH, Block.box(CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX, 16));
        ARMS.put(Direction.WEST, Block.box(0, CORE_MIN, CORE_MIN, ARM_LENGTH, CORE_MAX, CORE_MAX));
        ARMS.put(Direction.EAST, Block.box(CORE_MAX, CORE_MIN, CORE_MIN, 16, CORE_MAX, CORE_MAX));
        ARMS.put(Direction.DOWN, Block.box(CORE_MIN, 0, CORE_MIN, CORE_MAX, ARM_LENGTH, CORE_MAX));
        ARMS.put(Direction.UP, Block.box(CORE_MIN, CORE_MAX, CORE_MIN, CORE_MAX, 16, CORE_MAX));
    }

    public PipeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(TIER, 1);
        for (EnumProperty<Connection> face : FACES.values())
            state = state.setValue(face, Connection.NONE);
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIER);
        FACES.values().forEach(builder::add);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos,
            CollisionContext context) {
        VoxelShape shape = CORE;
        for (Direction direction : Direction.values()) {
            if (state.getValue(FACES.get(direction)).isOpen())
                shape = Shapes.or(shape, ARMS.get(direction));
        }
        return shape;
    }
}
