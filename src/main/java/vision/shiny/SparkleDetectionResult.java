package vision.shiny;

public final class SparkleDetectionResult {
    private final boolean likelyShiny;
    private final double score;
    private final int sparkleEvents;
    private final int analyzedFrames;
    private final String reason;

    public SparkleDetectionResult(
            boolean likelyShiny,
            double score,
            int sparkleEvents,
            int analyzedFrames,
            String reason
    ) {
        this.likelyShiny = likelyShiny;
        this.score = score;
        this.sparkleEvents = sparkleEvents;
        this.analyzedFrames = analyzedFrames;
        this.reason = reason;
    }

    public boolean isLikelyShiny() {
        return likelyShiny;
    }

    public double getScore() {
        return score;
    }

    public int getSparkleEvents() {
        return sparkleEvents;
    }

    public int getAnalyzedFrames() {
        return analyzedFrames;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "SparkleDetectionResult{" +
                "likelyShiny=" + likelyShiny +
                ", score=" + score +
                ", sparkleEvents=" + sparkleEvents +
                ", analyzedFrames=" + analyzedFrames +
                ", reason='" + reason + '\'' +
                '}';
    }
}