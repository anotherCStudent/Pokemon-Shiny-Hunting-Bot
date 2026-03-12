package vision.capture.linux;

import vision.capture.WindowCapture;

import java.awt.image.BufferedImage;
import java.util.List;

public class LinuxWindowCapture implements WindowCapture {
    @Override public List<WindowInfo> listWindows() { throw new UnsupportedOperationException("Implement X11 listing next"); }
    @Override public BufferedImage captureWindow(long windowId) { throw new UnsupportedOperationException("Implement X11 capture next"); }
}