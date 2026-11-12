package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/** Server-list style screen over the gateway's worlds. */
public final class SyncedWorldsScreen extends Screen {
    private static final Logger LOG = LogUtils.getLogger();

    private final Screen back;
    private volatile GatewayClient.Listing listing;
    private volatile String message = "Contacting the gateway...";
    private volatile String me = "";
    private volatile boolean busy;
    private volatile long fetchedAt;
    private int ticks;
    private WorldList list;
    private Button primary;
    private Button invite;
    private Button requestMods;

    public SyncedWorldsScreen(Screen back) {
        super(Component.literal("Shared Worlds"));
        this.back = back;
        refresh();
    }

    private GatewayClient gw() throws Exception {
        var id = MeshAuth.cached();
        me = id.name();
        return new GatewayClient(MeshConfig.API_URL.get(), id.token());
    }

    private void refresh() {
        Thread.ofPlatform().name("mesh-worlds").daemon().start(() -> {
            try {
                listing = gw().listWorlds();
                fetchedAt = System.currentTimeMillis();
                message = listing.worlds().isEmpty() ? "No shared worlds yet. Create one or redeem an invite code." : "";
            } catch (Exception e) {
                LOG.warn("listing worlds failed", e);
                message = "Could not reach the gateway: " + (e.getMessage() == null ? e : e.getMessage());
            }
            if (minecraft != null) {
                minecraft.execute(this::fill);
            }
        });
    }

    private void fill() {
        if (list == null) {
            return;
        }
        GatewayClient.WorldInfo keep = list.getSelected() == null ? null : list.getSelected().w;
        list.reset();
        if (listing != null) {
            for (GatewayClient.WorldInfo w : listing.worlds()) {
                WorldEntry e = new WorldEntry(w);
                list.add(e);
                if (keep != null && keep.id().equals(w.id())) {
                    list.setSelected(e);
                }
            }
        }
        updateButtons();
    }

