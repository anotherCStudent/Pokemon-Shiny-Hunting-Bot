package vision.capture;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

public class RectangleFrameSource {
    private final Robot robot;
    private volatile Rectangle captureRect;

    public RectangleFrameSource(Rectangle captureRect) {
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            throw new RuntimeException("Failed to init Robot for screen capture.", e);
        }
        this.captureRect = captureRect;
    }

    public void setCaptureRect(Rectangle r) {
        this.captureRect = r;
    }

    public Rectangle getCaptureRect() {
        return captureRect;
    }

    public BufferedImage capture() {
        Rectangle r = captureRect;
        if (r == null) throw new IllegalStateException("Capture rectangle not set.");
        return robot.createScreenCapture(r);
    }
}