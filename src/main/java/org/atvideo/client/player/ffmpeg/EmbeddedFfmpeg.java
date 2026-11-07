package org.atvideo.client.player.ffmpeg;

import net.minecraft.client.Minecraft;
import org.atvideo.AtvideoMod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * FFmpeg 定位策略（从上到下尝试）：
 *   1) JVM 参数 -Datvideo.ffmpeg=/path/to/ffmpeg[.exe]
 *   2) 游戏目录 atvideo/ffmpeg/bin/ffmpeg[.exe]（用户手动放置）
 *   3) JAR 内嵌 /META-INF/ffmpeg/<platform>/ffmpeg.zip（解压到 atvideo/.cache/ffmpeg/）
 *   4) 系统 PATH
 */
public final class EmbeddedFfmpeg {
    private static final String BUNDLE_ID_FILE = "bundle.id";
    private static final String COMPLETE_MARKER = ".complete";
    private static final List<String> LINK_MANIFEST_NAMES =
            List.of(".atvideo-links", ".video-synchronizer-links");
    private static final long EXECUTABLE_CHECK_TIMEOUT_SECONDS = 10L;

    private static volatile Executables resolved;

    private EmbeddedFfmpeg() {
    }

    public static String ffmpegExecutable() {
        return executables().ffmpeg();
    }

    public static String ffprobeExecutable() {
        return executables().ffprobe();
    }

    public static boolean verifyExecutables() {
        try {
            Executables current = executables();
            verify("ffmpeg", current.ffmpeg());
            verify("ffprobe", current.ffprobe());
            AtvideoMod.LOGGER.info("FFmpeg 检查通过：{}", current.ffmpeg());
            return true;
        } catch (Exception exception) {
            AtvideoMod.LOGGER.error("FFmpeg 不可用，本地视频播放将被禁用", exception);
            return false;
        }
    }

    public static ProcessBuilder processBuilder(List<String> command) {
        Executables current = executables();
        ProcessBuilder builder = new ProcessBuilder(command);
        if (current.libraryPath() != null) {
            String inherited = builder.environment().get("LD_LIBRARY_PATH");
            String libraryPath = current.libraryPath();
            if (inherited != null && !inherited.isBlank()) {
                libraryPath += File.pathSeparator + inherited;
            }
            builder.environment().put("LD_LIBRARY_PATH", libraryPath);
        }
        return builder;
    }

    private static void verify(String name, String executable) throws Exception {
        Process process = new ProcessBuilder(executable, "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(EXECUTABLE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException(name + " 无响应");
        }
        if (process.exitValue() != 0) {
            throw new IOException(name + " 返回状态码 " + process.exitValue());
        }
    }

    private static Executables executables() {
        Executables current = resolved;
        if (current != null) {
            return current;
        }
        synchronized (EmbeddedFfmpeg.class) {
            if (resolved == null) {
                resolved = resolve();
            }
            return resolved;
        }
    }

    private static Executables resolve() {
        // 1) JVM 参数
        String configured = System.getProperty("atvideo.ffmpeg");
        if (configured != null && !configured.isBlank()) {
            AtvideoMod.LOGGER.info("使用 JVM 参数指定的 FFmpeg：{}", configured);
            return externalExecutables(configured);
        }

        boolean windows = isWindows();
        String suffix = windows ? ".exe" : "";

        // 2) 游戏目录手动放置
        Path manualDir = gameDir().resolve("atvideo").resolve("ffmpeg").resolve("bin");
        Path manualFfmpeg = manualDir.resolve("ffmpeg" + suffix);
        Path manualFfprobe = manualDir.resolve("ffprobe" + suffix);
        if (Files.isRegularFile(manualFfmpeg) && Files.isRegularFile(manualFfprobe)) {
            makeExecutableIfNeeded(manualFfmpeg);
            makeExecutableIfNeeded(manualFfprobe);
            AtvideoMod.LOGGER.info("使用游戏目录手动放置的 FFmpeg：{}", manualDir);
            return new Executables(manualFfmpeg.toString(), manualFfprobe.toString(),
                    windows ? null : manualDir.toString());
        }

        // 3) JAR 内嵌资源
        Platform platform = currentPlatform();
        if (platform != null) {
            String resource = resourcePath(platform);
            if (resourceExists(resource)) {
                try {
                    Path directory = installEmbeddedBundle(platform, resource);
                    AtvideoMod.LOGGER.info("使用内嵌 FFmpeg（{}）：{}",
                            platform.displayName(), directory);
                    return new Executables(
                            executablePath(directory, platform, "ffmpeg").toString(),
                            executablePath(directory, platform, "ffprobe").toString(),
                            platform.windows() ? null : directory.resolve("lib").toString());
                } catch (IOException exception) {
                    AtvideoMod.LOGGER.warn("解压内嵌 FFmpeg 失败，回退到 PATH", exception);
                }
            } else {
                AtvideoMod.LOGGER.info("本 JAR 未包含 {} 平台的 FFmpeg，回退到 PATH",
                        platform.displayName());
            }
        }

        // 4) PATH
        AtvideoMod.LOGGER.info("使用系统 PATH 中的 FFmpeg");
        return new Executables("ffmpeg" + suffix, "ffprobe" + suffix, null);
    }

