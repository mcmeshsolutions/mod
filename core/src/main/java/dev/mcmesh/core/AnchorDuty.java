package dev.mcmesh.core;

/**
 * When an anchor should be running the world. An anchor that takes the lease the moment it is free keeps a modded
 * world loaded around the clock for nobody; with wake on join it waits until someone actually tries to play, and
 * gives the world back once everyone has left.
 */
public final class AnchorDuty {
    /** Demand older than this is somebody who gave up long ago, not a player waiting now. */
    public static final long WANTED_FRESH_S = 180;

    private AnchorDuty() { }

    /**
     * @param wantedSecondsAgo from {@link GatewayClient#wantedSecondsAgo}, -1 when nobody has asked
     * @return true when the anchor should take the host lease now
     */
    public static boolean shouldWake(boolean wakeOnJoin, long wantedSecondsAgo) {
        if (!wakeOnJoin) {
            return true;
        }
        return wantedSecondsAgo >= 0 && wantedSecondsAgo <= WANTED_FRESH_S;
    }

    /**
     * Whether to hand the world back. Only ever true with wake on join on, and never while anyone is playing.
     *
     * @param emptyForMs how long the server has had no players
     * @param idleMinutes configured grace, 0 or less switches releasing off
     */
    public static boolean shouldRelease(boolean wakeOnJoin, int players, long emptyForMs, int idleMinutes) {
        if (!wakeOnJoin || idleMinutes <= 0 || players > 0) {
            return false;
        }
        return emptyForMs >= idleMinutes * 60_000L;
    }
}
