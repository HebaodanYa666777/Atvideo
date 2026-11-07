package org.atvideo.network.packet;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.atvideo.client.AtvideoSession;

import java.util.function.Supplier;

/** 服务端 → 客户端：停止播放。 */
public record ClientStopVideoMessage() {
    public void encode(FriendlyByteBuf buf) {
    }

    public static ClientStopVideoMessage decode(FriendlyByteBuf buf) {
        return new ClientStopVideoMessage();
    }

    public static void handle(ClientStopVideoMessage msg,
                              Supplier<NetworkEvent.Context> ctx) {
        Minecraft.getInstance().execute(AtvideoSession::stop);
    }
}