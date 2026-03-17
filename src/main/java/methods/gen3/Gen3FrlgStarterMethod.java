package methods.gen3;

import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import app.AppState;
import core.KeySender;
import core.TargetPlatform;
import methods.HuntMethod;
import vision.FrlgSpriteLoader;
import vision.match.TemplateMatcher;
import vision.shiny.ShinySparkleDetector;
import vision.shiny.SparkleDetectionResult;

public class Gen3FrlgStarterMethod implements HuntMethod {

    private final KeySender keys;
    private final AppState state;
    private final int pokedexNumber;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    // Common startup timings
    private static final long DELAY_MS = 5000;
    private static final long B_SPAM_START_MS = 6000; //was 10000

    // FRLG starter timings
    private static final long A_MASH_MS = 3200;
    private static final long B_MASH_MS = 9000;

    // Menu sequence timings
    private static final long WAIT_500 = 500;
    private static final long WAIT_1000 = 1000;
    private static final long WAIT_1500 = 1500;
    private static final long WAIT_2000 = 2000;

    // Controlled mash rate
    private static final long TAP_GAP_MS = 70;
    private static final long RESET_SEED_STEP_MS = 17;

    // Summary-screen shiny check window
    private static final int CHECK_FRAME_COUNT = 6;
    private static final long CHECK_FRAME_GAP_MS = 250;

    // Strong global rules for non-Squirtle summary route
    private static final double SHINY_COLOR_THRESHOLD = 0.80;
    private static final double NORMAL_COLOR_THRESHOLD = 0.80;
    private static final double COLOR_DECISION_MARGIN = 0.05;

    private static final double COMBINED_FALLBACK_THRESHOLD = 0.76;
    private static final double COMBINED_DECISION_MARGIN = 0.06;

    // Weak repeated-win rules for non-Squirtle species whose shiny is subtler on summary screen
    private static final double WEAK_SHINY_COLOR_THRESHOLD = 0.75;
    private static final double WEAK_NORMAL_COLOR_THRESHOLD = 0.75;
    private static final double WEAK_COLOR_MARGIN = 0.03;
    private static final int WEAK_RULE_REQUIRED_FRAMES = 5;

    private static final int MATCH_STRIDE = 3;

    // Squirtle sparkle route timings / heuristics
    private static final long SQUIRTLE_TO_BATTLE_ENTRY_MASH_MS = 2500;
    private static final long SQUIRTLE_BLACK_SCREEN_TIMEOUT_MS = 8000;
    private static final long SQUIRTLE_BLACK_SCREEN_SAMPLE_GAP_MS = 60;
    private static final int SQUIRTLE_BLACK_SCREEN_REQUIRED_CONSECUTIVE = 2;
    private static final double SQUIRTLE_BLACK_SCREEN_BRIGHTNESS_THRESHOLD = 18.0;

    private static final long SQUIRTLE_BATTLE_SCENE_TIMEOUT_MS = 6000;
    private static final long SQUIRTLE_BATTLE_SCENE_SAMPLE_GAP_MS = 60;
    private static final double SQUIRTLE_BATTLE_SCENE_MIN_BRIGHTNESS = 28.0;
    private static final double SQUIRTLE_BATTLE_SCENE_MIN_CHANGED_RATIO = 0.10;
    private static final int SQUIRTLE_BATTLE_SCENE_PIXEL_DELTA_THRESHOLD = 28;

    // Advance after black screen, then verify battle scene, then advance farther to reveal
    private static final int SQUIRTLE_POST_SCENE_ADVANCE_PRESSES = 4;
    private static final long SQUIRTLE_POST_SCENE_ADVANCE_GAP_MS = 180;
    private static final long SQUIRTLE_SPARKLE_START_DELAY_MS = 300;
    private static final long SQUIRTLE_POST_DETECTION_RESET_DELAY_MS = 1000;

    // Debug output
    private static final boolean DEBUG_SAVE_CHECK_FRAMES = false;
    private static final boolean DEBUG_SAVE_TEMPLATES = false;
    private static final boolean DEBUG_VERBOSE_SCORES = true;

    // Non-Squirtle summary templates
    private final BufferedImage normalTemplate;
    private final BufferedImage shinyTemplate;

    private final ShinySparkleDetector squirtleSparkleDetector;

