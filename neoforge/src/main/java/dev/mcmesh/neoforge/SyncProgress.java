package dev.mcmesh.neoforge;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Live state of the world download so any screen can draw a real bar with an ETA. */
public final class SyncProgress {
    private static volatile boolean active;
    private static volatile long done;
    private static volatile long total;
    private static volatile long bytes;
    private static volatile long startMs;
    private static volatile boolean byteMode;
    private static volatile String label = "Downloading";

    private SyncProgress() { }

    public static void begin(String what, boolean bytesTotal) {
        label = what;
        byteMode = bytesTotal;
        begin0();
    }

    static void begin() {
        label = "Downloading";
        byteMode = false;
        begin0();
    }

    private static void begin0() {
        done = 0;
        total = 0;
        bytes = 0;
        startMs = System.currentTimeMillis();
        active = true;
    }

    public static void update(long d, long t, long b) {
        done = d;
        total = t;
        bytes = b;
    }

    public static void end() {
        active = false;
    }

    /** {done, total} files of a world download, null otherwise; sent to the gateway so others see the host loading. */
    public static long[] report() {
        return active && !byteMode && total > 0 ? new long[] {done, total} : null;
    }

    public static boolean active() {
        return active;
    }

    /** Draws the bar and a line like "Downloading 1,204 / 5,000 files (12.3 MB), about 1:12 left"; false when idle. */
    public static boolean draw(GuiGraphics g, Font font, int cx, int y, int bw) {
        if (!active || total <= 0) {
            return false;
        }
        long d = byteMode ? bytes : done;
        long t = total;
        double f = Math.min(1.0, d / (double) t);
        int x0 = cx - bw / 2;
        g.fill(x0, y, x0 + bw, y + 6, 0xFF2A2A2A);
        g.fill(x0, y, x0 + (int) (bw * f), y + 6, 0xFF55FF55);
        long el = System.currentTimeMillis() - startMs;
        String eta = "estimating";
        if (d > 0 && el > 1500) {
            long left = (long) (el / (double) d * (t - d) / 1000.0);
            eta = String.format("about %d:%02d left", left / 60, left % 60);
        }
        String text = byteMode
                ? String.format("%s %.1f / %.1f MB, %s", label, d / 1048576.0, t / 1048576.0, eta)
                : String.format("%s %,d / %,d files (%.1f MB), %s", label, d, t, bytes / 1048576.0, eta);
        g.drawCenteredString(font, text, cx, y + 12, 0x888888);
        return true;
    }
}
