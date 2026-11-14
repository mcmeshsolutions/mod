package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.IslandPlan;
import dev.mcmesh.neoforge.DebugNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Draws the island layout on a chunk grid. Shared by the corner overlay and the full screen map. */
final class DebugMapRender {
    /** One colour per island, reused past the sixth; chosen to stay apart on both the dark and the bright ground. */
    private static final int[] ISLAND = {
        0xFF5B94D6, 0xFF45B48C, 0xFFC8803F, 0xFFAB7FD0, 0xFFD05F76, 0xFF93AD34,
    };
    private static final int GROUND = 0xC0101418;
    private static final int REGION_LINE = 0x55FFFFFF;
    private static final int PENDING = 0x806B7A85;
    private static final int FORCED = 0xFF3C8F9C;
    private static final int SELF = 0xFFFFFFFF;
    private static final int CONTESTED = 0xFFD98324;

    record Opts(boolean halo, boolean pending, boolean regions, boolean labels) { }

    private DebugMapRender() { }

    static int islandColor(int id) {
        return ISLAND[Math.floorMod(id, ISLAND.length)];
    }

    /** Alpha-replaced copy of an island colour, for the washed-out owned and halo fills. */
    private static int fade(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /**
     * @param cx0 chunk at the centre of the viewport
     * @param cell pixels per chunk
     */
    static void draw(GuiGraphics g, DebugNet.Snapshot snap, int x0, int y0, int w, int h,
                     double cx0, double cz0, double cell, Opts opts) {
        g.fill(x0, y0, x0 + w, y0 + h, GROUND);
        g.enableScissor(x0, y0, x0 + w, y0 + h);
        try {
            double midX = x0 + w / 2.0;
            double midY = y0 + h / 2.0;
            // chunk -> pixel
            java.util.function.DoubleUnaryOperator px = c -> midX + (c - cx0) * cell;
            java.util.function.DoubleUnaryOperator py = c -> midY + (c - cz0) * cell;

            if (opts.regions() && cell > 1.5) {
                double firstX = Math.floor((cx0 - w / (2 * cell)) / 32) * 32;
                for (double c = firstX; c <= cx0 + w / (2 * cell); c += 32) {
                    int x = (int) Math.round(px.applyAsDouble(c));
                    g.fill(x, y0, x + 1, y0 + h, REGION_LINE);
                }
                double firstZ = Math.floor((cz0 - h / (2 * cell)) / 32) * 32;
                for (double c = firstZ; c <= cz0 + h / (2 * cell); c += 32) {
                    int y = (int) Math.round(py.applyAsDouble(c));
                    g.fill(x0, y, x0 + w, y + 1, REGION_LINE);
                }
            }

            if (opts.pending()) {
                for (DebugNet.Region r : snap.pending()) {
                    int ax = (int) Math.round(px.applyAsDouble(r.x() * 32.0));
                    int ay = (int) Math.round(py.applyAsDouble(r.z() * 32.0));
                    int bx = (int) Math.round(px.applyAsDouble(r.x() * 32.0 + 32));
                    int by = (int) Math.round(py.applyAsDouble(r.z() * 32.0 + 32));
                    g.fill(ax, ay, bx, by, PENDING);
                }
            }

            // one rectangle per anchor instead of per chunk: the union of the squares is the island's owned set
            if (opts.halo()) {
                int reach = IslandPlan.VIEW + IslandPlan.HALO;
                for (DebugNet.Anchor a : snap.anchors()) {
                    rect(g, px, py, a.x() - reach, a.z() - reach, 2 * reach + 1, cell, fade(islandColor(a.island()), 0x25));
                }
            }
            for (DebugNet.Anchor a : snap.anchors()) {
                rect(g, px, py, a.x() - IslandPlan.VIEW, a.z() - IslandPlan.VIEW, 2 * IslandPlan.VIEW + 1, cell,
                        fade(islandColor(a.island()), 0x60));
            }

            String self = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.getGameProfile().getName() : "";
            for (DebugNet.Anchor a : snap.anchors()) {
                int x = (int) Math.round(px.applyAsDouble(a.x() + 0.5));
                int y = (int) Math.round(py.applyAsDouble(a.z() + 0.5));
                int dot = a.kind() == 1 ? FORCED : islandColor(a.island());
                int r = a.kind() == 1 ? 1 : 2;
                g.fill(x - r, y - r, x + r + 1, y + r + 1, dot);
                if (a.kind() == 0 && a.name().equals(self)) {
                    g.renderOutline(x - r - 2, y - r - 2, 2 * r + 5, 2 * r + 5, SELF);
                }
                if (opts.labels() && a.kind() == 0) {
                    g.drawString(Minecraft.getInstance().font, a.name(), x + 5, y - 4, islandColor(a.island()), true);
                }
            }
        } finally {
            g.disableScissor();
        }
        g.renderOutline(x0, y0, w, h, snap.contested() > 0 ? CONTESTED : 0x40FFFFFF);
    }

    private static void rect(GuiGraphics g, java.util.function.DoubleUnaryOperator px,
                             java.util.function.DoubleUnaryOperator py, int chunkX, int chunkZ, int span, double cell, int argb) {
        int ax = (int) Math.round(px.applyAsDouble(chunkX));
        int ay = (int) Math.round(py.applyAsDouble(chunkZ));
        int bx = (int) Math.round(px.applyAsDouble(chunkX + (double) span));
        int by = (int) Math.round(py.applyAsDouble(chunkZ + (double) span));
        g.fill(ax, ay, Math.max(bx, ax + 1), Math.max(by, ay + 1), argb);
    }

    /** The lines of text that go with a map: what the layout is and whether it is safe to shard. */
    static java.util.List<String> summary(DebugNet.Snapshot snap) {
        java.util.Map<Integer, Integer> perIsland = new java.util.TreeMap<>();
        int players = 0;
        int forced = 0;
        for (DebugNet.Anchor a : snap.anchors()) {
            perIsland.merge(a.island(), 1, Integer::sum);
            if (a.kind() == 1) {
                forced++;
            } else {
                players++;
            }
        }
        var lines = new java.util.ArrayList<String>();
        lines.add(snap.dimension());
        lines.add(perIsland.size() + (perIsland.size() == 1 ? " island" : " islands")
                + ", " + players + "p " + forced + "f, " + snap.pending().size() + " pending");
        if (snap.contested() > 0) {
            lines.add(snap.contested() + " contested chunks: two hosts would collide");
        }
        return lines;
    }
}
