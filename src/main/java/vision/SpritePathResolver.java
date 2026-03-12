package vision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SpritePathResolver {

    public enum SpriteView { FRONT, BACK }
    public enum SpriteVariant { NORMAL, SHINY }

    private final Path projectDir;

    public SpritePathResolver(Path projectDir) {
        this.projectDir = projectDir;
    }

    public Path resolveGen3FrlgSprite(int dexNumber, SpriteView view, SpriteVariant variant) {
        return resolveGen3Sprite("firered-leafgreen", dexNumber, view, variant);
    }

    public Path resolveGen3RubySapphireSprite(int dexNumber, SpriteView view, SpriteVariant variant) {
        return resolveGen3Sprite("ruby-sapphire", dexNumber, view, variant);
    }

    public Path resolveGen3EmeraldSprite(int dexNumber, SpriteView view, SpriteVariant variant) {
        return resolveGen3Sprite("emerald", dexNumber, view, variant);
    }

    /**
     * RSE is split across two actual folders in the sprite repo:
     * - ruby-sapphire
     * - emerald
     *
     * This method safely tries Ruby/Sapphire first, then Emerald.
     * It does not change the behavior of existing FRLG / RS / Emerald callers.
     */
    public Path resolveGen3RseSprite(int dexNumber, SpriteView view, SpriteVariant variant) {
        if (dexNumber < 1) {
            throw new IllegalArgumentException("dexNumber must be >= 1");
        }

        List<Path> allCandidates = new ArrayList<>();

        Path rubySapphireBase = buildGen3Base("ruby-sapphire");
        List<Path> rubySapphireCandidates = buildCandidates(rubySapphireBase, dexNumber, view, variant);
        allCandidates.addAll(rubySapphireCandidates);

        for (Path p : rubySapphireCandidates) {
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return p;
            }
        }

        Path emeraldBase = buildGen3Base("emerald");
        List<Path> emeraldCandidates = buildCandidates(emeraldBase, dexNumber, view, variant);
        allCandidates.addAll(emeraldCandidates);

        for (Path p : emeraldCandidates) {
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return p;
            }
        }

        throw new IllegalArgumentException(
                "Sprite not found for generation-iii RSE"
                        + ", dex=" + dexNumber
                        + ", view=" + view
                        + ", variant=" + variant
                        + ". Tried paths: " + allCandidates
        );
    }

    private Path resolveGen3Sprite(String gameFolder, int dexNumber, SpriteView view, SpriteVariant variant) {
        if (dexNumber < 1) {
            throw new IllegalArgumentException("dexNumber must be >= 1");
        }

        Path base = buildGen3Base(gameFolder);
        List<Path> candidates = buildCandidates(base, dexNumber, view, variant);

        for (Path p : candidates) {
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return p;
            }
        }

        throw new IllegalArgumentException(
                "Sprite not found for generation-iii gameFolder=" + gameFolder
                        + ", dex=" + dexNumber
                        + ", view=" + view
                        + ", variant=" + variant
                        + ". Tried paths: " + candidates
        );
    }

    private Path buildGen3Base(String gameFolder) {
        return projectDir
                .resolve("sprites")
                .resolve("sprites")
                .resolve("pokemon")
                .resolve("versions")
                .resolve("generation-iii")
                .resolve(gameFolder);
    }

    private static List<Path> buildCandidates(Path base, int dex, SpriteView view, SpriteVariant variant) {
        String file = dex + ".png";
        List<Path> candidates = new ArrayList<>();

        if (variant == SpriteVariant.NORMAL && view == SpriteView.FRONT) {
            candidates.add(base.resolve(file));
            candidates.add(base.resolve("front").resolve(file));
            return List.copyOf(candidates);
        }

        if (variant == SpriteVariant.NORMAL && view == SpriteView.BACK) {
            candidates.add(base.resolve("back").resolve(file));
            candidates.add(base.resolve("back-normal").resolve(file));
            return List.copyOf(candidates);
        }

        if (variant == SpriteVariant.SHINY && view == SpriteView.FRONT) {
            candidates.add(base.resolve("shiny").resolve(file));
            candidates.add(base.resolve("front").resolve("shiny").resolve(file));
            candidates.add(base.resolve("shiny-front").resolve(file));
            return List.copyOf(candidates);
        }

        // SHINY + BACK
        candidates.add(base.resolve("back").resolve("shiny").resolve(file));
        candidates.add(base.resolve("shiny").resolve("back").resolve(file));
        candidates.add(base.resolve("back-shiny").resolve(file));
        candidates.add(base.resolve("shiny-back").resolve(file));

        // Last-resort fallback in case a repo layout is flatter than expected.
        candidates.add(base.resolve("shiny").resolve(file));

        return List.copyOf(candidates);
    }
}