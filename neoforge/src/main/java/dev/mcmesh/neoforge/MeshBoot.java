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
            if (s == null && ModGuard.problem == null && MeshConfig.ANCHOR_STANDBY.get()) {
                s = standby(root);
            }
            if (s == null) {
                String why = ModGuard.problem;
                throw new IllegalStateException(why != null
                        ? "mesh: not hosting, the mods on this machine do not match the world: " + why
                        : "mesh: another machine holds the host lease for this world, not starting a second writer");
            }
            held = s;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mesh: could not join the world, refusing to start", e);
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mcmesh");
    static final long STANDBY_POLL_MS = 10_000;

    /**
     * Anchor node: nobody else may be hosting, and the world must not be loaded until this machine holds the lease.
     * Asking again every few seconds keeps this machine a fresh candidate for the gateway's election, so when the
     * current host leaves with guests still playing, the anchor can be granted the world. Returns null only if interrupted.
     */
    private static MeshService standby(Path root) throws Exception {
        LOG.info("anchor standby: another machine hosts {}, waiting for the world to become free", MeshConfig.WORLD.get());
        long since = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(STANDBY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                MeshService s = MeshService.join(root, 1, Runtime.getRuntime().availableProcessors());
                if (s != null) {
                    LOG.info("anchor standby over after {} s, taking the host lease", (System.currentTimeMillis() - since) / 1000);
                    return s;
                }
            } catch (java.io.IOException e) {
                LOG.warn("anchor standby: gateway not reachable, retrying: {}", e.toString());
            }
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
