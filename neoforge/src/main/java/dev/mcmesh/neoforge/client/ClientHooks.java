package dev.mcmesh.neoforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = "mcmesh", value = Dist.CLIENT)
public final class ClientHooks {
    private static Button convert;

    private ClientHooks() { }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post e) {
        WorkerPool.ensureStarted();
        // the config is only guaranteed loaded by the time a screen opens; a static initializer ran before that and
        // threw IllegalStateException on every single launch, so the check never actually happened
        PackUpdate.start();
        if (e.getScreen() instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen m) {
            e.addListener(Button.builder(Component.literal("Shared Worlds"), b -> Minecraft.getInstance().setScreen(new SyncedWorldsScreen(m)))
                    .bounds(m.width - 104, 6, 100, 20).build());
            ServerEntry.ensure();
            return;
        }
        if (e.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen t) {
            e.addListener(Button.builder(Component.literal("Mesh settings"), b -> Minecraft.getInstance().setScreen(ConfigScreen.open(t)))
                    .bounds(4, 4, 90, 20).build());
            e.addListener(Button.builder(Component.literal("Shared worlds"), b -> Minecraft.getInstance().setScreen(new SyncedWorldsScreen(t)))
                    .bounds(4, 28, 90, 20).build());
            return;
        }
        if (!(e.getScreen() instanceof SelectWorldScreen s)) {
            return;
        }
        convert = Button.builder(Component.literal("Convert to shared world"), b -> ask(s))
                .bounds(s.width - 164, 22, 160, 20).build();
        e.addListener(convert);
    }

    static {
        dev.mcmesh.neoforge.WhitelistNet.onState = s -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof WhitelistScreen w) {
                w.update(s);
            } else {
                mc.setScreen(new WhitelistScreen(s));
            }
        };
    }

    @SubscribeEvent
    public static void opening(net.neoforged.neoforge.client.event.ScreenEvent.Opening e) {
        if (!(e.getNewScreen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen original)) {
            return;
        }
        if (e.getCurrentScreen() instanceof HandoverScreen) {
            e.setCanceled(true);
            return;
        }
        boolean announced = Standby.switching();
        if (!dev.mcmesh.core.SessionLoss.holdDisconnectScreen(HostFlow.guestAliveAt, System.currentTimeMillis(), announced, Standby.active())) {
            return;
        }
        // players never see the plain Server Disconnected screen while a world changes hands
        var wait = new HandoverScreen("Host switching", announced
                ? "Handing the world over. You will be reconnected automatically."
                : "The host went away. Waiting for the world to continue, you will be reconnected automatically.", 0, null);
        e.setNewScreen(wait);
        String world = HostFlow.guestWorld;
        if (!announced && world != null) {
            Standby.reconnect(world, original, wait);
        }
    }

    @SubscribeEvent
    public static void commands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent e) {
        var root = net.minecraft.commands.Commands.literal("meshvote");
        root.then(net.minecraft.commands.Commands.literal("status").executes(c -> vote("status")));
        root.then(net.minecraft.commands.Commands.literal("propose").executes(c -> vote("propose")));
        root.then(net.minecraft.commands.Commands.literal("yes").executes(c -> vote("yes")));
        root.then(net.minecraft.commands.Commands.literal("no").executes(c -> vote("no")));
        root.executes(c -> vote("status"));
        e.getDispatcher().register(root);
        e.getDispatcher().register(net.minecraft.commands.Commands.literal("meshpanel").executes(c -> panel()));
    }

    /** Opens the gateway's web panel in a browser, signed in, without leaving the world. */
    private static int panel() {
        Minecraft mc = Minecraft.getInstance();
        Thread.ofVirtual().name("mesh-panel-cmd").start(() -> {
            try {
                String api = dev.mcmesh.neoforge.MeshConfig.API_URL.get();
                String code = new dev.mcmesh.core.GatewayClient(api, dev.mcmesh.neoforge.MeshAuth.cached().token()).panelCode();
                mc.execute(() -> {
                    mc.keyboardHandler.setClipboard(code);
                    net.minecraft.Util.getPlatform().openUri(api + "/panel#code=" + code);
                });
                say(mc, "Opening the web panel in your browser. The code " + code + " is on your clipboard if the page asks for it.");
            } catch (dev.mcmesh.core.GatewayException ex) {
                say(mc, "The gateway refused: " + ex.code);
            } catch (Exception ex) {
                say(mc, "Could not reach the gateway: " + ex.getMessage());
            }
        });
        return 1;
    }

    /** Runs a vote action off the render thread and prints the answer in chat. */
    private static int vote(String action) {
        Minecraft mc = Minecraft.getInstance();
        String world = HostHealthHud.joinedWorld(mc);
        if (world == null) {
            say(mc, "You are not playing in a mesh world as a guest.");
            return 0;
        }
        Thread.ofVirtual().name("mesh-vote").start(() -> {
            try {
                var gw = new dev.mcmesh.core.GatewayClient(dev.mcmesh.neoforge.MeshConfig.API_URL.get(), dev.mcmesh.neoforge.MeshAuth.cached().token());
                dev.mcmesh.core.GatewayClient.VoteState st = switch (action) {
                    case "propose" -> {
                        var hw = dev.mcmesh.core.GatewayClient.hwScore;
                        yield gw.proposeHandover(world, 1, hw == null ? Runtime.getRuntime().availableProcessors() : hw.get());
                    }
                    case "yes" -> gw.castVote(world, true);
                    case "no" -> gw.castVote(world, false);
                    default -> gw.voteState(world);
                };
                say(mc, dev.mcmesh.core.VoteText.status(st));
            } catch (dev.mcmesh.core.GatewayException ex) {
                say(mc, dev.mcmesh.core.VoteText.error(ex.code));
            } catch (Exception ex) {
                say(mc, "Could not reach the gateway: " + ex.getMessage());
            }
        });
        return 1;
    }

    private static void say(Minecraft mc, String text) {
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("[mesh] " + text), false);
            }
        });
    }

    @SubscribeEvent
    public static void hud(net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) {
        if (HostHealthHud.joinedWorld(Minecraft.getInstance()) != null) {
            HostFlow.guestAliveAt = System.currentTimeMillis();
        }
        if (dev.mcmesh.neoforge.MeshConfig.HUD_ALWAYS_VISIBLE.get()) {
            drawStatusInfo(e.getGuiGraphics());
        }
        long left = Standby.remainingS();
        if (left < 0) {
            return;
        }
        var font = Minecraft.getInstance().font;
        String text = left == 0 ? "Taking over hosting..." : String.format("Taking over hosting in %02d:%02d", left / 60, left % 60);
        e.getGuiGraphics().drawString(font, text, e.getGuiGraphics().guiWidth() - font.width(text) - 6, 6, 0xFF55FF55, true);
    }

    /** Host/guest status, lazy bootstrap and worldgen progress, and the F9 debug map: routine info a player may
     * find distracting during play, so it is also drawable from the pause screen instead (see hudAlwaysVisible). */
    private static void drawStatusInfo(GuiGraphics g) {
        try {
            DebugMap.renderOverlay(g);
            var health = HostHealthHud.line();
            var f = Minecraft.getInstance().font;
            int w = g.guiWidth();
            if (health != null) {
                g.drawString(f, health.text(), w - f.width(health.text()) - 6, 18, health.color(), true);
            }
            int[] lazy = dev.mcmesh.neoforge.LazyStatus.progress();
            if (lazy != null && lazy[1] > 0) {
                String t = String.format("World sync: %,d / %,d regions", lazy[0], lazy[1]);
                g.drawString(f, t, w - f.width(t) - 6, 30, 0xFFAAAAAA, true);
                int bw = 150;
                int x0 = w - bw - 6;
                int y0 = 42;
                double frac = Math.min(1.0, lazy[0] / (double) lazy[1]);
                g.fill(x0, y0, x0 + bw, y0 + 6, 0xFF2A2A2A);
                g.fill(x0, y0, x0 + (int) (bw * frac), y0 + 6, 0xFF55AAFF);
            }
            if (dev.mcmesh.neoforge.MeshConfig.CONTRIBUTE_WORLDGEN.get()) {
                String ws = "Worldgen helper: " + WorkerPool.status();
                g.drawString(f, ws, w - f.width(ws) - 6, 54, 0xFF888888, true);
            }
            if (dev.mcmesh.neoforge.SyncProgress.active()) {
                int bw = 150;
                dev.mcmesh.neoforge.SyncProgress.draw(g, f, w - bw / 2 - 6, 64, bw);
            }
        } catch (RuntimeException ex) {
            // a status line must never take the frame down with it
        }
    }

    @SubscribeEvent
    public static void pause(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof net.minecraft.client.gui.screens.PauseScreen)) {
            return;
        }
        var s = dev.mcmesh.neoforge.MeshService.current();
        String guest = HostFlow.guestOf;
        String text = s != null ? "Mesh: you are hosting this world" : guest != null ? "Mesh: hosted by " + guest : null;
        if (text != null) {
            var font = Minecraft.getInstance().font;
            e.getGuiGraphics().drawCenteredString(font, text, e.getScreen().width / 2, e.getScreen().height - 14, 0xFF8FD18F);
        }
        if (!dev.mcmesh.neoforge.MeshConfig.HUD_ALWAYS_VISIBLE.get()) {
            drawStatusInfo(e.getGuiGraphics());
        }
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Pre e) {
        if (e.getScreen() instanceof SelectWorldScreen s && convert != null) {
            convert.active = s.list.getSelectedOpt().isPresent();
        }
    }

    private static void ask(SelectWorldScreen s) {
        s.list.getSelectedOpt().ifPresent(entry -> {
            String id = entry.summary.getLevelId();
            String name = entry.getLevelName();
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new ConfirmScreen(yes -> {
                if (!yes) {
                    mc.setScreen(s);
                    return;
                }
                java.nio.file.Path save = mc.getLevelSource().getLevelPath(id);
                Gateways.confirmHosting(s, ok -> {
                    if (ok) {
                        mc.setScreen(new ConvertScreen(s, save, name));
                    }
                });
            }, Component.literal("Convert \"" + name + "\" to a shared world?"),
                    Component.literal("A copy is uploaded to the gateway, including your inventory. The original save is not changed.")));
        });
    }
}
