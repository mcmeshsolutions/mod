package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.GatewayException;
import dev.mcmesh.core.GatewayList.Gateway;
import dev.mcmesh.neoforge.MeshConfig;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/** Server-list style screen over the worlds of every configured gateway, grouped by gateway. */
public final class SyncedWorldsScreen extends Screen {
    private static final Logger LOG = LogUtils.getLogger();

    private final Screen back;
    /** listing is null and error set when the gateway could not be reached. */
    private record Group(Gateway gw, GatewayClient.Listing listing, String me, String error) { }

    private volatile List<Group> groups = List.of();
    private volatile String message = "Contacting the gateway...";
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

    private static Group fetch(Gateway g) {
        try {
            String me = Gateways.identity(g).name();
            return new Group(g, Gateways.client(g).listWorlds(), me, null);
        } catch (Exception e) {
            LOG.warn("listing worlds on {} failed", g.host(), e);
            if (e instanceof GatewayException ge && ge.status == 401) {
                Gateways.forget(g);
            }
            return new Group(g, null, "", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void refresh() {
        Thread.ofPlatform().name("mesh-worlds").daemon().start(() -> {
            List<Group> got = MeshConfig.gateways().parallelStream().map(SyncedWorldsScreen::fetch).toList();
            groups = got;
            fetchedAt = System.currentTimeMillis();
            Group main = got.get(0);
            if (got.size() > 1) {
                message = "";
            } else if (main.listing() == null) {
                message = "Could not reach the gateway: " + main.error();
            } else {
                message = main.listing().worlds().isEmpty() ? "No shared worlds yet. Create one or redeem an invite code." : "";
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
        WorldEntry keep = list.getSelected();
        list.reset();
        List<Group> all = groups;
        for (Group g : all) {
            if (all.size() > 1) {
                WorldEntry head = new WorldEntry(g, null);
                list.add(head);
                if (keep != null && keep.w == null && keep.g.gw().equals(g.gw())) {
                    list.setSelected(head);
                }
            }
            if (g.listing() == null) {
                continue;
            }
            for (GatewayClient.WorldInfo w : g.listing().worlds()) {
                WorldEntry e = new WorldEntry(g, w);
                list.add(e);
                if (keep != null && keep.w != null && keep.g.gw().equals(g.gw()) && keep.w.id().equals(w.id())) {
                    list.setSelected(e);
                }
            }
        }
        updateButtons();
    }

    /** Gateway of the selected row, the default one when nothing is selected. */
    private Gateway target() {
        WorldEntry e = list == null ? null : list.getSelected();
        return e == null ? Gateways.main() : e.g.gw();
    }

    private static GatewayClient.WorldInfo guestOnly(GatewayClient.WorldInfo w) {
        return new GatewayClient.WorldInfo(w.id(), w.name(), w.mcVersion(), "CLIENT_ONLY", w.state(), w.host(), -1, w.hostReady(), w.syncDone(), w.syncTotal());
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
        addRenderableWidget(Button.builder(Component.literal("Create"), b -> Gateways.confirmHosting(this, ok -> {
            if (ok) {
                minecraft.setScreen(new PromptScreen(this, "New shared world", "World name", this::create));
            }
        }))
                .bounds(x + 232, y1, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Redeem"), b -> {
            Gateway g = target();
            minecraft.setScreen(new PromptScreen(this, "Invite code for " + g.host(), "Code", code -> redeem(g, code)));
        })
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
        WorldEntry sel = list == null ? null : list.getSelected();
        GatewayClient.WorldInfo w = sel == null ? null : sel.w;
        boolean joinOnly = sel != null && !sel.g.gw().isDefault();
        boolean hostedByOther = w != null && w.host() != null && !w.host().equalsIgnoreCase(sel.g.me());
        primary.active = w != null && !busy && !(w.host() != null && !w.hostReady()) && !(joinOnly && !hostedByOther);
        requestMods.visible = !dev.mcmesh.neoforge.ModGuard.offending.isEmpty() && !joinOnly;
        requestMods.active = !busy && w != null;
        invite.active = w != null && w.role().equals("ADMIN") && !busy;
        if (w != null) {
            primary.setMessage(Component.literal(w.host() != null && !w.hostReady() ? "Host is loading..."
                    : joinOnly ? (hostedByOther ? "Join World" : "Nobody is hosting")
                    : !hostedByOther ? "Play World" : w.takeoverInS() >= 0 ? "Join and Take Over" : "Join World"));
        }
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
        WorldEntry sel = list.getSelected();
        if (sel == null || sel.w == null) {
            return;
        }
        GatewayClient.WorldInfo w = sel.w;
        Gateway g = sel.g.gw();
        String relay = sel.g.listing().relay();
        boolean hostedByOther = w.host() != null && !w.host().equalsIgnoreCase(sel.g.me());
        if (!g.isDefault()) {
            if (hostedByOther) {
                run("mesh-join", "Joining " + w.name() + "...", () -> {
                    GatewayClient.ticketRoute(w.id(), Gateways.client(g));
                    return HostFlow.join(guestOnly(w), relay, this);
                });
            }
            return;
        }
        if (w.role().equals("CLIENT_ONLY")) {
            play(w, relay, hostedByOther);
            return;
        }
        Gateways.confirmHosting(this, ok -> {
            if (ok) {
                play(w, relay, hostedByOther);
            } else if (hostedByOther) {
                // declined: plain guest, no standby bidding for the lease
                play(guestOnly(w), relay, true);
            } else {
                message = "Hosting was not approved for " + g.host() + ".";
            }
        });
    }

    private void play(GatewayClient.WorldInfo w, String relay, boolean hostedByOther) {
        GatewayClient.ticketRoute(null, null);
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
        WorldEntry sel = list.getSelected();
        var mods = dev.mcmesh.neoforge.ModGuard.offending;
        if (sel == null || sel.w == null || mods.isEmpty()) {
            return;
        }
        GatewayClient.WorldInfo w = sel.w;
        Gateway g = sel.g.gw();
        run("mesh-modreq", "Sending request...", () -> {
            Gateways.client(g).requestMods(w.id(), mods);
            return "Requested " + mods.size() + " mod(s). An admin can allow them in the web panel, then try again.";
        });
    }

    private void invite() {
        WorldEntry sel = list.getSelected();
        if (sel == null || sel.w == null) {
            return;
        }
        GatewayClient.WorldInfo w = sel.w;
        Gateway g = sel.g.gw();
        run("mesh-invite", "Creating invite...", () -> {
            String code = Gateways.client(g).createInvite(w.id(), "HOST_ELIGIBLE");
            minecraft.execute(() -> minecraft.keyboardHandler.setClipboard(code));
            return "Invite code " + code + " copied. It works once.";
        });
    }

    private void panel() {
        Gateway g = target();
        run("mesh-panel", "Opening the web panel of " + g.host() + "...", () -> {
            String code = Gateways.client(g).panelCode();
            String url = g.apiUrl() + "/panel";
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

    private void redeem(Gateway g, String code) {
        run("mesh-redeem", "Redeeming...", () -> {
            Gateways.client(g).redeemInvite(code);
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
        final Group g;
        /** null for the gateway header row. */
        final GatewayClient.WorldInfo w;

        WorldEntry(Group g, GatewayClient.WorldInfo w) {
            this.g = g;
            this.w = w;
        }

        private void header(GuiGraphics gfx, int top, int left) {
            Gateway gw = g.gw();
            gfx.drawString(font, gw.isDefault() ? gw.host() : gw.id() + "  (" + gw.host() + ")", left + 3, top + 8, 0xFFFFFF, false);
            String sub = g.listing() == null ? "Unreachable: " + g.error()
                    : (gw.isDefault() ? "Home gateway" : "Join only") + ", " + g.listing().worlds().size() + " world(s)";
            gfx.drawString(font, font.plainSubstrByWidth(sub, 296), left + 3, top + 20, g.listing() == null ? 0xFF5555 : 0x888888, false);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mx, int my, boolean hover, float dt) {
            if (w == null) {
                header(g, top, left);
                return;
            }
            g.drawString(font, w.name(), left + 3, top + 2, 0xFFFFFF, false);
            boolean hosted = w.host() != null;
            String status = hosted ? "Hosted by " + w.host() : "Idle, no host";
            if (hosted && !w.hostReady()) {
                status = w.host() + " is loading the world" + (w.syncTotal() > 0
                        ? String.format(": %,d / %,d files (%d%%)", w.syncDone(), w.syncTotal(), w.syncDone() * 100 / w.syncTotal())
                        : "");
            }
            if (hosted && w.takeoverInS() >= 0 && this.g.gw().isDefault()) {
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
            return Component.literal(w == null ? g.gw().host() : w.name());
        }
    }
}
