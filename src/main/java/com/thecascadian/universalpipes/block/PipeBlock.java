package com.thecascadian.universalpipes.block;

import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.PipeNetworks;
import com.thecascadian.universalpipes.core.Targets;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.menu.PipeMenu;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One block for all tiers. The state is the tier (5 values) and one of three
 * values per face (none, connected, endpoint): 5 * 3^6 = 3645 states, far under
 * the vanilla ceiling and small enough for a multipart blockstate. A fourth
 * "disabled" face value would raise that to 20480 without changing rendering,
 * so wrench-disabled faces are kept in the block entity instead. Passive pipes
 * have no block entity: one is created only when a face is extracted or disabled.
 */
public class PipeBlock extends Block implements EntityBlock {

    public static final int MAX_TIER = 5;
    public static final IntegerProperty TIER = IntegerProperty.create("tier", 1, MAX_TIER);
    public static final Map<Direction, EnumProperty<Connection>> FACES = new EnumMap<>(Direction.class);

    private static final double CORE_MIN = 5.0;
    private static final double CORE_MAX = 11.0;
    private static final double ARM_LENGTH = 5.0;
    private static final double FULL = 16.0;
    private static final double HIT_EPSILON = 0.01;
    private static final VoxelShape CORE = Block.box(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.values())
            FACES.put(direction, EnumProperty.create(direction.getSerializedName(), Connection.class));
        ARMS.put(Direction.NORTH, Block.box(CORE_MIN, CORE_MIN, 0, CORE_MAX, CORE_MAX, ARM_LENGTH));
        ARMS.put(Direction.SOUTH, Block.box(CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX, FULL));
        ARMS.put(Direction.WEST, Block.box(0, CORE_MIN, CORE_MIN, ARM_LENGTH, CORE_MAX, CORE_MAX));
        ARMS.put(Direction.EAST, Block.box(CORE_MAX, CORE_MIN, CORE_MIN, FULL, CORE_MAX, CORE_MAX));
        ARMS.put(Direction.DOWN, Block.box(CORE_MIN, 0, CORE_MIN, CORE_MAX, ARM_LENGTH, CORE_MAX));
        ARMS.put(Direction.UP, Block.box(CORE_MIN, CORE_MAX, CORE_MIN, CORE_MAX, FULL, CORE_MAX));
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

    /** Recomputes the six face values from the neighbourhood and the block entity. */
    public static BlockState computeState(Level level, BlockPos pos, BlockState current) {
        PipeEntity self = level.getBlockEntity(pos) instanceof PipeEntity pipe ? pipe : null;
        BlockState result = current;
        for (Direction face : Direction.values()) {
            BlockPos next = pos.relative(face);
            Connection value;
            if (self != null && self.isExtract(face)) {
                value = Connection.ENDPOINT;
            } else if (self != null && self.isDisabled(face)) {
                value = Connection.NONE;
            } else if (!level.hasChunkAt(next)) {
                value = current.getValue(FACES.get(face));
            } else if (level.getBlockState(next).getBlock() instanceof PipeBlock) {
                boolean blocked = level.getBlockEntity(next) instanceof PipeEntity other
                        && (other.isDisabled(face.getOpposite()) || other.isExtract(face.getOpposite()));
                value = blocked ? Connection.NONE : Connection.CONNECTED;
            } else {
                value = Targets.connectable(level, pos, face) ? Connection.CONNECTED : Connection.NONE;
            }
            result = result.setValue(FACES.get(face), value);
        }
        return result;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        int tier = context.getItemInHand().getOrDefault(RegistryHandler.TIER_COMPONENT.get(), 1);
        return computeState(context.getLevel(), context.getClickedPos(), defaultBlockState().setValue(TIER, tier));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        if (!oldState.is(this))
            PipeNetworks.bump(level);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()))
            PipeNetworks.bump(level);
        super.onRemove(state, level, pos, newState, moved);
    }

    /**
     * Neighbour updates also cover a destination changing, so they bump the
     * epoch. The new state is written without a neighbour notification: the
     * connection is symmetric, and each neighbour recomputes from its own update.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
            boolean moved) {
        if (level.isClientSide)
            return;
        PipeNetworks.bump(level);
        BlockState updated = computeState(level, pos, state);
        if (updated != state)
            level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
        if (level.getBlockEntity(pos) instanceof PipeEntity pipe)
            pipe.wakeAll();
    }

    /** Creates or removes the block entity so that only pipes with state carry one. */
    public static void refresh(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock))
            return;
        if (level.getBlockEntity(pos) instanceof PipeEntity pipe && pipe.isEmptyState())
            level.removeBlockEntity(pos);
        BlockState updated = computeState(level, pos, state);
        level.setBlock(pos, updated, Block.UPDATE_ALL);
        PipeNetworks.bump(level);
    }

    public static PipeEntity entityFor(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PipeEntity existing)
            return existing;
        PipeEntity created = new PipeEntity(pos, level.getBlockState(pos));
        level.setBlockEntity(created);
        return created;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide || type != RegistryHandler.PIPE_ENTITY.get())
            return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<PipeEntity>) PipeEntity::serverTick;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        Direction face = faceAt(state, pos, hit.getLocation(), hit.getDirection());
        if (!(level.getBlockEntity(pos) instanceof PipeEntity pipe) || !pipe.isExtract(face))
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer server && level.mayInteract(player, pos))
            PipeMenu.open(server, pipe, face);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** The arm under the cursor, else the clicked face: a disabled face has no arm to aim at. */
    public static Direction faceAt(BlockState state, BlockPos pos, Vec3 hit, Direction clicked) {
        Vec3 local = hit.subtract(pos.getX(), pos.getY(), pos.getZ()).scale(1.0 / FULL);
        for (Direction face : Direction.values()) {
            if (!state.getValue(FACES.get(face)).isOpen())
                continue;
            AABB arm = ARMS.get(face).bounds().inflate(HIT_EPSILON);
            if (arm.contains(local))
                return face;
        }
        return clicked;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = CORE;
        for (Direction direction : Direction.values()) {
            if (state.getValue(FACES.get(direction)).isOpen())
                shape = Shapes.or(shape, ARMS.get(direction));
        }
        return shape;
    }

    /**
     * Breaking and dismantling share one result: the pipe keeps its settings as
     * data components, and the spent upgrade is either refunded as an item or
     * carried on the pipe item as its tier, depending on the server toggle.
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return dropsFor(state, params.getOptionalParameter(LootContextParams.BLOCK_ENTITY));
    }

    public static List<ItemStack> dropsFor(BlockState state, BlockEntity entity) {
        List<ItemStack> drops = new ArrayList<>();
        ItemStack pipe = new ItemStack(RegistryHandler.PIPE_ITEM.get());
        if (entity != null)
            pipe.applyComponents(entity.collectComponents());
        int tier = state.getValue(TIER);
        if (tier > 1 && PipesConfig.refundOnDismantle())
            drops.add(PipeUpgrade.create(tier));
        else if (tier > 1)
            pipe.set(RegistryHandler.TIER_COMPONENT.get(), tier);
        drops.add(0, pipe);
        return drops;
    }

    /** The configuration a fresh extraction face starts with. */
    public static EndpointConfig freshConfig() {
        return EndpointConfig.fromDefaults();
    }
}
