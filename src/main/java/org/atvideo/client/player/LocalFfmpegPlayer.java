package org.atvideo.client.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.atvideo.AtvideoMod;
import org.atvideo.client.player.ffmpeg.EmbeddedFfmpeg;
import org.atvideo.client.player.ffmpeg.FfmpegErrorCollector;
import org.atvideo.network.model.PlayOptions;
import org.atvideo.network.model.VideoPixelFormat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class LocalFfmpegPlayer {
    private static final int MAX_WIDTH = positiveProperty("atvideo.maxWidth", 3840);
    private static final int MAX_HEIGHT = positiveProperty("atvideo.maxHeight", 2160);
    private static final int MIN_TARGET_WIDTH = 1920;

    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int PIPE_ACCEPT_TIMEOUT_MS = 10_000;
    private static final int SOCKET_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final long FRAME_AHEAD_MS = 500L;

    private final VideoFrameBuffer frameBuffer;
    private final LocalAudioPlayer audioPlayer = new LocalAudioPlayer();
    private final AtomicLong generation = new AtomicLong();
    private final PlayOptions options;
    private final int screenWidth;
    private final CountDownLatch preloadLatch = new CountDownLatch(1);

    private volatile Process videoProcess;
    private volatile Process audioProcess;
    private volatile Process preparedAudioProcess;
    private volatile ServerSocket videoListener;
    private volatile Socket videoSocket;
    private volatile Path currentFile;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile long durationMs;
    private volatile double frameIntervalMs = 1000.0D / 30.0D;
    private volatile long anchorPositionMs;
    private volatile long anchorNanos;
    private volatile boolean playing;
    private volatile boolean paused;
    private volatile boolean preloading;
    private volatile boolean readingPaused;
    private volatile int preloadFrames;

    public LocalFfmpegPlayer(VideoFrameBuffer frameBuffer, PlayOptions options, int screenWidth) {
        this.frameBuffer = frameBuffer;
        this.options = options == null ? PlayOptions.DEFAULT : options;
        this.screenWidth = Math.max(1, screenWidth);
        this.preloadFrames = 30;
    }

    public long start(Path file) throws IOException {
        long duration = startInternal(file);
        readingPaused = false;
        preloading = false;
        paused = false;
        playing = true;
        anchorPositionMs = 0L;
        anchorNanos = System.nanoTime();
        Thread t = new Thread(() -> startAudioSync(file), "Atvideo-Audio-Starter");
        t.setDaemon(true);
        t.start();
        return duration;
    }

    public long prepare(Path file) throws IOException {
        long duration = startInternal(file);
        readingPaused = false;
        preloading = true;
        playing = false;
        paused = false;
        anchorPositionMs = 0L;
        anchorNanos = System.nanoTime();
        Thread t = new Thread(() -> prepareAudio(file), "Atvideo-Audio-Prepare");
        t.setDaemon(true);
        t.start();
        return duration;
    }

    public void startPlayback() {
        preloading = false;
        readingPaused = false;
        paused = false;
        playing = true;
        anchorPositionMs = 0L;
        anchorNanos = System.nanoTime();
        Thread t = new Thread(this::startAudioPlayback, "Atvideo-Audio-Play");
        t.setDaemon(true);
        t.start();
    }

    /** 阻塞等待预加载完成（30 帧就绪）。 */
    public boolean awaitPreloadComplete(long timeoutMs) {
        try {
            return preloadLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long startInternal(Path file) throws IOException {
        close();
        this.currentFile = file;
        long sessionGeneration = generation.incrementAndGet();
        MediaInfo info = probe(file);
        this.durationMs = info.durationMs;

        if (options.fps() > 0 && info.framesPerSecond > options.fps() + 0.1D) {
            this.frameIntervalMs = 1000.0D / options.fps();
        } else if (info.framesPerSecond > 0.0D) {
            this.frameIntervalMs = 1000.0D / info.framesPerSecond;
        } else {
            this.frameIntervalMs = 1000.0D / 30.0D;
        }

        OutputDimensions output = computeOutput(info.width, info.height);
        this.videoWidth = output.width;
        this.videoHeight = output.height;

        int frameSize = output.width * output.height * VideoPixelFormat.RGBA.bytesPerPixel();
        this.preloadFrames = adaptivePreloadFrames(frameSize);

        VideoPipe pipe = createVideoPipe();
        if (pipe == null) {
            throw new IOException("无法创建视频本地管道");
        }
        List<String> command = buildVideoCommand(file, info, output, pipe.url());
        AtvideoMod.LOGGER.info("启动视频 FFmpeg：{}", String.join(" ", command));

        Process started;
        try {
            started = EmbeddedFfmpeg.processBuilder(command).start();
        } catch (IOException exception) {
            pipe.close();
            throw exception;
        }
        if (generation.get() != sessionGeneration) {
            started.destroyForcibly();
            pipe.close();
            throw new IOException("播放会话已被取消");
        }
        this.videoProcess = started;
        FfmpegErrorCollector errors = new FfmpegErrorCollector(started.getErrorStream());
        errors.start();

        Thread videoThread = new Thread(() -> readVideoFrames(
                sessionGeneration, started, pipe, frameSize,
                output.width, output.height, errors), "Atvideo-Video");
        videoThread.setDaemon(true);
        videoThread.start();

        return durationMs;
    }

    private int adaptivePreloadFrames(int frameSizeBytes) {
        long frameMB = Math.max(1L, frameSizeBytes / (1024L * 1024L));
        long totalMB = 500L;
        long frames = Math.max(5L, Math.min(30L, totalMB / frameMB));
        return (int) frames;
    }

    private void prepareAudio(Path file) {
        long sessionGeneration = generation.get();
        if (file == null) return;
        if (!hasAudioStream(file)) return;
        if (generation.get() != sessionGeneration) return;

        List<String> command = buildAudioCommand(file);
        try {
            Process audio = EmbeddedFfmpeg.processBuilder(command).start();
            if (generation.get() != sessionGeneration) {
                audio.destroyForcibly();
                return;
            }
            audioProcess = audio;
            preparedAudioProcess = audio;
            FfmpegErrorCollector errors = new FfmpegErrorCollector(audio.getErrorStream());
            errors.start();
            AtvideoMod.LOGGER.info("预加载音频 FFmpeg 已就绪");
        } catch (IOException exception) {
            AtvideoMod.LOGGER.warn("预加载音频 FFmpeg 失败：{}", exception.getMessage());
        }
    }

    private void startAudioSync(Path file) {
        long sessionGeneration = generation.get();
        if (file == null) return;
        if (!hasAudioStream(file)) return;
        if (generation.get() != sessionGeneration) return;

        List<String> command = buildAudioCommand(file);
        AtvideoMod.LOGGER.info("启动音频 FFmpeg：{}", String.join(" ", command));
        try {
            Process audio = EmbeddedFfmpeg.processBuilder(command).start();
            if (generation.get() != sessionGeneration) {
                audio.destroyForcibly();
                return;
            }
            audioProcess = audio;
            FfmpegErrorCollector errors = new FfmpegErrorCollector(audio.getErrorStream());
            errors.start();
            if (generation.get() != sessionGeneration) {
                audio.destroyForcibly();
                audioProcess = null;
                return;
            }
            audioPlayer.start(audio.getInputStream(), options.volume(), this::positionMs);
        } catch (IOException exception) {
            AtvideoMod.LOGGER.warn("启动音频 FFmpeg 失败：{}", exception.getMessage());
        }
    }

    private void startAudioPlayback() {
        long sessionGeneration = generation.get();
        if (generation.get() != sessionGeneration) return;

        Process prepared = preparedAudioProcess;
        preparedAudioProcess = null;
        if (prepared != null && prepared.isAlive()) {
            if (generation.get() != sessionGeneration) {
                prepared.destroyForcibly();
                return;
            }
            audioProcess = prepared;
            audioPlayer.start(prepared.getInputStream(), options.volume(), this::positionMs);
        } else {
            startAudioSync(currentFile);
        }
    }

    public void setVolume(int volume) {
        audioPlayer.setVolume(volume);
    }

    private boolean hasAudioStream(Path file) {
        List<String> command = List.of(
                EmbeddedFfmpeg.ffprobeExecutable(),
                "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0",
                file.toAbsolutePath().toString());
        try {
            Process probe = EmbeddedFfmpeg.processBuilder(command).start();
            if (!probe.waitFor(10L, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            String output = new String(probe.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return !output.isBlank();
        } catch (Exception exception) {
            return false;
        }
    }

    private List<String> buildAudioCommand(Path file) {
        List<String> command = new ArrayList<>();
        command.add(EmbeddedFfmpeg.ffmpegExecutable());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-i");
        command.add(file.toAbsolutePath().toString());
        command.add("-vn");
        command.add("-sn");
        command.add("-dn");
        command.add("-map");
        command.add("0:a:0");
        command.add("-af");
        command.add("aresample=async=1:first_pts=0");
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add("-ac");
        command.add(Integer.toString(AUDIO_CHANNELS));
        command.add("-ar");
        command.add(Integer.toString(AUDIO_SAMPLE_RATE));
        command.add("-f");
        command.add("s16le");
        command.add("pipe:1");
        return command;
    }

    public int videoWidth() { return videoWidth; }
    public int videoHeight() { return videoHeight; }
    public long durationMs() { return durationMs; }
    public boolean isPlaying() { return playing && !paused; }

    public long positionMs() {
        long position = anchorPositionMs;
        if (playing && !paused) {
            position += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - anchorNanos);
        }
        return durationMs > 0L ? Math.min(position, durationMs) : position;
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        long now = System.nanoTime();
        long current = anchorPositionMs;
        if (playing && !this.paused) {
            current += TimeUnit.NANOSECONDS.toMillis(now - anchorNanos);
        }
        anchorPositionMs = durationMs > 0L ? Math.min(current, durationMs) : current;
        anchorNanos = now;
        this.paused = paused;
    }

    public void close() {
        generation.incrementAndGet();
        playing = false;
        paused = false;
        preloading = false;
        readingPaused = false;
        preloadLatch.countDown();   // 防止 awaitPreloadComplete 永久阻塞

        Process vp = videoProcess;
        videoProcess = null;
        if (vp != null) {
            vp.descendants().forEach(ProcessHandle::destroyForcibly);
            vp.destroyForcibly();
        }
        Process ap = audioProcess;
        audioProcess = null;
        if (ap != null) {
            ap.descendants().forEach(ProcessHandle::destroyForcibly);
            ap.destroyForcibly();
        }
        Process pa = preparedAudioProcess;
        preparedAudioProcess = null;
        if (pa != null && pa != ap) {
            pa.descendants().forEach(ProcessHandle::destroyForcibly);
            pa.destroyForcibly();
        }
        closeVideoPipe();
        audioPlayer.stop();
        frameBuffer.clear();
    }

    private void readVideoFrames(long sessionGeneration, Process ffmpeg, VideoPipe pipe,
                                 int frameSize, int width, int height,
                                 FfmpegErrorCollector errors) {
        long framesRead = 0L;
        try (InputStream input = pipe.accept(ffmpeg)) {
            while (generation.get() == sessionGeneration) {
                if (readingPaused || paused) {
                    LockSupport.parkNanos(15_000_000L);
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    continue;
                }
                byte[] frame = frameBuffer.acquire(frameSize);
                int offset = 0;
                boolean complete = true;
                while (offset < frameSize) {
                    if (generation.get() != sessionGeneration) {
                        frameBuffer.release(new VideoFrameBuffer.DecodedFrame(
                                width, height, 0L, VideoPixelFormat.RGBA, frame));
                        return;
                    }
                    int read = input.read(frame, offset, frameSize - offset);
                    if (read < 0) {
                        complete = false;
                        break;
                    }
                    offset += read;
                }
                if (!complete) {
                    frameBuffer.release(new VideoFrameBuffer.DecodedFrame(
                            width, height, 0L, VideoPixelFormat.RGBA, frame));
                    break;
                }

                long framePositionMs = Math.round(framesRead * frameIntervalMs);

                if (!preloading) {
                    while (generation.get() == sessionGeneration
                            && !readingPaused && !paused
                            && framePositionMs > positionMs() + FRAME_AHEAD_MS) {
                        LockSupport.parkNanos(2_000_000L);
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }
                    }
                    if (generation.get() != sessionGeneration) {
                        frameBuffer.release(new VideoFrameBuffer.DecodedFrame(
                                width, height, 0L, VideoPixelFormat.RGBA, frame));
                        return;
                    }
                }

                frameBuffer.submit(new VideoFrameBuffer.DecodedFrame(
                        width, height, framePositionMs, VideoPixelFormat.RGBA, frame));
                framesRead++;
                if (preloading && framesRead >= preloadFrames) {
                    readingPaused = true;
                    preloadLatch.countDown();   // 通知 awaitPreloadComplete
                    AtvideoMod.LOGGER.info("视频预加载完成：{} 帧就绪（约 {} ms）",
                            framesRead, Math.round(framesRead * frameIntervalMs));
                }
            }
        } catch (IOException exception) {
            if (generation.get() == sessionGeneration && !paused) {
                AtvideoMod.LOGGER.warn("视频管道异常：{}", exception.getMessage());
            }
        } finally {
            try {
                errors.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            String stderr = errors.text();
            if (!stderr.isBlank() && generation.get() == sessionGeneration) {
                AtvideoMod.LOGGER.warn("视频 FFmpeg stderr: {}", stderr);
            }
            closeVideoPipe();
        }
    }

    private VideoPipe createVideoPipe() {
        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
            listener.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            listener.setSoTimeout(Math.min(250, PIPE_ACCEPT_TIMEOUT_MS));
            videoListener = listener;
            return new VideoPipe(listener);
        } catch (IOException exception) {
            AtvideoMod.LOGGER.error("创建视频管道失败", exception);
            return null;
        }
    }

    private void closeVideoPipe() {
        try {
            Socket socket = videoSocket;
            videoSocket = null;
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        try {
            ServerSocket listener = videoListener;
            videoListener = null;
            if (listener != null) {
                listener.close();
            }
        } catch (IOException ignored) {
        }
    }

    private List<String> buildVideoCommand(Path file, MediaInfo info,
                                           OutputDimensions output, String videoPipeUrl) {
        List<String> command = new ArrayList<>();
        command.add(EmbeddedFfmpeg.ffmpegExecutable());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-i");
        command.add(file.toAbsolutePath().toString());

        command.add("-map");
        command.add("0:v:0");
        command.add("-an");
        command.add("-sn");
        command.add("-dn");

        List<String> filters = buildFilters(info, output);
        if (!filters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", filters));
        }

        command.add("-pix_fmt");
        command.add("rgba");
        command.add("-fps_mode");
        command.add("passthrough");
        command.add("-f");
        command.add("rawvideo");
        command.add("-flush_packets");
        command.add("1");
        command.add(videoPipeUrl);
        return command;
    }

    private List<String> buildFilters(MediaInfo info, OutputDimensions output) {
        boolean needsScaling = output.width != info.width || output.height != info.height;
        List<String> filters = new ArrayList<>();
        if (options.fps() > 0 && info.framesPerSecond > options.fps() + 0.1D) {
            filters.add(String.format(Locale.ROOT, "fps=%d", options.fps()));
        }
        if (needsScaling) {
            filters.add(String.format(Locale.ROOT,
                    "scale=%d:%d:flags=lanczos+accurate_rnd+full_chroma_int",
                    output.width, output.height));
        }
        return filters;
    }

    private MediaInfo probe(Path file) throws IOException {
        List<String> command = List.of(
                EmbeddedFfmpeg.ffprobeExecutable(),
                "-v", "error",
                "-show_entries",
                "stream=codec_type,width,height,avg_frame_rate:format=duration",
                "-of", "json",
                file.toAbsolutePath().toString());
        Process probe;
        try {
            probe = EmbeddedFfmpeg.processBuilder(command).start();
        } catch (IOException exception) {
            throw new IOException("无法启动 ffprobe", exception);
        }
        FfmpegErrorCollector errors = new FfmpegErrorCollector(probe.getErrorStream());
        errors.start();
        try {
            if (!probe.waitFor(30L, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                throw new IOException("ffprobe 超时");
            }
            String output = new String(probe.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (probe.exitValue() != 0) {
                throw new IOException("ffprobe 返回状态码 " + probe.exitValue()
                        + "：" + errors.text());
            }
            JsonObject root = JsonParser.parseString(output).getAsJsonObject();
            int width = 0;
            int height = 0;
            double fps = 0.0D;
            if (root.has("streams")) {
                for (var element : root.getAsJsonArray("streams")) {
                    JsonObject stream = element.getAsJsonObject();
                    if (stream.has("codec_type")
                            && "video".equals(stream.get("codec_type").getAsString())) {
                        width = stream.get("width").getAsInt();
                        height = stream.get("height").getAsInt();
                        if (stream.has("avg_frame_rate")) {
                            fps = parseFrameRate(stream.get("avg_frame_rate").getAsString());
                        }
                        break;
                    }
                }
            }
            if (width <= 0 || height <= 0) {
                throw new IOException("未找到视频流");
            }
            long durationMs = 0L;
            if (root.has("format")) {
                JsonObject format = root.getAsJsonObject("format");
                if (format.has("duration")) {
                    try {
                        durationMs = Math.max(0L,
                                Math.round(format.get("duration").getAsDouble() * 1000.0D));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return new MediaInfo(width, height, durationMs, fps);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("ffprobe 被中断", exception);
        } finally {
            if (probe.isAlive()) {
                probe.destroyForcibly();
            }
        }
    }

    private static double parseFrameRate(String value) {
        String[] parts = value.split("/", 2);
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length == 2 ? Double.parseDouble(parts[1]) : 1.0D;
            if (numerator <= 0.0D || denominator <= 0.0D) {
                return 0.0D;
            }
            double fps = numerator / denominator;
            return Double.isFinite(fps) && fps > 0.0D ? fps : 0.0D;
        } catch (NumberFormatException exception) {
            return 0.0D;
        }
    }

    private OutputDimensions computeOutput(int sourceWidth, int sourceHeight) {
        int screenCap = Math.max(MIN_TARGET_WIDTH, screenWidth);
        int sourceCap = Math.max(MIN_TARGET_WIDTH, sourceWidth);
        int targetWidth = Math.min(MAX_WIDTH, Math.min(screenCap, sourceCap));
        targetWidth &= ~1;

        double scale = targetWidth / (double) sourceWidth;
        int targetHeight = Math.max(2, ((int) Math.round(sourceHeight * scale)) & ~1);
        if (targetHeight > MAX_HEIGHT) {
            double fit = MAX_HEIGHT / (double) targetHeight;
            targetHeight = MAX_HEIGHT & ~1;
            targetWidth = Math.max(2, ((int) Math.round(targetWidth * fit)) & ~1);
        }
        return new OutputDimensions(targetWidth, targetHeight);
    }

    private static int positiveProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        return value > 0 ? value : fallback;
    }

    private record MediaInfo(int width, int height, long durationMs, double framesPerSecond) {
    }

    private record OutputDimensions(int width, int height) {
    }

    private final class VideoPipe {
        private final ServerSocket listener;

        private VideoPipe(ServerSocket listener) {
            this.listener = listener;
        }

        private String url() {
            return "tcp://127.0.0.1:" + listener.getLocalPort()
                    + "?tcp_nodelay=1&send_buffer_size=" + SOCKET_BUFFER_BYTES;
        }

        private InputStream accept(Process ffmpeg) throws IOException {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(PIPE_ACCEPT_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                try {
                    Socket socket = listener.accept();
                    socket.setTcpNoDelay(true);
                    socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
                    videoSocket = socket;
                    try {
                        listener.close();
                    } catch (IOException ignored) {
                    }
                    return socket.getInputStream();
                } catch (SocketTimeoutException exception) {
                    if (!ffmpeg.isAlive()) {
                        throw new IOException("FFmpeg 在视频管道建立前退出", exception);
                    }
                }
            }
            throw new IOException("视频管道连接超时");
        }

        private void close() {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
    }
}