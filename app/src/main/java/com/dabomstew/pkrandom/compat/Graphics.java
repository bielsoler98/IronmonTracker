package com.dabomstew.pkrandom.compat;

/**
 * Stub replacement for java.awt.Graphics.
 * Only the methods used by getMascotImage() are implemented.
 */
public class Graphics {
    private final BufferedImage target;

    public Graphics(BufferedImage target) {
        this.target = target;
    }

    /**
     * Copies a sub-region of src into the target image.
     * Parameters match java.awt.Graphics#drawImage(Image,int,int,int,int,int,int,int,int,ImageObserver).
     */
    public boolean drawImage(BufferedImage src,
                             int dx1, int dy1, int dx2, int dy2,
                             int sx1, int sy1, int sx2, int sy2,
                             Object observer) {
        if (src == null || target == null) return false;
        int dw = dx2 - dx1;
        int dh = dy2 - dy1;
        int sw = sx2 - sx1;
        int sh = sy2 - sy1;
        for (int dy = 0; dy < dh; dy++) {
            for (int dx = 0; dx < dw; dx++) {
                int sx = sw == 0 ? 0 : sx1 + dx * sw / dw;
                int sy = sh == 0 ? 0 : sy1 + dy * sh / dh;
                target.setRGB(dx1 + dx, dy1 + dy, src.getRGB(sx, sy));
            }
        }
        return true;
    }

    public void dispose() {
        // no-op
    }
}
