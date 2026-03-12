package vision;

public record HatchDetectionResult(
        boolean eggHatchedNow,
        boolean shinyFoundNow,
        boolean notShinyFoundNow
) {}