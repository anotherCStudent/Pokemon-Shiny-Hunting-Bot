package methods.gen3;

import java.awt.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import app.AppState;
import core.KeySender;
import methods.HuntMethod;

/**
 * Gen 3 Egg Hatch method (your proposed universal approach):
 *
 * - Do NOT shiny-check during hatch cutscene.
 * - Use full-black detection to detect hatch start + hatch end.
 * - After hatch ends, hold RIGHT to reach grass, then spin in place for encounters.
 * - Then run Torchic-style battle shiny check (sparkle primary + template support).
 *
 * User responsibility (README):
 * - Egg must be first party slot if using battle-based confirmation.
 * - Stand at a route where holding RIGHT reaches grass, and spinning can trigger encounters.
 */
public class Gen3EggHatchMethod implements HuntMethod {

    private final KeySender keys;
    private final AppState state;
    private final EggDetector detector;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private final Path debugRootDir;
    private int attemptCounter = 0;

    // Startup (exactly as requested)
    private static final long STARTUP_MASH_A_MS = 5000;
    private static final long STARTUP_MASH_B_MS = 3000;

    // Movement pacing
    private static final long STEP_DELAY_MS = 120;
    private static final long POST_HATCH_MOVE_RIGHT_MS = 5000;

    // Long watch timeouts
    private static final long HATCH_BLACK_TIMEOUT_MS = 30_000;
    private static final long BATTLE_BLACK_TIMEOUT_MS = 30_000;

    // Short per-step watch timeouts so movement stays rapid
    private static final long HATCH_STEP_BLACK_CHECK_MS = 80;
    private static final long BATTLE_STEP_BLACK_CHECK_MS = 80;

    // After detection, reset settle
    private static final long POST_RESET_DELAY_MS = 1500;

    public Gen3EggHatchMethod(KeySender keys, AppState state, EggDetector detector) {
        this.keys = keys;
        this.state = state;
        this.detector = detector;

        Path projectDir = Path.of(System.getProperty("user.dir"));
        this.debugRootDir = projectDir.resolve("debug").resolve("gen3-egg");
        ensureDir(debugRootDir);
    }

    @Override
    public String name() {
        return "Gen 3 Egg Hatch";
    }

