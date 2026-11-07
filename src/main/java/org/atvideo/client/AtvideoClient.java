package org.atvideo.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.atvideo.AtvideoMod;
import org.atvideo.client.player.ffmpeg.EmbeddedFfmpeg;

import java.nio.file.Files;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = AtvideoMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AtvideoClient {
    private static Path atvideoDirectory;

    private AtvideoClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            createAtvideoDirectory();
            Thread checker = new Thread(() -> {
                boolean available = EmbeddedFfmpeg.verifyExecutables();
                if (available) {
                    AtvideoMod.LOGGER.info("Atvideo 已就绪");
                } else {
                    AtvideoMod.LOGGER.warn("Atvideo 未找到 FFmpeg，无法播放视频");
                }
            }, "Atvideo-FFmpeg-Check");
            checker.setDaemon(true);
            checker.start();
        });
    }

    public static Path atvideoDirectory() {
        if (atvideoDirectory == null) {
            atvideoDirectory = FMLPaths.GAMEDIR.get().resolve("atvideo");
        }
        return atvideoDirectory;
    }

    private static void createAtvideoDirectory() {
        try {
            Path directory = atvideoDirectory();
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                AtvideoMod.LOGGER.info("已创建 atvideo 目录：{}", directory);
            }
        } catch (Exception exception) {
            AtvideoMod.LOGGER.error("无法创建 atvideo 目录", exception);
        }
    }
}