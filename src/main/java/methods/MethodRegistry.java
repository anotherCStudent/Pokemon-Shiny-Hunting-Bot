package methods;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Locale;

import javax.imageio.ImageIO;

import app.AppState;
import core.Generation;
import core.HuntMethodType;
import core.KeySender;
import methods.gen3.Gen3EggHatchMethod;
import methods.gen3.Gen3FrlgStarterMethod;
import methods.gen3.Gen3LegendaryMethod;
import methods.gen3.Gen3SpinMethod;
import methods.gen3.RSEStarterMethod;
import vision.SpritePathResolver;
import vision.SpritePathResolver.SpriteVariant;
import vision.SpritePathResolver.SpriteView;
import vision.match.TemplateMatcher;
import vision.shiny.ShinySparkleDetector;
import vision.shiny.SparkleDetectionResult;

/**
 * Registry builds methods from GUI-selected options.
 * - gameFolder chooses which sprite set (firered-leafgreen / emerald / ruby-sapphire / rse)
 * - dex selects the pokemon template
 */
public class MethodRegistry {

    // ----------------------------
    // Shared tuning (Legendary + Egg)
    // ----------------------------

    // Full-black watch (MUST be entire capture region black to trigger)
    private static final long BLACK_WATCH_POLL_MS = 120;

    // Require BOTH: very low avg brightness AND black pixel ratio high (whole region is black)
    private static final double LEGEND_BLACK_AVG_THRESHOLD = 18.0;
    private static final double LEGEND_BLACK_PIXEL_RATIO_THRESHOLD = 0.95;
    private static final int LEGEND_BLACK_REQUIRED_CONSECUTIVE = 2;
    private static final int LEGEND_BLACK_GRAY_THRESHOLD = 28;

    // After black confirmed, wait before starting sparkle (your proven timing)
    private static final long POST_BLACK_WAIT_MS = 1000;
    private static final long POST_BLACK_EXTRA_WAIT_MS = 100;

    // Sparkle detector (Torchic/RSE primary signal)
    private static final int SPARKLE_FRAME_COUNT = 28;
    private static final int SPARKLE_FRAME_DELAY_MS = 40;
    private static final int SPARKLE_BRIGHTNESS_JUMP_THRESHOLD = 45;
    private static final int SPARKLE_ABSOLUTE_BRIGHT_THRESHOLD = 180;
    private static final int SPARKLE_MIN_BRIGHT_PIXELS_PER_BURST = 6;
    private static final int SPARKLE_MAX_BRIGHT_PIXELS_PER_BURST = 350;
    private static final int SPARKLE_MIN_EVENTS = 3;
    private static final double SPARKLE_DECISION_THRESHOLD = 0.95;

    // ROI for sparkle & template confirm (keep universal)
    private static final double ROI_X_PCT = 0.12;
    private static final double ROI_Y_PCT = 0.12;
    private static final double ROI_W_PCT = 0.76;
    private static final double ROI_H_PCT = 0.76;

    // Template confirm window (Torchic-style support signal)
    private static final int CHECK_FRAME_COUNT = 4;
    private static final long CHECK_FRAME_GAP_MS = 120;
    private static final int MATCH_STRIDE = 3;

    // Torchic-style: sparkle is primary, template is support
    // Only block sparkle-shiny if template VERY strongly prefers normal
    private static final double STRONG_NORMAL_LEAD_BLOCK_SHINY = 0.06;

    // If sparkle does NOT trigger, allow template to confirm normal if it strongly prefers normal
    private static final double NORMAL_STRONG_THRESHOLD = 0.78;
    private static final double NORMAL_STRONG_MARGIN = 0.03;

