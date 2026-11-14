package dev.mcmesh.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides how carefully to boot a world after the last run of it crashed. One crash is bad luck and changes nothing.
 * Repeated crashes close together mean something about this world or this machine breaks hosting, so the newest and
 * most intricate paths are switched off for the next attempt rather than crashing the same way again.
 *
 * <p>It deliberately never rolls the world back on its own: an older revision loses the players' recent progress,
 * and only the owner can weigh that against a crash. {@link #recoveryHint} is what to tell them instead.
 */
public final class SafeMode {
    /** Crashes further apart than this are unrelated, so the streak starts over. */
    public static final long WINDOW_MS = 30 * 60 * 1000L;
    /** A run that lasted this long counts as healthy, whatever happens afterwards. */
    public static final long HEALTHY_MS = 3 * 60 * 1000L;

    public enum Level {
        /** Nothing wrong, or a single crash: host normally. */
        NORMAL,
        /** Two crashes in a row: eager download and no streaming, so the world takes the simplest path onto disk. */
        CAREFUL,
        /** Three or more: as above, and the owner is told the world may need a revert. */
        LAST_RESORT
    }

    public record State(int crashes, long lastCrashMs) {
        public static final State FRESH = new State(0, 0);
    }

    public record Decision(Level level, int crashes, boolean lazyBootstrap, boolean streaming, String reason) {
        public boolean careful() {
            return level != Level.NORMAL;
        }
    }

    private SafeMode() { }

    /**
     * @param previousUnclean start time of the previous run when it did not stop cleanly, else -1 (what
     *                        {@link CrashMarker#begin} returns)
     */
    public static Decision decide(State state, long previousUnclean, long nowMs) {
        if (previousUnclean < 0) {
            return new Decision(Level.NORMAL, 0, true, true, "the last run stopped cleanly");
        }
        int crashes = withinWindow(state, nowMs) ? state.crashes() + 1 : 1;
        if (crashes <= 1) {
            return new Decision(Level.NORMAL, crashes, true, true,
                    "the last run of this world did not stop cleanly, hosting normally once more");
        }
        Level level = crashes >= 3 ? Level.LAST_RESORT : Level.CAREFUL;
        return new Decision(level, crashes, false, false,
                crashes + " unclean stops in a row: downloading the whole world before it opens and not streaming chunks while it runs");
    }

    private static boolean withinWindow(State state, long nowMs) {
        return state.crashes() > 0 && nowMs - state.lastCrashMs() <= WINDOW_MS;
    }

    /** The state to store after {@link #decide}, so the next boot can tell a streak from a one-off. */
    public static State next(Decision decision, long nowMs) {
        return decision.crashes() == 0 ? State.FRESH : new State(decision.crashes(), nowMs);
    }

    /** Called once a run has lasted {@link #HEALTHY_MS}: the streak is over. */
    public static State healthy() {
        return State.FRESH;
    }

    /** What to tell the owner when even a careful boot keeps dying. Never acted on automatically. */
    public static String recoveryHint(String world, java.util.List<String> newestFirst) {
        if (newestFirst.size() < 2) {
            return "World " + world + " keeps crashing and has no earlier revision to go back to.";
        }
        return "World " + world + " has crashed three times in a row. If it keeps happening, the newest revision may be"
                + " the cause: stop hosting and revert it to " + newestFirst.get(1) + " (the one before "
                + newestFirst.get(0) + "). Nothing is reverted automatically, because that would undo whatever the"
                + " players did in the newest revision.";
    }

    public static State read(Path file) {
        try {
            if (!Files.exists(file)) {
                return State.FRESH;
            }
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return new State(o.get("crashes").getAsInt(), o.get("lastCrashMs").getAsLong());
        } catch (Exception e) {
            return State.FRESH;
        }
    }

    public static void write(Path file, State state) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("crashes", state.crashes());
        o.addProperty("lastCrashMs", state.lastCrashMs());
        Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
    }
}
