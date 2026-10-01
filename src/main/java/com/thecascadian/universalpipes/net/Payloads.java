package com.thecascadian.universalpipes.net;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.menu.PipeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The payloads. The server is authoritative: a configuration update is
 * accepted only from the player whose open menu it targets, within reach, at a
 * bounded rate, and is sanitized against the tier limits before it is applied.
 */
public final class Payloads {

    public static final String PROTOCOL = "1";
    private static final int MAX_SYNC_CHARACTERS = 30000;
    private static final long MIN_TICKS_BETWEEN_UPDATES = 1L;
    private static final int SYNC_BATCH = 512;

    public record DataSync(String json) implements CustomPacketPayload {
        public static final Type<DataSync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "data_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DataSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_SYNC_CHARACTERS), DataSync::json, DataSync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigUpdate(EndpointConfig config, Appearance appearance) implements CustomPacketPayload {
        public static final Type<ConfigUpdate> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "config_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigUpdate> CODEC = StreamCodec.composite(
                EndpointConfig.STREAM_CODEC, ConfigUpdate::config, Appearance.STREAM_CODEC, ConfigUpdate::appearance,
                ConfigUpdate::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: looks for pipes, because the client holds no block entity for them otherwise. */
    public record AppearanceSync(Map<BlockPos, Appearance> looks) implements CustomPacketPayload {
        public static final Type<AppearanceSync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "appearance_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AppearanceSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.<RegistryFriendlyByteBuf, BlockPos, Appearance, Map<BlockPos, Appearance>>map(HashMap::new,
                        BlockPos.STREAM_CODEC, Appearance.STREAM_CODEC, SYNC_BATCH),
                AppearanceSync::looks, AppearanceSync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final Map<UUID, Long> LAST_UPDATE = new HashMap<>();

    private Payloads() {
    }

    @EventBusSubscriber(modid = UniversalPipes.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar(PROTOCOL)
                    .playToClient(DataSync.TYPE, DataSync.CODEC, Payloads::handleSync)
                    .playToClient(AppearanceSync.TYPE, AppearanceSync.CODEC, Payloads::handleAppearance)
                    .playToServer(ConfigUpdate.TYPE, ConfigUpdate.CODEC, Payloads::handleUpdate);
        }
    }

    @EventBusSubscriber(modid = UniversalPipes.MODID)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onDatapackSync(OnDatapackSyncEvent event) {
            DataSync payload = new DataSync(PipeData.toSyncJson());
            event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
        }

        /** Sends every non-default look in a chunk right after the chunk itself, in bounded batches. */
        @SubscribeEvent
        public static void onChunkSent(ChunkWatchEvent.Sent event) {
            Map<BlockPos, Appearance> batch = new HashMap<>();
            for (BlockEntity entity : event.getChunk().getBlockEntities().values()) {
                if (!(entity instanceof PipeEntity pipe) || pipe.appearance().equals(Appearance.NONE))
                    continue;
                batch.put(pipe.getBlockPos(), pipe.appearance());
                if (batch.size() == SYNC_BATCH) {
                    PacketDistributor.sendToPlayer(event.getPlayer(), new AppearanceSync(batch));
                    batch = new HashMap<>();
                }
            }
            if (!batch.isEmpty())
                PacketDistributor.sendToPlayer(event.getPlayer(), new AppearanceSync(batch));
        }

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            LAST_UPDATE.remove(event.getEntity().getUUID());
        }
    }

    private static void handleSync(DataSync payload, IPayloadContext context) {
        context.enqueueWork(() -> PipeData.applySynced(payload.json()));
    }

    private static void handleAppearance(AppearanceSync payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Level level = context.player().level();
            payload.looks().forEach((pos, look) -> {
                if (!(level.getBlockState(pos).getBlock() instanceof PipeBlock))
                    return;
                if (look.equals(Appearance.NONE) && !(level.getBlockEntity(pos) instanceof PipeEntity))
                    return;
                PipeBlock.entityFor(level, pos).setAppearance(look);
            });
        });
    }

    private static void handleUpdate(ConfigUpdate payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof PipeMenu menu) || !menu.stillValid(player))
                return;
            long now = player.level().getGameTime();
            Long last = LAST_UPDATE.put(player.getUUID(), now);
            if (last != null && now - last < MIN_TICKS_BETWEEN_UPDATES)
                return;
            if (player.level().mayInteract(player, menu.pos()))
                menu.apply(payload.config(), payload.appearance());
        });
    }
}