    public static HuntMethod create(
            Generation gen,
            HuntMethodType type,
            KeySender keys,
            AppState state,
            int dex,
            String gameFolder
    ) {
        Path projectDir = Path.of(System.getProperty("user.dir"));

        return switch (gen) {
            case GEN_3 -> switch (type) {

                // Legendary
                case SOFT_RESET -> {
                    BufferedImage normal = loadSpriteForGame(
                            projectDir, gameFolder,
                            SpriteView.FRONT, SpriteVariant.NORMAL, dex
                    );
                    BufferedImage shiny = loadSpriteForGame(
                            projectDir, gameFolder,
                            SpriteView.FRONT, SpriteVariant.SHINY, dex
                    );

                    yield new Gen3LegendaryMethod(
                            keys,
                            buildLegendaryDetector(state),
                            normal,
                            shiny
                    );
                }

                // Spin
                case SPIN_METHOD -> {
                    BufferedImage normal = loadSpriteForGame(
                            projectDir, gameFolder,
                            SpriteView.FRONT, SpriteVariant.NORMAL, dex
                    );
                    BufferedImage shiny = loadSpriteForGame(
                            projectDir, gameFolder,
                            SpriteView.FRONT, SpriteVariant.SHINY, dex
                    );
                    yield new Gen3SpinMethod(keys, state, normal, shiny);
                }

                // Egg hatch (NEW detector contract)
                case EGG_HATCH -> new Gen3EggHatchMethod(
                        keys,
                        state,
                        buildEggDetector(state, projectDir, gameFolder, dex)
                );

                // RSE starter
                case STARTER_RSE -> {
                    BufferedImage normal = loadRseSprite(
                            projectDir, SpriteView.BACK, SpriteVariant.NORMAL, dex
                    );
                    BufferedImage shiny = loadRseSprite(
                            projectDir, SpriteView.BACK, SpriteVariant.SHINY, dex
                    );
                    yield new RSEStarterMethod(keys, state, dex, normal, shiny);
                }

                // FRLG starter
                case STARTER_FRLG -> new Gen3FrlgStarterMethod(keys, state, dex);
            };
        };
    }

    // ----------------------------
    // Legendary detector (unchanged)
    // ----------------------------

