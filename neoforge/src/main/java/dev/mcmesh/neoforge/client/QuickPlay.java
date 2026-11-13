package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** One button: join whoever hosts the world, take over if this machine is stronger, or host it when nobody does. */
public final class QuickPlay {
    private static volatile boolean busy;

    private QuickPlay() { }

    public static void start(Screen back) {
        Gateways.confirmHosting(back, ok -> start(back, ok));
    }

    private static void start(Screen back, boolean mayHost) {
        if (busy) {
            return;
        }
        busy = true;
        GatewayClient.ticketRoute(null, null);
        Minecraft mc = Minecraft.getInstance();
        var scr = new HandoverScreen("Connecting", "Looking for the world.", 0, null);
        scr.onCancel(() -> {
            busy = false;
            mc.setScreen(back);
        });
        mc.setScreen(scr);
        Thread t = new Thread(() -> {
            try {
                run(mc, scr, back, mayHost);
            } catch (Exception e) {
                LogUtils.getLogger().warn("quick play failed: {}", e.toString());
                scr.heading("Could not connect");
                scr.line(String.valueOf(e.getMessage()));
            } finally {
                busy = false;
            }
        }, "mesh-quickplay");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Minecraft mc, HandoverScreen scr, Screen back, boolean mayHost) throws Exception {
        var id = MeshAuth.cached();
        var gw = new GatewayClient(MeshConfig.API_URL.get(), id.token());
        var l = gw.listWorlds();
        var w = l.worlds().stream().filter(x -> x.id().equals(MeshConfig.WORLD.get())).findFirst()
                .orElse(l.worlds().isEmpty() ? null : l.worlds().get(0));
        if (w == null) {
            scr.heading("No shared world");
            scr.line("Create one under Shared worlds first.");
            return;
        }
        String differs = dev.mcmesh.core.PackCheck.warning(MeshConfig.PACK_VERSION.get(), w.packVersion());
        if (differs != null) {
            scr.heading("Modpack version differs");
            scr.line(differs);
            return;
        }
        boolean other = w.host() != null && !w.host().equalsIgnoreCase(id.name());
        if (other && w.takeoverInS() >= 0 && mayHost) {
            Standby.waitAndTake(w, l.relay(), back);
            return;
        }
        if (other) {
            for (int i = 0; i < 60 && !w.hostReady(); i++) {
                scr.heading("Waiting for " + w.host());
                scr.line("The host is loading the world.");
                Thread.sleep(2_000);
                w = l.worlds().isEmpty() ? w : refresh(gw, w);
            }
            // without approval to host: plain guest, no standby bidding for the lease
            String err = HostFlow.join(mayHost ? w : new GatewayClient.WorldInfo(w.id(), w.name(), w.mcVersion(), "CLIENT_ONLY", w.state(),
                    w.host(), -1, w.hostReady(), w.syncDone(), w.syncTotal(), w.packVersion()), l.relay(), back);
            if (err != null) {
                scr.heading("Could not connect");
                scr.line(err);
            }
            return;
        }
        if (w.role().equals("CLIENT_ONLY")) {
            scr.heading("Nobody is hosting");
            scr.line("You are not allowed to host " + w.name() + ".");
            return;
        }
        if (!mayHost) {
            scr.heading("Nobody is hosting");
            scr.line("Hosting was not approved for this gateway.");
            return;
        }
        String err = HostFlow.host(w, back, s -> scr.line(s + "..."));
        if (err != null) {
            scr.heading("Could not start the world");
            scr.line(err);
        }
    }

    private static GatewayClient.WorldInfo refresh(GatewayClient gw, GatewayClient.WorldInfo w) throws Exception {
        for (var x : gw.listWorlds().worlds()) {
            if (x.id().equals(w.id())) {
                return x;
            }
        }
        return w;
    }
}
