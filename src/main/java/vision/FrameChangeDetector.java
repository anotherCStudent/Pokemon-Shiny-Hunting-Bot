package vision;

import java.awt.image.BufferedImage;

public class FrameChangeDetector {
    private BufferedImage last;
    private final double changedRatioThreshold; // e.g. 0.18 means 18% pixels changed
    private final int pixelDeltaThreshold;      // per-pixel intensity delta threshold

    public void reset() {
        last = null;
    }

    public FrameChangeDetector(double changedRatioThreshold, int pixelDeltaThreshold) {
        this.changedRatioThreshold = changedRatioThreshold;
        this.pixelDeltaThreshold = pixelDeltaThreshold;
    }

    public boolean updateAndCheck(BufferedImage current) {
        if (last == null) {
            last = current;
            return false;
        }

        int w = Math.min(last.getWidth(), current.getWidth());
        int h = Math.min(last.getHeight(), current.getHeight());

        long changed = 0;
        long total = (long) w * h;

        // Sample stride so we don’t melt CPU (still whole-screen, just sampled)
        int stride = 2;

        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int a = last.getRGB(x, y);
                int b = current.getRGB(x, y);

                int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
                int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;

                int da = Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb);
                if (da > pixelDeltaThreshold) changed++;
            }
        }

        // Adjust total for stride sampling
        long sampledTotal = total / (stride * stride);
        double ratio = changed / (double) sampledTotal;

        last = current;
        return ratio >= changedRatioThreshold;
    }
}