    private static Gen3LegendaryMethod.LegendaryBattleDetector buildLegendaryDetector(AppState state) {

        ShinySparkleDetector.Config sparkleConfig = new ShinySparkleDetector.Config();
        sparkleConfig.frameCount = SPARKLE_FRAME_COUNT;
        sparkleConfig.frameDelayMs = SPARKLE_FRAME_DELAY_MS;
        sparkleConfig.brightnessJumpThreshold = SPARKLE_BRIGHTNESS_JUMP_THRESHOLD;
        sparkleConfig.absoluteBrightThreshold = SPARKLE_ABSOLUTE_BRIGHT_THRESHOLD;
        sparkleConfig.minBrightPixelsPerBurst = SPARKLE_MIN_BRIGHT_PIXELS_PER_BURST;
        sparkleConfig.maxBrightPixelsPerBurst = SPARKLE_MAX_BRIGHT_PIXELS_PER_BURST;
        sparkleConfig.minSparkleEvents = SPARKLE_MIN_EVENTS;
        sparkleConfig.decisionThreshold = SPARKLE_DECISION_THRESHOLD;
        sparkleConfig.debug = true;
        sparkleConfig.saveDebugFrames = true;

        ShinySparkleDetector sparkleDetector = new ShinySparkleDetector(sparkleConfig);

        return new Gen3LegendaryMethod.LegendaryBattleDetector() {

            @Override
            public void resetState() { }

            @Override
            public Gen3LegendaryMethod.BlackGateResult waitForBattleTransitionBlackScreen(long timeoutMs, Path attemptDir) {
                ensureDir(attemptDir);

                long end = System.currentTimeMillis() + timeoutMs;

                int frames = 0;
                int blackEventsSeen = 0;
                int consecutiveBlack = 0;
                int maxConsecutive = 0;

                while (System.currentTimeMillis() < end) {
                    BufferedImage frame = state.getFrameSourceOrThrow().capture();
                    frames++;

                    double avg = averageBrightness(frame);
                    double ratio = blackPixelRatio(frame, LEGEND_BLACK_GRAY_THRESHOLD);

                    boolean black = (avg <= LEGEND_BLACK_AVG_THRESHOLD)
                            && (ratio >= LEGEND_BLACK_PIXEL_RATIO_THRESHOLD);

                    if (black) {
                        blackEventsSeen++;
                        consecutiveBlack++;
                        if (consecutiveBlack > maxConsecutive) maxConsecutive = consecutiveBlack;
                    } else {
                        consecutiveBlack = 0;
                    }

                    String fname = String.format(
                            Locale.US,
                            "legend_black_watch_%02d__avg_%.2f__ratio_%.3f__black_%s.png",
                            frames, avg, ratio, black ? "true" : "false"
                    );
                    saveImage(attemptDir.resolve(fname), frame);

                    if (consecutiveBlack >= LEGEND_BLACK_REQUIRED_CONSECUTIVE) {
                        return Gen3LegendaryMethod.BlackGateResult.pass(
                                frames, blackEventsSeen, maxConsecutive, "full black transition detected"
                        );
                    }

                    sleepQuiet(BLACK_WATCH_POLL_MS);
                }

                return Gen3LegendaryMethod.BlackGateResult.fail(
                        frames, blackEventsSeen, maxConsecutive, "full black transition not detected in time"
                );
            }

            @Override
            public Gen3LegendaryMethod.LegendaryCheckResult analyzeEncounter(
                    BufferedImage normalTemplate,
                    BufferedImage shinyTemplate,
                    Path attemptDir
            ) {
                ensureDir(attemptDir);

                sleepQuiet(POST_BLACK_WAIT_MS);
                sleepQuiet(POST_BLACK_EXTRA_WAIT_MS);

                Path sparkleDir = attemptDir.resolve("sparkle-analysis");
                ensureDir(sparkleDir);

                BufferedImage pre = state.getFrameSourceOrThrow().capture();
                saveImage(attemptDir.resolve("sparkle_pre_detection_frame.png"), pre);

                Rectangle roi = buildRoi(pre);
                saveImage(attemptDir.resolve("sparkle_pre_detection_roi.png"), crop(pre, roi));

                SparkleDetectionResult sparkle = sparkleDetector.detect(
                        () -> state.getFrameSourceOrThrow().capture(),
                        roi,
                        sparkleDir
                );

                Path checkDir = attemptDir.resolve("color-check");
                ensureDir(checkDir);

                double bestShinyColor = Double.NEGATIVE_INFINITY;
                double bestNormalColor = Double.NEGATIVE_INFINITY;
                int bestFrameIdx = -1;

                for (int i = 0; i < CHECK_FRAME_COUNT; i++) {
                    int frameIndex = i + 1;

                    BufferedImage frame = state.getFrameSourceOrThrow().capture();
                    BufferedImage c = crop(frame, roi);

                    double shinyColor = TemplateMatcher.findBestColorMatchScore(c, shinyTemplate, MATCH_STRIDE);
                    double normalColor = TemplateMatcher.findBestColorMatchScore(c, normalTemplate, MATCH_STRIDE);
                    double shinyCombined = TemplateMatcher.findBestCombinedMatchScore(c, shinyTemplate, MATCH_STRIDE);
                    double normalCombined = TemplateMatcher.findBestCombinedMatchScore(c, normalTemplate, MATCH_STRIDE);

                    double margin = shinyColor - normalColor;
                    if (!Double.isFinite(bestShinyColor) || margin > (bestShinyColor - bestNormalColor)) {
                        bestShinyColor = shinyColor;
                        bestNormalColor = normalColor;
                        bestFrameIdx = frameIndex;
                    }

                    saveCheckFrame(checkDir, c, frameIndex, shinyColor, normalColor, shinyCombined, normalCombined);

                    if (i < CHECK_FRAME_COUNT - 1) sleepQuiet(CHECK_FRAME_GAP_MS);
                }

                boolean sparkleLikely = sparkle != null && sparkle.isLikelyShiny();
                double sparkleScore = sparkle != null ? sparkle.getScore() : 0.0;
                int sparkleEvents = sparkle != null ? sparkle.getSparkleEvents() : 0;

                double normalLead = bestNormalColor - bestShinyColor;

                if (sparkleLikely && sparkleScore >= SPARKLE_DECISION_THRESHOLD && sparkleEvents >= SPARKLE_MIN_EVENTS) {
                    if (normalLead >= STRONG_NORMAL_LEAD_BLOCK_SHINY) {
                        return Gen3LegendaryMethod.LegendaryCheckResult.notShiny(
                                true, sparkleScore, sparkleEvents, bestShinyColor, bestNormalColor,
                                String.format(Locale.US,
                                        "sparkle said shiny but template strongly favored normal (normalLead=%.4f) bestFrame=%d",
                                        normalLead, bestFrameIdx)
                        );
                    }

                    return Gen3LegendaryMethod.LegendaryCheckResult.shiny(
                            true, sparkleScore, sparkleEvents, bestShinyColor, bestNormalColor,
                            String.format(Locale.US,
                                    "sparkle confirmed shiny; template support bestFrame=%d margin=%.4f",
                                    bestFrameIdx, (bestShinyColor - bestNormalColor))
                    );
                }

                if (bestNormalColor >= NORMAL_STRONG_THRESHOLD && normalLead >= NORMAL_STRONG_MARGIN) {
                    return Gen3LegendaryMethod.LegendaryCheckResult.notShiny(
                            false, sparkleScore, sparkleEvents, bestShinyColor, bestNormalColor,
                            String.format(Locale.US,
                                    "no sparkle; template favored normal bestFrame=%d normalLead=%.4f",
                                    bestFrameIdx, normalLead)
                    );
                }

                return Gen3LegendaryMethod.LegendaryCheckResult.noDecision(
                        false, sparkleScore, sparkleEvents, bestShinyColor, bestNormalColor,
                        String.format(Locale.US,
                                "inconclusive (no sparkle; template not strong) bestFrame=%d",
                                bestFrameIdx)
                );
            }
        };
    }

