package app;

import java.awt.Rectangle;

import core.TargetPlatform;
import vision.capture.RectangleFrameSource;

public class AppState {
    private Rectangle captureRect;            // null until user sets it
    private RectangleFrameSource frameSource; // created after rect is set

    private TargetPlatform targetPlatform = TargetPlatform.GBA_EMULATOR;
    private String switchHost = "127.0.0.1";
    private int switchPort = 8000;

    public synchronized void setCaptureRect(Rectangle r) {
        this.captureRect = r;
        this.frameSource = new RectangleFrameSource(r);
    }

    public synchronized boolean hasCaptureRect() {
        return captureRect != null;
    }

    public synchronized Rectangle getCaptureRect() {
        return captureRect;
    }

    public synchronized Rectangle getCaptureRectOrThrow() {
        if (captureRect == null) {
            throw new IllegalStateException("Capture area not set yet.");
        }
        return captureRect;
    }

    public synchronized RectangleFrameSource getFrameSourceOrThrow() {
        if (frameSource == null) {
            throw new IllegalStateException("Capture area not set yet.");
        }
        return frameSource;
    }

    public synchronized TargetPlatform getTargetPlatform() {
        return targetPlatform;
    }

    public synchronized void setTargetPlatform(TargetPlatform targetPlatform) {
        if (targetPlatform == null) {
            throw new IllegalArgumentException("targetPlatform cannot be null");
        }
        this.targetPlatform = targetPlatform;
    }

    public synchronized String getSwitchHost() {
        return switchHost;
    }

    public synchronized void setSwitchHost(String switchHost) {
        if (switchHost == null || switchHost.trim().isEmpty()) {
            throw new IllegalArgumentException("switchHost cannot be blank");
        }
        this.switchHost = switchHost.trim();
    }

    public synchronized int getSwitchPort() {
        return switchPort;
    }

    public synchronized void setSwitchPort(int switchPort) {
        if (switchPort < 1 || switchPort > 65535) {
            throw new IllegalArgumentException("switchPort must be between 1 and 65535");
        }
        this.switchPort = switchPort;
    }
}