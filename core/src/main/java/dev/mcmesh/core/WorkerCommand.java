package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the command line this game was started with into the one for a hidden dedicated server that generates worldgen tiles:
 * same Java, same libraries and module path, server launch target, its own game folder, no account or window arguments.
 * Pure on purpose: it is the part that can be checked without a launcher.
 */
public final class WorkerCommand {
    /** Program arguments that only a client has, each followed by a value. */
    private static final Set<String> CLIENT_VALUE_ARGS = Set.of("--username", "--uuid", "--accessToken", "--clientId", "--xuid", "--userType", "--versionType",
            "--width", "--height", "--assetsDir", "--assetIndex", "--version", "--quickPlayPath", "--quickPlaySingleplayer", "--quickPlayMultiplayer",
            "--quickPlayRealms", "--server", "--port", "--proxyHost", "--proxyPort", "--proxyUser", "--proxyPass");
    private static final Set<String> CLIENT_FLAGS = Set.of("--demo", "--fullscreen", "--checkGlErrors", "--disableMultiplayer", "--disableChat");

    private WorkerCommand() { }

    /**
     * @param args the arguments of this JVM as {@code ProcessHandle.current().info().arguments()} gives them (JVM options, then the main
     *     class, then the program arguments); empty on platforms that do not report them, which the caller must treat as "cannot start one"
     * @return the arguments for the worker, or null when this is not a command line that can be turned into a server (no launch target)
     */
    public static List<String> rewrite(List<String> args, String gameDir, String heap, List<String> extraProperties) {
        if (!args.contains("--launchTarget")) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.addAll(extraProperties);
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            boolean hasNext = i + 1 < args.size();
            if (a.startsWith("-Xmx") || a.startsWith("-Xms") || a.equals("-XstartOnFirstThread")) {
                continue;
            }
            if (CLIENT_FLAGS.contains(a)) {
                continue;
            }
            if (CLIENT_VALUE_ARGS.contains(a) && hasNext) {
                i++;
                continue;
            }
            if (a.equals("--gameDir") && hasNext) {
                out.add(a);
                out.add(gameDir);
                i++;
                continue;
            }
            if (a.equals("--launchTarget") && hasNext) {
                out.add(a);
                out.add(args.get(i + 1).replace("client", "server"));
                i++;
                continue;
            }
            out.add(a);
        }
        out.add(0, "-Xmx" + heap);
        out.add("nogui");
        return out;
    }
}
