package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatBufferTest {
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void keepsOnlyTheNewestLinesInOrder() {
        ChatBuffer b = new ChatBuffer(3);
        for (int i = 1; i <= 5; i++) {
            b.add(T, "p", "m" + i);
        }
        assertEquals("2026-01-01T00:00:00Z <p> m3\n2026-01-01T00:00:00Z <p> m4\n2026-01-01T00:00:00Z <p> m5\n", b.last(10, 1000));
        assertEquals("2026-01-01T00:00:00Z <p> m5\n", b.last(1, 1000));
    }

    @Test
    void theCharacterLimitDropsTheOldestFirst() {
        ChatBuffer b = new ChatBuffer(10);
        b.add(T, "p", "old");
        b.add(T, "p", "new");
        String one = "2026-01-01T00:00:00Z <p> new\n";
        assertEquals(one, b.last(10, one.length() + 3));
    }

    @Test
    void zeroCapacityKeepsNothing() {
        ChatBuffer b = new ChatBuffer(0);
        b.add(T, "p", "x");
        assertEquals("", b.last(5, 100));
    }
}
