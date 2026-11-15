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
        if (TileWorker.enabled()) {
            // a worldgen worker generates one tile for a host and must never take the world's lease
            return;
        }
        if (MeshConfig.WORLD.get().isEmpty()) {
            return;
        }
        CompatRegistry.scan();
        MeshApiSelfTest.register();
        String memory = dev.mcmesh.core.PreFlight.heapProblem(Runtime.getRuntime().maxMemory(), MeshConfig.PACK_MIN_HEAP_MB.get());
        if (memory != null) {
            throw new IllegalStateException("mesh: not hosting, " + memory);
        }
        try {
            Path root = universe.resolve(levelName()).toAbsolutePath().normalize();
            MeshTap.setRoot(root);
            // an anchor with wake on join must not grab a free world at boot either, or it would never be idle
            boolean waitForDemand = MeshConfig.ANCHOR_STANDBY.get() && !wanted();
            MeshService s = waitForDemand ? null : MeshService.join(root, 1, Runtime.getRuntime().availableProcessors());
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
            if (MeshConfig.ANCHOR_STANDBY.get()) {
                registerAnchor(s);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mesh: could not join the world, refusing to start", e);
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mcmesh");

    private static void registerAnchor(MeshService s) {
        try {
            long ram = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() >> 20;
            s.gateway().registerAnchor(ram, Runtime.getRuntime().availableProcessors());
            LOG.info("anchor registered with the gateway: {} MB, {} cores", ram, Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            LOG.info("anchor not registered (the operator flags a device with `gatewayd set-anchor`): {}", e.toString());
        }
    }
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
                if (!wanted()) {
                    continue;
                }
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

    /**
     * With wake on join the anchor stays idle until a player has actually tried to get in, so a modded world is not
     * loaded all night for nobody. A gateway too old to answer the question is treated as "somebody wants it",
     * which is the behaviour the anchor had before.
     */
    private static boolean wanted() {
        if (!MeshConfig.ANCHOR_WAKE_ON_JOIN.get()) {
            return true;
        }
        try {
            var gw = new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), MeshAuth.login().token());
            long ago = gw.wantedSecondsAgo(MeshConfig.WORLD.get());
            boolean wake = dev.mcmesh.core.AnchorDuty.shouldWake(true, ago);
            if (wake) {
                LOG.info("anchor waking up: {} was asked for {} s ago", MeshConfig.WORLD.get(), ago);
            }
            return wake;
        } catch (Exception e) {
            LOG.warn("anchor cannot ask whether the world is wanted, taking it as wanted: {}", e.toString());
            return true;
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
