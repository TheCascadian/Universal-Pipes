package com.thecascadian.universalpipes.net;

import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.core.Appearance;
import com.thecascadian.universalpipes.core.EndpointConfig;
import com.thecascadian.universalpipes.data.PipeData;
import com.thecascadian.universalpipes.menu.PipeMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The two payloads. The server is authoritative: a configuration update is
 * accepted only from the player whose open menu it targets, within reach, at a
 * bounded rate, and is sanitized against the tier limits before it is applied.
 */
public final class Payloads {

    public static final String PROTOCOL = "1";
    private static final int MAX_SYNC_CHARACTERS = 30000;
    private static final long MIN_TICKS_BETWEEN_UPDATES = 1L;

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

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            LAST_UPDATE.remove(event.getEntity().getUUID());
        }
    }

    private static void handleSync(DataSync payload, IPayloadContext context) {
        context.enqueueWork(() -> PipeData.applySynced(payload.json()));
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
