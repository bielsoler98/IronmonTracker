package com.dabomstew.pkrandom.compat;

/**
 * Stub replacement for java.awt.image.BufferedImage.
 * Only the methods used by getMascotImage() and GFXFunctions are implemented.
 */
public class BufferedImage {
    public static final int TYPE_INT_ARGB = 2;

    private final int width;
    private final int height;
    private final int[] pixels;

    public BufferedImage(int width, int height, int type) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public void setRGB(int x, int y, int rgb) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            pixels[y * width + x] = rgb;
        }
    }

    public int getRGB(int x, int y) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            return pixels[y * width + x];
        }
        return 0;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Graphics getGraphics() {
        return new Graphics(this);
    }
}
