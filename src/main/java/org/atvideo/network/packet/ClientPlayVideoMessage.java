package org.atvideo.network.packet;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.atvideo.client.AtvideoSession;

import java.util.function.Supplier;

public record ClientPlayVideoMessage(ServerPlayVideoMessage.Action action, String rest) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(rest, 2048);
    }

    public static ClientPlayVideoMessage decode(FriendlyByteBuf buf) {
        return new ClientPlayVideoMessage(
                buf.readEnum(ServerPlayVideoMessage.Action.class),
                buf.readUtf(2048));
    }

    public static void handle(ClientPlayVideoMessage msg,
                              Supplier<NetworkEvent.Context> ctx) {
        Minecraft.getInstance().execute(() -> {
            switch (msg.action()) {
                case PLAY -> AtvideoSession.play(msg.rest());
                case PRELOAD -> AtvideoSession.preload(msg.rest());
                case STOP -> AtvideoSession.stop();
                case UNPRELOAD -> AtvideoSession.unpreload();
            }
        });
    }
}