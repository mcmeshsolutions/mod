package dev.mcmesh.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mcmesh.neoforge.DebugNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the spatial debug map: holds the last snapshot the server sent, the two key bindings, and the
 * corner overlay. The server only sends while something here is watching, so an idle client costs nothing.
 */
@EventBusSubscriber(modid = "mcmesh", value = Dist.CLIENT)
public final class DebugMap {
    static final KeyMapping OVERLAY_KEY = new KeyMapping("key.mcmesh.overlay", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.mcmesh");
    static final KeyMapping SCREEN_KEY = new KeyMapping("key.mcmesh.map", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.mcmesh");

    private static volatile DebugNet.Snapshot latest;
    private static boolean overlay;
    private static boolean screenOpen;

    private DebugMap() { }

    static DebugNet.Snapshot snapshot() {
        return latest;
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
        e.register(OVERLAY_KEY);
        e.register(SCREEN_KEY);
        DebugNet.onSnapshot = s -> latest = s;
    }

    /** Tells the server whether anything still wants snapshots. Silent on a server without the mod. */
    private static void refresh() {
        boolean want = overlay || screenOpen;
        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (connection == null || !connection.hasChannel(DebugNet.Watch.TYPE)) {
            return;
        }
        PacketDistributor.sendToServer(new DebugNet.Watch(want));
        if (!want) {
            latest = null;
        }
    }

    /** Idempotent: a screen's init runs again on every window resize. */
    static void screenOpen(boolean open) {
        if (screenOpen != open) {
            screenOpen = open;
            refresh();
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        while (OVERLAY_KEY.consumeClick()) {
            overlay = !overlay;
            refresh();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "[mesh] island overlay " + (overlay ? "on" : "off")
                                + (overlay ? " (operators only, nothing arrives otherwise)" : "")), true);
            }
        }
        while (SCREEN_KEY.consumeClick()) {
            Minecraft.getInstance().setScreen(new DebugMapScreen());
        }
    }

    @SubscribeEvent
    public static void loggedOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) {
        overlay = false;
        screenOpen = false;
        latest = null;
    }

    /** The small map in the corner. Called from the HUD hook; never throws into the frame. */
    static void renderOverlay(GuiGraphics g) {
        if (!overlay) {
            return;
        }
        DebugNet.Snapshot snap = latest;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        int size = 96;
        int x = 6;
        int y = 32;
        if (snap == null) {
            g.fill(x, y, x + size, y + 14, 0xC0101418);
            g.drawString(font, "no island data", x + 4, y + 3, 0xFFD98324, true);
            return;
        }
        double cx = mc.player != null ? mc.player.blockPosition().getX() / 16.0 : 0;
        double cz = mc.player != null ? mc.player.blockPosition().getZ() / 16.0 : 0;
        DebugMapRender.draw(g, snap, x, y, size, size, cx, cz, 1.5,
                new DebugMapRender.Opts(true, true, false, false));
        int line = y + size + 3;
        for (String s : DebugMapRender.summary(snap)) {
            g.drawString(font, s, x, line, snap.contested() > 0 ? 0xFFD98324 : 0xFFBFC9D1, true);
            line += 10;
        }
    }
}
