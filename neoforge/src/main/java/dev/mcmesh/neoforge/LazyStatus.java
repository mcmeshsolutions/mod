package dev.mcmesh.neoforge;

/** Lazy bootstrap progress for the HUD; LazyWorld itself is package-private. */
public final class LazyStatus {
    private LazyStatus() { }

    /** {done, total} regions of the active lazy bootstrap, or null when none is running. */
    public static int[] progress() {
        LazyWorld w = LazyWorld.active;
        return w == null ? null : new int[] {w.done(), w.total()};
    }
}
