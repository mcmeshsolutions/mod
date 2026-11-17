package dev.mcmesh.core;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Picks a safe heap size for this machine before each launch and walks it back a step whenever the previous
 * run OOM'd or tripped a mod's safety valve, instead of always handing out the same fixed -Xmx. Pure JDK
 * (same reasons as {@link PackPatch}: this runs from the standalone updater, nothing from the modpack).
 */
final class JvmMemory {
    private JvmMemory() { }

    record Plan(long xmxMb, long xmsMb, boolean steppedDown, boolean grew) { }

    private static final long MIN_XMX_MB = 2048;
    private static final long RESERVE_FOR_OS_MB = 2560;
    private static final long STEP_DOWN_MB = 1024;
    private static final long GROW_STEP_MB = 512;
    private static final long ROUND_MB = 256;
    /** First-run guess: a fraction of total RAM, but capped well below it - plenty of real machines get
     *  unstable above a few GB of Minecraft heap for reasons that have nothing to do with how much RAM they
     *  have (GC pause length, a laptop's thermal/power limits, other apps). Growth from here is earned by
     *  several launches in a row with no trouble, not assumed up front. */
    private static final long BASELINE_PCT = 35;
    private static final long BASELINE_CAP_MB = 6144;
    /** Consecutive trouble-free launches needed before the heap is allowed to grow one more step. */
    private static final int STABLE_STREAK_FOR_GROWTH = 3;

    /** The mod (or any other part of the pack) touches this to ask for less memory next launch, no crash needed. */
    static final String SAFETY_VALVE_FLAG = "safety-valve-flag.txt";
    private static final String STATE_FILE = "jvm-mem.txt";

