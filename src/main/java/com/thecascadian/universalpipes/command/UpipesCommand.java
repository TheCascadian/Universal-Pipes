package com.thecascadian.universalpipes.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.config.PipesConfig;
import com.thecascadian.universalpipes.core.PipeNetworks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /upipes stats, profile and rebuild. Output goes to chat only; nothing is
 * written to disk. The permission level is read when the command tree is built.
 */
@EventBusSubscriber(modid = UniversalPipes.MODID)
public final class UpipesCommand {

    private static final int DEFAULT_PROFILE_ROWS = 10;
    private static final int MAX_PROFILE_ROWS = 50;

    private UpipesCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("upipes")
                .requires(source -> source.hasPermission(PipesConfig.commandLevel()))
                .then(Commands.literal("stats").executes(UpipesCommand::stats))
                .then(Commands.literal("profile")
                        .executes(context -> profile(context, DEFAULT_PROFILE_ROWS))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_PROFILE_ROWS))
                                .executes(context -> profile(context, IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("rebuild").executes(UpipesCommand::rebuild)));
    }

    private static void say(CommandContext<CommandSourceStack> context, String text) {
        context.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        int endpoints = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            PipeNetworks.LevelState state = PipeNetworks.state(level);
            endpoints += state.endpoints().size();
            say(context, String.format(Locale.ROOT,
                    "%s: endpoints=%d epoch=%d discoveries=%d deferred=%d cost=%.4f ms/tick",
                    level.dimension().location(), state.endpoints().size(), state.epoch(), state.rebuilds(),
                    state.deferredRebuilds(), state.averageMillis()));
        }
        return endpoints;
    }

    private static int profile(CommandContext<CommandSourceStack> context, int rows) {
        List<PipeEntity.FaceProfile> faces = new ArrayList<>();
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            for (Object endpoint : PipeNetworks.state(level).endpoints()) {
                if (endpoint instanceof PipeEntity pipe)
                    faces.addAll(pipe.profile());
            }
        }
        faces.sort(Comparator.comparingDouble(PipeEntity.FaceProfile::averageMicros).reversed());
        int shown = Math.min(rows, faces.size());
        for (int i = 0; i < shown; i++) {
            PipeEntity.FaceProfile face = faces.get(i);
            say(context, String.format(Locale.ROOT, "%d. %s %s tier %d %.1f us/attempt %s", i + 1,
                    face.pos().toShortString(), face.face().getName(), face.tier(), face.averageMicros(),
                    face.status().name().toLowerCase(Locale.ROOT)));
        }
        if (shown == 0)
            say(context, "No active endpoints");
        return shown;
    }

    private static int rebuild(CommandContext<CommandSourceStack> context) {
        int endpoints = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            PipeNetworks.bump(level);
            for (Object endpoint : PipeNetworks.state(level).endpoints()) {
                if (endpoint instanceof PipeEntity pipe) {
                    pipe.wakeAll();
                    endpoints++;
                }
            }
        }
        int woken = endpoints;
        say(context, "Invalidated all topologies; " + woken + " endpoint(s) will rediscover within the rebuild budget");
        return woken;
    }
}
