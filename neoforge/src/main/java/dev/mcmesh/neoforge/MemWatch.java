package dev.mcmesh.neoforge;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Cheap early warning before the kernel's OOM killer takes the host down. */
final class MemWatch {
    private static final long LOW_AVAILABLE_MB = 1536;

    private MemWatch() { }

    /** Null when fine, else a short reason. */
    static String pressure() {
        long mb = availableMb();
        if (mb >= 0 && mb < LOW_AVAILABLE_MB) {
            return mb + " MB of system memory left";
        }
        Runtime r = Runtime.getRuntime();
        long max = r.maxMemory();
        long used = r.totalMemory() - r.freeMemory();
        if (max > 0 && used > max * 0.96) {
            return "Java heap " + used * 100 / max + "% full";
        }
        return null;
    }

    static long availableMb() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (l.startsWith("MemAvailable:")) {
                    return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
                }
            }
        } catch (Exception ignored) { }
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getFreeMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) { }
        return -1;
    }
}
