package dev.mcmesh.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Dedicated server only: takes the host lease and syncs the world folder before vanilla opens it. */
public final class MeshBoot {
    private static volatile MeshService held;

    private MeshBoot() { }

    public static MeshService held() {
        return held;
    }

    /** Client hosting: the lease was taken by the Synced Worlds screen before the integrated server starts. */
    public static void hold(MeshService s, Path root) {
        MeshTap.setRoot(root);
        held = s;
    }

    public static void clear() {
        held = null;
    }

    public static void beforeWorldLoad(Path universe) {
        if (MeshConfig.WORLD.get().isEmpty()) {
            return;
        }
        try {
            Path root = universe.resolve(levelName()).toAbsolutePath().normalize();
            MeshTap.setRoot(root);
            MeshService s = MeshService.join(root, 1, Runtime.getRuntime().availableProcessors());
            if (s == null) {
                throw new IllegalStateException("mesh: another machine holds the host lease for this world, not starting a second writer");
            }
            held = s;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mesh: could not join the world, refusing to start", e);
        }
    }

    private static String levelName() throws IOException {
        Path props = Path.of("server.properties");
        if (!Files.exists(props)) {
            return "world";
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(props)) {
            p.load(in);
        }
        return p.getProperty("level-name", "world");
    }
}
