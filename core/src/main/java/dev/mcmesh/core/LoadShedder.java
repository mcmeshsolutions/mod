package dev.mcmesh.core;

/**
 * Turns a stream of TPS samples into a shedding level from 0 to 3. A level is entered only after TPS stayed below its
 * threshold for ENTER_DWELL_MS and left only after TPS stayed clearly above it for EXIT_DWELL_MS, so a lag spike
 * or a brief recovery never flips it.
 */
public final class LoadShedder {
    public static final int MAX_LEVEL = 3;
    static final double[] ENTER_BELOW_TPS = {17.5, 14.5, 11.5};
    static final double EXIT_MARGIN = 1.5;
    static final long ENTER_DWELL_MS = 30_000;
    static final long EXIT_DWELL_MS = 90_000;

    private int level;
    private long belowSince = -1;
    private long aboveSince = -1;

    public int level() {
        return level;
    }

    /** Feeds one sample and returns the level to apply now. Timestamps must not go backwards. */
    public int update(double tps, long nowMs) {
        boolean worse = level < MAX_LEVEL && tps < ENTER_BELOW_TPS[level];
        boolean better = level > 0 && tps >= ENTER_BELOW_TPS[level - 1] + EXIT_MARGIN;
        belowSince = worse ? (belowSince < 0 ? nowMs : belowSince) : -1;
        aboveSince = better ? (aboveSince < 0 ? nowMs : aboveSince) : -1;
        if (worse && nowMs - belowSince >= ENTER_DWELL_MS) {
            level++;
            belowSince = nowMs;
            aboveSince = -1;
        } else if (better && nowMs - aboveSince >= EXIT_DWELL_MS) {
            level--;
            belowSince = -1;
            aboveSince = nowMs;
        }
        return level;
    }

    public void reset() {
        level = 0;
        belowSince = -1;
        aboveSince = -1;
    }
}