    @Override
    protected void init() {
        list = new WorldList();
        addRenderableWidget(list);
        int y1 = height - 52;
        int y2 = height - 28;
        int w = 100;
        int x = width / 2 - 154;
        primary = addRenderableWidget(Button.builder(Component.literal("Join / Play"), b -> primary()).bounds(x, y1, 150, 20).build());
        invite = addRenderableWidget(Button.builder(Component.literal("Invite code"), b -> invite()).bounds(x + 154, y1, 74, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Create"), b -> minecraft.setScreen(new PromptScreen(this, "New shared world", "World name", this::create)))
                .bounds(x + 232, y1, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Redeem"), b -> minecraft.setScreen(new PromptScreen(this, "Invite code", "Code", this::redeem)))
                .bounds(x, y2, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> { message = "Refreshing..."; refresh(); })
                .bounds(x + 60, y2, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Modpack"), b -> minecraft.setScreen(new ModpackScreen(this)))
                .bounds(x + 120, y2, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Web Panel"), b -> panel()).bounds(x + 184, y2, 64, 20).build());
        requestMods = addRenderableWidget(Button.builder(Component.literal("Request mods"), b -> requestMods()).bounds(x + 312, y1, 84, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> minecraft.setScreen(back)).bounds(x + 252, y2, 56, 20).build());
        fill();
    }

    private void updateButtons() {
        GatewayClient.WorldInfo w = selected();
        primary.active = w != null && !busy && !(w.host() != null && !w.hostReady());
        requestMods.visible = !dev.mcmesh.neoforge.ModGuard.offending.isEmpty();
        requestMods.active = !busy;
        invite.active = w != null && w.role().equals("ADMIN") && !busy;
        if (w != null) {
            boolean hostedByOther = w.host() != null && !w.host().equalsIgnoreCase(me);
            primary.setMessage(Component.literal(w.host() != null && !w.hostReady() ? "Host is loading..." : !hostedByOther ? "Play World" : w.takeoverInS() >= 0 ? "Join and Take Over" : "Join World"));
        }
    }

    private GatewayClient.WorldInfo selected() {
        return list == null || list.getSelected() == null ? null : list.getSelected().w;
    }

    @Override
    public void tick() {
        if (primary != null) {
            updateButtons();
            if (++ticks % 200 == 0 && !busy) {
                refresh();
            }
        }
    }

    private void run(String name, String working, Task t) {
        busy = true;
        message = working;
        Thread.ofPlatform().name(name).daemon().start(() -> {
            try {
                String r = t.run();
                if (r != null) {
                    message = r;
                }
            } catch (Exception e) {
                LOG.error("{} failed", name, e);
                message = "Failed: " + (e.getMessage() == null ? e : e.getMessage());
            }
            busy = false;
        });
    }

    private interface Task {
        String run() throws Exception;
    }

    private void primary() {
        GatewayClient.WorldInfo w = selected();
        if (w == null) {
            return;
        }
        String relay = listing.relay();
        boolean hostedByOther = w.host() != null && !w.host().equalsIgnoreCase(me);
        if (hostedByOther && w.takeoverInS() >= 0) {
            long left = Math.max(0, w.takeoverInS() - (System.currentTimeMillis() - fetchedAt) / 1000);
            Standby.waitAndTake(new GatewayClient.WorldInfo(w.id(), w.name(), w.mcVersion(), w.role(), w.state(), w.host(), left, w.hostReady(), w.syncDone(), w.syncTotal()), relay, this);
        } else if (hostedByOther) {
            run("mesh-join", "Joining " + w.name() + "...", () -> HostFlow.join(w, relay, this));
        } else if (w.role().equals("CLIENT_ONLY")) {
            message = "Nobody is hosting " + w.name() + " right now and you cannot host it.";
        } else {
            run("mesh-host", "Starting " + w.name() + "...", () -> {
                String refused = HostFlow.host(w, this, s -> message = s + "...");
                return refused;
            });
        }
    }

    private void requestMods() {
        GatewayClient.WorldInfo w = selected();
        var mods = dev.mcmesh.neoforge.ModGuard.offending;
        if (w == null || mods.isEmpty()) {
            return;
        }
        run("mesh-modreq", "Sending request...", () -> {
            gw().requestMods(w.id(), mods);
            return "Requested " + mods.size() + " mod(s). An admin can allow them in the web panel, then try again.";
        });
    }

    private void invite() {
        GatewayClient.WorldInfo w = selected();
        if (w == null) {
            return;
        }
        run("mesh-invite", "Creating invite...", () -> {
            String code = gw().createInvite(w.id(), "HOST_ELIGIBLE");
            minecraft.execute(() -> minecraft.keyboardHandler.setClipboard(code));
            return "Invite code " + code + " copied. It works once.";
        });
    }

    private void panel() {
        run("mesh-panel", "Opening the web panel...", () -> {
            String code = gw().panelCode();
            String url = dev.mcmesh.neoforge.MeshConfig.API_URL.get().replaceAll("/+$", "") + "/panel";
            minecraft.execute(() -> {
                minecraft.keyboardHandler.setClipboard(code);
                net.minecraft.Util.getPlatform().openUri(url);
            });
            return "Panel login code " + code + " copied. It works once for 5 minutes.";
        });
    }

    private void create(String name) {
        run("mesh-create", "Creating " + name + "...", () -> HostFlow.create(name, this, s -> message = s + "..."));
    }

    private void redeem(String code) {
        run("mesh-redeem", "Redeeming...", () -> {
            gw().redeemInvite(code);
            refresh();
            return "Invite accepted.";
        });
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        if (dev.mcmesh.neoforge.SyncProgress.draw(g, font, width / 2, height - 78, 240)) {
            g.drawCenteredString(font, message, width / 2, height - 92, 0xAAAAAA);
            return;
        }
        String m = message;
        if (!m.isEmpty()) {
            g.drawCenteredString(font, m, width / 2, height - 66, 0xAAAAAA);
        }
    }

    private final class WorldList extends ObjectSelectionList<WorldEntry> {
        WorldList() {
            super(SyncedWorldsScreen.this.minecraft, SyncedWorldsScreen.this.width, SyncedWorldsScreen.this.height - 108, 32, 36);
        }

        void reset() {
            clearEntries();
        }

        void add(WorldEntry e) {
            addEntry(e);
        }

        @Override
        public int getRowWidth() {
            return 305;
        }
    }

    private final class WorldEntry extends ObjectSelectionList.Entry<WorldEntry> {
        final GatewayClient.WorldInfo w;

        WorldEntry(GatewayClient.WorldInfo w) {
            this.w = w;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mx, int my, boolean hover, float dt) {
            g.drawString(font, w.name(), left + 3, top + 2, 0xFFFFFF, false);
            boolean hosted = w.host() != null;
            String status = hosted ? "Hosted by " + w.host() : "Idle, no host";
            if (hosted && !w.hostReady()) {
                status = w.host() + " is loading the world" + (w.syncTotal() > 0
                        ? String.format(": %,d / %,d files (%d%%)", w.syncDone(), w.syncTotal(), w.syncDone() * 100 / w.syncTotal())
                        : "");
            }
            if (hosted && w.takeoverInS() >= 0) {
                long s = Math.max(0, w.takeoverInS() - (System.currentTimeMillis() - fetchedAt) / 1000);
                status += s == 0 ? "  |  This machine can take over hosting"
                        : String.format("  |  This machine can take over hosting in %02d:%02d", s / 60, s % 60);
            }
            g.drawString(font, status, left + 3, top + 13, hosted && w.hostReady() ? 0x55FF55 : hosted ? 0xFFAA00 : 0x888888, false);
            g.drawString(font, "Minecraft " + w.mcVersion() + "  -  " + w.role().toLowerCase().replace('_', ' '), left + 3, top + 24, 0x777777, false);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            list.setSelected(this);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(w.name());
        }
    }
}
