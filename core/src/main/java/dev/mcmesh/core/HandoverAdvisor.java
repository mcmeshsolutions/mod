package dev.mcmesh.core;

/** Decides from the host machine's battery whether it should keep hosting. Unknown input never triggers anything. */
public final class HandoverAdvisor {
    public enum Decision { STAY, PREFER_HANDOVER, HAND_OVER_NOW }

    /** percent 0 to 100; only a battery that is actually discharging counts as a problem. */
    public record Battery(int percent, boolean discharging) { }

    static final int NOW_AT_OR_BELOW = 8;
    static final int PREFER_AT_OR_BELOW = 20;

    private HandoverAdvisor() { }

    public static Decision decide(Battery b) {
        if (b == null || !b.discharging() || b.percent() < 0 || b.percent() > 100) {
            return Decision.STAY;
        }
        if (b.percent() <= NOW_AT_OR_BELOW) {
            return Decision.HAND_OVER_NOW;
        }
        return b.percent() <= PREFER_AT_OR_BELOW ? Decision.PREFER_HANDOVER : Decision.STAY;
    }
}