    private final Path debugRootDir;
    private int attemptCounter = 0;
    private int resetFrameOffsetCount = 0;

    public Gen3FrlgStarterMethod(KeySender keys, AppState state, int pokedexNumber) {
        this.keys = keys;
        this.state = state;
        this.pokedexNumber = pokedexNumber;

        Path projectDir = Path.of(System.getProperty("user.dir"));
        FrlgSpriteLoader loader = new FrlgSpriteLoader(projectDir);

        if (pokedexNumber == 7) {
            this.normalTemplate = null;
            this.shinyTemplate = null;
        } else {
            this.normalTemplate = loader.loadNormalFront(pokedexNumber);
            this.shinyTemplate = loader.loadShinyFront(pokedexNumber);
        }

        ShinySparkleDetector.Config sparkleConfig = new ShinySparkleDetector.Config();
        sparkleConfig.frameCount = 36;
        sparkleConfig.frameDelayMs = 50;
        sparkleConfig.brightnessJumpThreshold = 45;
        sparkleConfig.absoluteBrightThreshold = 180;
        sparkleConfig.maxBrightPixelsPerBurst = 600;
        sparkleConfig.minSparkleEvents = 3;
        sparkleConfig.debug = true;
        sparkleConfig.saveDebugFrames = false;

        boolean switchMode = false;
        try {
            switchMode = state.getTargetPlatform() == TargetPlatform.NINTENDO_SWITCH;
        } catch (Throwable ignored) {
            // If platform state isn't available for some reason, fall back to emulator tuning.
        }

        if (switchMode) {
            // Stricter sparkle filtering for Switch capture-card noise
            sparkleConfig.minBrightPixelsPerBurst = 90;
            sparkleConfig.minClusterCount = 2;
            sparkleConfig.minBurstScore = 2.20;
            sparkleConfig.decisionThreshold = 1.40;
        } else {
            // Original emulator-friendly behavior
            sparkleConfig.minBrightPixelsPerBurst = 8;
            sparkleConfig.minClusterCount = 1;
            sparkleConfig.minBurstScore = 0.0;
            sparkleConfig.decisionThreshold = 0.90;
        }

        this.squirtleSparkleDetector = new ShinySparkleDetector(sparkleConfig);

        this.debugRootDir = projectDir.resolve("debug").resolve("frlg-starter");

        ensureDebugDirExists(debugRootDir);
        if (DEBUG_SAVE_TEMPLATES) {
            saveTemplateDebugImages();
        }
    }

    @Override
    public String name() {
        return "Gen 3 FRLG Starter";
    }

