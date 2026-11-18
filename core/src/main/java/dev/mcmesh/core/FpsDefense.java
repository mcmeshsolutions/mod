package dev.mcmesh.core;

/**
 * Monitors host client FPS to shed simulation and rendering burden when the host's local framerate drops below playable thresholds.
 */
public final class FpsDefense {
    public static final long ENTER_DWELL_MS = 10_000;
    public static final long EXIT_DWELL_MS = 20_000;
    public static final int EXIT_MARGIN_FPS = 5;

    private int level;
    private long belowSince = -1;
    private long aboveSince = -1;

    public int level() {
        return level;
    }

    public int update(int currentFps, int targetMinFps, long nowMs) {
        if (targetMinFps <= 0 || currentFps < 0) {
            return level;
        }
        boolean worse = level < 2 && currentFps < targetMinFps;
        boolean better = level > 0 && currentFps >= (targetMinFps + EXIT_MARGIN_FPS);

        belowSince = worse ? (belowSince < 0 ? nowMs : belowSince) : -1;
        aboveSince = better ? (aboveSince < 0 ? nowMs : aboveSince) : -1;

        if (worse && (nowMs - belowSince >= ENTER_DWELL_MS)) {
            level++;
            belowSince = nowMs;
            aboveSince = -1;
        } else if (better && (nowMs - aboveSince >= EXIT_DWELL_MS)) {
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
