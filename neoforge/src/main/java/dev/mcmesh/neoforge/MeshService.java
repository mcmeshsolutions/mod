package dev.mcmesh.neoforge;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.mcmesh.core.ChunkUploader;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.JoinResult;
import dev.mcmesh.core.Lease;
import dev.mcmesh.core.LeaseSession;
import dev.mcmesh.core.TunnelAgent;
import java.net.InetSocketAddress;
import org.slf4j.Logger;

/** Holds the live lease for this process: heartbeat, reverse tunnel and chunk uploader. */
public final class MeshService {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile MeshService current;

    private final GatewayClient gw;
    private final Lease lease;
    private final LeaseSession session;
    private volatile TunnelAgent tunnel;
    private final ChunkUploader uploader;
    private volatile boolean fenced;
    private volatile Runnable fenceAction;
    private String baseRev;
    private String token;
    private java.nio.file.Path worldRoot;
    private String owner;
    private String worldId;

    public String worldId() {
        return worldId;
    }
    private volatile boolean handedOff;
    private volatile long lastSaveAt;
    private volatile long lastEmergencyAt;
    private volatile boolean tunnelUp;
    private volatile java.util.function.Consumer<String> handoffAction;
    public static volatile java.util.function.BiConsumer<String, Runnable> handoffWarning;
    public static volatile java.util.function.Consumer<String> afterHandoff;
    static {
        dev.mcmesh.core.GatewayClient.syncProgress = SyncProgress::report;
        GatewayClient.hwScore = () -> HostBench.get().get("cpu").getAsDouble();
    }

    private final AuxSync aux = new AuxSync();
    private boolean closed;
    private Thread exitHook;

    private MeshService(GatewayClient gw, Lease lease) throws java.io.IOException {
        this.gw = gw;
        this.lease = lease;
        this.baseRev = lease.baseRev();
        this.uploader = new ChunkUploader(gw, lease);
        this.tunnel = newTunnel();
        this.session = LeaseSession.over(gw, lease, why -> {
            fenced = true;
            LOG.error("lease fenced: {}", why);
            Runnable a = fenceAction;
            if (a != null) {
                a.run();
            }
        }, hb -> {
            handedOff = true;
            LOG.info("gateway asked for a handover to {}", hb.migrateTargets());
            var a = handoffAction;
            if (a != null) {
                a.accept(hb.targetNames().isEmpty() ? "Another player" : hb.targetNames().get(0));
            }
        });
    }

    public static MeshService current() {
        return current;
    }

    public ChunkUploader uploader() {
        return uploader;
    }

    /** Installed once the server exists; runs right away if the lease was already lost. */
    public void onFence(Runnable action) {
        fenceAction = action;
        if (fenced) {
            action.run();
        }
    }

    /** Runs when the gateway wants this host to give the world to a better machine. */
    public void onHandoff(java.util.function.Consumer<String> action) {
        handoffAction = action;
    }

    public boolean fenced() {
        return fenced;
    }

    public String describe() {
        return lease.id() + " epoch " + lease.epoch() + (fenced ? " (fenced)" : "");
    }

    /**
     * Joins, starts heartbeating and brings the world folder to the lease base revision.
     * Returns null when this machine is not the host. Blocks on network; the server must not have loaded the world yet.
     */
    public static MeshService join(java.nio.file.Path worldRoot, int tier, double score) throws Exception {
        String token = MeshConfig.TOKEN.get();
        if (token.isEmpty()) {
            token = MeshAuth.login().token();
        }
        return join(worldRoot, MeshConfig.WORLD.get(), token, MeshConfig.PLAYER_NAME.get(), tier, score);
    }

    /** owner is the player whose singleplayer data moves into playerdata when an empty world is seeded from a local save. */
    public static MeshService join(java.nio.file.Path worldRoot, String world, String token, String owner, int tier, double score) throws Exception {
        return join(worldRoot, world, token, owner, tier, score, false);
    }

    public static MeshService join(java.nio.file.Path worldRoot, String world, String token, String owner, int tier, double score, boolean takeover) throws Exception {
        GatewayClient gw = new GatewayClient(MeshConfig.API_URL.get(), token);
        JsonObject bench = HostBench.get();
        JoinResult r = gw.join(world, tier, bench.get("cpu").getAsDouble(), ModGuard.allowed(gw, world), bench, takeover);
        if (!(r instanceof JoinResult.Host h)) {
            LOG.debug("not host: {}", r);
            return null;
        }
        MeshService s = new MeshService(gw, h.lease());
        s.token = token;
        s.worldRoot = worldRoot;
        s.owner = owner;
        s.worldId = world;
        s.session.start();
        // a quit that skips the normal server stop (SIGTERM, System.exit) must still hand the world back instead of leaving guests waiting for lease expiry
        s.exitHook = new Thread(s::shutdown, "mesh-exit");
        Runtime.getRuntime().addShutdownHook(s.exitHook);
        try {
            boolean empty = WorldSync.sync(gw, worldRoot, h.lease().baseRev());
            if (empty && WorldSync.hasChunkData(worldRoot)) {
                s.seed();
            }
        } catch (Exception e) {
            s.session.close();
            try {
                Runtime.getRuntime().removeShutdownHook(s.exitHook);
            } catch (IllegalStateException ignored) { }
            throw e;
        }
        return s;
    }

