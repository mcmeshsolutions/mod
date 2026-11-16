package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.WorkerCommand;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets this game generate worldgen tiles for a host (board/RESEARCH.md 1; setting `contributeWorldgen`). A tile has to come from a
 * world that starts empty, so it cannot be made inside the running game: a hidden dedicated server is started as a child process
 * from this game's own Java command line, library path and mods, in a folder of its own, one tile per process, and ends when the
 * tile is reported. It runs at low priority and is stopped the moment this machine starts hosting or the game closes.
 *
 * A dedicated server cannot load every mod of a client pack. Mods that fail to load are found from the child's log, written to
 * `mcmesh-worker/excludes.txt` and left out next time, so the first rounds on a new pack may fail while that list is learned.
 */
public final class WorkerPool {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final long POLL_MS = 60_000;
    private static final int GIVE_UP_AFTER = 8;
    /** Mods that are about the screen and the sound; a server never needs them and several refuse to start without a window. */
    private static final Pattern CLIENT_ONLY = Pattern.compile("^(mcmesh-never|sodium|embeddium|iris|xaero|dynamic|entityculling|immediatelyfast|reeses|continuity|fancymenu|"
            + "konkrete|drippy|appleskin|betterf3|craftpresence|presencefootsteps|sound-?physics|ambientsounds|lambdynamic|notenoughanimations|firstperson|"
            + "skinlayers|moreculling|clientcrafting|colorwheel|shaders?).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING = Pattern.compile("Mod ID: '([a-z0-9_]+)', Requested by: '([a-z0-9_]+)'");
    private static final Pattern FAILED = Pattern.compile("Mod loading issue for: ([a-z0-9_]+)");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([a-z0-9_]+)\"");

    private static Thread thread;
    private static volatile Process child;
    private static int failures;
    private static volatile String status = "idle, waiting for demand";

    private WorkerPool() { }

