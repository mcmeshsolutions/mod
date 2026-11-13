package dev.mcmesh.core;

/** Fires once when the average tick time stays above THRESHOLD_MS for DWELL_MS, then stays quiet for COOLDOWN_MS. */
public final class TickDegradation {
    public static final double THRESHOLD_MS = 45.0;
    static final long DWELL_MS = 10_000;
    static final long COOLDOWN_MS = 10 * 60_000;

    private long aboveSince = -1;
    private long quietUntil = -1;

    /** Timestamps must not go backwards. True means "start a profile now". */
    public boolean update(double tickMs, long nowMs) {
        if (tickMs <= THRESHOLD_MS) {
            aboveSince = -1;
            return false;
        }
        if (aboveSince < 0) {
            aboveSince = nowMs;
        }
        if (nowMs - aboveSince < DWELL_MS || nowMs < quietUntil) {
            return false;
        }
        quietUntil = nowMs + COOLDOWN_MS;
        aboveSince = -1;
        return true;
    }

    public void reset() {
        aboveSince = -1;
        quietUntil = -1;
    }
}
