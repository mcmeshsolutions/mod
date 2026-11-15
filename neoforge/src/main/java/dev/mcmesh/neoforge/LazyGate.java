package dev.mcmesh.neoforge;

import java.nio.file.Path;

/** The part of the region guard that mixin code may call. Public because mixin classes live in another package. */
public final class LazyGate {
    private LazyGate() { }

    public static void before(Path folder, int cx, int cz) {
        String kind = folder.getFileName().toString();
        if (!kind.equals("region") && !kind.equals("entities") && !kind.equals("poi")) {
            return;
        }
        String dim = MeshTap.dimOf(folder.getParent());
        if (dim == null) {
            return;
        }
        Ownership.touchedForeign(dim, cx, cz);
        // a region the game has opened is never written to by a tile install, so it is recorded before the game gets in
        if (dim.equals("minecraft:overworld")) {
            TileInstall.touch(cx >> 5, cz >> 5);
        }
        LazyWorld w = LazyWorld.active;
        if (w != null) {
            w.guard(dim, cx, cz);
        }
    }
}
