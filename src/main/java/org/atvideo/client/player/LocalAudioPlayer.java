package org.atvideo.client.player;

import org.atvideo.AtvideoMod;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public final class LocalAudioPlayer {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int FRAME_SIZE = CHANNELS * Short.BYTES;
    private static final int CHUNK_FRAMES = SAMPLE_RATE / 50;
    private static final int LINE_BUFFER_FRAMES = SAMPLE_RATE / 20;

    private static final long LEAD_TOLERANCE_MS = 100L;

    private volatile AtomicBoolean running;
    private volatile SourceDataLine line;
    private volatile int volume = 100;
    private volatile LongSupplier videoClock;
    private volatile Thread thread;
    private final Object lock = new Object();

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
        applyVolume();
    }

    public int volume() {
        return volume;
    }

    public void start(InputStream pcm, int volume, LongSupplier videoClock) {
        synchronized (lock) {
            stopLocked();

            this.volume = Math.max(0, Math.min(100, volume));
            this.videoClock = videoClock;

            AtomicBoolean newRunning = new AtomicBoolean(true);
            this.running = newRunning;

            Thread t = new Thread(() -> run(pcm, newRunning), "Atvideo-Audio");
            t.setDaemon(true);
            this.thread = t;
            t.start();
        }
    }

    private void run(InputStream pcm, AtomicBoolean myRunning) {
        SourceDataLine localLine = null;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            localLine = (SourceDataLine) AudioSystem.getLine(info);
            localLine.open(format, LINE_BUFFER_FRAMES * FRAME_SIZE);
            localLine.start();

            if (!myRunning.get() || running != myRunning) {
                return;
            }
            synchronized (lock) {
                if (!myRunning.get() || running != myRunning) {
                    return;
                }
                this.line = localLine;
                applyVolume();
            }

            byte[] buffer = new byte[CHUNK_FRAMES * FRAME_SIZE];
            long initialFramePos = -1L;

            while (myRunning.get()) {
                int read;
                try {
                    read = pcm.read(buffer);
                } catch (Exception e) {
                    break;
                }
                if (read < 0) break;
                if (read <= 0) continue;

                if (!myRunning.get() || running != myRunning) break;

                LongSupplier clock = videoClock;
                if (clock != null) {
                    try {
                        long currentFramePos = localLine.getLongFramePosition();
                        if (initialFramePos < 0L) {
                            initialFramePos = currentFramePos;
                        }
                        long playedFrames = currentFramePos - initialFramePos;
                        long playedMs = playedFrames * 1000L / SAMPLE_RATE;
                        long videoMs = clock.getAsLong();
                        long drift = playedMs - videoMs;

                        if (drift > LEAD_TOLERANCE_MS) {
                            long sleepMs = Math.min(drift - 20L, 100L);
                            if (sleepMs > 0) {
                                try {
                                    Thread.sleep(sleepMs);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                if (!myRunning.get()) break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        break;
                    }
                }

                try {
                    localLine.write(buffer, 0, read);
                } catch (IllegalStateException e) {
                    break;
                }
            }

            if (myRunning.get()) {
                try {
                    localLine.drain();
                } catch (IllegalStateException ignored) {
                }
            }
        } catch (Exception exception) {
            if (myRunning.get() && running == myRunning) {
                AtvideoMod.LOGGER.warn("音频播放失败（将继续无声播放）：{}", exception.getMessage());
            }
        } finally {
            if (localLine != null) {
                try { localLine.stop(); } catch (Exception ignored) {}
                try { localLine.flush(); } catch (Exception ignored) {}
                try { localLine.close(); } catch (Exception ignored) {}
            }
            synchronized (lock) {
                if (line == localLine) {
                    line = null;
                }
            }
        }
    }

    private void applyVolume() {
        SourceDataLine current = line;
        if (current == null) {
            return;
        }
        try {
            if (current.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) current.getControl(FloatControl.Type.MASTER_GAIN);
                float db = volume <= 0 ? gain.getMinimum()
                        : (float) (20.0 * Math.log10(volume / 100.0));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
        } catch (RuntimeException ignored) {
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    /** 必须在 lock 内调用。不 join，让旧线程自然退出（< 50ms 后台完成）。 */
    private void stopLocked() {
        AtomicBoolean current = running;
        running = null;
        if (current != null) current.set(false);

        SourceDataLine curLine = line;
        line = null;
        if (curLine != null) {
            try { curLine.stop(); } catch (Exception ignored) {}
            try { curLine.flush(); } catch (Exception ignored) {}
            try { curLine.close(); } catch (Exception ignored) {}
        }

        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();   // 唤醒 sleep，不等待
        }
    }
}