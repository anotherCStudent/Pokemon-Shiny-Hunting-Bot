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
import methods.HuntMethod;
import vision.FrameChangeDetector;
import vision.match.TemplateMatcher;
import vision.shiny.ShinySparkleDetector;
import vision.shiny.SparkleDetectionResult;

public class Gen3SpinMethod implements HuntMethod {

    private final KeySender keys;
    private final AppState state;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    // Spin pattern
    private static final int[] DIRS = {0, 1, 2, 3}; // L, D, R, U
    private int dirIndex = 0;

    // Timing knobs (safe defaults)
    private static final long TURN_DELAY_MS = 60;
    private static final long POST_BATTLE_WAIT_MS = 650;

    // Sparkle timing (same idea as legendary: delay a bit so we aren’t too early)
    private static final long PRE_SPARKLE_DELAY_MS = 1000;
    private static final long PRE_SPARKLE_EXTRA_DELAY_MS = 100;

    // (you asked for this to be 5000ms after a false check)
    private static final long RUNAWAY_MASH_B_MS = 5000;
    private static final long MENU_TAP_DELAY_MS = 120;

    // Template confirm window (DEBUG ONLY in spin mode)
    private static final int CHECK_FRAME_COUNT = 4;
    private static final long CHECK_FRAME_GAP_MS = 120;
    private static final int MATCH_STRIDE = 3;

    // ROI for sparkle + template (universal-ish)
    private static final double ROI_X_PCT = 0.12;
    private static final double ROI_Y_PCT = 0.12;
    private static final double ROI_W_PCT = 0.76;
    private static final double ROI_H_PCT = 0.76;

    // Battle detection via frame-change spike
    private final FrameChangeDetector battleChangeDetector =
            new FrameChangeDetector(0.16, 35);

    // Templates (provided by GUI/Registry) - used for debug only in Spin Method
    private final BufferedImage normalTemplate;
    private final BufferedImage shinyTemplate;

    // Debug
    private final Path debugRootDir;
    private int attemptCounter = 0;
    private static final boolean DEBUG_SAVE_TEMPLATES = true;

    // Sparkle detector
    private final ShinySparkleDetector sparkleDetector;

    // IMPORTANT: after we escape a battle, the end-of-battle transition can trigger the detector.
    // We skip exactly one subsequent "battle started" trigger to avoid false-entry.
    private boolean skipNextBattleTriggerOnce = false;

    public Gen3SpinMethod(KeySender keys, AppState state,
                          BufferedImage normalTemplate,
                          BufferedImage shinyTemplate) {
        this.keys = keys;
        this.state = state;
        this.normalTemplate = normalTemplate;
        this.shinyTemplate = shinyTemplate;

        Path projectDir = Path.of(System.getProperty("user.dir"));
        this.debugRootDir = projectDir.resolve("debug").resolve("gen3-spin");
        ensureDir(debugRootDir);

        if (DEBUG_SAVE_TEMPLATES) {
            saveImage(debugRootDir.resolve("template_normal.png"), normalTemplate);
            saveImage(debugRootDir.resolve("template_shiny.png"), shinyTemplate);
        }

        ShinySparkleDetector.Config cfg = new ShinySparkleDetector.Config();
        cfg.frameCount = 28;
        cfg.frameDelayMs = 40;
        cfg.brightnessJumpThreshold = 45;
        cfg.absoluteBrightThreshold = 180;
        cfg.minBrightPixelsPerBurst = 6;
        cfg.maxBrightPixelsPerBurst = 350;
        cfg.minSparkleEvents = 3;
        cfg.decisionThreshold = 0.95;
        cfg.debug = true;
        cfg.saveDebugFrames = true;

        this.sparkleDetector = new ShinySparkleDetector(cfg);
    }

    @Override
    public String name() {
        return "Gen 3 Spin Method";
    }