    /** Total physical RAM in MB, via the JDK's own OS bean (no shelling out, works the same on Windows and Linux). */
    static long totalPhysicalMb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long bytes = os.getTotalMemorySize();
            return bytes > 0 ? bytes / (1024 * 1024) : 8192;
        } catch (RuntimeException | LinkageError e) {
            return 8192;
        }
    }

    /** True if a crash report or JVM fatal-error log newer than sinceMs mentions running out of memory. */
    static boolean oomInLogsSince(Path gameDir, long sinceMs) {
        return mentionsOom(gameDir.resolve("crash-reports"), sinceMs, ".txt") || mentionsOom(gameDir, sinceMs, ".log");
    }

    private static boolean mentionsOom(Path dir, long sinceMs, String suffix) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                boolean candidate = name.endsWith(suffix) && (suffix.equals(".txt") || name.startsWith("hs_err_pid"));
                if (candidate && p.toFile().lastModified() >= sinceMs) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (text.toLowerCase(java.util.Locale.ROOT).contains("outofmemoryerror")) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            // an unreadable report is not evidence either way
        }
        return false;
    }

    /** True if something in the pack (not necessarily a crash) asked for less memory; consumes the flag. */
    static boolean consumeSafetyValveFlag(Path updateDir) {
        Path flag = updateDir.resolve(SAFETY_VALVE_FLAG);
        boolean tripped = Files.isRegularFile(flag);
        try {
            Files.deleteIfExists(flag);
        } catch (IOException ignored) {
            // leave it for next time rather than silently losing the signal
        }
        return tripped;
    }

    private static long round(long mb) {
        return Math.max(MIN_XMX_MB, (mb / ROUND_MB) * ROUND_MB);
    }

    /** Reads last launch's choice, decides this launch's, and persists it. Call once per pre-launch run. */
    static Plan plan(Path gameDir, Path updateDir, long nowMs) {
        Path state = updateDir.resolve(STATE_FILE);
        long lastXmx = 0;
        long lastLaunch = 0;
        int lastStreak = 0;
        try {
            for (String line : Files.readAllLines(state, StandardCharsets.UTF_8)) {
                String[] kv = line.split("=", 2);
                if (kv.length == 2) {
                    switch (kv[0]) {
                        case "xmxMb" -> lastXmx = Long.parseLong(kv[1]);
                        case "lastLaunchMs" -> lastLaunch = Long.parseLong(kv[1]);
                        case "stableStreak" -> lastStreak = Integer.parseInt(kv[1]);
                        default -> { }
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // no usable history: fall through to a fresh baseline below
        }

        long totalMb = totalPhysicalMb();
        long ceiling = round(totalMb - RESERVE_FOR_OS_MB);
        long baseline = round(Math.min(ceiling, Math.min(totalMb * BASELINE_PCT / 100, BASELINE_CAP_MB)));
        boolean trouble = lastLaunch > 0 && (oomInLogsSince(gameDir, lastLaunch) || consumeSafetyValveFlag(updateDir));

        long xmx;
        int streak;
        boolean grew = false;
        if (lastXmx <= 0) {
            xmx = baseline;
            streak = 0;
        } else if (trouble) {
            xmx = round(Math.max(MIN_XMX_MB, lastXmx - STEP_DOWN_MB));
            streak = 0;
        } else {
            streak = lastStreak + 1;
            if (streak >= STABLE_STREAK_FOR_GROWTH && lastXmx < ceiling) {
                xmx = round(Math.min(lastXmx + GROW_STEP_MB, ceiling));
                streak = 0;
                grew = xmx > lastXmx;
            } else {
                xmx = lastXmx;
            }
        }
        xmx = Math.min(xmx, ceiling);

        List<String> lines = new ArrayList<>();
        lines.add("xmxMb=" + xmx);
        lines.add("lastLaunchMs=" + nowMs);
        lines.add("stableStreak=" + streak);
        try {
            Path tmp = updateDir.resolve(STATE_FILE + ".tmp");
            Files.createDirectories(updateDir);
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, state, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // without persistence we just recompute the same baseline next time; not fatal
        }
        return new Plan(xmx, Math.min(xmx, 1024), trouble, grew);
    }

    /** G1 tuning good for Minecraft's allocation pattern; applied only when the instance has no custom JvmArgs yet. */
    static final String RECOMMENDED_GC_FLAGS = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 "
            + "-XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M "
            + "-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 "
            + "-XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 "
            + "-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1";

    /**
     * Rewrites only the memory (and, if untouched by the user, JVM arg) keys of a PrismLauncher/MultiMC
     * instance.cfg in place, line by line, so every other setting - including ones we don't know about -
     * survives untouched. The pre-launch command is a blocking step: Prism waits for it to exit before
     * building the actual java command line, so this is expected to take effect for the launch currently
     * in flight, not just the next one - that ordering has not been verified against Prism's source, though,
     * so treat "this launch" as a working assumption worth confirming with one real launch, not a guarantee.
     */
    static void applyToInstanceConfig(Path instanceCfg, Plan plan) {
        if (!Files.isRegularFile(instanceCfg)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(instanceCfg, StandardCharsets.UTF_8);
            boolean userHasCustomArgs = lines.stream().anyMatch(l -> l.equals("OverrideJavaArgs=true"));
            List<String> out = new ArrayList<>();
            java.util.Set<String> handled = new java.util.HashSet<>();
            // real instance.cfg files have more than just [General] (a [UI] section with column layouts is
            // common), so a key missing from [General] must be inserted before the next section header, not
            // appended past the end of the file where it would silently land inside a different section
            int generalEnd = lines.size();
            boolean inGeneral = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.equals("[General]")) {
                    inGeneral = true;
                } else if (inGeneral && line.startsWith("[")) {
                    generalEnd = i;
                    break;
                }
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String key = line.contains("=") ? line.substring(0, line.indexOf('=')) : "";
                switch (key) {
                    case "OverrideMemory" -> { out.add("OverrideMemory=true"); handled.add(key); }
                    case "MinMemAlloc" -> { out.add("MinMemAlloc=" + plan.xmsMb()); handled.add(key); }
                    case "MaxMemAlloc" -> { out.add("MaxMemAlloc=" + plan.xmxMb()); handled.add(key); }
                    case "OverrideJavaArgs" -> { out.add(userHasCustomArgs ? line : "OverrideJavaArgs=true"); handled.add(key); }
                    case "JvmArgs" -> { out.add(userHasCustomArgs ? line : "JvmArgs=" + RECOMMENDED_GC_FLAGS); handled.add(key); }
                    default -> out.add(line);
                }
                if (i == generalEnd - 1) {
                    if (!handled.contains("OverrideMemory")) {
                        out.add("OverrideMemory=true");
                    }
                    if (!handled.contains("MinMemAlloc")) {
                        out.add("MinMemAlloc=" + plan.xmsMb());
                    }
                    if (!handled.contains("MaxMemAlloc")) {
                        out.add("MaxMemAlloc=" + plan.xmxMb());
                    }
                    if (!userHasCustomArgs) {
                        if (!handled.contains("OverrideJavaArgs")) {
                            out.add("OverrideJavaArgs=true");
                        }
                        if (!handled.contains("JvmArgs")) {
                            out.add("JvmArgs=" + RECOMMENDED_GC_FLAGS);
                        }
                    }
                }
            }
            Path tmp = instanceCfg.resolveSibling("instance.cfg.tmp");
            Files.write(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, instanceCfg, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // leave the instance config alone rather than risk a half-written file
        }
    }
}
