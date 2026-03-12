package core.switchcontrol;

import core.KeySender;

public class SwitchNxbtKeySender implements KeySender {

    private static final double TAP_DOWN_SECONDS = 0.08;
    private static final double TAP_UP_SECONDS = 0.08;
    private static final double HOLD_RELEASE_SECONDS = 0.05;
    private static final double SOFT_RESET_DOWN_SECONDS = 0.15;
    private static final double SOFT_RESET_UP_SECONDS = 0.10;

    private static final boolean DEBUG_KEYS = true;

    private final SwitchClient client;

    public SwitchNxbtKeySender(String host, int port) {
        this(new SwitchClient(host, port));
    }

    public SwitchNxbtKeySender(SwitchClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client cannot be null");
        }
        this.client = client;
    }

    public String connect() {
        log("CONNECT");
        return client.connect();
    }

    public String disconnect() {
        log("DISCONNECT");
        return client.disconnect();
    }

    public String status() {
        return client.status();
    }

    @Override
    public void pressA() {
        tapButton("A", "A");
    }

    @Override
    public void pressB() {
        tapButton("B", "B");
    }

    @Override
    public void pressStart() {
        tapButton("PLUS", "START");
    }

    @Override
    public void pressSelect() {
        tapButton("MINUS", "SELECT");
    }

    @Override
    public void up() {
        tapDpad("UP", "UP");
    }

    @Override
    public void down() {
        tapDpad("DOWN", "DOWN");
    }

    @Override
    public void left() {
        tapDpad("LEFT", "LEFT");
    }

    @Override
    public void right() {
        tapDpad("RIGHT", "RIGHT");
    }

    @Override
    public void softReset() {
        log("SOFT_RESET press: PLUS + MINUS + A");
        client.softReset(SOFT_RESET_DOWN_SECONDS, SOFT_RESET_UP_SECONDS);
        log("SOFT_RESET release complete");
    }

    @Override
    public void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void holdDown(long ms) {
        holdDirection("DOWN", "DOWN", ms);
    }

    @Override
    public void holdLeft(long ms) {
        holdDirection("LEFT", "LEFT", ms);
    }

    @Override
    public void holdRight(long ms) {
        holdDirection("RIGHT", "RIGHT", ms);
    }

    @Override
    public void holdUp(long ms) {
        holdDirection("UP", "UP", ms);
    }

    public String macro(String macro) {
        log("MACRO");
        return client.macro(macro);
    }

    public String macro(String macro, boolean block) {
        log("MACRO block=" + block);
        return client.macro(macro, block);
    }

    public String clearMacros() {
        log("CLEAR_MACROS");
        return client.clearMacros();
    }

    private void tapButton(String buttonName, String label) {
        log(label + " press");
        client.pressButton(buttonName, TAP_DOWN_SECONDS, TAP_UP_SECONDS);
        log(label + " release");
    }

    private void tapDpad(String directionName, String label) {
        log(label + " press");
        client.pressDpad(directionName, TAP_DOWN_SECONDS, TAP_UP_SECONDS);
        log(label + " release");
    }

    private void holdDirection(String directionName, String label, long ms) {
        double holdSeconds = Math.max(0.0, ms / 1000.0);
        log(label + " hold for " + ms + " ms");
        client.holdDirection(directionName, holdSeconds, HOLD_RELEASE_SECONDS);
        log(label + " release");
    }

    private void log(String msg) {
        if (DEBUG_KEYS) {
            System.out.println("[SwitchNxbtKeySender] " + msg);
        }
    }
}