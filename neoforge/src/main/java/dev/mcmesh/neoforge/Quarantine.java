package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Things whose tick threw while this machine hosts. They are skipped, never removed, so their saved data stays intact. */
public final class Quarantine {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_DUMPS = 100;
    private static final Set<Object> frozen = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private Quarantine() { }

    public static boolean hosting() {
        return MeshBoot.held() != null || MeshService.current() != null;
    }

    public static boolean frozen(Object o) {
        return frozen.contains(o);
    }

    /** Freezes o and writes its data to mesh/quarantine/ in the world folder, so the state at the time of the failure survives a handover. */
    public static void freeze(Object o, String what, Throwable t, Level level, Supplier<CompoundTag> data) {
        if (!frozen.add(o)) {
            return;
        }
        LOG.error("quarantined {} after a tick failure, it stays in the world but stops ticking until its chunk reloads", what, t);
        if (level instanceof ServerLevel sl) {
            try {
                dump(sl.getServer().getWorldPath(LevelResource.ROOT).resolve("mesh").resolve("quarantine"), what, data.get());
            } catch (Throwable e) {
                LOG.warn("could not dump quarantined {}", what, e);
            }
        }
    }

    static String fileName(String what, long nowMs) {
        return nowMs + "-" + what.replaceAll("[^A-Za-z0-9._-]+", "_") + ".nbt";
    }

    /** Never throws more than IOException, never keeps more than MAX_DUMPS files. */
    static void dump(Path dir, String what, CompoundTag tag) throws IOException {
        Files.createDirectories(dir);
        try (var files = Files.list(dir)) {
            if (files.count() >= MAX_DUMPS) {
                LOG.warn("mesh/quarantine already holds {} dumps, not writing another for {}", MAX_DUMPS, what);
                return;
            }
        }
        Path tmp = dir.resolve(fileName(what, System.currentTimeMillis()) + ".tmp");
        NbtIo.writeCompressed(tag, tmp);
        Files.move(tmp, tmp.resolveSibling(tmp.getFileName().toString().replaceAll("\\.tmp$", "")));
    }
}
