package dev.mcmesh.core;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone, cross-platform pre-launch updater: downloads and verifies the signed pack manifest and applies
 * it directly to the instance's mods/ (no running JVM to wait on here, unlike the in-game path in PackUpdate),
 * then always exits 0 so a broken update, a dead gateway or no network never blocks the actual game launch.
 * Pure JDK plus Gson (bundled into the fat jar by the standaloneUpdaterJar task) - no Minecraft/NeoForge classes.
 *
 * Usage (as a PrismLauncher "pre-launch command", which substitutes $INST_MC_DIR itself and is identical on
 * Windows and Linux since it is Prism invoking `java`, not a shell script):
 *   java -jar mcmesh-updater.jar "$INST_MC_DIR"
 *   java -jar mcmesh-updater.jar "$INST_MC_DIR" <packId> <authorKey> <updateUrl>
 */
public final class PackPreLaunch {
    static final String DEFAULT_PACK_ID = "default";
    static final String DEFAULT_AUTHOR_KEY = "GaIjyKsDGyhXjC5QNC7Hbcgx49W6mCYjaMtkJ49i9qA";
    static final String DEFAULT_UPDATE_URL = "https://play.23012006.xyz/dl/pack-manifest.json";

    /** Bump this (and have PackBuilder's updaterVersion match) whenever mcmesh-updater.jar itself changes. */
    static final int SELF_VERSION = 4;

    private PackPreLaunch() { }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: PackPreLaunch <gameDir> [packId] [authorKey] [updateUrl]");
            return;
        }
        Path gameDir = Path.of(args[0]);
        if (trySelfSwap(gameDir, args)) {
            // the swapped-in jar already finished this launch's update and memory tuning; nothing left to do here
            return;
        }
        String packId = args.length > 1 ? args[1] : DEFAULT_PACK_ID;
        String authorKey = args.length > 2 ? args[2] : DEFAULT_AUTHOR_KEY;
        String updateUrl = args.length > 3 ? args[3] : DEFAULT_UPDATE_URL;
        try {
            run(gameDir, packId, authorKey, updateUrl);
        } catch (RuntimeException e) {
            // never let an unexpected error here stop the game from starting on whatever is already installed
            System.err.println("mcmesh pre-launch update crashed, continuing with the current install: " + e);
        }
        try {
            tuneMemory(gameDir);
        } catch (RuntimeException e) {
            System.err.println("mcmesh pre-launch memory tuning failed, launching with whatever is already configured: " + e);
        }
    }

    /**
     * A previous run may have staged a newer mcmesh-updater.jar (see PackUpdater.maybeStageSelfUpdate). Swap it
     * in and re-exec it with the same args right here, so the self-update takes effect within this one
     * pre-launch invocation instead of needing the player to quit and relaunch once more before it applies.
     * Windows keeps an open jar's file handle shareable for rename, so this works while still running from it;
     * if the swap or the re-exec fails for any reason, fall through and finish this launch on the old jar -
     * the pending build stays staged and is retried next launch instead of blocking this one.
     *
     * @return true if a newer build took over and already finished this launch's work
     */
    private static boolean trySelfSwap(Path gameDir, String[] args) {
        Path pending = PackSwap.updateDir(gameDir).resolve("updater-pending.jar");
        if (!Files.isRegularFile(pending)) {
            return false;
        }
        try {
            Path self = Path.of(PackPreLaunch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!self.toString().endsWith(".jar")) {
                return false; // running from exploded classes (tests, dev run): nothing to self-update
            }
            Files.move(pending, self, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(PackSwap.updateDir(gameDir).resolve("updater-pending.version"));
            System.out.println("mcmesh pre-launch: updater jar replaced with the staged newer build, taking over this launch");

            String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            List<String> cmd = new ArrayList<>(List.of(java, "-jar", self.toString()));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            p.waitFor();
            return true;
        } catch (IOException | URISyntaxException | SecurityException e) {
            System.err.println("mcmesh pre-launch: could not self-update this launch, will retry next launch: " + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true; // the child owns the result either way; do not also run the old code on top of it
        }
    }

    private static void tuneMemory(Path gameDir) {
        JvmMemory.Plan plan = JvmMemory.plan(gameDir, PackSwap.updateDir(gameDir), System.currentTimeMillis());
        if (plan.steppedDown()) {
            System.out.println("mcmesh pre-launch: last run OOM'd or tripped the safety valve, stepping heap down to " + plan.xmxMb() + "m");
        } else if (plan.grew()) {
            System.out.println("mcmesh pre-launch: several stable launches in a row, growing heap to " + plan.xmxMb() + "m");
        }
        Path instanceCfg = gameDir.getParent() != null ? gameDir.getParent().resolve("instance.cfg") : null;
        if (instanceCfg == null || !Files.isRegularFile(instanceCfg)) {
            System.out.println("mcmesh pre-launch: no instance.cfg next to " + gameDir + ", heap not tuned (not a PrismLauncher/MultiMC instance?)");
            return;
        }
        JvmMemory.applyToInstanceConfig(instanceCfg, plan);
        // read the file back rather than trust our own write: this line is what proves (in Prism's own
        // console output, right above the java command line it actually runs) that the values took
        String confirmed;
        try {
            confirmed = Files.readAllLines(instanceCfg).stream()
                    .filter(l -> l.startsWith("OverrideMemory=") || l.startsWith("MinMemAlloc=") || l.startsWith("MaxMemAlloc=")
                            || l.startsWith("OverrideJavaArgs=") || l.startsWith("JvmArgs="))
                    .reduce("", (acc, l) -> acc + l + " ");
        } catch (IOException e) {
            confirmed = "(could not read instance.cfg back: " + e.getMessage() + ")";
        }
        System.out.println("mcmesh pre-launch: heap set to " + plan.xmsMb() + "m-" + plan.xmxMb() + "m (detected "
                + JvmMemory.totalPhysicalMb() + "m total RAM) - instance.cfg now has: " + confirmed.strip());
        System.out.println("mcmesh pre-launch: verify by checking the java command line Prism prints for this launch contains "
                + "-Xmx" + plan.xmxMb() + "m");
    }

    static void run(Path gameDir, String packId, String authorKey, String updateUrl) {
        String have = PackSwap.appliedVersion(gameDir);
        var settings = new PackUpdater.Settings(packId, have == null ? "0" : have, authorKey, updateUrl, true, gameDir, SELF_VERSION);
        PackUpdater.Outcome out;
        try {
            out = new PackUpdater().run(settings);
        } catch (IOException e) {
            System.out.println("mcmesh pre-launch update: not applied (" + e.getMessage() + "), continuing with the current install");
            return;
        }
        System.out.println("mcmesh pre-launch update check: " + out);
        if (out != PackUpdater.Outcome.STAGED && !PackSwap.hasReady(gameDir)) {
            return;
        }
        try {
            PackSwap.apply(gameDir);
            System.out.println("mcmesh pre-launch update applied");
        } catch (IOException e) {
            System.err.println("mcmesh pre-launch update staged but could not be applied, continuing with the current install: " + e);
        }
    }
}
