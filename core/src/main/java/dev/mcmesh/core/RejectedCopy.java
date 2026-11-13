package dev.mcmesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Keeps a world the gateway refused: the next sync would otherwise wipe it, or host it again as if it were the synced revision. */
public final class RejectedCopy {
    public static final String MARKER = "mcmesh.rejected";
    private static final String SYNC_MARKER = "mcmesh.rev";

    private RejectedCopy() { }

    public static void mark(Path root, String baseRev) throws IOException {
        Files.writeString(root.resolve(MARKER), baseRev, StandardCharsets.UTF_8);
    }

    public static boolean marked(Path root) {
        return Files.exists(root.resolve(MARKER));
    }

    /**
     * Moves a marked world folder to a sibling and leaves an empty folder behind. Returns the sibling, or null when the world is not marked.
     * Call only while nothing has the world open.
     */
    public static Path setAside(Path root) throws IOException {
        if (!marked(root)) {
            return null;
        }
        String base = root.getFileName() + ".rejected-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path kept = root.resolveSibling(base);
        for (int n = 2; Files.exists(kept); n++) {
            kept = root.resolveSibling(base + "-" + n);
        }
        // one rename, so a crash cannot leave half of the world in each folder
        Files.move(root, kept, StandardCopyOption.ATOMIC_MOVE);
        Files.createDirectories(root);
        Files.deleteIfExists(kept.resolve(MARKER));
        Files.deleteIfExists(kept.resolve(SYNC_MARKER));
        return kept;
    }
}
