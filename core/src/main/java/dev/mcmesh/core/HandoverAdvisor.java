package dev.mcmesh.core;

/** Decides from the host machine's battery whether it should keep hosting. Unknown input never triggers anything. */
public final class HandoverAdvisor {
    public enum Decision { STAY, PREFER_HANDOVER, HAND_OVER_NOW }

    /** percent 0 to 100; only a battery that is actually discharging counts as a problem. */
    public record Battery(int percent, boolean discharging) { }

    static final double POOR_UPLINK_MBPS = 2.0;
    static final double UNUSABLE_UPLINK_MBPS = 0.5;
    static final int NOW_AT_OR_BELOW = 8;
    static final int PREFER_AT_OR_BELOW = 20;

    private HandoverAdvisor() { }

    public static Decision decide(Battery b) {
        return decide(b, -1);
    }

    /** `uploadMbps` below zero means unknown. The worse of the two verdicts wins. */
    public static Decision decide(Battery b, double uploadMbps) {
        return decide(b, uploadMbps, SleepProbe.Lid.UNKNOWN);
    }

    /** The worst of battery, uplink and lid wins. */
    public static Decision decide(Battery b, double uploadMbps, SleepProbe.Lid lid) {
        Decision battery = batteryVerdict(b);
        Decision link = uploadMbps < 0 ? Decision.STAY : uploadMbps < UNUSABLE_UPLINK_MBPS ? Decision.HAND_OVER_NOW : uploadMbps < POOR_UPLINK_MBPS ? Decision.PREFER_HANDOVER : Decision.STAY;
        Decision worst = battery.ordinal() >= link.ordinal() ? battery : link;
        Decision sleep = sleepVerdict(b, lid);
        return worst.ordinal() >= sleep.ordinal() ? worst : sleep;
    }

    /**
     * A closed lid on battery means the machine is about to suspend and take the world with it. A closed lid while
     * charging is the normal docked desktop setup and must not move hosting anywhere.
     */
    private static Decision sleepVerdict(Battery b, SleepProbe.Lid lid) {
        if (lid != SleepProbe.Lid.CLOSED || b == null || !b.discharging()) {
            return Decision.STAY;
        }
        return Decision.HAND_OVER_NOW;
    }

    private static Decision batteryVerdict(Battery b) {
        if (b == null || !b.discharging() || b.percent() < 0 || b.percent() > 100) {
            return Decision.STAY;
        }
        if (b.percent() <= NOW_AT_OR_BELOW) {
            return Decision.HAND_OVER_NOW;
        }
        return b.percent() <= PREFER_AT_OR_BELOW ? Decision.PREFER_HANDOVER : Decision.STAY;
    }
}
