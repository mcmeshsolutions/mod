package dev.mcmesh.neoforge;

import java.nio.file.Path;

/** The part of the region guard that mixin code may call. Public because mixin classes live in another package. */
public final class LazyGate {
    private LazyGate() { }

    public static void before(Path folder, int cx, int cz) {
        LazyWorld w = LazyWorld.active;
        if (w == null) {
            return;
        }
        String kind = folder.getFileName().toString();
        if (!kind.equals("region") && !kind.equals("entities") && !kind.equals("poi")) {
            return;
        }
        String dim = MeshTap.dimOf(folder.getParent());
        if (dim != null) {
            w.guard(dim, cx, cz);
        }
    }
}
