package dev.mcmesh.neoforge;

import com.google.gson.JsonObject;

/** Short single-thread benchmark plus machine facts, reported to the gateway on every join. */
public final class HostBench {
    private static JsonObject cached;

    private HostBench() {}

    public static synchronized JsonObject get() {
        if (cached == null) {
            JsonObject o = new JsonObject();
            spin(150);
            double best = 0;
            for (int i = 0; i < 4; i++) {
                best = Math.max(best, spin(300));
            }
            o.addProperty("cpu", Math.round(best));
            Runtime rt = Runtime.getRuntime();
            o.addProperty("cores", rt.availableProcessors());
            o.addProperty("heap_mb", rt.maxMemory() >> 20);
            o.addProperty("os", System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""));
            cached = o;
        }
        return cached;
    }

    /** The number every bid uses: the CPU score, discounted by the last measured uplink (see HostBid). */
    public static double bidScore() {
        return dev.mcmesh.core.HostBid.score(get().get("cpu").getAsDouble(), dev.mcmesh.core.BandwidthProbe.lastMbps);
    }

    /** Million mixing steps per second. */
    private static double spin(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        long x = 0x9E3779B97F4A7C15L;
        long n = 0;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 100_000; i++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                x *= 0xD6E8FEB86659FD93L;
            }
            n += 100_000;
        }
        sink = x;
        return n / (ms * 1000.0);
    }

    private static volatile long sink;
}
