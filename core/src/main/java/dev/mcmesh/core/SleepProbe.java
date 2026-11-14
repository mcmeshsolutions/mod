package dev.mcmesh.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Two signals about a host machine going to sleep. A laptop that suspends takes the world with it, and the guests
 * only find out when the lease expires, so a closed lid on battery is worth handing over for.
 */
public final class SleepProbe {
    /** Where the kernel exposes lid state on Linux; one directory per lid button. */
    private static final Path ACPI_LID = Path.of("/proc/acpi/button/lid");

    public enum Lid { OPEN, CLOSED, UNKNOWN }

    private SleepProbe() { }

    public static Lid read() {
        return lid(ACPI_LID);
    }

    /** UNKNOWN on anything that is not a Linux laptop, which must never trigger a handover. */
    public static Lid lid(Path acpiButtonDir) {
        if (!Files.isDirectory(acpiButtonDir)) {
            return Lid.UNKNOWN;
        }
        try (Stream<Path> buttons = Files.list(acpiButtonDir)) {
            for (Path b : buttons.sorted().toList()) {
                Path state = b.resolve("state");
                if (!Files.isReadable(state)) {
                    continue;
                }
                String text = Files.readString(state).toLowerCase(java.util.Locale.ROOT);
                if (text.contains("closed")) {
                    return Lid.CLOSED;
                }
                if (text.contains("open")) {
                    return Lid.OPEN;
                }
            }
        } catch (Exception e) {
            return Lid.UNKNOWN;
        }
        return Lid.UNKNOWN;
    }

    /**
     * Notices that the machine was suspended, by comparing the wall clock with a clock that does not run while
     * asleep. After a long sleep this host has almost certainly lost its lease, and its commits will be fenced off
     * by epoch, so the point is to say so in the log instead of leaving a confusing gap.
     */
    public static final class SleepWatch {
        /** Shorter gaps are ordinary scheduling delay, a long GC pause or a stopped debugger. */
        public static final long MIN_SLEEP_MS = 60_000;

        private long wallMs;
        private long monotonicMs;

        public SleepWatch(long wallMs, long monotonicMs) {
            this.wallMs = wallMs;
            this.monotonicMs = monotonicMs;
        }

        /** Milliseconds spent suspended since the last call, or 0. */
        public long check(long nowWallMs, long nowMonotonicMs) {
            long wall = nowWallMs - wallMs;
            long monotonic = nowMonotonicMs - monotonicMs;
            wallMs = nowWallMs;
            monotonicMs = nowMonotonicMs;
            long slept = wall - monotonic;
            return slept >= MIN_SLEEP_MS ? slept : 0;
        }
    }
}
