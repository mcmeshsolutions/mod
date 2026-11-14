package dev.mcmesh.api;

import java.util.List;

/**
 * Entry point for mods. {@link #current()} never returns null and never throws: when the mcmesh mod is not installed (or this build
 * of it is older) every call is a harmless no-op, so a mod can use this API without depending on mcmesh being present.
 */
public interface MeshApi {
    static MeshApi current() {
        return Discovery.get();
    }

    SessionMode mode();

    /** The mesh world id, or null when {@link #mode()} is STANDALONE_LOCAL. */
    String worldId();

    /** Blocks on the network (cached for 30 s): never call it on the server thread. Empty when there is no mesh world. */
    List<Peer> peers();

    /** Not available yet in v0: always false. */
    boolean requestTakeover();

    MeshAuxStore aux();

    MeshStateRegistry state();

    MeshRevisionBrowser revisions();

    void addListener(MeshListener listener);

    void removeListener(MeshListener listener);
}