    @Override
    public void start() {
        if (running.get()) return;
        running.set(true);

        worker = new Thread(this::loop, "Gen3FrlgStarterWorker");
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
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
            log("Starting attempt " + attemptCounter + " | dex=" + pokedexNumber + " | debugDir=" + attemptDir.toAbsolutePath());

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressA();

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressStart();

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressStart();

            long resetSeedDelayMs = resetFrameOffsetCount * RESET_SEED_STEP_MS;
            if (resetSeedDelayMs > 0) {
                log("Applying reset seed offset delay: " + resetSeedDelayMs + " ms (" + resetFrameOffsetCount + " frame steps)");
                keys.sleepMs(resetSeedDelayMs);
            }

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressA();

            mashAFor(B_SPAM_START_MS); // Was mash b for 9s in old method.
            if (!running.get()) break;

            mashAFor(A_MASH_MS);
            if (!running.get()) break;

            mashBFor(B_MASH_MS);
            if (!running.get()) break;

            if (pokedexNumber == 7) {
                SquirtleSparkleRouteResult result = runSquirtleBattleRoute(attemptDir);

                log(String.format(
                        Locale.US,
                        "Squirtle sparkle route decision: %s | blackDetected=%s | battleSceneDetected=%s | battleSceneBrightness=%.2f | battleSceneChangedRatio=%.4f | sparkleLikelyShiny=%s | sparkleScore=%.4f | sparkleEvents=%d | sparkleFrames=%d | reason=%s",
                        result.decision,
                        result.blackScreenDetected,
                        result.battleSceneDetected,
                        result.battleSceneBrightness,
                        result.battleSceneChangedRatio,
                        result.sparkleResult != null && result.sparkleResult.isLikelyShiny(),
                        result.sparkleResult != null ? result.sparkleResult.getScore() : 0.0,
                        result.sparkleResult != null ? result.sparkleResult.getSparkleEvents() : 0,
                        result.sparkleResult != null ? result.sparkleResult.getAnalyzedFrames() : 0,
                        result.sparkleResult != null ? result.sparkleResult.getReason() : "no sparkle result"
                ));

                if (result.decision == Decision.SHINY) {
                    alert("Shiny detected! Bot stopped.");
                    stop();
                    return;
                }

                log("Squirtle sparkle route did not confirm shiny. Soft resetting.");
                keys.softReset();
                resetFrameOffsetCount++;
                keys.sleepMs(SQUIRTLE_POST_DETECTION_RESET_DELAY_MS);
                continue;
            }

            keys.sleepMs(WAIT_2000);
            if (!running.get()) break;

            keys.pressStart();
            keys.sleepMs(WAIT_2000);
            if (!running.get()) break;

            keys.pressA();
            keys.sleepMs(WAIT_2000);
            if (!running.get()) break;

            keys.pressA();
            keys.sleepMs(WAIT_2000);
            if (!running.get()) break;

            keys.pressA();
            keys.sleepMs(WAIT_2000);
            if (!running.get()) break;

            keys.sleepMs(WAIT_1000);
            if (!running.get()) break;

            ShinyCheckWindowResult result = runShinyDecisionWindow(attemptDir);

            log(String.format(
                    Locale.US,
                    "Decision: %s | bestFrame=%d | bestShinyCombined=%.4f | bestNormalCombined=%.4f | combinedMargin=%.4f | bestShinyColor=%.4f | bestNormalColor=%.4f | colorMargin=%.4f | shinyWeakWins=%d | normalWeakWins=%d",
                    result.decision,
                    result.bestFrameIndex,
                    result.bestShinyCombined,
                    result.bestNormalCombined,
                    (result.bestShinyCombined - result.bestNormalCombined),
                    result.bestShinyColor,
                    result.bestNormalColor,
                    (result.bestShinyColor - result.bestNormalColor),
                    result.shinyWeakWins,
                    result.normalWeakWins
            ));

            if (result.decision == Decision.SHINY) {
                alert("Shiny detected! Bot stopped.");
                stop();
                return;
            }

            if (result.decision == Decision.NOT_SHINY) {
                log("Not shiny with sufficient confidence. Soft resetting.");
                keys.softReset();
                resetFrameOffsetCount++;
                keys.sleepMs(1500);
                continue;
            }

            log("No decision from check window. Waiting briefly, then soft resetting.");
            keys.sleepMs(1000);
            if (!running.get()) break;

            keys.softReset();
            resetFrameOffsetCount++;
            keys.sleepMs(1500);
        }
    }

    private SquirtleSparkleRouteResult runSquirtleBattleRoute(Path attemptDir) {
        keys.holdDown(1500);
        keys.sleepMs(WAIT_500);

        pressLeft();
        keys.sleepMs(WAIT_500);

        pressLeft();
        keys.sleepMs(WAIT_500);

        pressLeft();
        keys.sleepMs(WAIT_500);

        pressLeft();
        keys.sleepMs(WAIT_500);

        keys.holdDown(1500);
        keys.sleepMs(WAIT_500);

        mashBFor(SQUIRTLE_TO_BATTLE_ENTRY_MASH_MS);
        if (!running.get()) {
            return SquirtleSparkleRouteResult.noDecision();
        }

        log("Squirtle sparkle route: watching for black transition.");

        BlackScreenWatchResult blackResult = waitForBattleTransitionBlackScreen(attemptDir);
        if (!blackResult.detected) {
            log("Squirtle sparkle route: black transition not detected in time.");
            return new SquirtleSparkleRouteResult(
                    Decision.NO_DECISION,
                    false,
                    false,
                    0.0,
                    0.0,
                    null
            );
        }

        log(String.format(
                Locale.US,
                "Squirtle sparkle route: black transition detected | frame=%d | avgBrightness=%.2f",
                blackResult.frameIndex,
                blackResult.averageBrightness
        ));

        advanceSquirtleBattlePastIntro();
        if (!running.get()) {
            return SquirtleSparkleRouteResult.noDecision();
        }

        BattleSceneWatchResult sceneResult = waitForBattleSceneVisible(attemptDir, blackResult.referenceFrame);
        if (!sceneResult.detected) {
            log("Squirtle sparkle route: battle scene not detected after post-scene advance.");
            return new SquirtleSparkleRouteResult(
                    Decision.NO_DECISION,
                    true,
                    false,
                    sceneResult.averageBrightness,
                    sceneResult.changedRatio,
                    null
            );
        }

        log(String.format(
                Locale.US,
                "Squirtle sparkle route: battle scene detected AFTER post-scene advance | frame=%d | avgBrightness=%.2f | changedRatio=%.4f",
                sceneResult.frameIndex,
                sceneResult.averageBrightness,
                sceneResult.changedRatio
        ));

        advanceFromBattleSceneToPokemonReveal();
        if (!running.get()) {
            return SquirtleSparkleRouteResult.noDecision();
        }

        keys.sleepMs(SQUIRTLE_SPARKLE_START_DELAY_MS);
        if (!running.get()) {
            return SquirtleSparkleRouteResult.noDecision();
        }

        log("Squirtle sparkle route: starting sparkle detector now.");

        Path sparkleDebugDir = attemptDir.resolve("sparkle-analysis");
        ensureDebugDirExists(sparkleDebugDir);
        log("Squirtle sparkle route: sparkle debug dir = " + sparkleDebugDir.toAbsolutePath());

        BufferedImage preDetectionFrame = state.getFrameSourceOrThrow().capture();
        saveImage(attemptDir.resolve("sparkle_pre_detection_frame.png"), preDetectionFrame);

        Rectangle localRoi = buildFullFrameLocalRoi(preDetectionFrame);
        log(String.format(
                Locale.US,
                "Squirtle sparkle route: using local ROI x=%d y=%d w=%d h=%d",
                localRoi.x, localRoi.y, localRoi.width, localRoi.height
        ));

        SparkleDetectionResult sparkleResult = squirtleSparkleDetector.detect(
                () -> state.getFrameSourceOrThrow().capture(),
                localRoi,
                sparkleDebugDir
        );

        if (DEBUG_SAVE_CHECK_FRAMES) {
            BufferedImage afterDetectionFrame = state.getFrameSourceOrThrow().capture();
            saveSquirtleSparkleFrame(
                    attemptDir,
                    afterDetectionFrame,
                    "sparkle_final",
                    sparkleResult
            );
        }

        Decision finalDecision = sparkleResult.isLikelyShiny() ? Decision.SHINY : Decision.NOT_SHINY;

        return new SquirtleSparkleRouteResult(
                finalDecision,
                true,
                true,
                sceneResult.averageBrightness,
                sceneResult.changedRatio,
                sparkleResult
        );
    }

    private Rectangle buildFullFrameLocalRoi(BufferedImage frame) {
        if (frame == null) {
            throw new IllegalStateException("Could not capture pre-detection frame for sparkle ROI.");
        }
        return new Rectangle(0, 0, frame.getWidth(), frame.getHeight());
    }

    private void advanceSquirtleBattlePastIntro() {
        log("Squirtle sparkle route: advancing farther into battle intro before battle-scene check.");

        for (int i = 0; i < SQUIRTLE_POST_SCENE_ADVANCE_PRESSES && running.get(); i++) {
            mashAFor(1000);
            keys.sleepMs(SQUIRTLE_POST_SCENE_ADVANCE_GAP_MS);
        }
    }

    private void advanceFromBattleSceneToPokemonReveal() {
        log("Squirtle sparkle route: advancing from battle scene to pokemon reveal before sparkle detection.");

        mashAFor(1800);
        if (!running.get()) return;

        keys.sleepMs(250);

        mashAFor(1200);
        if (!running.get()) return;

        keys.sleepMs(300);
    }

    private BlackScreenWatchResult waitForBattleTransitionBlackScreen(Path attemptDir) {
        long deadline = System.currentTimeMillis() + SQUIRTLE_BLACK_SCREEN_TIMEOUT_MS;
        int consecutiveBlackFrames = 0;
        int frameIndex = 0;

        while (running.get() && System.currentTimeMillis() < deadline) {
            keys.pressB();
            BufferedImage frame = state.getFrameSourceOrThrow().capture();
            frameIndex++;

            double avgBrightness = averageBrightness(frame);
            boolean black = avgBrightness <= SQUIRTLE_BLACK_SCREEN_BRIGHTNESS_THRESHOLD;

            if (DEBUG_VERBOSE_SCORES) {
                log(String.format(
                        Locale.US,
                        "Squirtle black-watch frame %d | avgBrightness=%.2f | black=%s | consecutive=%d",
                        frameIndex,
                        avgBrightness,
                        black,
                        black ? (consecutiveBlackFrames + 1) : 0
                ));
            }

            if (DEBUG_SAVE_CHECK_FRAMES) {
                saveBlackWatchFrame(attemptDir, frame, frameIndex, avgBrightness, black);
            }

            if (black) {
                consecutiveBlackFrames++;
                if (consecutiveBlackFrames >= SQUIRTLE_BLACK_SCREEN_REQUIRED_CONSECUTIVE) {
                    return new BlackScreenWatchResult(true, frame, frameIndex, avgBrightness);
                }
            } else {
                consecutiveBlackFrames = 0;
            }

            keys.sleepMs(SQUIRTLE_BLACK_SCREEN_SAMPLE_GAP_MS);
        }

        return BlackScreenWatchResult.notDetected();
    }

    private BattleSceneWatchResult waitForBattleSceneVisible(Path attemptDir, BufferedImage referenceBlackFrame) {
        long deadline = System.currentTimeMillis() + SQUIRTLE_BATTLE_SCENE_TIMEOUT_MS;
        int frameIndex = 0;

        while (running.get() && System.currentTimeMillis() < deadline) {
            keys.pressB();
            BufferedImage frame = state.getFrameSourceOrThrow().capture();
            frameIndex++;

            double avgBrightness = averageBrightness(frame);
            double changedRatio = referenceBlackFrame == null
                    ? 0.0
                    : changedRatio(referenceBlackFrame, frame, SQUIRTLE_BATTLE_SCENE_PIXEL_DELTA_THRESHOLD);

            boolean battleSceneDetected =
                    avgBrightness >= SQUIRTLE_BATTLE_SCENE_MIN_BRIGHTNESS
                            && changedRatio >= SQUIRTLE_BATTLE_SCENE_MIN_CHANGED_RATIO;

            if (DEBUG_VERBOSE_SCORES) {
                log(String.format(
                        Locale.US,
                        "Squirtle battle-scene frame %d | avgBrightness=%.2f | changedRatio=%.4f | detected=%s",
                        frameIndex,
                        avgBrightness,
                        changedRatio,
                        battleSceneDetected
                ));
            }

            if (DEBUG_SAVE_CHECK_FRAMES) {
                saveBattleSceneWatchFrame(attemptDir, frame, frameIndex, avgBrightness, changedRatio, battleSceneDetected);
            }

            if (battleSceneDetected) {
                return new BattleSceneWatchResult(true, frameIndex, avgBrightness, changedRatio);
            }

            keys.sleepMs(SQUIRTLE_BATTLE_SCENE_SAMPLE_GAP_MS);
        }

        return BattleSceneWatchResult.notDetected();
    }

    private double averageBrightness(BufferedImage image) {
        if (image == null) {
            return 0.0;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        long total = 0L;
        long count = 0L;
        int stride = 2;

        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                int rgb = image.getRGB(x, y);
                total += gray(rgb);
                count++;
            }
        }

        return count == 0 ? 0.0 : total / (double) count;
    }

    private double changedRatio(BufferedImage previous, BufferedImage current, int pixelDeltaThreshold) {
        if (previous == null || current == null) {
            return 0.0;
        }

        int w = Math.min(previous.getWidth(), current.getWidth());
        int h = Math.min(previous.getHeight(), current.getHeight());

        long changed = 0L;
        long sampled = 0L;
        int stride = 2;

        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int a = previous.getRGB(x, y);
                int b = current.getRGB(x, y);

                int ar = (a >> 16) & 0xFF;
                int ag = (a >> 8) & 0xFF;
                int ab = a & 0xFF;

                int br = (b >> 16) & 0xFF;
                int bg = (b >> 8) & 0xFF;
                int bb = b & 0xFF;

                int delta = Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb);
                if (delta > pixelDeltaThreshold) {
                    changed++;
                }
                sampled++;
            }
        }

        return sampled == 0 ? 0.0 : changed / (double) sampled;
    }

    private int gray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private void pressLeft() {
        keys.left();
        keys.sleepMs(TAP_GAP_MS);
    }

    private ShinyCheckWindowResult runShinyDecisionWindow(Path attemptDir) {
        double bestShinyCombined = Double.NEGATIVE_INFINITY;
        double bestNormalCombined = Double.NEGATIVE_INFINITY;
        double bestShinyColor = Double.NEGATIVE_INFINITY;
        double bestNormalColor = Double.NEGATIVE_INFINITY;

        int bestFrameIndex = -1;
        int shinyWeakWins = 0;
        int normalWeakWins = 0;

        for (int i = 0; i < CHECK_FRAME_COUNT && running.get(); i++) {
            int frameIndex = i + 1;
            BufferedImage frame = state.getFrameSourceOrThrow().capture();

            double shinyCombined = TemplateMatcher.findBestCombinedMatchScore(frame, shinyTemplate, MATCH_STRIDE);
            double normalCombined = TemplateMatcher.findBestCombinedMatchScore(frame, normalTemplate, MATCH_STRIDE);

            double shinyColor = TemplateMatcher.findBestColorMatchScore(frame, shinyTemplate, MATCH_STRIDE);
            double normalColor = TemplateMatcher.findBestColorMatchScore(frame, normalTemplate, MATCH_STRIDE);

            double shinyGray = TemplateMatcher.findBestMatchScore(frame, shinyTemplate, MATCH_STRIDE);
            double normalGray = TemplateMatcher.findBestMatchScore(frame, normalTemplate, MATCH_STRIDE);

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
                        "Check frame %d/%d | shinyCombined=%.4f | normalCombined=%.4f | combinedMargin=%.4f | shinyColor=%.4f | normalColor=%.4f | colorMargin=%.4f | shinyGray=%.4f | normalGray=%.4f | strongDecision=%s | shinyWeakWin=%s | normalWeakWin=%s",
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
                        attemptDir,
                        frame,
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

        Decision finalDecision = decideWindowLevel(
                bestShinyCombined,
                bestNormalCombined,
                bestShinyColor,
                bestNormalColor,
                shinyWeakWins,
                normalWeakWins
        );

        return new ShinyCheckWindowResult(
                finalDecision,
                bestShinyCombined,
                bestNormalCombined,
                bestShinyColor,
                bestNormalColor,
                bestFrameIndex,
                shinyWeakWins,
                normalWeakWins
        );
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
        double currentColorMargin = shinyColor - normalColor;
        double bestColorMargin = bestShinyColor - bestNormalColor;

        if (currentColorMargin > bestColorMargin) return true;
        if (currentColorMargin < bestColorMargin) return false;

        double currentCombinedMargin = shinyCombined - normalCombined;
        double bestCombinedMargin = bestShinyCombined - bestNormalCombined;

        if (currentCombinedMargin > bestCombinedMargin) return true;
        if (currentCombinedMargin < bestCombinedMargin) return false;

        return shinyCombined > bestShinyCombined;
    }

    private Decision decideStrong(
            double shinyCombined,
            double normalCombined,
            double shinyColor,
            double normalColor
    ) {
        double colorMargin = shinyColor - normalColor;
        double combinedMargin = shinyCombined - normalCombined;

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

    private void mashAFor(long durationMs) {
        long end = System.currentTimeMillis() + durationMs;
        while (running.get() && System.currentTimeMillis() < end) {
            keys.pressA();
            keys.sleepMs(TAP_GAP_MS);
        }
    }

    private void mashBFor(long durationMs) {
        long end = System.currentTimeMillis() + durationMs;
        while (running.get() && System.currentTimeMillis() < end) {
            keys.pressB();
            keys.sleepMs(TAP_GAP_MS);
        }
    }

    private void saveTemplateDebugImages() {
        if (pokedexNumber == 7) {
            return;
        }

        if (normalTemplate != null) {
            saveImage(debugRootDir.resolve(String.format(Locale.US, "template_normal_dex_%03d.png", pokedexNumber)), normalTemplate);
        }

        if (shinyTemplate != null) {
            saveImage(debugRootDir.resolve(String.format(Locale.US, "template_shiny_dex_%03d.png", pokedexNumber)), shinyTemplate);
        }
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

    private void saveBlackWatchFrame(
            Path attemptDir,
            BufferedImage frame,
            int frameIndex,
            double avgBrightness,
            boolean black
    ) {
        DecimalFormat df = new DecimalFormat("0.00");
        String filename = String.format(
                Locale.US,
                "squirtle_black_watch_%02d__avg_%s__black_%s.png",
                frameIndex,
                df.format(avgBrightness),
                black
        );
        saveImage(attemptDir.resolve(filename), frame);
    }

    private void saveBattleSceneWatchFrame(
            Path attemptDir,
            BufferedImage frame,
            int frameIndex,
            double avgBrightness,
            double changedRatio,
            boolean detected
    ) {
        DecimalFormat dfBrightness = new DecimalFormat("0.00");
        DecimalFormat dfRatio = new DecimalFormat("0.0000");
        String filename = String.format(
                Locale.US,
                "squirtle_battle_scene_%02d__avg_%s__chg_%s__detected_%s.png",
                frameIndex,
                dfBrightness.format(avgBrightness),
                dfRatio.format(changedRatio),
                detected
        );
        saveImage(attemptDir.resolve(filename), frame);
    }

    private void saveSquirtleSparkleFrame(
            Path attemptDir,
            BufferedImage frame,
            String prefix,
            SparkleDetectionResult result
    ) {
        DecimalFormat df = new DecimalFormat("0.0000");
        String filename = String.format(
                Locale.US,
                "%s__likely_%s__score_%s__events_%d__frames_%d.png",
                prefix,
                result.isLikelyShiny(),
                df.format(result.getScore()),
                result.getSparkleEvents(),
                result.getAnalyzedFrames()
        );
        saveImage(attemptDir.resolve(filename), frame);
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

    private void log(String msg) {
        System.out.println("[Gen3FrlgStarterMethod] " + msg);
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

    private static final class BlackScreenWatchResult {
        private final boolean detected;
        private final BufferedImage referenceFrame;
        private final int frameIndex;
        private final double averageBrightness;

        private BlackScreenWatchResult(
                boolean detected,
                BufferedImage referenceFrame,
                int frameIndex,
                double averageBrightness
        ) {
            this.detected = detected;
            this.referenceFrame = referenceFrame;
            this.frameIndex = frameIndex;
            this.averageBrightness = averageBrightness;
        }

        private static BlackScreenWatchResult notDetected() {
            return new BlackScreenWatchResult(false, null, -1, 0.0);
        }
    }

    private static final class BattleSceneWatchResult {
        private final boolean detected;
        private final int frameIndex;
        private final double averageBrightness;
        private final double changedRatio;

        private BattleSceneWatchResult(
                boolean detected,
                int frameIndex,
                double averageBrightness,
                double changedRatio
        ) {
            this.detected = detected;
            this.frameIndex = frameIndex;
            this.averageBrightness = averageBrightness;
            this.changedRatio = changedRatio;
        }

        private static BattleSceneWatchResult notDetected() {
            return new BattleSceneWatchResult(false, -1, 0.0, 0.0);
        }
    }

    private static final class SquirtleSparkleRouteResult {
        private final Decision decision;
        private final boolean blackScreenDetected;
        private final boolean battleSceneDetected;
        private final double battleSceneBrightness;
        private final double battleSceneChangedRatio;
        private final SparkleDetectionResult sparkleResult;

        private SquirtleSparkleRouteResult(
                Decision decision,
                boolean blackScreenDetected,
                boolean battleSceneDetected,
                double battleSceneBrightness,
                double battleSceneChangedRatio,
                SparkleDetectionResult sparkleResult
        ) {
            this.decision = decision;
            this.blackScreenDetected = blackScreenDetected;
            this.battleSceneDetected = battleSceneDetected;
            this.battleSceneBrightness = battleSceneBrightness;
            this.battleSceneChangedRatio = battleSceneChangedRatio;
            this.sparkleResult = sparkleResult;
        }

        private static SquirtleSparkleRouteResult noDecision() {
            return new SquirtleSparkleRouteResult(
                    Decision.NO_DECISION,
                    false,
                    false,
                    0.0,
                    0.0,
                    null
            );
        }
    }
}