    private static Path installEmbeddedBundle(Platform platform, String resource)
            throws IOException {
        String bundleId = readBundleId(platform);
        Path cacheRoot = gameDir().resolve("atvideo").resolve(".cache").resolve("ffmpeg");
        Files.createDirectories(cacheRoot);
        Path installDirectory = cacheRoot.resolve(bundleId).resolve(platform.id());
        Path lockPath = cacheRoot.resolve(platform.id() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (!isStoredBundleComplete(installDirectory, platform, bundleId)) {
                deleteTree(installDirectory);
                Path staging = Files.createTempDirectory(cacheRoot, bundleId + ".tmp-");
                try {
                    extractBundle(resource, platform, staging);
                    Files.writeString(staging.resolve(COMPLETE_MARKER), bundleId,
                            StandardOpenOption.CREATE_NEW);
                    Files.createDirectories(installDirectory.getParent());
                    try {
                        Files.move(staging, installDirectory, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(staging, installDirectory);
                    }
                } finally {
                    deleteTree(staging);
                }
            }
        }
        if (!isStoredBundleComplete(installDirectory, platform, bundleId)) {
            throw new IOException("内嵌 FFmpeg 解压后缺少必要文件");
        }
        return installDirectory;
    }

    private static boolean isStoredBundleComplete(Path directory, Platform platform,
                                                  String bundleId) {
        Path ffmpeg = executablePath(directory, platform, "ffmpeg");
        Path ffprobe = executablePath(directory, platform, "ffprobe");
        if (!Files.isRegularFile(ffmpeg) || !Files.isRegularFile(ffprobe)) {
            return false;
        }
        try {
            return bundleId.equals(Files.readString(directory.resolve(COMPLETE_MARKER)));
        } catch (IOException exception) {
            return false;
        }
    }

    private static void extractBundle(String resource, Platform platform, Path destination)
            throws IOException {
        Map<String, String> symbolicLinks = new LinkedHashMap<>();
        int extractedFiles = 0;
        try (InputStream resourceInput = EmbeddedFfmpeg.class.getResourceAsStream(resource)) {
            if (resourceInput == null) {
                throw new IOException("找不到内嵌资源：" + resource);
            }
            try (ZipInputStream zip = new ZipInputStream(resourceInput)) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null;
                     entry = zip.getNextEntry()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (LINK_MANIFEST_NAMES.contains(entry.getName())) {
                        parseSymbolicLinks(
                                new String(zip.readAllBytes(), StandardCharsets.UTF_8),
                                symbolicLinks);
                        continue;
                    }
                    Path relative = runtimePath(entry.getName(), platform);
                    if (relative == null) {
                        continue;
                    }
                    Path target = destination.resolve(relative).normalize();
                    if (!target.startsWith(destination)) {
                        throw new IOException("非法的压缩包条目：" + entry.getName());
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    extractedFiles++;
                }
            }
        }
        restoreSymbolicLinks(symbolicLinks, platform, destination);

        Path ffmpeg = executablePath(destination, platform, "ffmpeg");
        Path ffprobe = executablePath(destination, platform, "ffprobe");
        if (extractedFiles < 2 || !Files.isRegularFile(ffmpeg) || !Files.isRegularFile(ffprobe)) {
            throw new IOException("FFmpeg 压缩包缺少必要文件");
        }
        if (!platform.windows()) {
            makeExecutableIfNeeded(ffmpeg);
            makeExecutableIfNeeded(ffprobe);
        }
    }

