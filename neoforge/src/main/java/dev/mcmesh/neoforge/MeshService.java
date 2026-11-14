package dev.mcmesh.neoforge;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.mcmesh.core.ChunkUploader;
import dev.mcmesh.core.Commits;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.HostSigner;
import dev.mcmesh.core.IdentityKey;
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
    private final Commits commits;
    private volatile boolean fenced;
    private volatile Runnable fenceAction;
    private volatile Runnable refusedAction;
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
        GatewayClient.playerCount = LoadShed::reportedPlayers;
        GatewayClient.loadLevel = LoadShed::reportedLevel;
    }

    private final AuxSync aux = new AuxSync();
    /** Set once the world matches the gateway revision; null before that. */
    private volatile RegionScan regions;
    private volatile boolean closed;
    private Thread exitHook;

    private MeshService(GatewayClient gw, Lease lease) throws java.io.IOException {
        this.gw = gw;
        this.lease = lease;
        this.baseRev = lease.baseRev();
        this.uploader = new ChunkUploader(gw, lease);
        this.commits = new Commits(gw, lease);
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

    /** The host machine itself gives the world away; shutdown then follows the same path as a gateway-requested handover. */
    public void markHandedOff() {
        handedOff = true;
    }

    /** Megabits per second of one upload to the gateway, or -1 when it failed. Blocks, so never call it on the server thread. */
    public double probeUploadMbps() {
        return dev.mcmesh.core.BandwidthProbe.measure(gw);
    }

    public boolean fenced() {
        return fenced;
    }

    /** Runs once when the gateway's sanity gate refuses a save; from then on nothing is uploaded for this lease. */
    public void onRefused(Runnable action) {
        refusedAction = action;
        if (commits.refused()) {
            action.run();
        }
    }

    public boolean refused() {
        return commits.refused();
    }

    /** False when the sanity gate refused the commit, now or earlier. */
    private boolean commit(String kind, boolean last) throws Exception {
        if (commits.refused()) {
            return false;
        }
        dev.mcmesh.core.Revision r = commits.commit(baseRev, kind, last);
        if (r != null) {
            baseRev = r.id();
            mark(baseRev);
            return true;
        }
        LOG.error("gateway sanity gate refused the {} commit on top of {}; uploads stop, the world in {} is kept", kind, baseRev, worldRoot);
        try {
            // without this the next host run on this machine would load the refused state as if it were baseRev
            dev.mcmesh.core.RejectedCopy.mark(worldRoot, baseRev);
        } catch (java.io.IOException e) {
            LOG.error("could not mark the refused world", e);
        }
        Runnable a = refusedAction;
        if (a != null) {
            a.run();
        }
        return false;
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
        JoinResult r = gw.join(world, tier, bench.get("cpu").getAsDouble(), ModGuard.allowed(gw, world), bench, takeover, true);
        if (!(r instanceof JoinResult.Host h)) {
            LOG.debug("not host: {}", r);
            return null;
        }
        MeshService s = new MeshService(gw, h.lease());
        s.token = token;
        s.worldRoot = worldRoot;
        s.owner = owner;
        s.worldId = world;
        s.startSigner(token);
        s.session.start();
        // a quit that skips the normal server stop (SIGTERM, System.exit) must still hand the world back instead of leaving guests waiting for lease expiry
        s.exitHook = new Thread(s::shutdownAfterServerStop, "mesh-exit");
        Runtime.getRuntime().addShutdownHook(s.exitHook);
        try {
            try {
                var files = gw.listRevision(h.lease().baseRev());
                // rebuilding from the gateway only makes sense when it holds the terrain; without chunks the local folder is the only copy
                boolean gatewayHasData = !files.chunks().isEmpty();
                if (dev.mcmesh.core.SafeBoot.prepare(s.crashMarker(), worldRoot, gatewayHasData)) {
                    LOG.warn("the last hosting run of {} did not stop cleanly: rebuilding the local world from the gateway, the old copy is kept next to it", world);
                }
            } catch (Exception e) {
                LOG.warn("safe boot check failed, keeping the normal path: {}", e.toString());
            }
            boolean[] keptTerrain = new boolean[1];
            boolean empty = WorldSync.sync(gw, worldRoot, h.lease().baseRev(), keptTerrain);
            if (empty && WorldSync.hasChunkData(worldRoot)) {
                s.seed();
            }
            // from here on, chunks stamped later than this are changes the gateway does not have; when it has none of the
            // terrain at all, everything on disk counts, so the first save uploads the whole world
            s.regions = new RegionScan(keptTerrain[0] ? 0 : System.currentTimeMillis());
        } catch (Exception e) {
            s.session.close();
            try {
                Runtime.getRuntime().removeShutdownHook(s.exitHook);
            } catch (IllegalStateException ignored) { }
            throw e;
        }
        return s;
    }

    /** A missing or unreadable key leaves commits unsigned; hosting goes on. */
    private void startSigner(String token) {
        try {
            HostSigner h = new HostSigner(MeshConfig.API_URL.get(), token, IdentityKey.loadOrCreate(MeshAuth.keyFile()));
            LOG.info(h.register() ? "device key registered, commits are signed" : "device key not registered, commits are unsigned");
            commits.sendWith((base, kind, last) -> h.commit(worldId, lease.id(), lease.epoch(), base, kind, last,
                    uploader::lastBarrierSeq, () -> uploader.flush(true)));
        } catch (Exception e) {
            LOG.warn("device key unavailable, commits are unsigned: {}", e.toString());
        }
    }

    /** Uploads chunks found changed on disk; never lets a scan problem stop a save. */
    private void scanRegions() {
        RegionScan r = regions;
        if (r == null) {
            return;
        }
        try {
            r.scan(worldRoot, uploader);
        } catch (Exception e) {
            LOG.warn("region scan failed, relying on the write hook only: {}", e.toString());
        }
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
        boolean connected = true;
        try {
            tunnel.connect(lease, token);
        } catch (java.io.IOException e) {
            // an unreachable relay must not switch off autosaves, load shedding and the final save: the host holds the lease and the
            // world is playable locally, so only guests are affected. The tunnel is redialled below.
            connected = false;
            LOG.warn("the relay at {}:{} is not reachable ({}). Guests cannot join yet; hosting and saving go on and the tunnel is retried",
                    MeshConfig.RELAY_HOST.get(), MeshConfig.RELAY_PORT.get(), e.toString());
        }
        tunnelUp = connected;
        if (connected) {
            LOG.info("tunnel up to {}:{}", MeshConfig.RELAY_HOST.get(), MeshConfig.RELAY_PORT.get());
        }
        boolean first = connected;
        Thread.ofVirtual().name("mesh-tunnel").start(() -> keepTunnel(first));
        gw.hostReady(lease.id(), lease.epoch());
        current = this;
        Thread.ofVirtual().name("mesh-crash-report").start(this::reportPreviousCrash);
    }

    private java.nio.file.Path crashMarker() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mcmesh-running-" + worldId + ".marker");
    }

    /** If the last hosting run of this world died without a clean stop, sends its crash report to the gateway. Best effort. */
    private void reportPreviousCrash() {
        try {
            long previous = dev.mcmesh.core.CrashMarker.begin(crashMarker(), System.currentTimeMillis());
            if (previous < 0) {
                return;
            }
            var report = dev.mcmesh.core.CrashMarker.newestReportSince(
                    net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("crash-reports"), previous);
            if (report == null) {
                LOG.warn("the last hosting run of {} did not stop cleanly, no crash report found", worldId);
                return;
            }
            gw.crashReport(worldId, dev.mcmesh.core.CrashMarker.head(report, 60_000));
            LOG.warn("the last hosting run of {} crashed, report {} sent to the gateway", worldId, report.getFileName());
        } catch (Exception e) {
            LOG.debug("crash report not sent: {}", e.toString());
        }
    }

    private void probeProof() {
        try {
            gw.listWorlds();
        } catch (Exception e) {
            LOG.debug("tunnel capability probe failed: {}", e.toString());
        }
    }

    /** A dead tunnel silently strands every guest, so redial until the lease ends. */
    private void keepTunnel(boolean connected) {
        long backoff = 1_000;
        boolean running = connected;
        while (true) {
            if (running) {
                try {
                    tunnel.run();
                    tunnelUp = false;
                    LOG.warn("tunnel closed");
                } catch (java.io.IOException e) {
                    tunnelUp = false;
                    LOG.warn("tunnel ended: {}", e.toString());
                }
            }
            running = true;
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
        if (fenced || commits.refused()) {
            return;
        }
        scanRegions();
        aux.scan(worldRoot, uploader);
        uploader.flush(true);
        if (commit("AUTOSAVE", false)) {
            lastSaveAt = System.currentTimeMillis();
        }
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
                + ", last upload " + (age < 0 ? "none yet" : age + " s ago") + ", base " + baseRev
                + (commits.refused() ? ", UPLOADS STOPPED: the gateway refused the last save" : "");
    }

    private void mark(String rev) {
        try {
            java.nio.file.Files.writeString(worldRoot.resolve(WorldSync.MARKER), rev);
        } catch (java.io.IOException e) {
            LOG.warn("could not record synced revision", e);
        }
    }

    /** Final upload, last commit and lease release. */
    /**
     * The JVM exit hook (SIGTERM, a service manager stopping the process). Vanilla's own stop saves the world and then reaches
     * {@link #shutdown()} through the server stopped event; running the final commit before that would miss everything the
     * stop is about to write. So the server gets a head start, and only a stop that never arrives (hung or crashed server) is
     * committed from here after the grace period.
     */
    private void shutdownAfterServerStop() {
        long begun = System.currentTimeMillis() + STOP_BEGIN_WAIT_MS;
        while (!closed && !serverStopping && System.currentTimeMillis() < begun) {
            if (!sleepQuietly(100)) {
                break;
            }
        }
        // saving a big world takes a minute or more; a commit made halfway would miss the rest, so a stop that has begun is awaited
        long done = System.currentTimeMillis() + STOP_FINISH_WAIT_MS;
        while (serverStopping && !closed && System.currentTimeMillis() < done) {
            if (!sleepQuietly(100)) {
                break;
            }
        }
        shutdown();
    }

    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** The server's own stop has begun; it will reach {@link #shutdown()} itself once the world is saved. */
    public void serverStopping() {
        serverStopping = true;
    }

    private volatile boolean serverStopping;
    /** A SIGTERM starts the server's stop within moments; if none starts in this time the process is exiting some other way. */
    private static final long STOP_BEGIN_WAIT_MS = 5_000;
    private static final long STOP_FINISH_WAIT_MS = 10 * 60_000;

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
            if (!fenced && !commits.refused()) {
                scanRegions();
                aux.scan(worldRoot, uploader);
                uploader.flush(true);
                commit("SHUTDOWN", true);
            }
        } catch (Exception e) {
            LOG.error("final commit failed", e);
        } finally {
            dev.mcmesh.core.CrashMarker.end(crashMarker());
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
