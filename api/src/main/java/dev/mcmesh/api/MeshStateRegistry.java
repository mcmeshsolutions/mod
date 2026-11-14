package dev.mcmesh.api;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handover snapshots. A mod registers how to capture and how to restore some in-memory state; the mesh writes the snapshot into the
 * world before every save and before a handover, and calls {@code restore} on the machine that hosts next, after the world has loaded.
 */
public interface MeshStateRegistry {
    /** {@code id} is 1 to 64 characters of a-z 0-9 . _ -. Registering the same id again replaces the earlier registration. */
    void register(String id, Supplier<byte[]> snapshot, Consumer<byte[]> restore);
}