    private static Path runtimePath(String entryName, Platform platform) throws IOException {
        Path relative = safeRelativePath(entryName);
        if (relative == null) {
            return null;
        }
        String normalized = relative.toString().replace('\\', '/');
        if (normalized.equals("bin/ffplay") || normalized.equals("bin/ffplay.exe")) {
            return null;
        }
        if (normalized.startsWith("bin/")
                || (!platform.windows() && normalized.startsWith("lib/"))) {
            return relative;
        }
        return null;
    }

    private static void parseSymbolicLinks(String manifest, Map<String, String> links)
            throws IOException {
        for (String line : manifest.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IOException("符号链接清单条目非法");
            }
            links.put(line.substring(0, separator), line.substring(separator + 1));
        }
    }

    private static void restoreSymbolicLinks(Map<String, String> links, Platform platform,
                                             Path destination) throws IOException {
        for (Map.Entry<String, String> entry : links.entrySet()) {
            Path linkRelative = runtimePath(entry.getKey(), platform);
            Path targetRelative = runtimePath(entry.getValue(), platform);
            if (linkRelative == null || targetRelative == null) {
                continue;
            }
            Path link = destination.resolve(linkRelative).normalize();
            Path target = destination.resolve(targetRelative).normalize();
            if (!link.startsWith(destination) || !target.startsWith(destination)
                    || !Files.isRegularFile(target)) {
                throw new IOException("非法符号链接：" + entry.getKey());
            }
            Files.createDirectories(link.getParent());
            Files.deleteIfExists(link);
            try {
                Files.createSymbolicLink(link, link.getParent().relativize(target));
            } catch (UnsupportedOperationException | SecurityException | IOException exception) {
                Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path safeRelativePath(String value) throws IOException {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IOException("非法压缩包路径：" + value);
        }
        return path.getNameCount() == 0 ? null : path;
    }

    private static void makeExecutableIfNeeded(Path executable) {
        try {
            if (!executable.toFile().setExecutable(true, true)
                    && !Files.isExecutable(executable)) {
                AtvideoMod.LOGGER.warn("无法设置可执行权限：{}", executable);
            }
        } catch (Exception exception) {
            AtvideoMod.LOGGER.debug("设置可执行权限失败：{}", executable, exception);
        }
    }

    private static Path executablePath(Path directory, Platform platform, String executable) {
        String suffix = platform.windows() ? ".exe" : "";
        return directory.resolve("bin").resolve(executable + suffix);
    }

    private static String resourcePath(Platform platform) {
        return "/META-INF/ffmpeg/" + platform.id() + "/ffmpeg.zip";
    }

    private static String readBundleId(Platform platform) throws IOException {
        String resource = "/META-INF/ffmpeg/" + platform.id() + "/" + BUNDLE_ID_FILE;
        try (InputStream input = EmbeddedFfmpeg.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("缺少内嵌 FFmpeg 版本标识：" + resource);
            }
            String bundleId = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!bundleId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IOException("内嵌 FFmpeg 版本标识非法");
            }
            return bundleId;
        }
    }

    private static boolean resourceExists(String resource) {
        try (InputStream input = EmbeddedFfmpeg.class.getResourceAsStream(resource)) {
            return input != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = "aarch64".equals(architecture) || "arm64".equals(architecture);
        boolean amd64 = "amd64".equals(architecture) || "x86_64".equals(architecture);
        if (os.startsWith("windows")) {
            if (arm64) {
                return new Platform("windows-aarch64", true, "Windows ARM64");
            }
            if (amd64) {
                return new Platform("windows-x86_64", true, "Windows AMD64");
            }
        }
        if (os.startsWith("linux")) {
            if (arm64) {
                return new Platform("linux-aarch64", false, "Linux ARM64");
            }
            if (amd64) {
                return new Platform("linux-x86_64", false, "Linux AMD64");
            }
        }
        return null;
    }

    private static Executables externalExecutables(String configured) {
        Path path = Path.of(configured);
        Path fileName = path.getFileName();
        if (fileName == null || path.getParent() == null) {
            String probe = configured.toLowerCase(Locale.ROOT).endsWith(".exe")
                    ? "ffprobe.exe" : "ffprobe";
            return new Executables(configured, probe, null);
        }
        String probeName = fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                ? "ffprobe.exe" : "ffprobe";
        return new Executables(configured, path.getParent().resolve(probeName).toString(), null);
    }

    private static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Platform(String id, boolean windows, String displayName) {
    }

    private record Executables(String ffmpeg, String ffprobe, String libraryPath) {
    }
}