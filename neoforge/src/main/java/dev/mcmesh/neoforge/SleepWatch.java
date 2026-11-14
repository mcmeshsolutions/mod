package dev.mcmesh.neoforge;

import dev.mcmesh.core.SleepSignal;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suspend and shutdown are the one failure a host sees coming. While hosting we hold a logind delay inhibitor (a few
 * seconds of grace, `InhibitDelayMaxSec`) and listen for PrepareForSleep, so the world is flushed and handed over
 * before the lid closes instead of after the gateway has fenced a host that went silent mid-save.
 * Needs systemd plus dbus-monitor or gdbus; without them it stays off and nothing changes.
 */
final class SleepWatch {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static volatile Process monitor;
    private static volatile Process inhibitor;
    private static volatile boolean started;

    private SleepWatch() { }

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("mcmesh.sleepwatch", "on"));
    }

    /** Only true while both halves are up; an inhibitor alone would delay a suspend we never hear about. */
    static boolean watching() {
        return monitor != null && monitor.isAlive() && inhibitor != null && inhibitor.isAlive();
    }

    /**
     * dbus-monitor is tried first: it needs no privileges for broadcast signals (it falls back to eavesdropping when
     * the bus refuses BecomeMonitor, which it does for a normal user), and it ships with dbus itself.
     */
    private static List<String> monitorCommand() {
        if (which("dbus-monitor")) {
            return List.of("dbus-monitor", "--system", SleepSignal.MATCH);
        }
        if (which("gdbus")) {
            return List.of("gdbus", "monitor", "--system", "--dest", "org.freedesktop.login1",
                    "--object-path", SleepSignal.LOGIND_PATH);
        }
        return List.of();
    }

    private static boolean which(String exe) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, exe))) {
                return true;
            }
        }
        return false;
    }

    /** Idempotent, so the hosting tick hook can just call it. `onPowerDown` runs on a virtual thread, not the server thread. */
    static synchronized void start(Runnable onPowerDown) {
        if (started || !enabled() || !Files.isDirectory(Path.of("/run/systemd/system"))) {
            return;
        }
        List<String> cmd = monitorCommand();
        if (cmd.isEmpty() || !which("systemd-inhibit")) {
            LOG.info("suspend watch off: need systemd-inhibit and dbus-monitor or gdbus");
            started = true;
            return;
        }
        started = true;
        try {
            inhibitor = new ProcessBuilder("systemd-inhibit", "--what=sleep:shutdown", "--mode=delay",
                    "--who=mcmesh", "--why=Saving the hosted world", "cat")
                    .redirectErrorStream(true).start();
            monitor = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (Exception e) {
            LOG.info("suspend watch off: {}", e.toString());
            stop();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(SleepWatch::stop, "mesh-sleepwatch-stop"));
        Thread.ofVirtual().name("mesh-sleepwatch").start(() -> read(monitor, onPowerDown));
        LOG.info("suspend watch armed with {} (logind delay inhibitor held while hosting)", cmd.get(0));
    }

    private static void read(Process p, Runnable onPowerDown) {
        SleepSignal signal = new SleepSignal();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!signal.accept(line)) {
                    continue;
                }
                LOG.warn("the host machine is suspending or shutting down, flushing the world now");
                try {
                    onPowerDown.run();
                } catch (Exception e) {
                    LOG.warn("power-down handler failed: {}", e.toString());
                } finally {
                    // release the lock as soon as we are done; holding it to the timeout only delays the user's suspend
                    release(inhibitor);
                    inhibitor = null;
                }
                return;
            }
        } catch (Exception e) {
            LOG.debug("suspend watch reader stopped: {}", e.toString());
        }
    }

    static synchronized void stop() {
        release(monitor);
        release(inhibitor);
        monitor = null;
        inhibitor = null;
        started = false;
    }

    private static void release(Process p) {
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            // systemd-inhibit holds the lock until its child is gone, so make sure the child goes too
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
