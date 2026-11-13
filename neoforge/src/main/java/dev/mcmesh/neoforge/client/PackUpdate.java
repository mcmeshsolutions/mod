package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.PackSwap;
import dev.mcmesh.core.PackUpdater;
import dev.mcmesh.neoforge.MeshConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Stages the signed pack update in the background and arranges the swap for after the game exits. */
final class PackUpdate {
    private static final Logger LOG = LogUtils.getLogger();
    private static boolean started;
    private static boolean hooked;

    private PackUpdate() { }

    static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread.ofPlatform().name("mesh-pack-update").daemon().start(PackUpdate::run);
    }

    private static void run() {
        Path game = FMLPaths.GAMEDIR.get();
        try {
            var s = new PackUpdater.Settings(MeshConfig.PACK_ID.get(), MeshConfig.packVersion(), MeshConfig.PACK_AUTHOR_KEY.get(),
                    MeshConfig.PACK_UPDATE_URL.get(), MeshConfig.PACK_AUTO_UPDATE.get(), game);
            var out = new PackUpdater().run(s);
            LOG.info("pack update check: {}", out);
        } catch (IOException e) {
            LOG.warn("pack update not staged, current install untouched: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("pack update check failed", e);
        }
        // a previous launch may have staged an update the swap never got to apply
        if (MeshConfig.PACK_AUTO_UPDATE.get() && PackSwap.hasReady(game)) {
            registerSwap(game);
        }
    }

    private static synchronized void registerSwap(Path game) {
        if (hooked) {
            return;
        }
        try {
            Path helper = PackSwap.updateDir(game).resolve("helper");
            Path cls = helper.resolve("dev/mcmesh/core/PackSwap.class");
            Files.createDirectories(cls.getParent());
            try (InputStream in = PackSwap.class.getResourceAsStream("PackSwap.class")) {
                if (in == null) {
                    throw new IOException("PackSwap.class not found in the mod");
                }
                Files.copy(in, cls, StandardCopyOption.REPLACE_EXISTING);
            }
            String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            long pid = ProcessHandle.current().pid();
            Path log = PackSwap.updateDir(game).resolve("swap.log");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    new ProcessBuilder(java, "-cp", helper.toString(), "dev.mcmesh.core.PackSwap", Long.toString(pid), game.toString())
                            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                } catch (IOException ignored) {
                    // nothing to do at shutdown; the update stays staged and is retried next launch
                }
            }, "mesh-pack-swap"));
            hooked = true;
            LOG.info("pack update staged, it is applied when the game exits");
        } catch (IOException e) {
            LOG.warn("pack update staged but the swap helper could not be prepared: {}", e.getMessage());
        }
    }
}
