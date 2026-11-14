package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.IslandPlan;
import dev.mcmesh.neoforge.DebugNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The big spatial debug map: pan with the mouse, zoom with the wheel, toggle what is drawn. */
final class DebugMapScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int BAR = 24;

    private double centerX;
    private double centerZ;
    private double cell = 4;
    private boolean halo = true;
    private boolean pending = true;
    private boolean regions = true;
    private boolean labels = true;
    private boolean followPlayer = true;
    private Button followBtn;

    DebugMapScreen() {
        super(Component.literal("Island map"));
    }

    @Override
    protected void init() {
        DebugMap.screenOpen(true);
        if (followPlayer) {
            recenter();
        }
        int x = MARGIN;
        int y = height - BAR;
        x += add(x, y, "Halo", () -> halo, v -> halo = v);
        x += add(x, y, "Pending", () -> pending, v -> pending = v);
        x += add(x, y, "Regions", () -> regions, v -> regions = v);
        x += add(x, y, "Names", () -> labels, v -> labels = v);
        followBtn = addButton(x, y, "Follow", () -> followPlayer, v -> {
            followPlayer = v;
            if (v) {
                recenter();
            }
        });
    }

    private int add(int x, int y, String label, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        Button b = addButton(x, y, label, get, set);
        return b.getWidth() + 4;
    }

    private Button addButton(int x, int y, String label, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        int w = Math.max(54, font.width(label) + 24);
        Button b = addRenderableWidget(Button.builder(text(label, get.getAsBoolean()), btn -> {
            set.accept(!get.getAsBoolean());
            btn.setMessage(text(label, get.getAsBoolean()));
        }).bounds(x, y, w, 20).build());
        b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(tip(label))));
        return b;
    }

    private static Component text(String label, boolean on) {
        return Component.literal((on ? "x " : "  ") + label);
    }

    private static String tip(String label) {
        return switch (label) {
            case "Halo" -> "The " + IslandPlan.HALO + " chunk band around an island that neither host may write.";
            case "Pending" -> "Regions the lazy bootstrap is still fetching from the gateway.";
            case "Regions" -> "Region file boundaries, every 32 chunks.";
            case "Names" -> "Player names next to their chunk.";
            default -> "Keep the view centred on you.";
        };
    }

    private void recenter() {
        if (minecraft != null && minecraft.player != null) {
            centerX = minecraft.player.blockPosition().getX() / 16.0;
            centerZ = minecraft.player.blockPosition().getZ() / 16.0;
        }
    }

    /** Also runs when another screen replaces this one, which onClose does not. */
    @Override
    public void removed() {
        DebugMap.screenOpen(false);
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (followPlayer) {
            recenter();
        }
        if (followBtn != null) {
            // dragging the map switches following off, so the button must not keep claiming it is on
            followBtn.setMessage(text("Follow", followPlayer));
        }
        int w = width - 2 * MARGIN;
        int h = height - BAR - 2 * MARGIN;
        DebugNet.Snapshot snap = DebugMap.snapshot();
        if (snap == null) {
            g.fill(MARGIN, MARGIN, MARGIN + w, MARGIN + h, 0xC0101418);
            String msg = "Waiting for the server. Only operators receive island data.";
            g.drawCenteredString(font, msg, width / 2, MARGIN + h / 2 - 4, 0xFFD98324);
            return;
        }
        DebugMapRender.draw(g, snap, MARGIN, MARGIN, w, h, centerX, centerZ, cell,
                new DebugMapRender.Opts(halo, pending, regions, labels && cell >= 3));

        int y = MARGIN + 4;
        for (String s : DebugMapRender.summary(snap)) {
            g.drawString(font, s, MARGIN + 5, y, snap.contested() > 0 ? 0xFFD98324 : 0xFFE8EDF1, true);
            y += 10;
        }
        g.drawString(font, "merge " + IslandPlan.MERGE_DISTANCE + "  split " + IslandPlan.SPLIT_DISTANCE
                + "  view " + IslandPlan.VIEW + "  halo " + IslandPlan.HALO, MARGIN + 5, y, 0xFF93A1AC, true);

        // where the cursor is, in the three coordinate systems a chunk bug is ever reported in
        if (mouseX >= MARGIN && mouseX <= MARGIN + w && mouseY >= MARGIN && mouseY <= MARGIN + h) {
            int cx = (int) Math.floor(centerX + (mouseX - (MARGIN + w / 2.0)) / cell);
            int cz = (int) Math.floor(centerZ + (mouseY - (MARGIN + h / 2.0)) / cell);
            String read = "chunk " + cx + " " + cz + "   block " + (cx * 16) + " " + (cz * 16)
                    + "   region " + Math.floorDiv(cx, 32) + " " + Math.floorDiv(cz, 32);
            g.drawString(font, read, MARGIN + 5, MARGIN + h - 12, 0xFFBFC9D1, true);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            followPlayer = false;
            centerX -= dragX / cell;
            centerZ -= dragY / cell;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            cell = Math.max(0.5, Math.min(24, cell * (scrollY > 0 ? 1.15 : 0.87)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
