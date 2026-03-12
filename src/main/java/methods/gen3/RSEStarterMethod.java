package methods.gen3;

import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import app.AppState;
import core.KeySender;
import methods.HuntMethod;
import vision.match.TemplateMatcher;
import vision.shiny.ShinySparkleDetector;
import vision.shiny.SparkleDetectionResult;

public class RSEStarterMethod implements HuntMethod {

    private final KeySender keys;
    private final AppState state;
    private final int pokedexNumber;

    // RSE starters:
    // 252 = Treecko (left)
    // 255 = Torchic (center)
    // 258 = Mudkip  (right)
    private final BufferedImage normalBackTemplate;
    private final BufferedImage shinyBackTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private final ShinySparkleDetector sparkleDetector;
    private final Path debugRootDir;
    private int attemptCounter = 0;

    // Common startup timings
    private static final long DELAY_MS = 5000;

    // Startup / route timings
    private static final long WAIT_100 = 100;
    private static final long WAIT_1000 = 1000;
    private static final long WAIT_2000 = 2000;

    // Controlled mash rate
    private static final long TAP_GAP_MS = 70;

    // Battle entry timing
    private static final long RSE_TO_BATTLE_ENTRY_MASH_MS = 8000;

    // Tuned from testing
    private static final long RSE_SPARKLE_PREP_DELAY_MS = 2000;

    // After sparkle watch, wait a bit for stable sprite frames
    private static final long RSE_COLOR_CONFIRM_DELAY_MS = 180;

    private static final long RSE_POST_DETECTION_RESET_DELAY_MS = 1000;

    // Color/template decision window
    private static final int CHECK_FRAME_COUNT = 4;
    private static final long CHECK_FRAME_GAP_MS = 120;
    private static final int MATCH_STRIDE = 3;

    // Strong rules - relaxed to match actual shiny Treecko scores
    private static final double SHINY_COLOR_THRESHOLD = 0.755;
    private static final double NORMAL_COLOR_THRESHOLD = 0.755;
    private static final double COLOR_DECISION_MARGIN = 0.018;

    // Fallback combined rules
    private static final double COMBINED_FALLBACK_THRESHOLD = 0.74;
    private static final double COMBINED_DECISION_MARGIN = 0.05;

    // Weak repeated-win rules
    private static final double WEAK_SHINY_COLOR_THRESHOLD = 0.748;
    private static final double WEAK_NORMAL_COLOR_THRESHOLD = 0.748;
    private static final double WEAK_COLOR_MARGIN = 0.015;
    private static final int WEAK_RULE_REQUIRED_FRAMES = 2;

    // Sparkle rules
    private static final int SPARKLE_FRAME_COUNT = 28;
    private static final int SPARKLE_FRAME_DELAY_MS = 40;
    private static final int SPARKLE_BRIGHTNESS_JUMP_THRESHOLD = 45;
    private static final int SPARKLE_ABSOLUTE_BRIGHT_THRESHOLD = 180;
    private static final int SPARKLE_MIN_BRIGHT_PIXELS_PER_BURST = 6;
    private static final int SPARKLE_MAX_BRIGHT_PIXELS_PER_BURST = 220;
    private static final int SPARKLE_MIN_EVENTS = 3;
    private static final double SPARKLE_DECISION_THRESHOLD = 0.95;

    // Torchic-only override:
    // If sparkle strongly supports shiny but color only slightly leans normal,
    // count it as SHINY instead of letting Torchic get skipped.
    private static final int TORCHIC_DEX = 255;
    private static final double TORCHIC_SMALL_NORMAL_LEAD_MAX = 0.025;

    // Tight ROI around player's starter sprite in battle scene.
    private static final double ROI_X_PCT = 0.03;
    private static final double ROI_Y_PCT = 0.33;
    private static final double ROI_W_PCT = 0.36;
    private static final double ROI_H_PCT = 0.46;

