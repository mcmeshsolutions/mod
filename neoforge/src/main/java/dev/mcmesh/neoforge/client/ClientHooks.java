package dev.mcmesh.neoforge.client;

import net.minecraft.client.Minecraft;
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
        if (e.getScreen() instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen m) {
            e.addListener(Button.builder(Component.literal("Shared Worlds"), b -> Minecraft.getInstance().setScreen(new SyncedWorldsScreen(m)))
                    .bounds(m.width - 104, 6, 100, 20).build());
            ServerEntry.ensure();
            return;
        }
        if (e.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen t) {
            e.addListener(Button.builder(Component.literal("Play Mesh"), b -> QuickPlay.start(t))
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
        PackUpdate.start();
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
        if (!Standby.switching() || !(e.getNewScreen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen)) {
            return;
        }
        if (e.getCurrentScreen() instanceof HandoverScreen) {
            e.setCanceled(true);
        } else {
            e.setNewScreen(new HandoverScreen("Host switching", "Handing the world over. You will be reconnected automatically.", 0, null));
        }
    }

    @SubscribeEvent
    public static void hud(net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) {
        try {
            var health = HostHealthHud.line();
            if (health != null) {
                var f = Minecraft.getInstance().font;
                e.getGuiGraphics().drawString(f, health.text(), e.getGuiGraphics().guiWidth() - f.width(health.text()) - 6, 18, health.color(), true);
            }
        } catch (RuntimeException ex) {
            // a status line must never take the frame down with it
        }
        long left = Standby.remainingS();
        if (left < 0) {
            return;
        }
        var font = Minecraft.getInstance().font;
        String text = left == 0 ? "Taking over hosting..." : String.format("Taking over hosting in %02d:%02d", left / 60, left % 60);
        e.getGuiGraphics().drawString(font, text, e.getGuiGraphics().guiWidth() - font.width(text) - 6, 6, 0xFF55FF55, true);
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
