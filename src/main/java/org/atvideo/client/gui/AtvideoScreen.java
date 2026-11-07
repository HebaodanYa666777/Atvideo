package org.atvideo.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.atvideo.client.AtvideoSession;
import org.atvideo.client.player.LocalFfmpegPlayer;
import org.atvideo.client.render.VideoTexture;
import org.atvideo.network.model.PlayOptions;

public final class AtvideoScreen extends Screen {
    private final LocalFfmpegPlayer player;
    private final VideoTexture texture;
    private final PlayOptions options;

    public AtvideoScreen(LocalFfmpegPlayer player, VideoTexture texture, PlayOptions options) {
        super(Component.literal("Atvideo"));
        this.player = player;
        this.texture = texture;
        this.options = options;
    }

    @Override
    protected void init() {
        super.init();
        try {
            if (texture.get() == null) {
                texture.update(player.positionMs());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        texture.update(player.positionMs());
        ResourceLocation location = texture.get();

        int fadeAlpha = computeFadeAlpha();
        float visibility = 1.0f - fadeAlpha / 255.0f;

        if (location != null) {
            int videoW = player.videoWidth();
            int videoH = player.videoHeight();
            if (videoW > 0 && videoH > 0) {
                float videoAspect = videoW / (float) videoH;
                float screenAspect = width / (float) height;
                int drawWidth;
                int drawHeight;
                if (screenAspect > videoAspect) {
                    drawHeight = height;
                    drawWidth = Math.round(drawHeight * videoAspect);
                } else {
                    drawWidth = width;
                    drawHeight = Math.round(drawWidth / videoAspect);
                }
                int x = (width - drawWidth) / 2;
                int y = (height - drawHeight) / 2;

                if (!options.alpha()) {
                    int blackAlpha = Math.round(255 * visibility);
                    if (blackAlpha > 0) {
                        if (y > 0) {
                            graphics.fill(0, 0, width, y, blackAlpha << 24);
                        }
                        if (y + drawHeight < height) {
                            graphics.fill(0, y + drawHeight, width, height, blackAlpha << 24);
                        }
                        if (x > 0) {
                            graphics.fill(0, y, x, y + drawHeight, blackAlpha << 24);
                        }
                        if (x + drawWidth < width) {
                            graphics.fill(x + drawWidth, y, width, y + drawHeight, blackAlpha << 24);
                        }
                    }
                }

                if (visibility > 0.0f) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    if (visibility < 1.0f) {
                        graphics.setColor(1.0f, 1.0f, 1.0f, visibility);
                    }
                    graphics.blit(location, x, y, drawWidth, drawHeight,
                            0.0F, 0.0F, videoW, videoH, videoW, videoH);
                    if (visibility < 1.0f) {
                        graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
                    }
                    RenderSystem.disableBlend();
                }
            }
        }

        if (options.autoClose() && player.durationMs() > 0L
                && player.positionMs() >= player.durationMs() - 100L) {
            Minecraft.getInstance().execute(this::onClose);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int computeFadeAlpha() {
        if (options.fadeIn() <= 0.0 && options.fadeOut() <= 0.0) {
            return 0;
        }
        long positionMs = player.positionMs();
        long durationMs = player.durationMs();
        int alpha = 0;
        long fadeInMs = Math.round(options.fadeIn() * 1000.0D);
        long fadeOutMs = Math.round(options.fadeOut() * 1000.0D);
        if (fadeInMs > 0L && positionMs < fadeInMs) {
            alpha = (int) ((1.0D - positionMs / (double) fadeInMs) * 255);
        }
        if (fadeOutMs > 0L && durationMs > 0L && positionMs > durationMs - fadeOutMs) {
            long elapsed = positionMs - (durationMs - fadeOutMs);
            int outAlpha = (int) (elapsed / (double) fadeOutMs * 255);
            alpha = Math.max(alpha, outAlpha);
        }
        return Math.max(0, Math.min(255, alpha));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            player.setPaused(!player.isPlaying());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        AtvideoSession.stop();
        // stop() 里已经 setScreen(null) 了，不需要再调用 super.onClose()
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}