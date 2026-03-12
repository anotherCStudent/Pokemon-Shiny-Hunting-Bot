package core.mgba;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;

import core.KeySender;

public class MgbaKeySender implements KeySender {
    private final Robot robot;

    // Tune these if needed
    private static final long TAP_HOLD_MS = 5;
    private static final long TAP_RELEASE_GAP_MS = 0;
    private static final long SOFT_RESET_HOLD_MS = 100;
    private static final long SOFT_RESET_AFTER_MS = 250;

    // Leave true while debugging input reliability
    private static final boolean DEBUG_KEYS = true;

    public MgbaKeySender() {
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(10);
        } catch (AWTException e) {
            throw new RuntimeException("Failed to init Robot for key sending.", e);
        }
    }

    // mGBA mapping: A=X, B=Z, Start=Enter, Select=Backspace
    @Override
    public void pressA() {
        tap(KeyEvent.VK_X, "A");
    }

    @Override
    public void pressB() {
        tap(KeyEvent.VK_Z, "B");
    }

    @Override
    public void pressStart() {
        tap(KeyEvent.VK_ENTER, "START");
    }

    @Override
    public void pressSelect() {
        tap(KeyEvent.VK_BACK_SPACE, "SELECT");
    }

    @Override
    public void up() {
        tap(KeyEvent.VK_UP, "UP");
    }

    @Override
    public void down() {
        tap(KeyEvent.VK_DOWN, "DOWN");
    }

    @Override
    public void left() {
        tap(KeyEvent.VK_LEFT, "LEFT");
    }

    @Override
    public void right() {
        tap(KeyEvent.VK_RIGHT, "RIGHT");
    }

    @Override
    public void softReset() {
        log("SOFT_RESET press: A + B + START + SELECT");

        // Press all four
        robot.keyPress(KeyEvent.VK_X);          // A
        robot.keyPress(KeyEvent.VK_Z);          // B
        robot.keyPress(KeyEvent.VK_ENTER);      // Start
        robot.keyPress(KeyEvent.VK_BACK_SPACE); // Select

        sleepMs(SOFT_RESET_HOLD_MS);

        // Release in reverse order
        robot.keyRelease(KeyEvent.VK_BACK_SPACE);
        robot.keyRelease(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_Z);
        robot.keyRelease(KeyEvent.VK_X);

        log("SOFT_RESET release complete");

        sleepMs(SOFT_RESET_AFTER_MS);
    }

    @Override
    public void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void tap(int keyCode, String label) {
        log(label + " press");
        robot.keyPress(keyCode);

        sleepMs(TAP_HOLD_MS);

        robot.keyRelease(keyCode);
        log(label + " release");

        sleepMs(TAP_RELEASE_GAP_MS);
    }

    public void holdDown(long ms) {
        robot.keyPress(KeyEvent.VK_DOWN);
        sleepMs(ms);
        robot.keyRelease(KeyEvent.VK_DOWN);
        sleepMs(TAP_RELEASE_GAP_MS);
    }

    public void holdLeft(long ms) {
        robot.keyPress(KeyEvent.VK_LEFT);
        sleepMs(ms);
        robot.keyRelease(KeyEvent.VK_LEFT);
        sleepMs(TAP_RELEASE_GAP_MS);
    }

    public void holdRight(long ms) {
        robot.keyPress(KeyEvent.VK_RIGHT);
        sleepMs(ms);
        robot.keyRelease(KeyEvent.VK_RIGHT);
        sleepMs(TAP_RELEASE_GAP_MS);
    }

    public void holdUp(long ms) {
        robot.keyPress(KeyEvent.VK_UP);
        sleepMs(ms);
        robot.keyRelease(KeyEvent.VK_UP);
        sleepMs(TAP_RELEASE_GAP_MS);
    }

    private void log(String msg) {
        if (DEBUG_KEYS) {
            System.out.println("[MgbaKeySender] " + msg);
        }
    }
}