    // ----------------------------
    // Egg detector (NEW)
    // ----------------------------

    private static Gen3EggHatchMethod.EggDetector buildEggDetector(
            AppState state,
            Path projectDir,
            String gameFolder,
            int dex
    ) {
        // Load templates for the egg species (user-selected dex)
        BufferedImage normal = loadSpriteForGame(projectDir, gameFolder, SpriteView.FRONT, SpriteVariant.NORMAL, dex);
        BufferedImage shiny  = loadSpriteForGame(projectDir, gameFolder, SpriteView.FRONT, SpriteVariant.SHINY,  dex);

        // Sparkle detector reused from legendary/torchic
        ShinySparkleDetector.Config sparkleConfig = new ShinySparkleDetector.Config();
        sparkleConfig.frameCount = SPARKLE_FRAME_COUNT;
        sparkleConfig.frameDelayMs = SPARKLE_FRAME_DELAY_MS;
        sparkleConfig.brightnessJumpThreshold = SPARKLE_BRIGHTNESS_JUMP_THRESHOLD;
        sparkleConfig.absoluteBrightThreshold = SPARKLE_ABSOLUTE_BRIGHT_THRESHOLD;
        sparkleConfig.minBrightPixelsPerBurst = SPARKLE_MIN_BRIGHT_PIXELS_PER_BURST;
        sparkleConfig.maxBrightPixelsPerBurst = SPARKLE_MAX_BRIGHT_PIXELS_PER_BURST;
        sparkleConfig.minSparkleEvents = SPARKLE_MIN_EVENTS;
        sparkleConfig.decisionThreshold = SPARKLE_DECISION_THRESHOLD;
        sparkleConfig.debug = true;
        sparkleConfig.saveDebugFrames = true;

        ShinySparkleDetector sparkleDetector = new ShinySparkleDetector(sparkleConfig);

        return new Gen3EggHatchMethod.EggDetector() {

            @Override
            public void resetState() { }

            @Override
            public Gen3EggHatchMethod.EggBlackResult watchForFullBlackOnce(
                    long timeoutMs,
                    Path attemptDir,
                    String filenamePrefix
            ) {
                ensureDir(attemptDir);

                long end = System.currentTimeMillis() + timeoutMs;

                int frames = 0;
                int blackEventsSeen = 0;
                int consecutiveBlack = 0;
                int maxConsecutive = 0;

                while (System.currentTimeMillis() < end) {
                    BufferedImage frame = state.getFrameSourceOrThrow().capture();
                    frames++;

                    double avg = averageBrightness(frame);
                    double ratio = blackPixelRatio(frame, LEGEND_BLACK_GRAY_THRESHOLD);

                    boolean black = (avg <= LEGEND_BLACK_AVG_THRESHOLD)
                            && (ratio >= LEGEND_BLACK_PIXEL_RATIO_THRESHOLD);

                    if (black) {
                        blackEventsSeen++;
                        consecutiveBlack++;
                        if (consecutiveBlack > maxConsecutive) maxConsecutive = consecutiveBlack;
                    } else {
                        consecutiveBlack = 0;
                    }

                    String fname = String.format(
                            Locale.US,
                            "%s_%02d__avg_%.2f__ratio_%.3f__black_%s.png",
                            filenamePrefix, frames, avg, ratio, black ? "true" : "false"
                    );
                    saveImage(attemptDir.resolve(fname), frame);

                    if (consecutiveBlack >= LEGEND_BLACK_REQUIRED_CONSECUTIVE) {
                        return Gen3EggHatchMethod.EggBlackResult.hit(
                                frames, blackEventsSeen, maxConsecutive, "full black detected"
                        );
                    }

                    sleepQuiet(BLACK_WATCH_POLL_MS);
                }

                return Gen3EggHatchMethod.EggBlackResult.miss(
                        frames, blackEventsSeen, maxConsecutive, "full black not detected in time"
                );
            }

            @Override
            public Gen3EggHatchMethod.EggDecisionResult checkBattleForShiny(Path attemptDir) {
                ensureDir(attemptDir);

                // Match your proven legendary timing
                sleepQuiet(POST_BLACK_WAIT_MS);
                sleepQuiet(POST_BLACK_EXTRA_WAIT_MS);

                Path sparkleDir = attemptDir.resolve("sparkle-analysis");
                ensureDir(sparkleDir);

                BufferedImage pre = state.getFrameSourceOrThrow().capture();
                saveImage(attemptDir.resolve("sparkle_pre_detection_frame.png"), pre);

                Rectangle roi = buildRoi(pre);
                saveImage(attemptDir.resolve("sparkle_pre_detection_roi.png"), crop(pre, roi));

                SparkleDetectionResult sparkle = sparkleDetector.detect(
                        () -> state.getFrameSourceOrThrow().capture(),
                        roi,
                        sparkleDir
                );

                boolean sparkleLikely = sparkle != null && sparkle.isLikelyShiny();
                double sparkleScore = sparkle != null ? sparkle.getScore() : 0.0;
                int sparkleEvents = sparkle != null ? sparkle.getSparkleEvents() : 0;

                // Template support window (like Torchic/Legendary)
                Path checkDir = attemptDir.resolve("color-check");
                ensureDir(checkDir);

                double bestShinyColor = Double.NEGATIVE_INFINITY;
                double bestNormalColor = Double.NEGATIVE_INFINITY;

                for (int i = 0; i < CHECK_FRAME_COUNT; i++) {
                    int idx = i + 1;

                    BufferedImage frame = state.getFrameSourceOrThrow().capture();
                    BufferedImage c = crop(frame, roi);

                    double shinyColor = TemplateMatcher.findBestColorMatchScore(c, shiny, MATCH_STRIDE);
                    double normalColor = TemplateMatcher.findBestColorMatchScore(c, normal, MATCH_STRIDE);
                    double shinyCombined = TemplateMatcher.findBestCombinedMatchScore(c, shiny, MATCH_STRIDE);
                    double normalCombined = TemplateMatcher.findBestCombinedMatchScore(c, normal, MATCH_STRIDE);

                    double margin = shinyColor - normalColor;
                    if (!Double.isFinite(bestShinyColor) || margin > (bestShinyColor - bestNormalColor)) {
                        bestShinyColor = shinyColor;
                        bestNormalColor = normalColor;
                    }

                    saveCheckFrame(checkDir, c, idx, shinyColor, normalColor, shinyCombined, normalCombined);

                    if (i < CHECK_FRAME_COUNT - 1) sleepQuiet(CHECK_FRAME_GAP_MS);
                }

                double normalLead = bestNormalColor - bestShinyColor;

                // Decision: sparkle primary, template only blocks if it screams normal
                if (sparkleLikely && sparkleScore >= SPARKLE_DECISION_THRESHOLD && sparkleEvents >= SPARKLE_MIN_EVENTS) {
                    if (normalLead >= STRONG_NORMAL_LEAD_BLOCK_SHINY) {
                        return Gen3EggHatchMethod.EggDecisionResult.notShiny(
                                String.format(Locale.US,
                                        "sparkle said shiny but template strongly favored normal (normalLead=%.4f)",
                                        normalLead)
                        );
                    }

                    return Gen3EggHatchMethod.EggDecisionResult.shiny(
                            String.format(Locale.US,
                                    "sparkle confirmed shiny score=%.4f events=%d",
                                    sparkleScore, sparkleEvents)
                    );
                }

                if (bestNormalColor >= NORMAL_STRONG_THRESHOLD && normalLead >= NORMAL_STRONG_MARGIN) {
                    return Gen3EggHatchMethod.EggDecisionResult.notShiny(
                            String.format(Locale.US,
                                    "no sparkle; template favored normal (normalLead=%.4f)",
                                    normalLead)
                    );
                }

                return Gen3EggHatchMethod.EggDecisionResult.noDecision(
                        "inconclusive (no sparkle; template not strong)"
                );
            }
        };
    }

