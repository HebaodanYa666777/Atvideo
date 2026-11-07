package org.atvideo.client.player;

import org.atvideo.network.model.VideoPixelFormat;

import java.util.ArrayDeque;

/** Keeps a small timestamp-ordered frame queue and reuses its backing arrays. */
public final class VideoFrameBuffer {
    private static final int CAPACITY = 64;
    private static final long FRAME_SELECTION_LEAD_MS = 0L;

    private final ArrayDeque<DecodedFrame> frames = new ArrayDeque<>(CAPACITY);
    private final ArrayDeque<byte[]> pool = new ArrayDeque<>();

    public synchronized byte[] acquire(int size) {
        byte[] candidate;
        while ((candidate = pool.poll()) != null) {
            if (candidate.length == size) {
                return candidate;
            }
        }
        return new byte[size];
    }

    public synchronized void submit(DecodedFrame frame) {
        if (frames.size() >= CAPACITY) {
            release(frames.remove());
        }
        frames.add(frame);
        notifyAll();   // 唤醒 awaitAnyFrame
    }

    /**
     * 等待缓冲里至少有 1 帧就绪（用于异步 play 的首帧同步）。
     * @return true = 已就绪；false = 超时或中断
     */
    public synchronized boolean awaitAnyFrame(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (frames.isEmpty()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public synchronized DecodedFrame takeForPosition(long targetPositionMs) {
        if (frames.isEmpty()) {
            return null;
        }
        if (targetPositionMs < 0L) {
            return frames.remove();
        }
        long duePositionMs = targetPositionMs + FRAME_SELECTION_LEAD_MS;
        if (frames.peek().positionMs() > duePositionMs) {
            return null;
        }
        DecodedFrame selected = frames.remove();
        while (!frames.isEmpty() && frames.peek().positionMs() <= duePositionMs) {
            DecodedFrame older = selected;
            selected = frames.remove();
            release(older);
        }
        return selected;
    }

    public synchronized void release(DecodedFrame frame) {
        if (frame == null) {
            return;
        }
        if (pool.size() < CAPACITY) {
            pool.offer(frame.data());
        }
    }

    public synchronized void clear() {
        while (!frames.isEmpty()) {
            release(frames.remove());
        }
        pool.clear();
    }

    public record DecodedFrame(int width, int height, long positionMs,
                               VideoPixelFormat pixelFormat, byte[] data) {
    }
}