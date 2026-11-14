package dev.mcmesh.core;

/**
 * Finds logind's PrepareForSleep / PrepareForShutdown announcement in the output of a D-Bus monitor.
 * Both signals are sent twice, true before the machine goes down and false once it is back, so the argument decides.
 * Two output shapes are understood: dbus-monitor puts the argument on the line after the header, gdbus puts it on the
 * same line. Feed every line in order and act on the first {@code true}.
 */
public final class SleepSignal {
    public static final String LOGIND_PATH = "/org/freedesktop/login1";
    public static final String LOGIND_IFACE = "org.freedesktop.login1.Manager";
    /** dbus-monitor match rule: broadcast signals need no privileges, unlike monitoring the whole bus. */
    public static final String MATCH = "type='signal',interface='" + LOGIND_IFACE + "'";

    private boolean argumentPending;

    /** True exactly for the line that completes a power-down announcement. */
    public boolean accept(String line) {
        if (line == null) {
            return false;
        }
        boolean header = line.contains("PrepareForSleep") || line.contains("PrepareForShutdown");
        if (header) {
            // gdbus prints "...PrepareForSleep (true,)"; dbus-monitor's header carries no argument
            if (hasTrue(line)) {
                argumentPending = false;
                return true;
            }
            argumentPending = !line.contains("false");
            return false;
        }
        if (!argumentPending) {
            return false;
        }
        if (hasTrue(line)) {
            argumentPending = false;
            return true;
        }
        // any other signal header means our argument never came; a plain "boolean false" ends it too
        if (line.startsWith("signal ") || line.startsWith("method ") || line.contains("false")) {
            argumentPending = false;
        }
        return false;
    }

    private static boolean hasTrue(String line) {
        return line.contains("true") && !line.contains("false");
    }
}