    // Debug output
    private static final boolean DEBUG_SAVE_CHECK_FRAMES = true;
    private static final boolean DEBUG_SAVE_TEMPLATES = true;
    private static final boolean DEBUG_VERBOSE_SCORES = true;
    private static final boolean DEBUG_SAVE_ROI_CROPS = true;

    public RSEStarterMethod(KeySender keys,
                            AppState state,
                            int pokedexNumber,
                            BufferedImage normalBackTemplate,
                            BufferedImage shinyBackTemplate) {
        this.keys = Objects.requireNonNull(keys, "keys must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.pokedexNumber = pokedexNumber;
        this.normalBackTemplate = Objects.requireNonNull(normalBackTemplate, "normalBackTemplate must not be null");
        this.shinyBackTemplate = Objects.requireNonNull(shinyBackTemplate, "shinyBackTemplate must not be null");

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

        this.sparkleDetector = new ShinySparkleDetector(sparkleConfig);

        Path projectDir = Path.of(System.getProperty("user.dir"));
        this.debugRootDir = projectDir.resolve("debug").resolve("rse-starter");
        ensureDebugDirExists(debugRootDir);

        if (DEBUG_SAVE_TEMPLATES) {
            saveTemplateDebugImages();
        }
    }

    @Override
    public String name() {
        return "Gen 3 RSE Starter";
    }

    @Override
    public void start() {
        if (running.get()) return;
        running.set(true);

        worker = new Thread(this::loop, "Gen3RseStarterWorker");
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        if (!state.hasCaptureRect()) {
            alert("Set Capture Area first.");
            stop();
            return;
        }

        while (running.get()) {
            attemptCounter++;
            Path attemptDir = debugRootDir.resolve(String.format(Locale.US, "attempt_%05d", attemptCounter));
            ensureDebugDirExists(attemptDir);

            log("Starting attempt " + attemptCounter
                    + " | dex=" + pokedexNumber
                    + " | debugDir=" + attemptDir.toAbsolutePath());

            runStartupSequence();
            if (!running.get()) break;

            RouteResult result = runStarterSelectionAndDetection(attemptDir);

            log(String.format(
                    Locale.US,
                    "RSE final decision: %s | sparkleLikely=%s | sparkleScore=%.4f | sparkleEvents=%d | sparkleFrames=%d | bestShinyColor=%.4f | bestNormalColor=%.4f | bestShinyCombined=%.4f | bestNormalCombined=%.4f | bestFrame=%d | reason=%s",
                    result.decision,
                    result.sparkleResult != null && result.sparkleResult.isLikelyShiny(),
                    result.sparkleResult != null ? result.sparkleResult.getScore() : 0.0,
                    result.sparkleResult != null ? result.sparkleResult.getSparkleEvents() : 0,
                    result.sparkleResult != null ? result.sparkleResult.getAnalyzedFrames() : 0,
                    result.colorResult != null ? result.colorResult.bestShinyColor : 0.0,
                    result.colorResult != null ? result.colorResult.bestNormalColor : 0.0,
                    result.colorResult != null ? result.colorResult.bestShinyCombined : 0.0,
                    result.colorResult != null ? result.colorResult.bestNormalCombined : 0.0,
                    result.colorResult != null ? result.colorResult.bestFrameIndex : -1,
                    result.reason
            ));

            if (result.decision == Decision.SHINY) {
                alert("Shiny detected! Bot stopped.");
                stop();
                return;
            }

            keys.softReset();
            keys.sleepMs(RSE_POST_DETECTION_RESET_DELAY_MS);
        }
    }

    private void runStartupSequence() {
        keys.sleepMs(DELAY_MS);
        if (!running.get()) return;
        keys.pressA();

        keys.sleepMs(8000);
        if (!running.get()) return;
        keys.pressA();

        keys.sleepMs(8000);
        if (!running.get()) return;
        keys.pressA();

        keys.sleepMs(8000);
        if (!running.get()) return;
        keys.pressA();

        keys.sleepMs(DELAY_MS);
        if (!running.get()) return;

        keys.sleepMs(8000);
    }

    private RouteResult runStarterSelectionAndDetection(Path attemptDir) {
        keys.sleepMs(WAIT_1000);
        if (!running.get()) return RouteResult.noDecision("stopped before starter movement");

        moveToSelectedStarter();
        if (!running.get()) return RouteResult.noDecision("stopped during starter movement");

        keys.pressA();
        keys.sleepMs(WAIT_100);
        if (!running.get()) return RouteResult.noDecision("stopped after first confirm");

        keys.pressA();
        keys.sleepMs(WAIT_2000);
        if (!running.get()) return RouteResult.noDecision("stopped after second confirm");

        mashAFor(RSE_TO_BATTLE_ENTRY_MASH_MS);
        if (!running.get()) return RouteResult.noDecision("stopped during battle entry mash");

        keys.sleepMs(RSE_SPARKLE_PREP_DELAY_MS);
        if (!running.get()) return RouteResult.noDecision("stopped during sparkle prep delay");

        log("RSE route: starting sparkle watch after prep delay = " + RSE_SPARKLE_PREP_DELAY_MS + "ms");

        Path sparkleDebugDir = attemptDir.resolve("sparkle-analysis");
        ensureDebugDirExists(sparkleDebugDir);

        BufferedImage preDetectionFrame = state.getFrameSourceOrThrow().capture();
        saveImage(attemptDir.resolve("sparkle_pre_detection_frame.png"), preDetectionFrame);

        Rectangle sparkleRoi = buildStarterLocalRoi(preDetectionFrame);

        log(String.format(
                Locale.US,
                "RSE sparkle ROI | x=%d y=%d w=%d h=%d",
                sparkleRoi.x, sparkleRoi.y, sparkleRoi.width, sparkleRoi.height
        ));

        if (DEBUG_SAVE_ROI_CROPS) {
            saveRoiCrop(attemptDir.resolve("sparkle_pre_detection_roi.png"), preDetectionFrame, sparkleRoi);
        }

        if (DEBUG_VERBOSE_SCORES) {
            log(String.format(
                    Locale.US,
                    "RSE sparkle config | frameCount=%d | frameDelayMs=%d | minSparkleEvents=%d | decisionThreshold=%.2f",
                    SPARKLE_FRAME_COUNT,
                    SPARKLE_FRAME_DELAY_MS,
                    SPARKLE_MIN_EVENTS,
                    SPARKLE_DECISION_THRESHOLD
            ));
        }

        SparkleDetectionResult sparkleResult = sparkleDetector.detect(
                () -> state.getFrameSourceOrThrow().capture(),
                sparkleRoi,
                sparkleDebugDir
        );

        if (DEBUG_SAVE_CHECK_FRAMES) {
            BufferedImage afterSparkleFrame = state.getFrameSourceOrThrow().capture();
            saveSparkleFinalFrame(attemptDir, afterSparkleFrame, sparkleResult);
        }

        keys.sleepMs(RSE_COLOR_CONFIRM_DELAY_MS);
        if (!running.get()) {
            return new RouteResult(Decision.NO_DECISION, sparkleResult, null, "stopped before color confirm");
        }

        ShinyCheckWindowResult colorResult = runColorDecisionWindow(attemptDir, sparkleRoi);

        boolean sparkleSupportsShiny =
                sparkleResult != null
                        && sparkleResult.isLikelyShiny()
                        && sparkleResult.getSparkleEvents() >= SPARKLE_MIN_EVENTS
                        && sparkleResult.getScore() >= SPARKLE_DECISION_THRESHOLD;

        Decision finalDecision;
        String reason;

        if (sparkleSupportsShiny && colorResult.decision == Decision.SHINY) {
            finalDecision = Decision.SHINY;
            reason = "sparkle + color confirmed shiny";
        } else if (colorResult.decision == Decision.NOT_SHINY) {
            double normalLead = colorResult.bestNormalColor - colorResult.bestShinyColor;

            if (isTorchic() && sparkleSupportsShiny && normalLead < TORCHIC_SMALL_NORMAL_LEAD_MAX) {
                finalDecision = Decision.SHINY;
                reason = String.format(
                        Locale.US,
                        "torchic override: sparkle strong and color only slightly leaned normal (normalLead=%.4f)",
                        normalLead
                );
            } else {
                finalDecision = Decision.NOT_SHINY;
                reason = "color confirmed normal";
            }
        } else if (!sparkleSupportsShiny && colorResult.decision == Decision.SHINY) {
            finalDecision = Decision.NO_DECISION;
            reason = "color leaned shiny but sparkle did not confirm";
        } else {
            finalDecision = Decision.NO_DECISION;
            reason = "insufficient agreement";
        }

        if (DEBUG_VERBOSE_SCORES) {
            log(String.format(
                    Locale.US,
                    "RSE hybrid decision | sparkleSupportsShiny=%s | sparkleLikely=%s | sparkleScore=%.4f | sparkleEvents=%d | colorDecision=%s | bestShinyColor=%.4f | bestNormalColor=%.4f | colorMargin=%.4f | reason=%s",
                    sparkleSupportsShiny,
                    sparkleResult != null && sparkleResult.isLikelyShiny(),
                    sparkleResult != null ? sparkleResult.getScore() : 0.0,
                    sparkleResult != null ? sparkleResult.getSparkleEvents() : 0,
                    colorResult.decision,
                    colorResult.bestShinyColor,
                    colorResult.bestNormalColor,
                    colorResult.bestShinyColor - colorResult.bestNormalColor,
                    reason
            ));
        }

        return new RouteResult(finalDecision, sparkleResult, colorResult, reason);
    }

    private void moveToSelectedStarter() {
        switch (pokedexNumber) {
            case 252 -> {
                keys.left();
                keys.sleepMs(WAIT_1000);
            }
            case 255 -> {
                // center starter
            }
            case 258 -> {
                keys.right();
                keys.sleepMs(WAIT_1000);
            }
            default -> {
                // fallback = center
            }
        }
    }

    private ShinyCheckWindowResult runColorDecisionWindow(Path attemptDir, Rectangle roi) {
        double bestShinyCombined = Double.NEGATIVE_INFINITY;
        double bestNormalCombined = Double.NEGATIVE_INFINITY;
        double bestShinyColor = Double.NEGATIVE_INFINITY;
        double bestNormalColor = Double.NEGATIVE_INFINITY;

        int bestFrameIndex = -1;
        int shinyWeakWins = 0;
        int normalWeakWins = 0;

        Path checkDir = attemptDir.resolve("color-check");
        ensureDebugDirExists(checkDir);

        for (int i = 0; i < CHECK_FRAME_COUNT && running.get(); i++) {
            int frameIndex = i + 1;
            BufferedImage frame = state.getFrameSourceOrThrow().capture();
            BufferedImage crop = crop(frame, roi);

            double shinyCombined = TemplateMatcher.findBestCombinedMatchScore(crop, shinyBackTemplate, MATCH_STRIDE);
            double normalCombined = TemplateMatcher.findBestCombinedMatchScore(crop, normalBackTemplate, MATCH_STRIDE);

            double shinyColor = TemplateMatcher.findBestColorMatchScore(crop, shinyBackTemplate, MATCH_STRIDE);
            double normalColor = TemplateMatcher.findBestColorMatchScore(crop, normalBackTemplate, MATCH_STRIDE);

            double shinyGray = TemplateMatcher.findBestMatchScore(crop, shinyBackTemplate, MATCH_STRIDE);
            double normalGray = TemplateMatcher.findBestMatchScore(crop, normalBackTemplate, MATCH_STRIDE);

            double combinedMargin = shinyCombined - normalCombined;
            double colorMargin = shinyColor - normalColor;

            Decision frameDecision = decideStrong(shinyCombined, normalCombined, shinyColor, normalColor);

            boolean shinyWeakWin = isWeakShinyWin(shinyColor, normalColor);
            boolean normalWeakWin = isWeakNormalWin(shinyColor, normalColor);

            if (shinyWeakWin) shinyWeakWins++;
            if (normalWeakWin) normalWeakWins++;

            if (DEBUG_VERBOSE_SCORES) {
                log(String.format(
                        Locale.US,
                        "RSE check frame %d/%d | shinyCombined=%.4f | normalCombined=%.4f | combinedMargin=%.4f | shinyColor=%.4f | normalColor=%.4f | colorMargin=%.4f | shinyGray=%.4f | normalGray=%.4f | strongDecision=%s | shinyWeakWin=%s | normalWeakWin=%s",
                        frameIndex,
                        CHECK_FRAME_COUNT,
                        shinyCombined,
                        normalCombined,
                        combinedMargin,
                        shinyColor,
                        normalColor,
                        colorMargin,
                        shinyGray,
                        normalGray,
                        frameDecision,
                        shinyWeakWin,
                        normalWeakWin
                ));
            }

            if (DEBUG_SAVE_CHECK_FRAMES) {
                saveCheckFrame(
                        checkDir,
                        crop,
                        frameIndex,
                        shinyCombined,
                        normalCombined,
                        shinyColor,
                        normalColor,
                        shinyGray,
                        normalGray,
                        frameDecision
                );
            }

            if (shouldReplaceBestFrame(
                    shinyCombined,
                    normalCombined,
                    shinyColor,
                    normalColor,
                    bestShinyCombined,
                    bestNormalCombined,
                    bestShinyColor,
                    bestNormalColor
            )) {
                bestShinyCombined = shinyCombined;
                bestNormalCombined = normalCombined;
                bestShinyColor = shinyColor;
                bestNormalColor = normalColor;
                bestFrameIndex = frameIndex;
            }

            if (frameDecision == Decision.SHINY) {
                return new ShinyCheckWindowResult(
                        Decision.SHINY,
                        shinyCombined,
                        normalCombined,
                        shinyColor,
                        normalColor,
                        frameIndex,
                        shinyWeakWins,
                        normalWeakWins
                );
            }

            if (frameDecision == Decision.NOT_SHINY) {
                return new ShinyCheckWindowResult(
                        Decision.NOT_SHINY,
                        shinyCombined,
                        normalCombined,
                        shinyColor,
                        normalColor,
                        frameIndex,
                        shinyWeakWins,
                        normalWeakWins
                );
            }

            if (i < CHECK_FRAME_COUNT - 1) {
                keys.sleepMs(CHECK_FRAME_GAP_MS);
            }
        }

        Decision windowDecision = decideWindowLevel(
                bestShinyCombined,
                bestNormalCombined,
                bestShinyColor,
                bestNormalColor,
                shinyWeakWins,
                normalWeakWins
        );

        return new ShinyCheckWindowResult(
                windowDecision,
                bestShinyCombined,
                bestNormalCombined,
                bestShinyColor,
                bestNormalColor,
                bestFrameIndex,
                shinyWeakWins,
                normalWeakWins
        );
    }

    private Decision decideStrong(
            double shinyCombined,
            double normalCombined,
            double shinyColor,
            double normalColor
    ) {
        double combinedMargin = shinyCombined - normalCombined;
        double colorMargin = shinyColor - normalColor;

        if (shinyColor >= SHINY_COLOR_THRESHOLD && colorMargin >= COLOR_DECISION_MARGIN) {
            return Decision.SHINY;
        }

        if (normalColor >= NORMAL_COLOR_THRESHOLD && (-colorMargin) >= COLOR_DECISION_MARGIN) {
            return Decision.NOT_SHINY;
        }

        if (shinyCombined >= COMBINED_FALLBACK_THRESHOLD && combinedMargin >= COMBINED_DECISION_MARGIN) {
            return Decision.SHINY;
        }

        if (normalCombined >= COMBINED_FALLBACK_THRESHOLD && (-combinedMargin) >= COMBINED_DECISION_MARGIN) {
            return Decision.NOT_SHINY;
        }

        return Decision.NO_DECISION;
    }

    private boolean isWeakShinyWin(double shinyColor, double normalColor) {
        return shinyColor >= WEAK_SHINY_COLOR_THRESHOLD
                && (shinyColor - normalColor) >= WEAK_COLOR_MARGIN;
    }

    private boolean isWeakNormalWin(double shinyColor, double normalColor) {
        return normalColor >= WEAK_NORMAL_COLOR_THRESHOLD
                && (normalColor - shinyColor) >= WEAK_COLOR_MARGIN;
    }

    private Decision decideWindowLevel(
            double bestShinyCombined,
            double bestNormalCombined,
            double bestShinyColor,
            double bestNormalColor,
            int shinyWeakWins,
            int normalWeakWins
    ) {
        Decision strong = decideStrong(
                bestShinyCombined,
                bestNormalCombined,
                bestShinyColor,
                bestNormalColor
        );
        if (strong != Decision.NO_DECISION) return strong;

        if (shinyWeakWins >= WEAK_RULE_REQUIRED_FRAMES && shinyWeakWins > normalWeakWins) {
            return Decision.SHINY;
        }

        if (normalWeakWins >= WEAK_RULE_REQUIRED_FRAMES && normalWeakWins > shinyWeakWins) {
            return Decision.NOT_SHINY;
        }

        return Decision.NO_DECISION;
    }

    private boolean shouldReplaceBestFrame(
            double shinyCombined,
            double normalCombined,
            double shinyColor,
            double normalColor,
            double bestShinyCombined,
            double bestNormalCombined,
            double bestShinyColor,
            double bestNormalColor
    ) {
        if (!Double.isFinite(bestShinyColor) || !Double.isFinite(bestNormalColor)
                || !Double.isFinite(bestShinyCombined) || !Double.isFinite(bestNormalCombined)) {
            return true;
        }

        double currentColorMargin = shinyColor - normalColor;
        double bestColorMargin = bestShinyColor - bestNormalColor;

        if (currentColorMargin > bestColorMargin) return true;
        if (currentColorMargin < bestColorMargin) return false;

        double currentCombinedMargin = shinyCombined - normalCombined;
        double bestCombinedMargin = bestShinyCombined - bestNormalCombined;

        return currentCombinedMargin > bestCombinedMargin;
    }

    private Rectangle buildStarterLocalRoi(BufferedImage frame) {
        if (frame == null) {
            throw new IllegalStateException("Could not capture pre-detection frame for RSE starter ROI.");
        }

        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();

        int x = (int) Math.round(frameWidth * ROI_X_PCT);
        int y = (int) Math.round(frameHeight * ROI_Y_PCT);
        int w = (int) Math.round(frameWidth * ROI_W_PCT);
        int h = (int) Math.round(frameHeight * ROI_H_PCT);

        x = clamp(x, 0, frameWidth - 1);
        y = clamp(y, 0, frameHeight - 1);
        w = clamp(w, 1, frameWidth - x);
        h = clamp(h, 1, frameHeight - y);

        return new Rectangle(x, y, w, h);
    }

    private BufferedImage crop(BufferedImage image, Rectangle roi) {
        Rectangle bounded = roi.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        return image.getSubimage(bounded.x, bounded.y, bounded.width, bounded.height);
    }

    private void mashAFor(long durationMs) {
        long end = System.currentTimeMillis() + durationMs;
        while (running.get() && System.currentTimeMillis() < end) {
            keys.pressA();
            keys.sleepMs(TAP_GAP_MS);
        }
    }

    private boolean isTorchic() {
        return pokedexNumber == TORCHIC_DEX;
    }

    private void saveTemplateDebugImages() {
        saveImage(debugRootDir.resolve(String.format(Locale.US, "template_normal_back_dex_%03d.png", pokedexNumber)), normalBackTemplate);
        saveImage(debugRootDir.resolve(String.format(Locale.US, "template_shiny_back_dex_%03d.png", pokedexNumber)), shinyBackTemplate);
    }

    private void saveCheckFrame(
            Path attemptDir,
            BufferedImage frame,
            int frameIndex,
            double shinyCombined,
            double normalCombined,
            double shinyColor,
            double normalColor,
            double shinyGray,
            double normalGray,
            Decision decision
    ) {
        DecimalFormat df = new DecimalFormat("0.0000");
        String filename = String.format(
                Locale.US,
                "frame_%02d__decision_%s__sc_%s__nc_%s__scol_%s__ncol_%s__sg_%s__ng_%s.png",
                frameIndex,
                decision.name().toLowerCase(Locale.US),
                df.format(shinyCombined),
                df.format(normalCombined),
                df.format(shinyColor),
                df.format(normalColor),
                df.format(shinyGray),
                df.format(normalGray)
        );
        saveImage(attemptDir.resolve(filename), frame);
    }

    private void saveSparkleFinalFrame(
            Path attemptDir,
            BufferedImage frame,
            SparkleDetectionResult result
    ) {
        DecimalFormat df = new DecimalFormat("0.0000");
        String filename = String.format(
                Locale.US,
                "sparkle_final__likely_%s__score_%s__events_%d__frames_%d.png",
                result.isLikelyShiny(),
                df.format(result.getScore()),
                result.getSparkleEvents(),
                result.getAnalyzedFrames()
        );
        saveImage(attemptDir.resolve(filename), frame);
    }

    private void saveRoiCrop(Path path, BufferedImage frame, Rectangle roi) {
        BufferedImage crop = crop(frame, roi);
        saveImage(path, crop);
    }

    private void saveImage(Path path, BufferedImage image) {
        try {
            ensureDebugDirExists(path.getParent());
            ImageIO.write(image, "png", path.toFile());
            log("Saved debug image: " + path.toAbsolutePath());
        } catch (IOException e) {
            log("Failed to save debug image: " + path.toAbsolutePath() + " | " + e.getMessage());
        }
    }

    private void ensureDebugDirExists(Path dir) {
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create debug directory: " + dir, e);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void log(String msg) {
        System.out.println("[RSEStarterMethod] " + msg);
    }

    private void alert(String msg) {
        Toolkit.getDefaultToolkit().beep();
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(
                        null,
                        msg,
                        "Pokemon Shiny Hunting Bot",
                        JOptionPane.INFORMATION_MESSAGE
                )
        );
    }

