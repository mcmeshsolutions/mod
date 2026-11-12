package dev.mcmesh.neoforge.client;

import dev.mcmesh.neoforge.WhitelistNet;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Whitelist editor for operators; every change is applied by the host and echoed back. */
final class WhitelistScreen extends Screen {
    private static final int PER_PAGE = 8;
    private boolean enabled;
    private List<String> names;
    private int page;
    private EditBox box;

    WhitelistScreen(WhitelistNet.State s) {
        super(Component.literal("World whitelist"));
        update(s);
    }

    void update(WhitelistNet.State s) {
        enabled = s.enabled();
        names = s.names();
        page = Math.min(page, Math.max(0, (names.size() - 1) / PER_PAGE));
        if (minecraft != null) {
            rebuildWidgets();
        }
    }

    private static void send(String action, String name) {
        PacketDistributor.sendToServer(new WhitelistNet.Edit(action, name));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Whitelist: " + (enabled ? "ON" : "OFF")),
                b -> send(enabled ? "off" : "on", "")).bounds(cx - 100, 28, 200, 20).build());
        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(names.size(), from + PER_PAGE); i++) {
            String n = names.get(i);
            int y = 58 + (i - from) * 22;
            addRenderableWidget(Button.builder(Component.literal("Remove"), b -> send("remove", n)).bounds(cx + 40, y, 60, 20).build());
        }
        int by = 58 + PER_PAGE * 22 + 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(cx - 100, by, 20, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(cx + 80, by, 20, 20).build()).active = from + PER_PAGE < names.size();
        String keep = box == null ? "" : box.getValue();
        box = new EditBox(font, cx - 100, by + 26, 130, 20, title);
        box.setHint(Component.literal("Player name"));
        box.setMaxLength(16);
        box.setValue(keep);
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> add()).bounds(cx + 36, by + 26, 64, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(cx - 100, by + 52, 200, 20).build());
    }

    private void add() {
        String v = box.getValue().strip();
        if (!v.isEmpty()) {
            box.setValue("");
            send("add", v);
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == 257 || key == 335) && box.isFocused()) {
            add();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(names.size(), from + PER_PAGE); i++) {
            g.drawString(font, names.get(i), width / 2 - 100, 64 + (i - from) * 22, 0xFFFFFF, false);
        }
        if (names.isEmpty()) {
            g.drawString(font, "Nobody listed yet", width / 2 - 100, 64, 0x888888, false);
        }
    }
}