    public static synchronized void ensureStarted() {
        if (thread != null || !MeshConfig.CONTRIBUTE_WORLDGEN.get()) {
            return;
        }
        thread = new Thread(WorkerPool::loop, "mesh-worker-pool");
        thread.setDaemon(true);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(WorkerPool::stopChild, "mesh-worker-exit"));
    }

    /** One line for the HUD; only meaningful while contributeWorldgen is on. */
    public static String status() {
        return status;
    }

    private static void stopChild() {
        Process p = child;
        if (p != null) {
            p.destroy();
        }
    }

    private static boolean busyHosting() {
        return MeshService.current() != null || Minecraft.getInstance().getSingleplayerServer() != null;
    }

    private static void loop() {
        try {
            Thread.sleep(POLL_MS);
            while (failures < GIVE_UP_AFTER) {
                if (busyHosting()) {
                    status = "idle, this machine is hosting";
                } else if (MeshConfig.CONTRIBUTE_WORLDGEN.get()) {
                    round();
                } else {
                    status = "off";
                }
                Thread.sleep(POLL_MS);
            }
            status = "stopped after " + failures + " failed starts, see mcmesh-worker/logs/worker.log";
            LOG.warn("worldgen worker: giving up for this session after {} failed starts, see mcmesh-worker/logs/worker.log", failures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void round() throws InterruptedException {
        try {
            var id = MeshAuth.cached();
            var gw = new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), id.token());
            var demand = gw.worldgenDemand();
            if (demand.isEmpty()) {
                status = "idle, waiting for demand";
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Path dir = mc.gameDirectory.toPath().resolve("mcmesh-worker").toAbsolutePath().normalize();
            Process p = launch(mc.gameDirectory.toPath().toAbsolutePath().normalize(), dir, demand.get(0).seed(), id.token());
            if (p == null) {
                failures = GIVE_UP_AFTER;
                return;
            }
            child = p;
            status = "generating a tile";
            LOG.info("worldgen worker started for a tile (seed {}), log in {}", demand.get(0).seed(), dir.resolve("logs").resolve("worker.log"));
            while (p.isAlive()) {
                if (busyHosting() || !MeshConfig.CONTRIBUTE_WORLDGEN.get()) {
                    LOG.info("worldgen worker stopped: this machine is hosting or the setting was turned off");
                    p.destroy();
                    break;
                }
                if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    break;
                }
            }
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            child = null;
            if (p.isAlive()) {
                p.destroyForcibly();
            } else if (p.exitValue() != 0 && !busyHosting()) {
                failures++;
                learnExcludes(dir);
            } else {
                failures = 0;
            }
            status = "idle, waiting for demand";
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            failures++;
            LOG.warn("worldgen worker round failed: {}", e.toString());
        }
    }

    private static Process launch(Path gameDir, Path dir, String seed, String token) throws IOException {
        var info = ProcessHandle.current().info();
        var args = info.arguments();
        var java = info.command();
        if (args.isEmpty() || java.isEmpty()) {
            LOG.warn("worldgen worker: this platform does not report the game's command line, so no worker can be started from it");
            return null;
        }
        List<String> cmd = WorkerCommand.rewrite(List.of(args.get()), dir.toString(), MeshConfig.WORKER_HEAP.get(),
                List.of("-Dmcmesh.worker=true", "-Dmcmesh.worker.idleRounds=3"));
        if (cmd == null) {
            LOG.warn("worldgen worker: the game was not started through a launch target, nothing to base a server on");
            return null;
        }
        prepare(gameDir, dir, seed, token);
        cmd.add(0, java.get());
        Files.createDirectories(dir.resolve("logs"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("logs").resolve("worker.log").toFile());
        try {
            List<String> nice = new ArrayList<>(List.of("nice", "-n", "10"));
            nice.addAll(cmd);
            return new ProcessBuilder(nice).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("logs").resolve("worker.log").toFile()).start();
        } catch (IOException noNice) {
            return pb.start();
        }
    }

    private static void prepare(Path gameDir, Path dir, String seed, String token) throws IOException {
        Files.createDirectories(dir);
        deleteTree(dir.resolve("world"));
        Path mods = dir.resolve("mods");
        deleteTree(mods);
        Files.createDirectories(mods);
        TreeSet<String> out = excluded(dir);
        try (Stream<Path> jars = Files.list(gameDir.resolve("mods"))) {
            for (Path j : (Iterable<Path>) jars.filter(p -> p.getFileName().toString().endsWith(".jar"))::iterator) {
                String name = j.getFileName().toString();
                if (out.contains(name) || CLIENT_ONLY.matcher(name).matches()) {
                    continue;
                }
                try {
                    Files.createSymbolicLink(mods.resolve(name), j.toAbsolutePath());
                } catch (IOException | UnsupportedOperationException e) {
                    Files.copy(j, mods.resolve(name));
                }
            }
        }
        Path cfg = dir.resolve("config");
        deleteTree(cfg);
        copyTree(gameDir.resolve("config"), cfg);
        Files.writeString(cfg.resolve("mcmesh-common.toml"), "apiUrl = \"" + MeshConfig.API_URL.get() + "\"\ntoken = \"" + token + "\"\npackId = \"" + MeshConfig.PACK_ID.get()
                + "\"\nplayerName = \"worker\"\n");
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=" + freePort() + "\nonline-mode=false\nlevel-seed=" + seed
                + "\nspawn-protection=0\nmax-tick-time=-1\nenable-command-block=false\nenable-rcon=false\nenable-query=false\n");
        if (!Files.exists(cfg.resolve("fml.toml"))) {
            LOG.warn("worldgen worker: the pack has no config/fml.toml, so maxThreads is not pinned to 1 and this worker may not agree with the host");
        }
    }

    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 25590;
        }
    }

    private static TreeSet<String> excluded(Path dir) throws IOException {
        Path f = dir.resolve("excludes.txt");
        TreeSet<String> out = new TreeSet<>();
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f)) {
                if (!l.isBlank()) {
                    out.add(l.trim());
                }
            }
        }
        return out;
    }

    /** Reads why the child died, maps the named mods to their jars and remembers them as not loadable on a server. */
    private static void learnExcludes(Path dir) {
        try {
            Path log = dir.resolve("logs").resolve("worker.log");
            if (!Files.exists(log)) {
                return;
            }
            String text = Files.readString(log, java.nio.charset.StandardCharsets.ISO_8859_1);
            TreeSet<String> ids = new TreeSet<>();
            Matcher m = MISSING.matcher(text);
            while (m.find()) {
                ids.add(m.group(2));
            }
            m = FAILED.matcher(text);
            while (m.find()) {
                ids.add(m.group(1));
            }
            ids.remove("mcmesh");
            if (ids.isEmpty()) {
                return;
            }
            TreeSet<String> out = excluded(dir);
            int before = out.size();
            try (Stream<Path> jars = Files.list(dir.resolve("mods"))) {
                for (Path j : (Iterable<Path>) jars::iterator) {
                    String id = modIdOf(j);
                    if (id != null && ids.contains(id)) {
                        out.add(j.getFileName().toString());
                    }
                }
            }
            if (out.size() > before) {
                Files.write(dir.resolve("excludes.txt"), out);
                LOG.info("worldgen worker: {} not loadable on a server, left out from now on", String.join(", ", ids));
            }
        } catch (IOException e) {
            LOG.debug("could not learn from the worker log: {}", e.toString());
        }
    }

    private static String modIdOf(Path jar) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toRealPath().toFile())) {
            var e = z.getEntry("META-INF/neoforge.mods.toml");
            if (e == null) {
                return null;
            }
            Matcher m = MOD_ID.matcher(new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.createDirectories(to);
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(f);
            }
        }
    }
}
