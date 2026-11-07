package org.atvideo.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.atvideo.AtvideoMod;
import org.atvideo.network.packet.ClientPlayVideoMessage;
import org.atvideo.network.packet.ClientStopVideoMessage;
import org.atvideo.network.packet.ServerPlayVideoMessage;

public final class AtvideoNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(AtvideoMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private AtvideoNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(ClientPlayVideoMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClientPlayVideoMessage::encode)
                .decoder(ClientPlayVideoMessage::decode)
                .consumerMainThread(ClientPlayVideoMessage::handle)
                .add();
        CHANNEL.messageBuilder(ClientStopVideoMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClientStopVideoMessage::encode)
                .decoder(ClientStopVideoMessage::decode)
                .consumerMainThread(ClientStopVideoMessage::handle)
                .add();
        CHANNEL.messageBuilder(ServerPlayVideoMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ServerPlayVideoMessage::encode)
                .decoder(ServerPlayVideoMessage::decode)
                .consumerMainThread(ServerPlayVideoMessage::handle)
                .add();
    }
}