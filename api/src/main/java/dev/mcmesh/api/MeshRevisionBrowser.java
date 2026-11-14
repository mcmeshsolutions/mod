package dev.mcmesh.api;

import java.util.List;
import java.util.Optional;

/** Reads history from the gateway without booting a world. These calls block on the network: never call them on the server thread. */
public interface MeshRevisionBrowser {
    /** Newest first. */
    List<String> revisionIds(int limit);

    /** An aux file (for example {@code mesh/aux/world_global/mymod/key}) as it was in that revision. */
    Optional<byte[]> readAux(String revisionId, String path);
}
