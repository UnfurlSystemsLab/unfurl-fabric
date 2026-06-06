package com.unfurl.fabric.studio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class StudioFixtureAssets {
    private static final List<String> FIXTURE_PATHS = List.of(
            "META-INF/visual/validation-service.glb",
            "META-INF/visual/validation-service-thumbnail.png",
            "META-INF/visual/storage-s3.glb",
            "META-INF/visual/storage-s3-thumbnail.png");

    static Path assetRoot() {
        try {
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "unfurl-studio-assets").toAbsolutePath().normalize();
            for (String path : FIXTURE_PATHS) {
                copyFixture(root, path);
            }
            return root;
        } catch (IOException ex) {
            throw new IllegalStateException("unable to prepare bundled Studio visual assets", ex);
        }
    }

    private static void copyFixture(Path root, String path) throws IOException {
        Path target = root.resolve(path).normalize();
        Files.createDirectories(target.getParent());
        try (InputStream input = StudioFixtureAssets.class.getResourceAsStream("/studio-assets/" + path)) {
            if (input == null) {
                throw new IOException("missing bundled Studio asset fixture: " + path);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private StudioFixtureAssets() {
    }
}
