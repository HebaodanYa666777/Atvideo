package org.atvideo.network.model;

import org.atvideo.AtvideoMod;

/** 播放参数。支持 key=value 和位置参数两种写法。 */
public record PlayOptions(int volume, int fps, double fadeIn, double fadeOut,
                          boolean alpha, boolean autoClose) {

    public static final PlayOptions DEFAULT =
            new PlayOptions(100, 0, 0.0D, 0.0D, false, true);

    public boolean matchesDecoding(PlayOptions other) {
        return other != null && fps == other.fps;
    }

    /**
     * 解析 tokens[start..]。
     * 支持两种写法（可混用）：
     *   - 位置参数：100 60hz 0.5 1 false true
     *       position 0 → volume (0-100)
     *       position 1 → fps (支持 "60hz" / "60" / "fps=60")
     *       position 2 → fadein (秒)
     *       position 3 → fadeout (秒)
     *       position 4 → alpha (true/false)
     *       position 5 → autoclose (true/false)
     *   - 键值对：volume=100 fps=60 fadein=0.5 fadeout=1 alpha=false autoclose=true
     */
    public static PlayOptions parse(String[] tokens, int start) {
        int volume = 100;
        int fps = 0;
        double fadeIn = 0.0D;
        double fadeOut = 0.0D;
        boolean alpha = false;
        boolean autoClose = true;

        int position = 0;
        for (int i = start; i < tokens.length; i++) {
            String token = tokens[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq > 0) {
                // key=value 形式
                String key = token.substring(0, eq).toLowerCase().trim();
                String value = token.substring(eq + 1).trim();
                try {
                    switch (key) {
                        case "volume" -> volume = clampInt(Integer.parseInt(value), 0, 100);
                        case "fps" -> fps = clampInt(parseFpsValue(value), 0, 240);
                        case "fadein" -> fadeIn = Math.max(0.0D, Double.parseDouble(value));
                        case "fadeout" -> fadeOut = Math.max(0.0D, Double.parseDouble(value));
                        case "alpha" -> alpha = parseBoolValue(value);
                        case "autoclose" -> autoClose = parseBoolValue(value);
                        default -> AtvideoMod.LOGGER.debug("忽略未知参数：{}", token);
                    }
                } catch (NumberFormatException exception) {
                    AtvideoMod.LOGGER.warn("播放参数格式错误：{}", token);
                }
            } else {
                // 位置参数
                try {
                    switch (position) {
                        case 0 -> volume = clampInt(Integer.parseInt(token.trim()), 0, 100);
                        case 1 -> fps = clampInt(parseFpsValue(token), 0, 240);
                        case 2 -> fadeIn = Math.max(0.0D, Double.parseDouble(token.trim()));
                        case 3 -> fadeOut = Math.max(0.0D, Double.parseDouble(token.trim()));
                        case 4 -> alpha = parseBoolValue(token);
                        case 5 -> autoClose = parseBoolValue(token);
                        default -> AtvideoMod.LOGGER.debug("多余的位置参数：{}", token);
                    }
                } catch (NumberFormatException exception) {
                    AtvideoMod.LOGGER.warn("位置参数格式错误（位置 {}）：{}", position, token);
                }
                position++;
            }
        }
        return new PlayOptions(volume, fps, fadeIn, fadeOut, alpha, autoClose);
    }

    /** 支持 "60"、"60hz"、"60HZ"、"60fps" 等。 */
    private static int parseFpsValue(String value) {
        String v = value.toLowerCase().trim();
        if (v.endsWith("hz")) {
            v = v.substring(0, v.length() - 2).trim();
        } else if (v.endsWith("fps")) {
            v = v.substring(0, v.length() - 3).trim();
        }
        return Integer.parseInt(v);
    }

    private static boolean parseBoolValue(String value) {
        String v = value.toLowerCase().trim();
        return switch (v) {
            case "true", "1", "yes", "on", "t", "y" -> true;
            case "false", "0", "no", "off", "f", "n" -> false;
            default -> Boolean.parseBoolean(v);
        };
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}