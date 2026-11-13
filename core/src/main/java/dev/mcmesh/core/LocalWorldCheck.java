package dev.mcmesh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Decides whether a local world folder can be trusted to equal the revision its sync marker names. */
public final class LocalWorldCheck {
    /** File timestamps on some systems are coarse; a region written within this window of the marker still counts as before it. */
    static final long SLACK_MS = 2_000;

    private LocalWorldCheck() { }

    /**
     * True when no region, entity or poi file was modified after the marker was last written. The marker is rewritten after
     * every commit and after a download, so a newer file means local changes that no revision contains.
     */
    public static boolean untouchedSince(Path root, Path marker) {
        try {
            long mark = Files.getLastModifiedTime(marker).toMillis();
            try (Stream<Path> files = Files.walk(root)) {
                return files.filter(f -> f.getFileName().toString().endsWith(".mca"))
                        .noneMatch(f -> f.toFile().lastModified() > mark + SLACK_MS);
            }
        } catch (IOException e) {
            return false;
        }
    }
}
