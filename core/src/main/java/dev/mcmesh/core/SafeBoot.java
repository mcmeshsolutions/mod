package dev.mcmesh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * After a hosting run that did not stop cleanly the local world folder may be half written. Marking it as rejected
 * makes the next sync set it aside (nothing is deleted) and rebuild the world from the gateway.
 */
public final class SafeBoot {
    private SafeBoot() { }

    /**
     * @param gatewayHasData false when the revision to host holds no chunks (an empty world, or one whose terrain was never uploaded): the local folder is then the only copy and is kept
     * @return true when the local world was marked for a rebuild
     */
    public static boolean prepare(Path crashMarker, Path worldRoot, boolean gatewayHasData) throws IOException {
        if (!gatewayHasData || CrashMarker.peek(crashMarker) < 0 || !Files.isDirectory(worldRoot)) {
            return false;
        }
        try (Stream<Path> files = Files.list(worldRoot)) {
            if (files.findAny().isEmpty()) {
                return false;
            }
        }
        RejectedCopy.mark(worldRoot, "unclean-stop");
        return true;
    }
}
