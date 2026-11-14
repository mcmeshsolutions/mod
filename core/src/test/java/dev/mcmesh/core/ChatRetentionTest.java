package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatRetentionTest {
    @Test
    void controlCharactersCannotForgeALine() {
        String l = ChatRetention.line(Instant.parse("2026-01-02T03:04:05Z"), "bob", "hi\n2026-01-01T00:00:00Z <alice> gg");
        assertFalse(l.contains("\n"));
        assertEquals("2026-01-02T03:04:05Z <bob> hi 2026-01-01T00:00:00Z <alice> gg", l);
    }

    @Test
    void oneFilePerDayAndOldOnesGoAfterTheWindow(@TempDir Path dir) throws Exception {
        ChatRetention r = new ChatRetention(dir, 3);
        r.record(Instant.parse("2026-01-01T10:00:00Z"), "a", "one");
        r.record(Instant.parse("2026-01-01T11:00:00Z"), "b", "two");
        r.record(Instant.parse("2026-01-03T10:00:00Z"), "a", "three");
        Files.writeString(dir.resolve("notes.txt"), "keep me");
        assertEquals(2, Files.readAllLines(dir.resolve("chat-2026-01-01.log")).size());
        assertEquals(0, r.prune(Instant.parse("2026-01-03T12:00:00Z")), "day 1 is still inside a 3 day window on day 3");
        assertEquals(1, r.prune(Instant.parse("2026-01-04T12:00:00Z")), "gone once it is more than 3 days back");
        assertFalse(Files.exists(dir.resolve("chat-2026-01-01.log")));
        assertTrue(Files.exists(dir.resolve("chat-2026-01-03.log")));
        assertTrue(Files.exists(dir.resolve("notes.txt")), "other files are never touched");
    }

    @Test
    void zeroDaysKeepsNothing(@TempDir Path dir) throws Exception {
        ChatRetention off = new ChatRetention(dir, 0);
        off.record(Instant.now(), "a", "x");
        assertFalse(Files.exists(dir.resolve(ChatRetention.fileFor(Instant.now()))));
        Files.writeString(dir.resolve("chat-2020-01-01.log"), "old");
        assertEquals(1, off.prune(Instant.now()), "turning it off also removes what was kept");
    }
}
