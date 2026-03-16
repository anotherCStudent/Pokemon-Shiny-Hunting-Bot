package vision.shiny;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Detects likely shiny sparkle animation by analyzing a short sequence of frames
 * inside a selected region of interest.
 */
public final class ShinySparkleDetector {

    public interface FrameProvider {
        BufferedImage captureFrame();
    }

    public static final class Config {
        public int frameCount = 36;
        public int frameDelayMs = 50;

        public int brightnessJumpThreshold = 45;
        public int absoluteBrightThreshold = 180;

        public int minBrightPixelsPerBurst = 8;
        public int maxBrightPixelsPerBurst = 300;

        public int minClusterCount = 1;
        public double minBurstScore = 0.0;

        public int minSparkleEvents = 3;
        public double decisionThreshold = 1.0;

        public boolean debug = true;

        /** If true, saves cropped frames and masks for debugging */
        public boolean saveDebugFrames = false;
    }

    private final Config config;

    public ShinySparkleDetector() {
        this(new Config());
    }

    public ShinySparkleDetector(Config config) {
        this.config = config;
    }

    public SparkleDetectionResult detect(FrameProvider frameProvider, Rectangle roi) {
        return detect(frameProvider, roi, null);
    }

    public SparkleDetectionResult detect(FrameProvider frameProvider, Rectangle roi, Path debugDir) {
        if (frameProvider == null) {
            return new SparkleDetectionResult(false, 0.0, 0, 0, "frameProvider was null");
        }

        if (roi == null || roi.width <= 0 || roi.height <= 0) {
            return new SparkleDetectionResult(false, 0.0, 0, 0, "invalid ROI");
        }

        if (config.saveDebugFrames && debugDir != null) {
            ensureDir(debugDir);
        }

        List<BufferedImage> frames = captureFrames(frameProvider, config.frameCount, config.frameDelayMs);
        if (frames.size() < 2) {
            return new SparkleDetectionResult(false, 0.0, 0, frames.size(), "not enough frames");
        }

        int sparkleEvents = 0;
        double totalScore = 0.0;
        int analyzedPairs = 0;

        BufferedImage previousFrame = null;
        int savedFrameIndex = 0;

        for (BufferedImage currentFrame : frames) {
            if (currentFrame == null) {
                continue;
            }

            BufferedImage currentCrop = safeCrop(currentFrame, roi);
            if (currentCrop == null) {
                continue;
            }

            if (config.saveDebugFrames && debugDir != null) {
                saveImage(debugDir.resolve(String.format("sparkle_frame_%02d.png", savedFrameIndex)), currentCrop);
            }

            if (previousFrame != null) {
                BufferedImage previousCrop = safeCrop(previousFrame, roi);
                if (previousCrop != null) {
                    FrameAnalysis analysis = analyzeFramePair(previousCrop, currentCrop);
                    analyzedPairs++;
                    totalScore += analysis.score;

                    if (analysis.isSparkleBurst) {
                        sparkleEvents++;
                    }

                    if (config.debug) {
                        System.out.println(
                                "[SparkleDetector] pair=" + analyzedPairs +
                                " brightPixels=" + analysis.brightChangedPixels +
                                " clusters=" + analysis.clusterCount +
                                " score=" + String.format("%.3f", analysis.score) +
                                " burst=" + analysis.isSparkleBurst
                        );
                    }

                    if (config.saveDebugFrames && debugDir != null) {
                        saveImage(
                                debugDir.resolve(String.format(
                                        "sparkle_mask_%02d__bright_%d__clusters_%d__score_%.3f__burst_%s.png",
                                        analyzedPairs,
                                        analysis.brightChangedPixels,
                                        analysis.clusterCount,
                                        analysis.score,
                                        analysis.isSparkleBurst
                                )),
                                buildMaskImage(analysis.changedMask)
                        );
                    }
                }
            }

            previousFrame = currentFrame;
            savedFrameIndex++;
        }

        double normalizedScore = analyzedPairs == 0 ? 0.0 : totalScore / analyzedPairs;

        boolean likelyShiny =
                sparkleEvents >= config.minSparkleEvents &&
                normalizedScore >= config.decisionThreshold;

        String reason =
                "sparkleEvents=" + sparkleEvents +
                ", normalizedScore=" + String.format("%.3f", normalizedScore);

        if (config.debug) {
            System.out.println("[SparkleDetector] FINAL " + reason + ", likelyShiny=" + likelyShiny);
        }

        return new SparkleDetectionResult(
                likelyShiny,
                normalizedScore,
                sparkleEvents,
                frames.size(),
                reason
        );
    }

