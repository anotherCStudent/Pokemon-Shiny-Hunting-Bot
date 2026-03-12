package vision.match;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public final class TemplateMatcher {

    public record MatchResult(boolean found, double score, int x, int y, int w, int h) {}

    private static final int ALPHA_THRESHOLD = 8;

    public static BufferedImage loadPng(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load image: " + p, e);
        }
    }

    public static MatchResult findTemplate(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        return findTemplateGray(frame, template, stride, threshold);
    }

    public static MatchResult findTemplateGray(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        BufferedImage normal = cropToOpaqueBounds(template);
        BufferedImage flipped = flipHorizontal(normal);

        MatchResult a = findTemplateGraySingle(frame, normal, stride, threshold);
        MatchResult b = findTemplateGraySingle(frame, flipped, stride, threshold);
        return b.score() > a.score() ? b : a;
    }

    public static MatchResult findTemplateColor(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        BufferedImage normal = cropToOpaqueBounds(template);
        BufferedImage flipped = flipHorizontal(normal);

        MatchResult a = findTemplateColorSingle(frame, normal, stride, threshold);
        MatchResult b = findTemplateColorSingle(frame, flipped, stride, threshold);
        return b.score() > a.score() ? b : a;
    }

    public static MatchResult findTemplateCombined(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        BufferedImage normal = cropToOpaqueBounds(template);
        BufferedImage flipped = flipHorizontal(normal);

        MatchResult a = findTemplateCombinedSingle(frame, normal, stride, threshold);
        MatchResult b = findTemplateCombinedSingle(frame, flipped, stride, threshold);
        return b.score() > a.score() ? b : a;
    }

    public static double findBestMatchScore(BufferedImage frame, BufferedImage template, int stride) {
        return findTemplateGray(frame, template, stride, -1.0).score();
    }

    public static double findBestColorMatchScore(BufferedImage frame, BufferedImage template, int stride) {
        return findTemplateColor(frame, template, stride, -1.0).score();
    }

    public static double findBestCombinedMatchScore(BufferedImage frame, BufferedImage template, int stride) {
        return findTemplateCombined(frame, template, stride, -1.0).score();
    }

    private static MatchResult findTemplateGraySingle(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        BufferedImage f = toGray(frame);
        BufferedImage t = toGray(template);

        int fw = f.getWidth();
        int fh = f.getHeight();
        int tw = t.getWidth();
        int th = t.getHeight();

        if (tw > fw || th > fh) {
            return new MatchResult(false, 0, 0, 0, tw, th);
        }

        double tMean = meanGrayMasked(t, template, 0, 0, tw, th);
        double tStd = stdGrayMasked(t, template, 0, 0, tw, th, tMean);
        if (tStd < 1e-6) {
            return new MatchResult(false, 0, 0, 0, tw, th);
        }

        double bestScore = -1.0;
        int bestX = 0;
        int bestY = 0;

        int step = Math.max(1, stride);

        for (int y = 0; y <= fh - th; y += step) {
            for (int x = 0; x <= fw - tw; x += step) {
                double s = nccGrayMasked(f, x, y, t, template, tw, th, tMean, tStd);
                if (s > bestScore) {
                    bestScore = s;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        return new MatchResult(bestScore >= threshold, bestScore, bestX, bestY, tw, th);
    }

    private static MatchResult findTemplateColorSingle(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int tw = template.getWidth();
        int th = template.getHeight();

        if (tw > fw || th > fh) {
            return new MatchResult(false, 0, 0, 0, tw, th);
        }

        double bestScore = -1.0;
        int bestX = 0;
        int bestY = 0;

        int step = Math.max(1, stride);

        for (int y = 0; y <= fh - th; y += step) {
            for (int x = 0; x <= fw - tw; x += step) {
                double s = colorSimilarityMasked(frame, x, y, template, tw, th);
                if (s > bestScore) {
                    bestScore = s;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        return new MatchResult(bestScore >= threshold, bestScore, bestX, bestY, tw, th);
    }

    private static MatchResult findTemplateCombinedSingle(BufferedImage frame, BufferedImage template, int stride, double threshold) {
        BufferedImage grayFrame = toGray(frame);
        BufferedImage grayTemplate = toGray(template);

        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int tw = template.getWidth();
        int th = template.getHeight();

        if (tw > fw || th > fh) {
            return new MatchResult(false, 0, 0, 0, tw, th);
        }

        double tMean = meanGrayMasked(grayTemplate, template, 0, 0, tw, th);
        double tStd = stdGrayMasked(grayTemplate, template, 0, 0, tw, th, tMean);
        if (tStd < 1e-6) {
            return new MatchResult(false, 0, 0, 0, tw, th);
        }

        double bestScore = -1.0;
        int bestX = 0;
        int bestY = 0;

        int step = Math.max(1, stride);

        for (int y = 0; y <= fh - th; y += step) {
            for (int x = 0; x <= fw - tw; x += step) {
                double grayScore = nccGrayMasked(grayFrame, x, y, grayTemplate, template, tw, th, tMean, tStd);
                double colorScore = colorSimilarityMasked(frame, x, y, template, tw, th);

                double grayNormalized = (grayScore + 1.0) / 2.0;
                if (grayNormalized < 0.0) grayNormalized = 0.0;
                if (grayNormalized > 1.0) grayNormalized = 1.0;

                double combined = (0.35 * grayNormalized) + (0.65 * colorScore);

                if (combined > bestScore) {
                    bestScore = combined;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        return new MatchResult(bestScore >= threshold, bestScore, bestX, bestY, tw, th);
    }

    private static BufferedImage toGray(BufferedImage src) {
        BufferedImage g = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gg = g.createGraphics();
        gg.drawImage(src, 0, 0, null);
        gg.dispose();
        return g;
    }

    private static BufferedImage flipHorizontal(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();

        AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
        tx.translate(-src.getWidth(), 0);

        g.drawImage(src, tx, null);
        g.dispose();
        return out;
    }

    private static BufferedImage cropToOpaqueBounds(BufferedImage src) {
        Rectangle bounds = findOpaqueBounds(src);
        if (bounds == null) {
            return src;
        }
        return src.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    private static Rectangle findOpaqueBounds(BufferedImage src) {
        int minX = src.getWidth();
        int minY = src.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int alpha = (src.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha > ALPHA_THRESHOLD) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return null;
        }

        return new Rectangle(minX, minY, (maxX - minX) + 1, (maxY - minY) + 1);
    }

    private static boolean isTemplatePixelActive(BufferedImage template, int x, int y) {
        int alpha = (template.getRGB(x, y) >>> 24) & 0xFF;
        return alpha > ALPHA_THRESHOLD;
    }

    private static double meanGrayMasked(BufferedImage grayImg, BufferedImage alphaMaskSource, int x0, int y0, int w, int h) {
        long sum = 0;
        int count = 0;

        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (!isTemplatePixelActive(alphaMaskSource, x - x0, y - y0)) continue;
                sum += grayImg.getRaster().getSample(x, y, 0);
                count++;
            }
        }

        return count == 0 ? 0.0 : sum / (double) count;
    }

    private static double stdGrayMasked(BufferedImage grayImg, BufferedImage alphaMaskSource, int x0, int y0, int w, int h, double mean) {
        double sumSq = 0.0;
        int count = 0;

        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (!isTemplatePixelActive(alphaMaskSource, x - x0, y - y0)) continue;
                double d = grayImg.getRaster().getSample(x, y, 0) - mean;
                sumSq += d * d;
                count++;
            }
        }

        return count == 0 ? 0.0 : Math.sqrt(sumSq / count);
    }

    private static double nccGrayMasked(
            BufferedImage grayFrame,
            int fx,
            int fy,
            BufferedImage grayTemplate,
            BufferedImage alphaMaskSource,
            int tw,
            int th,
            double tMean,
            double tStd
    ) {
        double fMean = meanGrayMasked(grayFrame, alphaMaskSource, fx, fy, tw, th);
        double fStd = stdGrayMasked(grayFrame, alphaMaskSource, fx, fy, tw, th, fMean);
        if (fStd < 1e-6) {
            return -1.0;
        }

        double num = 0.0;
        int count = 0;

        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                if (!isTemplatePixelActive(alphaMaskSource, x, y)) continue;

                double fVal = grayFrame.getRaster().getSample(fx + x, fy + y, 0) - fMean;
                double tVal = grayTemplate.getRaster().getSample(x, y, 0) - tMean;
                num += fVal * tVal;
                count++;
            }
        }

        if (count == 0) {
            return -1.0;
        }

        return num / (count * fStd * tStd);
    }

    private static double colorSimilarityMasked(BufferedImage frame, int fx, int fy, BufferedImage template, int tw, int th) {
        double totalDiff = 0.0;
        double maxDiffPerPixel = 255.0 * 3.0;
        int count = 0;

        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                if (!isTemplatePixelActive(template, x, y)) continue;

                int a = frame.getRGB(fx + x, fy + y);
                int b = template.getRGB(x, y);

                int ar = (a >> 16) & 0xFF;
                int ag = (a >> 8) & 0xFF;
                int ab = a & 0xFF;

                int br = (b >> 16) & 0xFF;
                int bg = (b >> 8) & 0xFF;
                int bb = b & 0xFF;

                totalDiff += Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb);
                count++;
            }
        }

        if (count == 0) {
            return 0.0;
        }

        double avgDiff = totalDiff / count;
        double normalized = avgDiff / maxDiffPerPixel;
        double similarity = 1.0 - normalized;

        if (similarity < 0.0) return 0.0;
        if (similarity > 1.0) return 1.0;
        return similarity;
    }

    private TemplateMatcher() {}
}