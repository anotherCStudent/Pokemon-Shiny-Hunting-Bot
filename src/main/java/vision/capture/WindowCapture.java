package vision.capture;

import java.awt.image.BufferedImage;
import java.util.List;

public interface WindowCapture {

    record WindowInfo(long id, String title, int x, int y, int width, int height) {}

    List<WindowInfo> listWindows();

    BufferedImage captureWindow(long windowId);
}