package vision;

import vision.capture.WindowCapture;

public final class ObsWindowFinder {

    public static WindowCapture.WindowInfo findObs(WindowCapture cap) {
        return cap.listWindows().stream()
                .filter(w -> w.title().toLowerCase().contains("obs"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not find OBS window. Make sure OBS is open and visible."));
    }

    private ObsWindowFinder() {}
}