    @Override
    public void start() {
        if (running.get()) return;
        running.set(true);

        worker = new Thread(this::loop, "Gen3EggHatchWorker");
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
            ensureDir(attemptDir);

            detector.resetState();

            log("Starting attempt " + attemptCounter + " | debugDir=" + attemptDir.toAbsolutePath());

            // ---- Startup: mash A then mash B ----
            mashAFor(STARTUP_MASH_A_MS);
            if (!running.get()) break;

            mashBFor(STARTUP_MASH_B_MS);
            if (!running.get()) break;

            // ---- State flags ----
            boolean hatchOccurred = false;
            boolean inHatchSequence = false;

            // ---- 1) Repeat exact hatch walking pattern rapidly until hatch start is detected ----
            while (running.get() && !hatchOccurred) {
                // Pattern: up, up, down, down, down, up
                if (doHatchWalkTapAndCheck(+1, attemptDir, "UP")) {
                    hatchOccurred = true;
                    inHatchSequence = true;
                    break;
                }

                if (!running.get() || hatchOccurred) break;

                if (doHatchWalkTapAndCheck(+1, attemptDir, "UP")) {
                    hatchOccurred = true;
                    inHatchSequence = true;
                    break;
                }

                if (!running.get() || hatchOccurred) break;

                if (doHatchWalkTapAndCheck(-1, attemptDir, "DOWN")) {
                    hatchOccurred = true;
                    inHatchSequence = true;
                    break;
                }

                if (!running.get() || hatchOccurred) break;

                if (doHatchWalkTapAndCheck(-1, attemptDir, "DOWN")) {
                    hatchOccurred = true;
                    inHatchSequence = true;
                    break;
                }

                if (!running.get() || hatchOccurred) break;

                if (doHatchWalkTapAndCheck(-1, attemptDir, "DOWN")) {
                    hatchOccurred = true;
                    inHatchSequence = true;
                    break;
                }

                if (!running.get() || hatchOccurred) break;

                if (doHatchWalkTapAndCheck(+1, attemptDir, "UP")) {
                    hatchOccurred = true;
                    inHatchSequence = true;
                    break;
                }
            }

            if (!running.get()) break;

            // ---- 2) Wait for hatch end via full-black again ----
            if (hatchOccurred && inHatchSequence) {
                EggBlackResult end = detector.watchForFullBlackOnce(
                        HATCH_BLACK_TIMEOUT_MS,
                        attemptDir,
                        "hatch_end_black_watch"
                );

                if (end.triggered) {
                    inHatchSequence = false;
                    log("Hatch end detected (full black).");
                } else {
                    log("Hatch end black not detected in time. Resetting.");
                    keys.softReset();
                    keys.sleepMs(POST_RESET_DELAY_MS);
                    continue;
                }
            }

            if (!running.get()) break;

            // ---- 3) After hatch, move to grass then spin until battle black occurs ----
            boolean battleTriggered = false;

            if (running.get() && hatchOccurred && !inHatchSequence) {
                log("Holding RIGHT for " + POST_HATCH_MOVE_RIGHT_MS + " ms to reach grass.");
                keys.holdRight(POST_HATCH_MOVE_RIGHT_MS);
            }

            if (!running.get()) break;

            while (running.get() && hatchOccurred && !inHatchSequence && !battleTriggered) {
                battleTriggered = doSpinTapAndCheck(+2, attemptDir, "battle_black_watch_up", "UP");
                if (battleTriggered || !running.get()) break;

                battleTriggered = doSpinTapAndCheck(-1, attemptDir, "battle_black_watch_left", "LEFT");
                if (battleTriggered || !running.get()) break;

                battleTriggered = doSpinTapAndCheck(-2, attemptDir, "battle_black_watch_down", "DOWN");
                if (battleTriggered || !running.get()) break;

                battleTriggered = doSpinTapAndCheck(+1, attemptDir, "battle_black_watch_right", "RIGHT");
            }

            if (!running.get()) break;

            if (!battleTriggered) {
                log("Battle was not triggered after hatch. Resetting.");
                keys.softReset();
                keys.sleepMs(POST_RESET_DELAY_MS);
                continue;
            }

            // ---- 4) Now run battle shiny detection ----
            EggDecisionResult decision = detector.checkBattleForShiny(attemptDir);

            log(String.format(
                    Locale.US,
                    "Egg battle decision: %s | reason=%s",
                    decision.decision,
                    decision.reason
            ));

            if (decision.decision == EggDecision.SHINY) {
                alert("Shiny detected! Bot stopped.");
                stop();
                return;
            }

            // ---- 5) Not shiny -> reset ----
            keys.softReset();
            keys.sleepMs(POST_RESET_DELAY_MS);
        }
    }

    /**
     * dir:
     *   +1 = up
     *   -1 = down
     */
    private boolean doHatchWalkTapAndCheck(int dir, Path attemptDir, String label) {
        if (dir > 0) {
            keys.up();
        } else {
            keys.down();
        }

        keys.sleepMs(STEP_DELAY_MS);

        EggBlackResult result = detector.watchForFullBlackOnce(
                HATCH_STEP_BLACK_CHECK_MS,
                attemptDir,
                "hatch_black_watch"
        );

        if (result.triggered) {
            log("Hatch start detected (full black) during " + label + ".");
            return true;
        }

        return false;
    }

    /**
     * dir:
     *   +2 = up
     *   -1 = left
     *   -2 = down
     *   +1 = right
     */
    private boolean doSpinTapAndCheck(int dir, Path attemptDir, String prefix, String label) {
        switch (dir) {
            case +2 -> keys.up();
            case -1 -> keys.left();
            case -2 -> keys.down();
            case +1 -> keys.right();
            default -> throw new IllegalArgumentException("Invalid spin dir: " + dir);
        }

        keys.sleepMs(STEP_DELAY_MS);

        EggBlackResult battleBlack = detector.watchForFullBlackOnce(
                BATTLE_STEP_BLACK_CHECK_MS,
                attemptDir,
                prefix
        );

        if (battleBlack.triggered) {
            log("Battle black detected after hatch while spinning " + label + ".");
            return true;
        }

        return false;
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

    private void ensureDir(Path dir) {
        try {
            if (dir != null) Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create debug directory: " + dir, e);
        }
    }

    private void log(String msg) {
        System.out.println("[Gen3EggHatchMethod] " + msg);
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

    public enum EggDecision {
        SHINY,
        NOT_SHINY,
        NO_DECISION
    }

    public static final class EggDecisionResult {
        public final EggDecision decision;
        public final String reason;

        public EggDecisionResult(EggDecision decision, String reason) {
            this.decision = decision;
            this.reason = reason;
        }

        public static EggDecisionResult shiny(String reason) {
            return new EggDecisionResult(EggDecision.SHINY, reason);
        }

        public static EggDecisionResult notShiny(String reason) {
            return new EggDecisionResult(EggDecision.NOT_SHINY, reason);
        }

        public static EggDecisionResult noDecision(String reason) {
            return new EggDecisionResult(EggDecision.NO_DECISION, reason);
        }
    }

    public static final class EggBlackResult {
        public final boolean triggered;
        public final int framesChecked;
        public final int blackEventsSeen;
        public final int maxConsecutiveBlack;
        public final String reason;

        public EggBlackResult(boolean triggered,
                              int framesChecked,
                              int blackEventsSeen,
                              int maxConsecutiveBlack,
                              String reason) {
            this.triggered = triggered;
            this.framesChecked = framesChecked;
            this.blackEventsSeen = blackEventsSeen;
            this.maxConsecutiveBlack = maxConsecutiveBlack;
            this.reason = reason;
        }

        public static EggBlackResult hit(int framesChecked, int blackEventsSeen, int maxConsecutiveBlack, String reason) {
            return new EggBlackResult(true, framesChecked, blackEventsSeen, maxConsecutiveBlack, reason);
        }

        public static EggBlackResult miss(int framesChecked, int blackEventsSeen, int maxConsecutiveBlack, String reason) {
            return new EggBlackResult(false, framesChecked, blackEventsSeen, maxConsecutiveBlack, reason);
        }
    }

    public interface EggDetector {
        void resetState();

        EggBlackResult watchForFullBlackOnce(
                long timeoutMs,
                Path attemptDir,
                String filenamePrefix
        );

        EggDecisionResult checkBattleForShiny(Path attemptDir);
    }
}