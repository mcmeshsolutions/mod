package dev.mcmesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** A marker file that exists only while hosting runs; finding it at the next start means the last run did not stop cleanly. */
public final class CrashMarker {
    private CrashMarker() { }

    /** Writes the marker and returns the start time of the unclean previous run, or -1 when the last run ended cleanly. */
    public static long begin(Path marker, long nowMs) {
        long previous = -1;
        try {
            if (Files.exists(marker)) {
                previous = Long.parseLong(Files.readString(marker).trim());
            }
        } catch (Exception e) {
            previous = 0;
        }
        try {
            Files.writeString(marker, Long.toString(nowMs));
        } catch (IOException ignored) { }
        return previous;
    }

    public static void end(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) { }
    }

    /** Newest .txt file in the crash report folder modified at or after the given time, or null. */
    public static Path newestReportSince(Path dir, long sinceMs) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> p.toFile().lastModified() >= sinceMs)
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** The top of a report, where the exception is; capped so it fits the gateway's size limit with room to spare. */
    public static String head(Path report, int maxChars) throws IOException {
        String s = Files.readString(report, StandardCharsets.UTF_8);
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