    private List<BufferedImage> captureFrames(FrameProvider provider, int frameCount, int frameDelayMs) {
        List<BufferedImage> frames = new ArrayList<>(frameCount);

        for (int i = 0; i < frameCount; i++) {
            frames.add(provider.captureFrame());

            if (i < frameCount - 1 && frameDelayMs > 0) {
                try {
                    Thread.sleep(frameDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return frames;
    }

    private BufferedImage safeCrop(BufferedImage image, Rectangle roi) {
        if (image == null) {
            return null;
        }

        int x = Math.max(0, roi.x);
        int y = Math.max(0, roi.y);

        if (x >= image.getWidth() || y >= image.getHeight()) {
            return null;
        }

        int width = Math.min(roi.width, image.getWidth() - x);
        int height = Math.min(roi.height, image.getHeight() - y);

        if (width <= 0 || height <= 0) {
            return null;
        }

        return image.getSubimage(x, y, width, height);
    }

    private FrameAnalysis analyzeFramePair(BufferedImage previous, BufferedImage current) {
        int width = Math.min(previous.getWidth(), current.getWidth());
        int height = Math.min(previous.getHeight(), current.getHeight());

        boolean[][] changedMask = new boolean[height][width];
        int brightChangedPixels = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int previousGray = gray(previous.getRGB(x, y));
                int currentGray = gray(current.getRGB(x, y));
                int difference = currentGray - previousGray;

                if (difference >= config.brightnessJumpThreshold &&
                        currentGray >= config.absoluteBrightThreshold) {
                    changedMask[y][x] = true;
                    brightChangedPixels++;
                }
            }
        }

        int clusterCount = countSmallClusters(changedMask, width, height);

        boolean isReasonableBurst =
                brightChangedPixels >= config.minBrightPixelsPerBurst &&
                brightChangedPixels <= config.maxBrightPixelsPerBurst &&
                clusterCount >= config.minClusterCount;

        double densityScore = Math.min(1.0, brightChangedPixels / 40.0);
        double clusterScore = Math.min(1.0, clusterCount / 3.0);
        double burstBonus = isReasonableBurst ? 0.75 : 0.0;

        double score = densityScore + clusterScore + burstBonus;

        if (score < config.minBurstScore) {
            isReasonableBurst = false;
        }

        return new FrameAnalysis(
                brightChangedPixels,
                clusterCount,
                isReasonableBurst,
                score,
                changedMask
        );
    }

    private int countSmallClusters(boolean[][] mask, int width, int height) {
        boolean[][] visited = new boolean[height][width];
        int clusterCount = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!mask[y][x] || visited[y][x]) {
                    continue;
                }

                int size = floodFill(mask, visited, x, y, width, height);

                if (size >= 2 && size <= 80) {
                    clusterCount++;
                }
            }
        }

        return clusterCount;
    }

    private int floodFill(boolean[][] mask, boolean[][] visited, int startX, int startY, int width, int height) {
        int maxCells = width * height;
        int[] queueX = new int[maxCells];
        int[] queueY = new int[maxCells];

        int head = 0;
        int tail = 0;

        queueX[tail] = startX;
        queueY[tail] = startY;
        tail++;

        visited[startY][startX] = true;

        int size = 0;

        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};

        while (head < tail) {
            int x = queueX[head];
            int y = queueY[head];
            head++;
            size++;

            for (int i = 0; i < dx.length; i++) {
                int nx = x + dx[i];
                int ny = y + dy[i];

                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }

                if (visited[ny][nx]) {
                    continue;
                }

                if (!mask[ny][nx]) {
                    continue;
                }

                visited[ny][nx] = true;
                queueX[tail] = nx;
                queueY[tail] = ny;
                tail++;
            }
        }

        return size;
    }

    private BufferedImage buildMaskImage(boolean[][] changedMask) {
        int height = changedMask.length;
        int width = changedMask[0].length;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, changedMask[y][x] ? 0xFFFFFF : 0x000000);
            }
        }

        return image;
    }

    private int gray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sparkle debug directory: " + dir, e);
        }
    }

    private void saveImage(Path path, BufferedImage image) {
        try {
            ImageIO.write(image, "png", path.toFile());
        } catch (IOException e) {
            System.out.println("[SparkleDetector] Failed to save debug image: " + path + " | " + e.getMessage());
        }
    }

    private static final class FrameAnalysis {
        final int brightChangedPixels;
        final int clusterCount;
        final boolean isSparkleBurst;
        final double score;
        final boolean[][] changedMask;

        FrameAnalysis(int brightChangedPixels, int clusterCount, boolean isSparkleBurst, double score, boolean[][] changedMask) {
            this.brightChangedPixels = brightChangedPixels;
            this.clusterCount = clusterCount;
            this.isSparkleBurst = isSparkleBurst;
            this.score = score;
            this.changedMask = changedMask;
        }
    }
}
