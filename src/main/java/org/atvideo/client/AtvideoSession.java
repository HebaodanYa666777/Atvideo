package org.atvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.atvideo.AtvideoMod;
import org.atvideo.client.gui.AtvideoScreen;
import org.atvideo.client.player.LocalFfmpegPlayer;
import org.atvideo.client.player.VideoFrameBuffer;
import org.atvideo.client.render.VideoTexture;
import org.atvideo.network.model.PlayOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AtvideoSession {
    private static final long PRELOAD_TTL_NANOS = TimeUnit.MINUTES.toNanos(30L);
    private static final long PRELOAD_TIMEOUT_MS = 15000L;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3000L;

    private static LocalFfmpegPlayer player;
    private static VideoFrameBuffer frameBuffer;
    private static VideoTexture texture;
    private static Path currentFile;
    private static PlayOptions currentOptions = PlayOptions.DEFAULT;

    private static Preloaded preloaded;
    private static volatile PreloadInProgress preloadInProgress;
    private static final AtomicLong playGeneration = new AtomicLong();

    private AtvideoSession() {
    }

    public static void play(String rest) {
        String[] tokens = rest.trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isBlank()) {
            return;
        }
        play(tokens[0], PlayOptions.parse(tokens, 1));
    }

    public static void play(String fileName, PlayOptions options) {
        Path videoDir = AtvideoClient.atvideoDirectory();
        Path file = videoDir.resolve(fileName).normalize();
        if (!file.startsWith(videoDir)) {
            AtvideoMod.LOGGER.warn("拒绝非法文件名：{}", fileName);
            return;
        }
        if (!Files.isRegularFile(file)) {
            AtvideoMod.LOGGER.warn("播放文件不存在：{}", file);
            return;
        }

        long myGen = playGeneration.incrementAndGet();

        PreloadInProgress inProgress = preloadInProgress;
        if (inProgress != null && inProgress.path.equals(file)) {
            inProgress.cancelled.set(true);
        }

        // 命中预加载：秒开
        if (hasPreloaded()
                && preloaded.path.equals(file)
                && preloaded.options.matchesDecoding(options)) {
            Preloaded hit = preloaded;
            preloaded = null;

            LocalFfmpegPlayer oldPlayer = player;
            VideoTexture oldTexture = texture;

            player = hit.player;
            frameBuffer = hit.frameBuffer;
            texture = hit.texture;
            currentFile = file;
            currentOptions = options;

            hit.player.setVolume(options.volume());
            hit.player.startPlayback();

            Minecraft.getInstance().setScreen(
                    new AtvideoScreen(hit.player, hit.texture, options));

            if (oldPlayer != null) oldPlayer.close();
            if (oldTexture != null) oldTexture.scheduleClose();

            AtvideoMod.LOGGER.info("从预加载秒开播放：{}（fps={}）",
                    file.getFileName(), options.fps());
            return;
        }

        if (preloaded != null) {
            unpreload();
        }

        // 判断是否正在播放
        boolean alreadyPlaying = Minecraft.getInstance().screen instanceof AtvideoScreen
                && player != null;

        if (!alreadyPlaying) {
            int screenWidth = Minecraft.getInstance().getWindow().getWidth();
            frameBuffer = new VideoFrameBuffer();
            player = new LocalFfmpegPlayer(frameBuffer, options, screenWidth);
            texture = new VideoTexture(frameBuffer);
            currentOptions = options;

            try {
                long duration = player.start(file);
                currentFile = file;
                AtvideoMod.LOGGER.info("开始播放 {}（{}x{}，{} 秒，fps={}）",
                        file.getFileName(), player.videoWidth(), player.videoHeight(),
                        duration / 1000.0D, options.fps());
                Minecraft.getInstance().setScreen(new AtvideoScreen(player, texture, options));
            } catch (IOException exception) {
                AtvideoMod.LOGGER.error("播放失败：{}", file, exception);
                stopPlaying();
            }
            return;
        }

        // 正在播放：异步启动新视频，无缝切换
        final LocalFfmpegPlayer oldPlayer = player;
        final VideoTexture oldTexture = texture;

        int screenWidth = Minecraft.getInstance().getWindow().getWidth();
        VideoFrameBuffer newBuffer = new VideoFrameBuffer();
        LocalFfmpegPlayer newPlayer = new LocalFfmpegPlayer(newBuffer, options, screenWidth);
        VideoTexture newTexture = new VideoTexture(newBuffer);

        Thread starter = new Thread(() -> {
            try {
                newPlayer.start(file);
                boolean ok = newBuffer.awaitAnyFrame(FIRST_FRAME_TIMEOUT_MS);

                Minecraft.getInstance().execute(() -> {
                    if (myGen != playGeneration.get()) {
                        newPlayer.close();
                        newTexture.scheduleClose();
                        return;
                    }

                    if (!ok) {
                        newPlayer.close();
                        newTexture.scheduleClose();
                        return;
                    }

                    player = newPlayer;
                    frameBuffer = newBuffer;
                    texture = newTexture;
                    currentFile = file;
                    currentOptions = options;

                    Minecraft.getInstance().setScreen(
                            new AtvideoScreen(newPlayer, newTexture, options));

                    if (oldPlayer != null) oldPlayer.close();
                    if (oldTexture != null) oldTexture.scheduleClose();
                });
            } catch (IOException exception) {
                Minecraft.getInstance().execute(() -> {
                    newPlayer.close();
                    newTexture.scheduleClose();
                });
            }
        }, "Atvideo-Play-Starter");
        starter.setDaemon(true);
        starter.start();
    }

    public static void preload(String rest) {
        String trimmed = rest.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String[] tokens = trimmed.split("\\s+");
        String fileName = tokens[0];
        int fps = 0;
        if (tokens.length > 1) {
            fps = parseFpsToken(tokens[1]);
        }
        PlayOptions options = new PlayOptions(100, fps, 0.0D, 0.0D, false, true);
        preload(fileName, options);
    }

    private static int parseFpsToken(String token) {
        if (token == null || token.isBlank()) return 0;
        String v = token.toLowerCase().trim();
        int eq = v.indexOf('=');
        if (eq > 0) v = v.substring(eq + 1).trim();
        if (v.endsWith("hz")) v = v.substring(0, v.length() - 2).trim();
        else if (v.endsWith("fps")) v = v.substring(0, v.length() - 3).trim();
        try {
            return Math.max(0, Math.min(240, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void preload(String fileName, PlayOptions options) {
        Path videoDir = AtvideoClient.atvideoDirectory();
        Path file = videoDir.resolve(fileName).normalize();
        if (!file.startsWith(videoDir) || !Files.isRegularFile(file)) {
            return;
        }

        unpreload();

        if (currentFile != null && currentFile.equals(file) && player != null) {
            return;
        }

        PreloadInProgress old = preloadInProgress;
        if (old != null) {
            old.cancelled.set(true);
        }

        int screenWidth = Minecraft.getInstance().getWindow().getWidth();
        VideoFrameBuffer buffer = new VideoFrameBuffer();
        VideoTexture tex = new VideoTexture(buffer);
        LocalFfmpegPlayer newPlayer = new LocalFfmpegPlayer(buffer, options, screenWidth);

        try {
            newPlayer.prepare(file);

            PreloadInProgress inProgress = new PreloadInProgress(file, newPlayer);
            preloadInProgress = inProgress;

            Thread waiter = new Thread(() -> {
                boolean ok = newPlayer.awaitPreloadComplete(PRELOAD_TIMEOUT_MS);
                Minecraft.getInstance().execute(() -> {
                    if (inProgress.cancelled.get() || inProgress != preloadInProgress) {
                        newPlayer.close();
                        tex.scheduleClose();
                        return;
                    }
                    preloadInProgress = null;

                    if (ok && !inProgress.cancelled.get()) {
                        preloaded = new Preloaded(fileName, file, newPlayer, buffer, tex,
                                options, System.nanoTime() + PRELOAD_TTL_NANOS);

                        RenderSystem.recordRenderCall(() -> {
                            try {
                                tex.update(0L);
                            } catch (Exception ignored) {
                            }
                        });
                    } else {
                        newPlayer.close();
                        tex.scheduleClose();
                    }
                });
            }, "Atvideo-Preload-Waiter");
            waiter.setDaemon(true);
            waiter.start();

        } catch (IOException exception) {
            newPlayer.close();
            tex.scheduleClose();
        }
    }

    public static void unpreload() {
        PreloadInProgress inProgress = preloadInProgress;
        if (inProgress != null) {
            inProgress.cancelled.set(true);
            inProgress.player.close();
            preloadInProgress = null;
        }
        if (preloaded == null) {
            return;
        }
        preloaded.player.close();
        preloaded.texture.scheduleClose();
        preloaded = null;
    }

    public static boolean hasPreloaded() {
        if (preloaded == null) {
            return false;
        }
        if (System.nanoTime() >= preloaded.expiresAtNanos) {
            unpreload();
            return false;
        }
        return true;
    }

    public static void stopPlaying() {
        if (player != null) {
            player.close();
            player = null;
        }
        if (texture != null) {
            texture.scheduleClose();
            texture = null;
        }
        frameBuffer = null;
        currentFile = null;
    }

    public static void stop() {
        playGeneration.incrementAndGet();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AtvideoScreen) {
            minecraft.setScreen(null);
        }
        stopPlaying();
        unpreload();
    }

    public static boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public static Path currentFile() {
        return currentFile;
    }

    public static PlayOptions currentOptions() {
        return currentOptions;
    }

    private record Preloaded(String fileName, Path path, LocalFfmpegPlayer player,
                             VideoFrameBuffer frameBuffer, VideoTexture texture,
                             PlayOptions options, long expiresAtNanos) {
    }

    private static final class PreloadInProgress {
        final Path path;
        final LocalFfmpegPlayer player;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        PreloadInProgress(Path path, LocalFfmpegPlayer player) {
            this.path = path;
            this.player = player;
        }
    }
}