package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshService;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;

/** While playing as a guest, bids for the host lease so a stronger machine takes the world over, and takes it when granted. */
public final class Standby {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long POLL_MS = 5_000;
    private static final long GRACE_MS = 150_000;
    private static volatile Thread running;
    private static volatile long switchUntil;
    private static volatile long hudSeconds = -1;
    private static volatile long hudAt;

    /** True while a host switch is under way, so the plain Server Disconnected screen stays out of the way. */
    public static boolean switching() {
        return System.currentTimeMillis() < switchUntil;
    }

    public static void markSwitching(long ms) {
        switchUntil = System.currentTimeMillis() + ms;
    }

    /** Seconds left before this machine takes the world over, or -1 when it is not waiting for that. */
    public static long remainingS() {
        long s = hudSeconds;
        return s < 0 ? -1 : Math.max(0, s - (System.currentTimeMillis() - hudAt) / 1000);
    }

    private Standby() { }

    public static void stop() {
        Thread t = running;
        running = null;
        hudSeconds = -1;
        if (t != null) {
            t.interrupt();
        }
    }

    public static void start(GatewayClient.WorldInfo w, String relay) {
        stop();
        Thread t = new Thread(() -> loop(w), "mesh-standby");
        t.setDaemon(true);
        running = t;
        t.start();
    }

    private static void loop(GatewayClient.WorldInfo w) {
        Minecraft mc = Minecraft.getInstance();
        long lastUp = System.currentTimeMillis();
        try {
            while (running == Thread.currentThread()) {
                Thread.sleep(POLL_MS);
                var screen = mc.screen;
                if (mc.level != null || screen instanceof ConnectScreen) {
                    lastUp = System.currentTimeMillis();
                } else if (!(screen instanceof DisconnectedScreen || screen instanceof HandoverScreen)
                        || System.currentTimeMillis() - lastUp > GRACE_MS) {
                    if (screen instanceof HandoverScreen) {
                        mc.execute(() -> mc.setScreen(new TitleScreen()));
                    }
                    return;
                } else if (screen instanceof DisconnectedScreen) {
                    mc.execute(() -> mc.setScreen(new HandoverScreen("Taking over as host",
                            "The host left. Waiting for the gateway to hand the world to this machine.", 0, null)));
                }
                try {
                    var id = MeshAuth.cached();
                    Path root = mc.gameDirectory.toPath().resolve("saves").resolve(HostFlow.saveName(w.id())).toAbsolutePath().normalize();
                    MeshService s = MeshService.join(root, w.id(), id.token(), id.name(), 1, Runtime.getRuntime().availableProcessors());
                    if (s != null) {
                        running = null;
                        LOG.info("granted the host lease for {}, taking over", w.id());
                        HostFlow.takeover(s, root, w.id());
                        return;
                    }
                } catch (Exception e) {
                    LOG.warn("standby poll failed: {}", e.toString());
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            hudSeconds = -1;
        }
    }

    /** A stronger machine joining a weaker host waits on a screen, then asks for the lease instead of playing as a guest. */
    public static void waitAndTake(GatewayClient.WorldInfo w, String relay, net.minecraft.client.gui.screens.Screen back) {
        stop();
        Minecraft mc = Minecraft.getInstance();
        var scr = new HandoverScreen("Joining and taking over hosting", "", 0, null);
        scr.onCancel(() -> {
            stop();
            mc.setScreen(back);
        });
        mc.execute(() -> mc.setScreen(scr));
        Thread t = new Thread(() -> waitLoop(w, relay, scr, back), "mesh-standby");
        t.setDaemon(true);
        running = t;
        t.start();
    }

    private static void waitLoop(GatewayClient.WorldInfo w, String relay, HandoverScreen scr, net.minecraft.client.gui.screens.Screen back) {
        Minecraft mc = Minecraft.getInstance();
        long end = System.currentTimeMillis() + w.takeoverInS() * 1000;
        String host = w.host();
        long lastSync = System.currentTimeMillis();
        long lastBid = 0;
        try {
            while (running == Thread.currentThread()) {
                long now = System.currentTimeMillis();
                try {
                    var id = MeshAuth.cached();
                    if (now - lastSync > 10_000) {
                        lastSync = now;
                        var l = new GatewayClient(MeshConfig.API_URL.get(), id.token()).listWorlds();
                        for (var x : l.worlds()) {
                            if (!x.id().equals(w.id())) {
                                continue;
                            }
                            host = x.host();
                            if (x.takeoverInS() >= 0) {
                                end = now + x.takeoverInS() * 1000;
                            } else if (x.host() != null) {
                                running = null;
                                mc.execute(() -> HostFlow.join(x, l.relay(), back));
                                return;
                            }
                        }
                    }
                    long left = Math.max(0, (end - now + 999) / 1000);
                    if (left > 0) {
                        scr.heading(String.format("Joining and taking over hosting in %02d:%02d", left / 60, left % 60));
                        scr.line((host == null ? "The host" : host) + " keeps hosting until then. Stay on this screen.");
                    } else {
                        scr.heading("Taking over hosting");
                        scr.line("Asking " + (host == null ? "the host" : host) + " to hand the world over.");
                        if (now - lastBid >= 3_000) {
                            lastBid = now;
                            Path root = mc.gameDirectory.toPath().resolve("saves").resolve(HostFlow.saveName(w.id())).toAbsolutePath().normalize();
                            MeshService s = MeshService.join(root, w.id(), id.token(), id.name(), 1, Runtime.getRuntime().availableProcessors(), true);
                            if (s != null) {
                                running = null;
                                scr.heading("Taking over hosting");
                                scr.line("Loading the world.");
                                HostFlow.takeover(s, root, w.id());
                                return;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    LOG.warn("take over wait failed: {}", e.toString());
                }
                Thread.sleep(1_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Old host after a handover: waits for the new host and connects as a guest. */
    public static void rejoin(String worldId) {
        Minecraft mc = Minecraft.getInstance();
        markSwitching(90_000);
        try {
            for (int i = 0; i < 40 && !(mc.level == null && mc.getSingleplayerServer() == null); i++) {
                Thread.sleep(500);
            }
            var wait = new HandoverScreen("Host switched", "Waiting for the new host to load the world.", 0, null);
            mc.execute(() -> mc.setScreen(wait));
            for (int i = 0; i < 60; i++) {
                Thread.sleep(3_000);
                var id = MeshAuth.cached();
                var l = new GatewayClient(MeshConfig.API_URL.get(), id.token()).listWorlds();
                for (var w : l.worlds()) {
                    if (w.id().equals(worldId) && "LOCKED".equals(w.state()) && w.host() != null && !w.host().equalsIgnoreCase(id.name())) {
                        wait.line("Reconnecting to " + w.host() + ".");
                        switchUntil = 0;
                        mc.execute(() -> HostFlow.join(w, l.relay(), new TitleScreen()));
                        return;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("rejoin after handover failed: {}", e.toString());
        }
        switchUntil = 0;
        if (mc.screen instanceof HandoverScreen) {
            mc.execute(() -> mc.setScreen(new TitleScreen()));
        }
    }
}