    // ----------------------------
    // Sprite loading (kept intact)
    // ----------------------------

    private static BufferedImage loadSpriteForGame(
            Path projectDir,
            String gameFolder,
            SpriteView view,
            SpriteVariant variant,
            int dex
    ) {
        if (isRseFolder(gameFolder)) {
            return loadRseSprite(projectDir, view, variant, dex);
        }

        SpritePathResolver resolver = new SpritePathResolver(projectDir);
        Path spritePath = resolveSpecificGen3Sprite(resolver, gameFolder, view, variant, dex);

        try {
            return ImageIO.read(spritePath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sprite: " + spritePath, e);
        }
    }

    private static BufferedImage loadRseSprite(
            Path projectDir,
            SpriteView view,
            SpriteVariant variant,
            int dex
    ) {
        SpritePathResolver resolver = new SpritePathResolver(projectDir);
        Path spritePath = resolver.resolveGen3RseSprite(dex, view, variant);

        try {
            return ImageIO.read(spritePath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load RSE sprite: " + spritePath, e);
        }
    }

    private static Path resolveSpecificGen3Sprite(
            SpritePathResolver resolver,
            String gameFolder,
            SpriteView view,
            SpriteVariant variant,
            int dex
    ) {
        return switch (normalizeGameFolder(gameFolder)) {
            case "firered-leafgreen" -> resolver.resolveGen3FrlgSprite(dex, view, variant);
            case "ruby-sapphire" -> resolver.resolveGen3RubySapphireSprite(dex, view, variant);
            case "emerald" -> resolver.resolveGen3EmeraldSprite(dex, view, variant);
            default -> throw new IllegalArgumentException("Unsupported generation-iii game folder: " + gameFolder);
        };
    }

    private static boolean isRseFolder(String gameFolder) {
        if (gameFolder == null) return false;
        String normalized = normalizeGameFolder(gameFolder);
        return normalized.equals("rse")
                || normalized.equals("ruby-sapphire-emerald")
                || normalized.equals("ruby_sapphire_emerald");
    }

    private static String normalizeGameFolder(String gameFolder) {
        if (gameFolder == null) return "";
        String normalized = gameFolder.trim().toLowerCase();

        return switch (normalized) {
            case "frlg" -> "firered-leafgreen";
            case "firered-leafgreen" -> "firered-leafgreen";
            case "ruby-sapphire", "rs" -> "ruby-sapphire";
            case "emerald" -> "emerald";
            case "rse", "ruby_sapphire_emerald", "ruby-sapphire-emerald" -> "rse";
            default -> normalized;
        };
    }

    // ----------------------------
    // Utilities
    // ----------------------------

    private static Rectangle buildRoi(BufferedImage frame) {
        int w = frame.getWidth();
        int h = frame.getHeight();

        int x = (int) Math.round(w * ROI_X_PCT);
        int y = (int) Math.round(h * ROI_Y_PCT);
        int rw = (int) Math.round(w * ROI_W_PCT);
        int rh = (int) Math.round(h * ROI_H_PCT);

        x = clamp(x, 0, w - 1);
        y = clamp(y, 0, h - 1);
        rw = clamp(rw, 1, w - x);
        rh = clamp(rh, 1, h - y);

        return new Rectangle(x, y, rw, rh);
    }

    private static BufferedImage crop(BufferedImage image, Rectangle roi) {
        Rectangle bounded = roi.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        return image.getSubimage(bounded.x, bounded.y, bounded.width, bounded.height);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void saveImage(Path path, BufferedImage image) {
        try {
            ensureDir(path.getParent());
            ImageIO.write(image, "png", path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save debug image: " + path, e);
        }
    }

    private static void saveCheckFrame(Path dir,
                                       BufferedImage frame,
                                       int idx,
                                       double shinyColor,
                                       double normalColor,
                                       double shinyCombined,
                                       double normalCombined) {
        DecimalFormat df = new DecimalFormat("0.0000");
        String name = String.format(
                Locale.US,
                "frame_%02d__decision_no__scol_%s__ncol_%s__sc_%s__nc_%s.png",
                idx,
                df.format(shinyColor),
                df.format(normalColor),
                df.format(shinyCombined),
                df.format(normalCombined)
        );
        saveImage(dir.resolve(name), frame);
    }

    private static void ensureDir(Path dir) {
        try {
            if (dir != null) Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create debug directory: " + dir, e);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static double averageBrightness(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int stride = 4;

        long sum = 0;
        long count = 0;

        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = (rgb) & 0xFF;
                sum += (r + g + b);
                count += 3;
            }
        }

        if (count == 0) return 255.0;
        return (double) sum / (double) count;
    }

    private static double blackPixelRatio(BufferedImage img, int grayThreshold) {
        int w = img.getWidth();
        int h = img.getHeight();
        int stride = 3;

        long black = 0;
        long total = 0;

        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (r * 299 + g * 587 + b * 114) / 1000;

                if (gray <= grayThreshold) black++;
                total++;
            }
        }

        if (total == 0) return 0.0;
        return (double) black / (double) total;
    }

    private MethodRegistry() {}
}