    private void seed() throws Exception {
        WorldSeeder.seed(worldRoot, uploader, owner);
        aux.scan(worldRoot, uploader);
        uploader.flush(true);
        baseRev = gw.commit(lease.id(), lease.epoch(), baseRev, "AUTOSAVE", false).id();
        mark(baseRev);
    }

    private static TunnelAgent newTunnel() {
        TunnelAgent t = new TunnelAgent(
                new InetSocketAddress(MeshConfig.RELAY_HOST.get(), MeshConfig.RELAY_PORT.get()),
                new InetSocketAddress("127.0.0.1", MeshConfig.LOCAL_PORT.get()),
                new InetSocketAddress("127.0.0.1", MeshConfig.VOICE_PORT.get()));
        t.channels(MeshConfig.CHANNELS.get().stream()
                .map(p -> new InetSocketAddress("127.0.0.1", Integer.parseInt(p))).toList());
        return t;
    }

    /** Opens the tunnel and announces the server once it accepts connections. */
    public void activate() throws Exception {
        probeProof();
        tunnel.connect(lease, token);
        tunnelUp = true;
        LOG.info("tunnel up to {}:{}", MeshConfig.RELAY_HOST.get(), MeshConfig.RELAY_PORT.get());
        Thread.ofVirtual().name("mesh-tunnel").start(this::keepTunnel);
        gw.hostReady(lease.id(), lease.epoch());
        current = this;
    }

    private void probeProof() {
        try {
            gw.listWorlds();
        } catch (Exception e) {
            LOG.debug("tunnel capability probe failed: {}", e.toString());
        }
    }

    /** A dead tunnel silently strands every guest, so redial until the lease ends. */
    private void keepTunnel() {
        long backoff = 1_000;
        while (true) {
            try {
                tunnel.run();
                tunnelUp = false;
                LOG.warn("tunnel closed");
            } catch (java.io.IOException e) {
                tunnelUp = false;
                LOG.warn("tunnel ended: {}", e.toString());
            }
            while (!closed && !fenced) {
                try {
                    Thread.sleep(backoff);
                    probeProof();
                    TunnelAgent t = newTunnel();
                    t.connect(lease, token);
                    tunnel = t;
                    backoff = 1_000;
                    tunnelUp = true;
                    LOG.info("tunnel re-established");
                    break;
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    LOG.warn("tunnel redial failed: {}", e.toString());
                    backoff = Math.min(backoff * 2, 15_000);
                }
            }
            if (closed || fenced) {
                return;
            }
        }
    }

    /** Seals pending chunks and commits them as a revision. */
    public synchronized void autosave() throws Exception {
        if (fenced) {
            return;
        }
        aux.scan(worldRoot, uploader);
        uploader.flush(true);
        baseRev = gw.commit(lease.id(), lease.epoch(), baseRev, "AUTOSAVE", false).id();
        mark(baseRev);
        lastSaveAt = System.currentTimeMillis();
    }

    /** Autosave out of schedule, at most once a minute. Returns false when throttled. */
    public boolean emergencySave() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastEmergencyAt < 60_000) {
            return false;
        }
        lastEmergencyAt = now;
        autosave();
        return true;
    }

    public String status() {
        long age = lastSaveAt == 0 ? -1 : (System.currentTimeMillis() - lastSaveAt) / 1000;
        return "world " + worldId + ", " + describe() + ", tunnel " + (tunnelUp ? "up" : "DOWN")
                + ", last upload " + (age < 0 ? "none yet" : age + " s ago") + ", base " + baseRev;
    }

    private void mark(String rev) {
        try {
            java.nio.file.Files.writeString(worldRoot.resolve(WorldSync.MARKER), rev);
        } catch (java.io.IOException e) {
            LOG.warn("could not record synced revision", e);
        }
    }

    /** Final upload, last commit and lease release. */
    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        if (exitHook != null && Thread.currentThread() != exitHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(exitHook);
            } catch (IllegalStateException ignored) { }
        }
        try {
            if (!fenced) {
                aux.scan(worldRoot, uploader);
                uploader.flush(true);
                mark(gw.commit(lease.id(), lease.epoch(), baseRev, "SHUTDOWN", true).id());
            }
        } catch (Exception e) {
            LOG.error("final commit failed", e);
        } finally {
            session.close();
            tunnel.close();
            current = null;
            var after = afterHandoff;
            if (handedOff && after != null) {
                Thread.ofVirtual().start(() -> after.accept(worldId));
            }
        }
    }

    public Lease lease() {
        return lease;
    }
}
