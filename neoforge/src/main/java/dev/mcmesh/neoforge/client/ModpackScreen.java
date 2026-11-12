package dev.mcmesh.neoforge.client;

import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.SyncProgress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Offers to install or update the configured modpack as its own Prism instance. */
final class ModpackScreen extends Screen {
    private final Screen back;
    private volatile String message = "Checking the modpack link...";
    private volatile ModpackSync.Status status;
    private volatile boolean busy = true;
    private Button action;

    ModpackScreen(Screen back) {
        super(Component.literal("Modpack"));
        this.back = back;
        check();
    }

    private void check() {
        busy = true;
        Thread.ofVirtual().name("mesh-modpack-check").start(() -> {
            try {
                status = ModpackSync.check();
                message = switch (status.state()) {
                    case NO_LINK -> "No modpack link is configured. Set modpackUrl in the mcmesh config.";
                    case NOT_INSTALLED -> "The modpack is not installed yet.";
                    case UP_TO_DATE -> "The modpack is up to date.";
                    case UPDATE -> "A newer modpack is available.";
                };
            } catch (Exception e) {
                status = null;
                message = "Could not check: " + (e.getMessage() == null ? e : e.getMessage());
            }
            busy = false;
        });
    }

    private void install() {
        ModpackSync.Status st = status;
        if (st == null || busy) {
            return;
        }
        busy = true;
        message = "Working...";
        Thread.ofVirtual().name("mesh-modpack").start(() -> {
            try {
                message = ModpackSync.install(st);
                status = new ModpackSync.Status(ModpackSync.State.UP_TO_DATE, st.remote());
            } catch (Exception e) {
                message = "Failed: " + (e.getMessage() == null ? e : e.getMessage());
            }
            busy = false;
        });
    }

    @Override
    protected void init() {
        int cx = width / 2;
        action = addRenderableWidget(Button.builder(Component.literal("Install / Update"), b -> install()).bounds(cx - 100, height / 2 + 10, 200, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> minecraft.setScreen(back)).bounds(cx - 100, height / 2 + 34, 200, 20).build());
    }

    @Override
    public void tick() {
        ModpackSync.Status s = status;
        action.active = !busy && s != null && (s.state() == ModpackSync.State.NOT_INSTALLED || s.state() == ModpackSync.State.UPDATE);
        action.setMessage(Component.literal(s != null && s.state() == ModpackSync.State.UPDATE ? "Update" : "Install"));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        g.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        g.drawCenteredString(font, MeshConfig.MODPACK_URL.get().isEmpty() ? "" : MeshConfig.MODPACK_URL.get(), width / 2, 40, 0x666666);
        g.drawCenteredString(font, message, width / 2, height / 2 - 20, 0xAAAAAA);
        SyncProgress.draw(g, font, width / 2, height / 2 - 4, 240);
    }
}
