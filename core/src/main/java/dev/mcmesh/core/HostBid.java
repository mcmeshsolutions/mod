package dev.mcmesh.core;

/** Folds the measured uplink into the hosting bid: the fastest CPU on the worst line is still a bad host. */
public final class HostBid {
    /** at or above this the uplink is no longer what limits hosting, so it stops moving the bid */
    static final double GOOD_UPLINK_MBPS = 10.0;
    /** even an unusable link keeps a tenth of the bid, so a world with no other candidate still gets hosted */
    static final double FLOOR = 0.1;

    private HostBid() { }

    /** `uploadMbps` below zero means unknown and never costs a machine anything. */
    public static double score(double cpuScore, double uploadMbps) {
        if (uploadMbps < 0 || uploadMbps >= GOOD_UPLINK_MBPS || cpuScore <= 0) {
            return cpuScore;
        }
        return cpuScore * Math.max(FLOOR, uploadMbps / GOOD_UPLINK_MBPS);
    }
}
