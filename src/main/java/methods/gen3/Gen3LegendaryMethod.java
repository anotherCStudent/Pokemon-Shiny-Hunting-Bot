package methods.gen3;

import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import core.KeySender;
import methods.HuntMethod;

public class Gen3LegendaryMethod implements HuntMethod {

    private final KeySender keys;
    private final LegendaryBattleDetector detector;
    private final BufferedImage normalTemplate;
    private final BufferedImage shinyTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private final Path debugRootDir;
    private int attemptCounter = 0;

    private static final long DELAY_MS = 5000;
    private static final long B_SPAM_MS = 10_000;

    // Give it enough time to actually fully fade to black
    private static final long BLACK_TRANSITION_TIMEOUT_MS = 30_000;

    private static final long POST_RESET_DELAY_MS = 1500;

    private static final boolean DEBUG_SAVE_TEMPLATES = true;

    public Gen3LegendaryMethod(
            KeySender keys,
            LegendaryBattleDetector detector,
            BufferedImage normalTemplate,
            BufferedImage shinyTemplate
    ) {
        this.keys = Objects.requireNonNull(keys, "keys must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.normalTemplate = Objects.requireNonNull(normalTemplate, "normalTemplate must not be null");
        this.shinyTemplate = Objects.requireNonNull(shinyTemplate, "shinyTemplate must not be null");

        Path projectDir = Path.of(System.getProperty("user.dir"));
        this.debugRootDir = projectDir.resolve("debug").resolve("gen3-legendary");
        ensureDebugDirExists(debugRootDir);

        if (DEBUG_SAVE_TEMPLATES) {
            saveTemplateDebugImages();
        }
    }

    @Override
    public String name() {
        return "Gen 3 Legendary (Reset)";
    }

    @Override
    public void start() {
        if (running.get()) return;
        running.set(true);

        worker = new Thread(this::loop, "Gen3LegendaryWorker");
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
        while (running.get()) {
            attemptCounter++;
            Path attemptDir = debugRootDir.resolve(String.format(Locale.US, "attempt_%05d", attemptCounter));
            ensureDebugDirExists(attemptDir);

            log("Starting attempt " + attemptCounter + " | debugDir=" + attemptDir.toAbsolutePath());

            detector.resetState();

            // 1) Startup: A, Start, Start, A
            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressA();

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressStart();

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressStart();

            keys.sleepMs(DELAY_MS);
            if (!running.get()) break;
            keys.pressA();

            // 2) Mash B
            mashBFor(B_SPAM_MS);
            if (!running.get()) break;

            mashAFor(2000);
            if (!running.get()) break;

            // 3) BLACK WATCH MUST PASS BEFORE ANY SPARKLE WORK
            // Keep watching until it passes (do not reset here).
            BlackGateResult gate;
            while (running.get()) {
                gate = detector.waitForBattleTransitionBlackScreen(
                        BLACK_TRANSITION_TIMEOUT_MS,
                        attemptDir
                );

                log(String.format(
                        Locale.US,
                        "Legend transition gate: %s | frames=%d | events=%d | consecutive=%d | reason=%s",
                        gate.success ? "PASS" : "FAIL",
                        gate.framesChecked,
                        gate.blackEventsSeen,
                        gate.maxConsecutiveBlack,
                        gate.reason
                ));

                if (gate.success) break;

                // Gate failed -> do NOT reset, do NOT sparkle.
                // Just keep watching; user can stop the bot if something is wrong.
                log("Black gate not satisfied yet (need FULL black). Continuing to watch...");
            }

            if (!running.get()) break;

            mashAFor(1000);

            // 4) Sparkle + template hybrid analysis (resets decided here)
            LegendaryCheckResult result = detector.analyzeEncounter(
                    normalTemplate,
                    shinyTemplate,
                    attemptDir
            );

            log(String.format(
                    Locale.US,
                    "Legendary decision: %s | sparkleLikely=%s | sparkleScore=%.4f | sparkleEvents=%d | shinyRef=%.4f | normalRef=%.4f | reason=%s",
                    result.decision,
                    result.sparkleLikelyShiny,
                    result.sparkleScore,
                    result.sparkleEvents,
                    result.shinyReferenceScore,
                    result.normalReferenceScore,
                    result.reason
            ));

            if (result.decision == Decision.SHINY) {
                alert("Shiny detected! Bot stopped.");
                stop();
                return;
            }

            // 5) Reset only after shiny watch has run
            keys.softReset();
            keys.sleepMs(POST_RESET_DELAY_MS);
        }
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

    private void saveTemplateDebugImages() {
        saveImage(debugRootDir.resolve("template_normal.png"), normalTemplate);
        saveImage(debugRootDir.resolve("template_shiny.png"), shinyTemplate);
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
        System.out.println("[Gen3LegendaryMethod] " + msg);
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

    public enum Decision {
        SHINY,
        NOT_SHINY,
        NO_DECISION
    }

    public static final class BlackGateResult {
        public final boolean success;
        public final int framesChecked;
        public final int blackEventsSeen;
        public final int maxConsecutiveBlack;
        public final String reason;

        public BlackGateResult(boolean success,
                               int framesChecked,
                               int blackEventsSeen,
                               int maxConsecutiveBlack,
                               String reason) {
            this.success = success;
            this.framesChecked = framesChecked;
            this.blackEventsSeen = blackEventsSeen;
            this.maxConsecutiveBlack = maxConsecutiveBlack;
            this.reason = reason;
        }

        public static BlackGateResult pass(int framesChecked, int blackEventsSeen, int maxConsecutiveBlack, String reason) {
            return new BlackGateResult(true, framesChecked, blackEventsSeen, maxConsecutiveBlack, reason);
        }

        public static BlackGateResult fail(int framesChecked, int blackEventsSeen, int maxConsecutiveBlack, String reason) {
            return new BlackGateResult(false, framesChecked, blackEventsSeen, maxConsecutiveBlack, reason);
        }
    }

    public interface LegendaryBattleDetector {
        void resetState();

        BlackGateResult waitForBattleTransitionBlackScreen(
                long timeoutMs,
                Path attemptDir
        );

        LegendaryCheckResult analyzeEncounter(
                BufferedImage normalTemplate,
                BufferedImage shinyTemplate,
                Path attemptDir
        );
    }

    public static final class LegendaryCheckResult {
        public final Decision decision;
        public final boolean sparkleLikelyShiny;
        public final double sparkleScore;
        public final int sparkleEvents;
        public final double shinyReferenceScore;
        public final double normalReferenceScore;
        public final String reason;

        public LegendaryCheckResult(
                Decision decision,
                boolean sparkleLikelyShiny,
                double sparkleScore,
                int sparkleEvents,
                double shinyReferenceScore,
                double normalReferenceScore,
                String reason
        ) {
            this.decision = decision;
            this.sparkleLikelyShiny = sparkleLikelyShiny;
            this.sparkleScore = sparkleScore;
            this.sparkleEvents = sparkleEvents;
            this.shinyReferenceScore = shinyReferenceScore;
            this.normalReferenceScore = normalReferenceScore;
            this.reason = reason;
        }

        public static LegendaryCheckResult shiny(
                boolean sparkleLikelyShiny,
                double sparkleScore,
                int sparkleEvents,
                double shinyReferenceScore,
                double normalReferenceScore,
                String reason
        ) {
            return new LegendaryCheckResult(
                    Decision.SHINY,
                    sparkleLikelyShiny,
                    sparkleScore,
                    sparkleEvents,
                    shinyReferenceScore,
                    normalReferenceScore,
                    reason
            );
        }

        public static LegendaryCheckResult notShiny(
                boolean sparkleLikelyShiny,
                double sparkleScore,
                int sparkleEvents,
                double shinyReferenceScore,
                double normalReferenceScore,
                String reason
        ) {
            return new LegendaryCheckResult(
                    Decision.NOT_SHINY,
                    sparkleLikelyShiny,
                    sparkleScore,
                    sparkleEvents,
                    shinyReferenceScore,
                    normalReferenceScore,
                    reason
            );
        }

        public static LegendaryCheckResult noDecision(
                boolean sparkleLikelyShiny,
                double sparkleScore,
                int sparkleEvents,
                double shinyReferenceScore,
                double normalReferenceScore,
                String reason
        ) {
            return new LegendaryCheckResult(
                    Decision.NO_DECISION,
                    sparkleLikelyShiny,
                    sparkleScore,
                    sparkleEvents,
                    shinyReferenceScore,
                    normalReferenceScore,
                    reason
            );
        }
    }
}