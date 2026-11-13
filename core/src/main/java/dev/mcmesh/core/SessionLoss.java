package dev.mcmesh.core;

/** Decides whether a dropped connection should be hidden behind the mod's own reconnect screen. */
public final class SessionLoss {
    /** The player must have been in the world this recently for a drop to count as the session being lost. */
    public static final long ALIVE_WINDOW_MS = 15_000;

    private SessionLoss() { }

    /**
     * @param guestAliveAt last time (epoch ms) the player was in a mesh world as a guest, 0 if never
     * @param switching true while a host switch is already under way
     * @param standbyRunning true while this machine is waiting to take the world over
     */
    public static boolean holdDisconnectScreen(long guestAliveAt, long now, boolean switching, boolean standbyRunning) {
        if (switching || standbyRunning) {
            return true;
        }
        return guestAliveAt > 0 && now - guestAliveAt <= ALIVE_WINDOW_MS;
    }
}
