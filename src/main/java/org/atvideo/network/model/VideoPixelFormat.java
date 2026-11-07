package org.atvideo.network.model;

public enum VideoPixelFormat {
    RGBA("rgba", 4),
    RGB24("rgb24", 3);

    private final String ffmpegName;
    private final int bytesPerPixel;

    VideoPixelFormat(String ffmpegName, int bytesPerPixel) {
        this.ffmpegName = ffmpegName;
        this.bytesPerPixel = bytesPerPixel;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }
}