    @Override
    public void start() {
        if (running.get()) return;
        running.set(true);

        worker = new Thread(this::loop, "Gen3SpinWorker");
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

        // Clear dialogs/menus safely (your changes preserved)
        mashAFor(5000);
        mashBFor(3000);

        while (running.get()) {
            // 1) spin
            pressNextTurn();

            // 2) detect battle start
            BufferedImage frame = state.getFrameSourceOrThrow().capture();
            boolean battleStarted = battleChangeDetector.updateAndCheck(frame);

            // If we just escaped a battle, ignore exactly one trigger (end-of-battle fade)
            if (battleStarted && skipNextBattleTriggerOnce) {
                skipNextBattleTriggerOnce = false;
                keys.sleepMs(25);
                continue;
            }

            if (!battleStarted) {
                keys.sleepMs(25);
                continue;
            }

            // ---- battle detected ----
            attemptCounter++;
            Path attemptDir = debugRootDir.resolve(String.format(Locale.US, "attempt_%05d", attemptCounter));
            ensureDir(attemptDir);

            // settle into battle UI
            keys.sleepMs(POST_BATTLE_WAIT_MS);

            BufferedImage battleFrame = state.getFrameSourceOrThrow().capture();
            saveImage(attemptDir.resolve("battle_settle_frame.png"), battleFrame);

            // delay sparkle so we aren’t too early (your existing delays preserved)
            keys.sleepMs(PRE_SPARKLE_DELAY_MS);
            keys.sleepMs(PRE_SPARKLE_EXTRA_DELAY_MS);

            // EXTRA delay requested: sparkle was ~1000ms early
            keys.sleepMs(1000);

            // pre-detection frame + ROI
            BufferedImage pre = state.getFrameSourceOrThrow().capture();
            saveImage(attemptDir.resolve("sparkle_pre_detection_frame.png"), pre);

            Rectangle roi = buildRoi(pre);
            saveImage(attemptDir.resolve("sparkle_pre_detection_roi.png"), crop(pre, roi));

            // sparkle analysis folder
            Path sparkleDir = attemptDir.resolve("sparkle-analysis");
            ensureDir(sparkleDir);

            SparkleDetectionResult sparkle = sparkleDetector.detect(
                    () -> state.getFrameSourceOrThrow().capture(),
                    roi,
                    sparkleDir
            );

            boolean sparkleLikely = sparkle != null && sparkle.isLikelyShiny();
            double sparkleScore = sparkle != null ? sparkle.getScore() : 0.0;
            int sparkleEvents = sparkle != null ? sparkle.getSparkleEvents() : 0;

            // template confirm window (DEBUG ONLY — does NOT affect stop decision)
            Path checkDir = attemptDir.resolve("color-check");
            ensureDir(checkDir);

            for (int i = 0; i < CHECK_FRAME_COUNT; i++) {
                int idx = i + 1;

                BufferedImage bf = state.getFrameSourceOrThrow().capture();
                BufferedImage bfCrop = crop(bf, roi);

                double shinyColor = TemplateMatcher.findBestColorMatchScore(bfCrop, shinyTemplate, MATCH_STRIDE);
                double normalColor = TemplateMatcher.findBestColorMatchScore(bfCrop, normalTemplate, MATCH_STRIDE);
                double shinyCombined = TemplateMatcher.findBestCombinedMatchScore(bfCrop, shinyTemplate, MATCH_STRIDE);
                double normalCombined = TemplateMatcher.findBestCombinedMatchScore(bfCrop, normalTemplate, MATCH_STRIDE);

                saveCheckFrame(checkDir, bfCrop, idx, shinyColor, normalColor, shinyCombined, normalCombined);

                if (i < CHECK_FRAME_COUNT - 1) keys.sleepMs(CHECK_FRAME_GAP_MS);
            }

            // ---- DECISION RULE (spin): sparkle alone is enough to stop ----
            if (sparkleLikely && sparkleScore >= 0.95 && sparkleEvents >= 3) {
                alert(String.format(Locale.US,
                        "Shiny detected (sparkle)! score=%.4f events=%d",
                        sparkleScore, sparkleEvents));
                stop();
                return;
            }

            // not shiny -> EXACT escape logic you requested:
            // mash B for 5000ms, then Right, Down, A, then resume spinning where we left off
            mashBFor(RUNAWAY_MASH_B_MS);

            keys.right();
            keys.sleepMs(MENU_TAP_DELAY_MS);

            keys.down();
            keys.sleepMs(MENU_TAP_DELAY_MS);

            mashAFor(2000);

            keys.sleepMs(1000);

            // We just performed end-of-battle exit flow; the next detector trigger may be the fade-out.
            skipNextBattleTriggerOnce = true;
        }
    }

    private void pressNextTurn() {
        int d = DIRS[dirIndex];
        dirIndex = (dirIndex + 1) % DIRS.length;

        switch (d) {
            case 0 -> keys.left();
            case 1 -> keys.down();
            case 2 -> keys.right();
            case 3 -> keys.up();
            default -> { }
        }
        keys.sleepMs(TURN_DELAY_MS);
    }

    private void mashBFor(long durationMs) {
        long end = System.currentTimeMillis() + durationMs;
        while (running.get() && System.currentTimeMillis() < end) {
            keys.pressB();
            keys.sleepMs(70);
        }
    }

    private void mashAFor(long durationMs) {
        long end = System.currentTimeMillis() + durationMs;
        while (running.get() && System.currentTimeMillis() < end) {
            keys.pressA();
            keys.sleepMs(70);
        }
    }

    private Rectangle buildRoi(BufferedImage frame) {
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

    private static void ensureDir(Path dir) {
        try {
            if (dir != null) Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create debug directory: " + dir, e);
        }
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
}