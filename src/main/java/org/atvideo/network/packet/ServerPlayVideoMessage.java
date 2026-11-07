package org.atvideo.network.packet;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.atvideo.network.AtvideoNetwork;

import java.util.List;
import java.util.function.Supplier;

/** 客户端 → 服务端：请求给 targets 广播操作。 */
public record ServerPlayVideoMessage(Action action, String targets, String rest) {

    public enum Action { PLAY, PRELOAD, STOP, UNPRELOAD }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(targets, 256);
        buf.writeUtf(rest, 2048);
    }

    public static ServerPlayVideoMessage decode(FriendlyByteBuf buf) {
        return new ServerPlayVideoMessage(buf.readEnum(Action.class),
                buf.readUtf(256), buf.readUtf(2048));
    }

    public static void handle(ServerPlayVideoMessage msg,
                              Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender == null || sender.getServer() == null) {
            return;
        }
        try {
            EntitySelector selector = new EntitySelectorParser(
                    new StringReader(msg.targets())).parse();
            List<ServerPlayer> players = selector.findPlayers(
                    sender.createCommandSourceStack());
            if (players.isEmpty()) {
                sender.sendSystemMessage(Component.translatable("atvideo.command.no_players"));
                return;
            }
            for (ServerPlayer player : players) {
                AtvideoNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new ClientPlayVideoMessage(msg.action(), msg.rest()));
            }
            String verbKey = switch (msg.action()) {
                case PLAY -> "atvideo.command.verb.play";
                case PRELOAD -> "atvideo.command.verb.preload";
                case STOP -> "atvideo.command.verb.stop";
                case UNPRELOAD -> "atvideo.command.verb.unpreload";
            };
            sender.sendSystemMessage(Component.translatable("atvideo.command.success",
                    Component.translatable(verbKey), players.size()));
        } catch (Exception exception) {
            sender.sendSystemMessage(Component.translatable("atvideo.command.invalid_selector",
                    msg.targets(), exception.getMessage()));
        }
    }
}