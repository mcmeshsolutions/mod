package dev.mcmesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;

/**
 * One text file per UTC day, kept for a limited number of days and then deleted. Meant for the owner to read when a report comes in.
 * Lines are plain text; control characters in a message are replaced so a message cannot forge another line.
 */
public final class ChatRetention {
    private final Path dir;
    private final int days;

    public ChatRetention(Path dir, int days) {
        this.dir = dir;
        this.days = days;
    }

    public static String line(Instant at, String player, String message) {
        return at + " <" + clean(player) + "> " + clean(message);
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return b.toString();
    }

    static String fileFor(Instant at) {
        return "chat-" + LocalDate.ofInstant(at, ZoneOffset.UTC) + ".log";
    }

    public synchronized void record(Instant at, String player, String message) throws IOException {
        if (days <= 0) {
            return;
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileFor(at)), line(at, player, message) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Deletes day files older than the retention window; returns how many went. Files with other names are left alone. */
    public synchronized int prune(Instant now) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        LocalDate oldest = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(Math.max(days, 0) - 1L);
        int gone = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (!n.matches("chat-\\d{4}-\\d{2}-\\d{2}\\.log")) {
                    continue;
                }
                LocalDate d = LocalDate.parse(n.substring(5, 15));
                if (days <= 0 || d.isBefore(oldest)) {
                    Files.delete(f);
                    gone++;
                }
            }
        }
        return gone;
    }
}
