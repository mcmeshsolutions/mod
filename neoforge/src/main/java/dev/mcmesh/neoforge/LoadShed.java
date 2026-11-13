package dev.mcmesh.neoforge;

import dev.mcmesh.core.LoadShedder;
import dev.mcmesh.core.TickDegradation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lowers view and simulation distance while the host struggles, and puts them back afterwards. Never touches players or saving. */
final class LoadShed {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final int MIN_VIEW = 6;
    private static final int MIN_SIMULATION = 5;
    private static final LoadShedder SHEDDER = new LoadShedder();
    private static final TickDegradation DEGRADE = new TickDegradation();
    /** Read by the heartbeat thread: the shed level (-1 unknown) and the real players online (-1 unknown). */
    private static volatile int reportedLevel = -1;
    private static volatile int reportedPlayers = -1;
    private static int baseView = -1;
    private static int baseSimulation = -1;

    private LoadShed() { }

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("mcmesh.loadshed", "on"));
    }

    static int reportedLevel() {
        return reportedLevel;
    }

    static int reportedPlayers() {
        return reportedPlayers;
    }

    /** Fake players (bots, automation) are not people and must not make a host look busy. */
    static void countPlayers(MinecraftServer server) {
        reportedPlayers = (int) server.getPlayerList().getPlayers().stream().filter(p -> !(p instanceof net.neoforged.neoforge.common.util.FakePlayer)).count();
    }

    static synchronized void sample(MinecraftServer server) {
        double tickMs = server.getAverageTickTimeNanos() / 1_000_000.0;
        profileIfDegraded(server, tickMs);
        if (!enabled()) {
            reportedLevel = -1;
            return;
        }
        double tps = tickMs <= 50 ? 20.0 : 1000.0 / tickMs;
        int before = SHEDDER.level();
        int level = SHEDDER.update(tps, System.currentTimeMillis());
        reportedLevel = level;
        if (level == before) {
            return;
        }
        if (baseView < 0) {
            baseView = server.getPlayerList().getViewDistance();
            baseSimulation = server.getPlayerList().getSimulationDistance();
        }
        apply(server, level);
        LOG.warn("host load level {} -> {} at {} TPS: view distance {}, simulation distance {}", before, level, Math.round(tps * 10) / 10.0,
                server.getPlayerList().getViewDistance(), server.getPlayerList().getSimulationDistance());
        if (level == 0) {
            baseView = -1;
            baseSimulation = -1;
        }
    }

    /** Starts a 60 second Spark profile when ticks stay slow. Only if Spark is installed; the report link goes to the server log. */
    private static void profileIfDegraded(MinecraftServer server, double tickMs) {
        if ("off".equalsIgnoreCase(System.getProperty("mcmesh.sparkprofile", "on")) || !DEGRADE.update(tickMs, System.currentTimeMillis())) {
            return;
        }
        if (!net.neoforged.fml.ModList.get().isLoaded("spark")) {
            return;
        }
        LOG.warn("ticks averaged {} ms for over 10 s, starting a 60 s spark profile", Math.round(tickMs));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "spark profiler start --timeout 60");
    }

    /** Back to the settings the server had before shedding; call when hosting stops. */
    static synchronized void restore(MinecraftServer server) {
        if (baseView >= 0) {
            apply(server, 0);
            LOG.info("load shedding off, distances restored");
        }
        baseView = -1;
        baseSimulation = -1;
        SHEDDER.reset();
        DEGRADE.reset();
        reportedLevel = -1;
        reportedPlayers = -1;
    }

    private static void apply(MinecraftServer server, int level) {
        server.getPlayerList().setViewDistance(Math.min(baseView, Math.max(MIN_VIEW, baseView - 2 * level)));
        server.getPlayerList().setSimulationDistance(Math.min(baseSimulation, Math.max(MIN_SIMULATION, baseSimulation - 2 * level)));
    }
}
