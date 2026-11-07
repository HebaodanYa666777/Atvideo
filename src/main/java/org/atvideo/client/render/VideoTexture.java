package org.atvideo.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.atvideo.AtvideoMod;
import org.atvideo.client.player.VideoFrameBuffer;
import org.atvideo.network.model.VideoPixelFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/** 每个实例用独立的 ResourceLocation，避免切换时互相释放纹理。 */
public final class VideoTexture {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0);
    private static final int PBO_COUNT = 2;

    private final VideoFrameBuffer frameBuffer;
    private final ResourceLocation location;
    private DynamicTexture texture;
    private int width;
    private int height;
    private VideoPixelFormat pixelFormat;
    private ByteBuffer directUploadBuffer;

    private final int[] pbos = new int[PBO_COUNT];
    private int pboIndex = 0;
    private int pboSize = 0;
    private boolean pboReady = false;

    public VideoTexture(VideoFrameBuffer frameBuffer) {
        this.frameBuffer = frameBuffer;
        this.location = ResourceLocation.fromNamespaceAndPath(
                AtvideoMod.MODID, "video/t" + NEXT_ID.getAndIncrement());
    }

    public void update(long positionMs) {
        RenderSystem.assertOnRenderThread();
        VideoFrameBuffer.DecodedFrame frame = frameBuffer.takeForPosition(positionMs);
        if (frame != null) {
            upload(frame);
        }
    }

    @Nullable
    public ResourceLocation get() {
        RenderSystem.assertOnRenderThread();
        return texture == null ? null : location;
    }

    public void close() {
        RenderSystem.assertOnRenderThread();
        frameBuffer.clear();
        releaseTexture();
    }

    public void scheduleClose() {
        if (RenderSystem.isOnRenderThread()) {
            close();
        } else {
            RenderSystem.recordRenderCall(this::close);
        }
    }

    private void upload(VideoFrameBuffer.DecodedFrame frame) {
        try {
            if (texture == null || width != frame.width() || height != frame.height()
                    || pixelFormat != frame.pixelFormat()) {
                releaseTexture();
                width = frame.width();
                height = frame.height();
                pixelFormat = frame.pixelFormat();
                texture = new DynamicTexture(width, height, false);
                texture.setFilter(true, false);
                Minecraft.getInstance().getTextureManager().register(location, texture);
                createPbos(frame.data().length);
                uploadDirect(frame.data(), frame.pixelFormat());
                AtvideoMod.LOGGER.info("创建视频纹理 {}x{} ({})", width, height, location);
                return;
            }
            if (pboReady && uploadWithPbo(frame.data(), frame.pixelFormat())) {
                return;
            }
            uploadDirect(frame.data(), frame.pixelFormat());
        } finally {
            frameBuffer.release(frame);
        }
    }

    private boolean uploadWithPbo(byte[] data, VideoPixelFormat format) {
        int cur = pbos[pboIndex];
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, cur);
        try {
            ByteBuffer mapped = GL30.glMapBufferRange(
                    GL21.GL_PIXEL_UNPACK_BUFFER, 0L, pboSize,
                    GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT);
            if (mapped == null) {
                return false;
            }
            mapped.put(data);
            if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)) {
                return false;
            }
            texture.bind();
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,
                    format == VideoPixelFormat.RGB24 ? 1 : 4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    format == VideoPixelFormat.RGB24 ? GL11.GL_RGB : GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, 0L);
            pboIndex = (pboIndex + 1) % PBO_COUNT;
            return true;
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    private void uploadDirect(byte[] data, VideoPixelFormat format) {
        if (texture == null) {
            return;
        }
        if (directUploadBuffer == null || directUploadBuffer.capacity() != data.length) {
            releaseDirectUploadBuffer();
            directUploadBuffer = MemoryUtil.memAlloc(data.length);
        }
        directUploadBuffer.clear();
        directUploadBuffer.put(data);
        directUploadBuffer.flip();
        texture.bind();
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,
                    format == VideoPixelFormat.RGB24 ? 1 : 4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    format == VideoPixelFormat.RGB24 ? GL11.GL_RGB : GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, directUploadBuffer);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
    }

    private void createPbos(int size) {
        deletePbos();
        pboSize = size;
        pboIndex = 0;
        GL15.glGenBuffers(pbos);
        for (int i = 0; i < PBO_COUNT; i++) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbos[i]);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size, GL15.GL_STREAM_DRAW);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        pboReady = true;
    }

    private void deletePbos() {
        if (!pboReady) {
            return;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL15.glDeleteBuffers(pbos);
        for (int i = 0; i < PBO_COUNT; i++) {
            pbos[i] = 0;
        }
        pboSize = 0;
        pboReady = false;
    }

    private void releaseTexture() {
        deletePbos();
        releaseDirectUploadBuffer();
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(location);
        }
        texture = null;
        width = 0;
        height = 0;
        pixelFormat = null;
    }

    private void releaseDirectUploadBuffer() {
        if (directUploadBuffer != null) {
            MemoryUtil.memFree(directUploadBuffer);
            directUploadBuffer = null;
        }
    }
}