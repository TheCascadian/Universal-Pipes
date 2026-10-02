package com.thecascadian.universalpipes.menu;

import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.core.Status;
import com.thecascadian.universalpipes.registry.RegistryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The single pipe screen's menu. It has no item slots of its own: filters are
 * ghost entries drawn by the screen from the configuration, so only the player
 * inventory is here, pushed down by a height extension while the 176 pixel
 * width and the standard inventory layout stay untouched.
 */
public class PipeMenu extends AbstractContainerMenu {

    public static final int EXTENSION = 90;
    public static final int MAX_DISTANCE_SQUARED = 64;

    private static final int INVENTORY_ROWS = 3;
    private static final int COLUMNS = 9;
    private static final int SLOT_SIZE = 18;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84 + EXTENSION;
    private static final int HOTBAR_GAP = 4;
    private static final int MAX_DESTINATIONS = 64;

    private final BlockPos pos;
    private final Direction face;
    private final int tier;
    private final ContainerData data;
    private final List<BlockPos> destinations;
    private final PipeEntity pipe;
    private EndpointConfig config;
    private Appearance appearance;

    public PipeMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, buf.readBlockPos(), buf.readEnum(Direction.class), buf.readVarInt(),
                EndpointConfig.STREAM_CODEC.decode(buf), Appearance.STREAM_CODEC.decode(buf), readDestinations(buf), null,
                new SimpleContainerData(1));
    }

    private PipeMenu(int containerId, Inventory inventory, BlockPos pos, Direction face, int tier,
            EndpointConfig config, Appearance appearance, List<BlockPos> destinations, PipeEntity pipe,
            ContainerData data) {
        super(RegistryHandler.PIPE_MENU.get(), containerId);
        this.pos = pos;
        this.face = face;
        this.tier = tier;
        this.config = config;
        this.appearance = appearance;
        this.destinations = destinations;
        this.pipe = pipe;
        this.data = data;
        for (int row = 0; row < INVENTORY_ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++)
                addSlot(new Slot(inventory, column + row * COLUMNS + COLUMNS, INVENTORY_X + column * SLOT_SIZE,
                        INVENTORY_Y + row * SLOT_SIZE));
        }
        for (int column = 0; column < COLUMNS; column++)
            addSlot(new Slot(inventory, column, INVENTORY_X + column * SLOT_SIZE,
                    INVENTORY_Y + INVENTORY_ROWS * SLOT_SIZE + HOTBAR_GAP));
        addDataSlots(data);
    }

    private static List<BlockPos> readDestinations(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_DESTINATIONS);
        List<BlockPos> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            result.add(buf.readBlockPos());
        return result;
    }

    public static void open(ServerPlayer player, PipeEntity pipe, Direction face) {
        EndpointConfig config = pipe.config(face);
        List<BlockPos> found = pipe.destinations(face);
        List<BlockPos> shown = found.size() > MAX_DESTINATIONS ? found.subList(0, MAX_DESTINATIONS) : found;
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                return pipe.status(face).ordinal();
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 1;
            }
        };
        player.openMenu(
                new SimpleMenuProvider((id, inventory, unused) -> new PipeMenu(id, inventory, pipe.getBlockPos(), face,
                        pipe.tier(), config, pipe.appearance(), shown, pipe, data), Component.translatable("block.universalpipes.pipe")),
                buf -> {
                    buf.writeBlockPos(pipe.getBlockPos());
                    buf.writeEnum(face);
                    buf.writeVarInt(pipe.tier());
                    EndpointConfig.STREAM_CODEC.encode(buf, config);
                    Appearance.STREAM_CODEC.encode(buf, pipe.appearance());
                    buf.writeVarInt(shown.size());
                    shown.forEach(buf::writeBlockPos);
                });
    }

    public BlockPos pos() {
        return pos;
    }

    public Direction face() {
        return face;
    }

    public int tier() {
        return tier;
    }

    public EndpointConfig config() {
        return config;
    }

    public Appearance appearance() {
        return appearance;
    }

    public List<BlockPos> destinations() {
        return destinations;
    }

    public Status status() {
        return Status.byOrdinal(data.get(0));
    }

    /** Server side: sanitizes a received configuration and look before applying them. */
    public void apply(EndpointConfig incoming, Appearance look) {
        if (pipe == null)
            return;
        config = incoming.sanitize(tier);
        appearance = look.sanitize(PipesConfig.glowAllowed());
        pipe.updateConfig(face, config);
        pipe.setAppearance(appearance);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (pipe != null && (pipe.isRemoved() || !pipe.isExtract(face)))
            return false;
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= MAX_DISTANCE_SQUARED;
    }
}
