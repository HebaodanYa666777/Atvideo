package org.atvideo.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.atvideo.AtvideoMod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = AtvideoMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AtvideoServerEvents {
    private AtvideoServerEvents() {
    }

    private static final Set<UUID> WELCOMED_PLAYERS = new HashSet<>();

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            WELCOMED_PLAYERS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WELCOMED_PLAYERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();

            if (!WELCOMED_PLAYERS.contains(uuid) && player.tickCount > 20) {
                WELCOMED_PLAYERS.add(uuid);

                AtvideoMod.LOGGER.info("玩家 {} 进服，检测到的客户端语言代码: {}",
                        player.getName().getString(), player.getLanguage());

                if ("zh_cn".equalsIgnoreCase(player.getLanguage())) {
                    player.sendSystemMessage(Component.translatable("atvideo.message.welcome"));
                }
            }
        }
    }
}