    private enum Decision {
        SHINY,
        NOT_SHINY,
        NO_DECISION
    }

    private static final class RouteResult {
        private final Decision decision;
        private final SparkleDetectionResult sparkleResult;
        private final ShinyCheckWindowResult colorResult;
        private final String reason;

        private RouteResult(
                Decision decision,
                SparkleDetectionResult sparkleResult,
                ShinyCheckWindowResult colorResult,
                String reason
        ) {
            this.decision = decision;
            this.sparkleResult = sparkleResult;
            this.colorResult = colorResult;
            this.reason = reason;
        }

        private static RouteResult noDecision(String reason) {
            return new RouteResult(Decision.NO_DECISION, null, null, reason);
        }
    }

    private static final class ShinyCheckWindowResult {
        private final Decision decision;
        private final double bestShinyCombined;
        private final double bestNormalCombined;
        private final double bestShinyColor;
        private final double bestNormalColor;
        private final int bestFrameIndex;
        private final int shinyWeakWins;
        private final int normalWeakWins;

        private ShinyCheckWindowResult(
                Decision decision,
                double bestShinyCombined,
                double bestNormalCombined,
                double bestShinyColor,
                double bestNormalColor,
                int bestFrameIndex,
                int shinyWeakWins,
                int normalWeakWins
        ) {
            this.decision = decision;
            this.bestShinyCombined = bestShinyCombined;
            this.bestNormalCombined = bestNormalCombined;
            this.bestShinyColor = bestShinyColor;
            this.bestNormalColor = bestNormalColor;
            this.bestFrameIndex = bestFrameIndex;
            this.shinyWeakWins = shinyWeakWins;
            this.normalWeakWins = normalWeakWins;
        }
    }
}