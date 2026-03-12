package vision;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public class FrlgSpriteLoader {
    private static final boolean DEBUG_SPRITES = true;

    private final SpritePathResolver resolver;

    public FrlgSpriteLoader(Path projectDir) {
        this.resolver = new SpritePathResolver(projectDir);
        log("Initialized FRLG sprite loader with projectDir=" + projectDir.toAbsolutePath());
    }

    public BufferedImage loadNormalFront(int dex) {
        Path p = resolver.resolveGen3FrlgSprite(
                dex,
                SpritePathResolver.SpriteView.FRONT,
                SpritePathResolver.SpriteVariant.NORMAL
        );

        log("Resolved normal FRONT sprite for dex " + dex + ": " + p.toAbsolutePath());
        return load(p, "normal FRONT", dex);
    }

    public BufferedImage loadShinyFront(int dex) {
        Path p = resolver.resolveGen3FrlgSprite(
                dex,
                SpritePathResolver.SpriteView.FRONT,
                SpritePathResolver.SpriteVariant.SHINY
        );

        log("Resolved shiny FRONT sprite for dex " + dex + ": " + p.toAbsolutePath());
        return load(p, "shiny FRONT", dex);
    }

    public BufferedImage loadNormalBack(int dex) {
        Path p = resolver.resolveGen3FrlgSprite(
                dex,
                SpritePathResolver.SpriteView.BACK,
                SpritePathResolver.SpriteVariant.NORMAL
        );

        log("Resolved normal BACK sprite for dex " + dex + ": " + p.toAbsolutePath());
        return load(p, "normal BACK", dex);
    }

    public BufferedImage loadShinyBack(int dex) {
        Path p = resolver.resolveGen3FrlgSprite(
                dex,
                SpritePathResolver.SpriteView.BACK,
                SpritePathResolver.SpriteVariant.SHINY
        );

        log("Resolved shiny BACK sprite for dex " + dex + ": " + p.toAbsolutePath());
        return load(p, "shiny BACK", dex);
    }

    private BufferedImage load(Path p, String label, int dex) {
        try {
            if (p == null) {
                throw new RuntimeException("Resolved sprite path was null for dex " + dex + " (" + label + ")");
            }

            if (!Files.exists(p)) {
                throw new RuntimeException(
                        "Sprite file does not exist for dex " + dex + " (" + label + "): " + p.toAbsolutePath()
                );
            }

            if (!Files.isRegularFile(p)) {
                throw new RuntimeException(
                        "Resolved sprite path is not a regular file for dex " + dex + " (" + label + "): " + p.toAbsolutePath()
                );
            }

            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) {
                throw new RuntimeException("ImageIO.read returned null for sprite: " + p.toAbsolutePath());
            }

            if (img.getWidth() <= 0 || img.getHeight() <= 0) {
                throw new RuntimeException(
                        "Loaded sprite has invalid dimensions for dex " + dex + " (" + label + "): "
                                + img.getWidth() + "x" + img.getHeight()
                                + " from " + p.toAbsolutePath()
                );
            }

            log("Loaded " + label + " sprite for dex " + dex
                    + " | size=" + img.getWidth() + "x" + img.getHeight()
                    + " | path=" + p.toAbsolutePath());

            return img;
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to load sprite for dex " + dex + " (" + label + "): " + p.toAbsolutePath(),
                    e
            );
        }
    }

    private void log(String msg) {
        if (DEBUG_SPRITES) {
            System.out.println("[FrlgSpriteLoader] " + msg);
        }
    }
}