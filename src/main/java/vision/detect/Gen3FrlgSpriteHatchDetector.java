package vision.detect;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import app.AppState;
import vision.FrlgSpriteLoader;
import vision.HatchDetectionResult;
import vision.match.TemplateMatcher;

/**
 * Uses:
 * - capture rectangle from AppState (Robot screen capture)
 * - FRLG normal + shiny sprite templates (PokeAPI sprites repo on disk)
 * - simple frame-change spike as "egg hatched" event
 *
 * Output:
 * - eggHatchedNow: true when frame change spikes
 * - shinyFoundNow: true when shiny template matches strongly
 * - notShinyFoundNow: true when normal template matches strongly (and shiny does NOT)
 */
public class Gen3FrlgSpriteHatchDetector {

    private final AppState state;
    private final BufferedImage normalTemplate;
    private final BufferedImage shinyTemplate;

    // tune these if needed
    private final double matchThreshold;       // e.g. 0.88
    private final int stride;                  // e.g. 3 (higher = faster, less accurate)
    private final double hatchChangeThreshold; // e.g. 0.16 (16% pixels changed)
    private final int pixelDeltaThreshold;     // e.g. 35

    private BufferedImage lastFrame;

    public Gen3FrlgSpriteHatchDetector(
            AppState state,
            Path projectDir,
            int pokedexNumber,
            double matchThreshold,
            int stride,
            double hatchChangeThreshold,
            int pixelDeltaThreshold
    ) {
        this.state = state;

        FrlgSpriteLoader loader = new FrlgSpriteLoader(projectDir);
        this.normalTemplate = loader.loadNormalFront(pokedexNumber);
        this.shinyTemplate = loader.loadShinyFront(pokedexNumber);

        this.matchThreshold = matchThreshold;
        this.stride = Math.max(1, stride);
        this.hatchChangeThreshold = hatchChangeThreshold;
        this.pixelDeltaThreshold = pixelDeltaThreshold;
    }

    /** Call once right before starting a new cycle so hatch detection doesn't trigger on old frames. */
    public void resetState() {
        lastFrame = null;
    }

    /** Capture + detect. Call this repeatedly from your method loop. */
    public HatchDetectionResult detect() {
    BufferedImage frame = state.getFrameSourceOrThrow().capture();

    // 1) egg hatch event by frame-change spike
    boolean eggHatchedNow = frameChangeSpike(frame);

    // 2) sprite matching: shiny wins; otherwise normal means "not shiny"
    double shinyScore = TemplateMatcher.findTemplate(frame, shinyTemplate, stride, matchThreshold).score();
    if (shinyScore >= matchThreshold) {
        return new HatchDetectionResult(eggHatchedNow, true, false);
    }

    double normalScore = TemplateMatcher.findTemplate(frame, normalTemplate, stride, matchThreshold).score();
    boolean notShiny = normalScore >= matchThreshold;

    return new HatchDetectionResult(eggHatchedNow, false, notShiny);
}

    private boolean frameChangeSpike(BufferedImage current) {
        if (lastFrame == null) {
            lastFrame = current;
            return false;
        }

        int w = Math.min(lastFrame.getWidth(), current.getWidth());
        int h = Math.min(lastFrame.getHeight(), current.getHeight());

        long changed = 0;
        long sampled = 0;

        int s = 2; // sampling stride for speed; still checks whole capture area
        for (int y = 0; y < h; y += s) {
            for (int x = 0; x < w; x += s) {
                int a = lastFrame.getRGB(x, y);
                int b = current.getRGB(x, y);

                int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
                int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;

                int delta = Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb);
                if (delta > pixelDeltaThreshold) changed++;
                sampled++;
            }
        }

        lastFrame = current;
        double ratio = changed / (double) sampled;
        return ratio >= hatchChangeThreshold;
    }
}