package dev.mcmesh.neoforge.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Notice shown while the world moves to another machine: a countdown on the old host, plain waiting on the others. */
final class HandoverScreen extends Screen {
    private volatile String heading;
    private Runnable cancel;
    private volatile String line;
    private final long countdownMs;
    private final Runnable atZero;
    private final long start = System.currentTimeMillis();
    private volatile boolean fired;
    private Button now;

    HandoverScreen(String heading, String line, long countdownMs, Runnable atZero) {
        super(Component.literal(heading));
        this.heading = heading;
        this.line = line;
        this.countdownMs = countdownMs;
        this.atZero = atZero;
    }

    void line(String s) {
        line = s;
    }

    void heading(String s) {
        heading = s;
    }

    void onCancel(Runnable r) {
        cancel = r;
    }

    @Override
    protected void init() {
        if (cancel != null) {
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel.run())
                    .bounds(atZero != null ? width / 2 - 104 : width / 2 - 50, height / 2 + 30, 100, 20).build());
        }
        if (atZero != null) {
            now = addRenderableWidget(Button.builder(Component.literal("Switch now"), b -> fire())
                    .bounds(cancel != null ? width / 2 + 4 : width / 2 - 50, height / 2 + 30, 100, 20).build());
            now.active = !fired;
        }
    }

    private void fire() {
        if (fired) {
            return;
        }
        fired = true;
        line = "Saving the world and handing it over...";
        if (now != null) {
            now.active = false;
        }
        atZero.run();
    }

    @Override
    public void tick() {
        if (atZero != null && !fired && System.currentTimeMillis() - start >= countdownMs) {
            fire();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        int cx = width / 2;
        int cy = height / 2;
        g.drawCenteredString(font, heading, cx, cy - 36, 0xFFFFFF);
        g.drawCenteredString(font, line, cx, cy - 20, 0xAAAAAA);
        int bw = 220;
        int x0 = cx - bw / 2;
        int y0 = cy - 4;
        g.fill(x0, y0, x0 + bw, y0 + 6, 0xFF2A2A2A);
        long t = System.currentTimeMillis() - start;
        if (atZero != null) {
            double f = fired ? 1.0 : Math.min(1.0, t / (double) countdownMs);
            g.fill(x0, y0, x0 + (int) (bw * f), y0 + 6, 0xFF55FF55);
            if (!fired) {
                long left = Math.max(0, (countdownMs - t + 999) / 1000);
                g.drawCenteredString(font, "Switching in " + left + " s", cx, cy + 10, 0x888888);
            }
        } else if (!dev.mcmesh.neoforge.SyncProgress.draw(g, font, cx, y0, bw)) {
            int seg = 60;
            int off = (int) ((t / 8) % (bw + seg)) - seg;
            int a = Math.max(0, off);
            int b = Math.min(bw, off + seg);
            if (b > a) {
                g.fill(x0 + a, y0, x0 + b, y0 + 6, 0xFF55FF55);
            }
        }
    }
}
