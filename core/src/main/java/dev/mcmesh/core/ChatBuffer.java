package dev.mcmesh.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

/** The last few chat lines, in memory only, so a player who files a report can choose to attach them. Never written to disk. */
public final class ChatBuffer {
    private final int capacity;
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    public ChatBuffer(int capacity) {
        this.capacity = Math.max(0, capacity);
    }

    public synchronized void add(Instant at, String player, String message) {
        if (capacity == 0) {
            return;
        }
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(ChatRetention.line(at, player, message));
    }

    /** The newest `n` lines, oldest first, cut to at most `maxChars` characters from the newest end. */
    public synchronized String last(int n, int maxChars) {
        List<String> all = List.copyOf(lines);
        List<String> tail = all.subList(Math.max(0, all.size() - n), all.size());
        StringBuilder b = new StringBuilder();
        for (int i = tail.size() - 1; i >= 0; i--) {
            String l = tail.get(i);
            if (b.length() + l.length() + 1 > maxChars) {
                break;
            }
            b.insert(0, l + "\n");
        }
        return b.toString();
    }
}
