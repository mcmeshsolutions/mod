package dev.mcmesh.core;

import java.util.Random;

/** Times one upload to the gateway. A rough number is enough to tell a usable uplink from a bad one. */
public final class BandwidthProbe {
    static final int PROBE_BYTES = 512 * 1024;

    /** The last measurement anything in this JVM made, or -1 when nothing has measured yet. Read by {@link HostBid}. */
    public static volatile double lastMbps = -1;

    private BandwidthProbe() { }

    /** Megabits per second, or -1 when the probe failed or the clock gave nothing to divide by. */
    public static double measure(GatewayClient gw) {
        byte[] body = new byte[PROBE_BYTES];
        new Random().nextBytes(body);
        try {
            long start = System.nanoTime();
            gw.bandwidthProbe(body);
            lastMbps = mbps(PROBE_BYTES, System.nanoTime() - start);
        } catch (Exception e) {
            lastMbps = -1;
        }
        return lastMbps;
    }

    static double mbps(long bytes, long nanos) {
        return nanos <= 0 ? -1 : bytes * 8.0 / 1_000_000.0 / (nanos / 1_000_000_000.0);
    }
}
