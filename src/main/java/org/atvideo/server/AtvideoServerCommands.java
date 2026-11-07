package org.atvideo.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.atvideo.AtvideoMod;
import org.atvideo.network.AtvideoNetwork;
import org.atvideo.network.packet.ClientPlayVideoMessage;
import org.atvideo.network.packet.ServerPlayVideoMessage;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Mod.EventBusSubscriber(modid = AtvideoMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AtvideoServerCommands {
    private AtvideoServerCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("atvideo")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("preload")
                                .then(Commands.argument("rest", StringArgumentType.greedyString())
                                        .suggests(AtvideoServerCommands::suggestPreloadArgs)
                                        .executes(ctx -> dispatch(ctx,
                                                ServerPlayVideoMessage.Action.PRELOAD))))
                        .then(Commands.literal("stop")
                                .executes(ctx -> dispatch(ctx,
                                        ServerPlayVideoMessage.Action.STOP)))
                        .then(Commands.literal("unpreload")
                                .executes(ctx -> dispatch(ctx,
                                        ServerPlayVideoMessage.Action.UNPRELOAD)))
                        .then(Commands.argument("rest", StringArgumentType.greedyString())
                                .suggests(AtvideoServerCommands::suggestPlayArgs)
                                .executes(ctx -> dispatch(ctx,
                                        ServerPlayVideoMessage.Action.PLAY)))));
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx,
                                ServerPlayVideoMessage.Action action) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "targets");
        String rest = "";
        if (action == ServerPlayVideoMessage.Action.PLAY
                || action == ServerPlayVideoMessage.Action.PRELOAD) {
            rest = StringArgumentType.getString(ctx, "rest").trim();
            if (rest.isBlank()) {
                ctx.getSource().sendFailure(Component.translatable("atvideo.command.missing_file"));
                return 0;
            }
        }

        for (ServerPlayer player : players) {
            AtvideoNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ClientPlayVideoMessage(action, rest));
        }

        String verbKey = switch (action) {
            case PLAY -> "atvideo.command.verb.play";
            case PRELOAD -> "atvideo.command.verb.preload";
            case STOP -> "atvideo.command.verb.stop";
            case UNPRELOAD -> "atvideo.command.verb.unpreload";
        };
        int count = players.size();
        ctx.getSource().sendSuccess(() -> Component.translatable("atvideo.command.success",
                Component.translatable(verbKey), count), false);
        return count;
    }

    private static CompletableFuture<Suggestions> suggestPlayArgs(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestPositional(builder, false);
    }

    private static CompletableFuture<Suggestions> suggestPreloadArgs(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggestPositional(builder, true);
    }

    private static CompletableFuture<Suggestions> suggestPositional(
            SuggestionsBuilder builder, boolean isPreload) {
        String remaining = builder.getRemaining();
        String trimmed = remaining.stripTrailing();
        int trailingSpaces = remaining.length() - trimmed.length();
        String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");

        int nextPosition;
        if (trailingSpaces > 0) {
            nextPosition = tokens.length;
        } else if (tokens.length > 0) {
            nextPosition = tokens.length - 1;
        } else {
            nextPosition = 0;
        }

        int currentTokenStart;
        if (remaining.isEmpty()) {
            currentTokenStart = 0;
        } else if (trailingSpaces > 0) {
            currentTokenStart = remaining.length();
        } else {
            currentTokenStart = remaining.lastIndexOf(' ') + 1;
        }

        SuggestionsBuilder local = builder.createOffset(
                builder.getStart() + currentTokenStart);

        if (isPreload) {
            switch (nextPosition) {
                case 0 -> local.suggest(".mp4", Component.translatable("atvideo.suggest.file"));
                case 1 -> {
                    local.suggest("60hz", Component.translatable("atvideo.suggest.fps_60"));
                    local.suggest("30hz", Component.translatable("atvideo.suggest.fps_30"));
                }
                default -> {
                }
            }
        } else {
            switch (nextPosition) {
                case 0 -> local.suggest(".mp4", Component.translatable("atvideo.suggest.file"));
                case 1 -> local.suggest("100", Component.translatable("atvideo.suggest.volume"));
                case 2 -> {
                    local.suggest("60hz", Component.translatable("atvideo.suggest.fps_60"));
                    local.suggest("30hz", Component.translatable("atvideo.suggest.fps_30"));
                }
                case 3 -> local.suggest("0.5", Component.translatable("atvideo.suggest.fadein"));
                case 4 -> local.suggest("1", Component.translatable("atvideo.suggest.fadeout"));
                case 5 -> {
                    local.suggest("false", Component.translatable("atvideo.suggest.alpha"));
                    local.suggest("true", Component.translatable("atvideo.suggest.alpha"));
                }
                case 6 -> {
                    local.suggest("true", Component.translatable("atvideo.suggest.autoclose"));
                    local.suggest("false", Component.translatable("atvideo.suggest.autoclose"));
                }
                default -> {
                }
            }
        }

        return local.buildFuture();
    }
}