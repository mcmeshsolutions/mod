package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.HostHealth;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshService;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One line of text about the host machine: read from the gateway by guests, from the running server by the host itself. */
final class HostHealthHud {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final long POLL_MS = 10_000;
    private static volatile HostHealth guestView = HostHealth.UNKNOWN;
    private static volatile String polledWorld;
    private static volatile long lastPoll;
    private static volatile boolean polling;

    private HostHealthHud() { }

    /** Text and ARGB colour to draw, or null when there is nothing to say. Called every frame, so it only reads fields. */
    static Line line() {
        if (!MeshConfig.SHOW_HOST_HEALTH.get()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) {
            return null;
        }
        MeshService hosting = MeshService.current();
        HostHealth h = hosting != null ? dev.mcmesh.neoforge.HostStatus.snapshot() : joinedWorldHealth(mc);
        String text = h == null ? null : h.label();
        if (text == null) {
            return null;
        }
        if (hosting != null) {
            double up = dev.mcmesh.neoforge.HostStatus.uploadMbps();
            text = text.replace("Host ", "You are hosting: ") + (up >= 0 ? String.format(", uplink %.0f Mbit/s", up) : "");
        }
        int color = switch (h.status()) {
            case SMOOTH -> 0xFF55FF55;
            case STRAINED -> 0xFFFFFF55;
            case OVERLOADED -> 0xFFFF5555;
        };
        return new Line(text, color);
    }

    /** Renders the Host Peace-of-Mind HUD corner widget with background badge, active TPS, guest health, and failover status. */
    static void renderPeaceOfMindWidget(net.minecraft.client.gui.GuiGraphics g) {
        if (!MeshConfig.SHOW_HOST_HEALTH.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) {
            return;
        }
        MeshService hosting = MeshService.current();
        HostHealth h = hosting != null ? dev.mcmesh.neoforge.HostStatus.snapshot() : joinedWorldHealth(mc);
        if (h == null || !h.known()) {
            return;
        }
        var f = mc.font;
        int statusColor = switch (h.status()) {
            case SMOOTH -> 0xFF55FF55;
            case STRAINED -> 0xFFFFFF55;
            case OVERLOADED -> 0xFFFF5555;
        };

        String l1;
        String l2;
        if (hosting != null) {
            double tps = h.tps() >= 0 ? h.tps() : dev.mcmesh.neoforge.HostStatus.tps();
            String healthWord = switch (h.status()) {
                case SMOOTH -> "Optimal";
                case STRAINED -> "Strained";
                case OVERLOADED -> "Overloaded";
            };
            l1 = String.format("Hosting: %.1f TPS (%s)", tps, healthWord);
            int guests = Math.max(0, h.players() - 1);
            double up = dev.mcmesh.neoforge.HostStatus.uploadMbps();
            String upPart = up >= 0 ? String.format(" · %.0f Mb/s", up) : "";
            l2 = String.format("Guests: %d online%s", guests, upPart);
        } else {
            String host = HostFlow.guestOf != null ? HostFlow.guestOf : "Mesh Host";
            double tps = h.tps() >= 0 ? h.tps() : 20.0;
            l1 = String.format("Host (%s): %.1f TPS", host, tps);
            l2 = String.format("World players: %d", Math.max(1, h.players()));
        }

        int maxW = Math.max(f.width(l1), f.width(l2));
        int x = g.guiWidth() - maxW - 8;
        int y = 6;

        // Background card
        g.fill(x - 4, y - 3, x + maxW + 4, y + 23, 0x90101010);
        // Status indicator dot / left accent line
        g.fill(x - 4, y - 3, x - 2, y + 23, statusColor);

        g.drawString(f, l1, x, y, statusColor, true);
        g.drawString(f, l2, x, y + 11, 0xFFCCCCCC, true);
    }

    record Line(String text, int color) { }

    /** The world id when the current connection is the relay of a mesh world this client joined, else null. */
    static String joinedWorld(Minecraft mc) {
        var server = mc.getCurrentServer();
        if (server == null || dev.mcmesh.neoforge.MeshTap.joinWorld.isEmpty() || dev.mcmesh.neoforge.MeshTap.joinHost.isEmpty()) {
            return null;
        }
        String host = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server.ip).getHost();
        return host.equals(dev.mcmesh.neoforge.MeshTap.joinHost) ? dev.mcmesh.neoforge.MeshTap.joinWorld : null;
    }

    private static HostHealth joinedWorldHealth(Minecraft mc) {
        String world = joinedWorld(mc);
        if (world == null) {
            guestView = HostHealth.UNKNOWN;
            polledWorld = null;
            return null;
        }
        long now = System.currentTimeMillis();
        if (!polling && (!world.equals(polledWorld) || now - lastPoll >= POLL_MS)) {
            polling = true;
            polledWorld = world;
            lastPoll = now;
            Thread.ofVirtual().name("mesh-host-health").start(() -> {
                try {
                    var id = MeshAuth.cached();
                    guestView = new GatewayClient(MeshConfig.API_URL.get(), id.token()).hostHealth(world);
                } catch (Exception e) {
                    LOG.debug("host health poll failed: {}", e.toString());
                } finally {
                    polling = false;
                }
            });
        }
        return world.equals(polledWorld) ? guestView : null;
    }
}
