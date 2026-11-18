package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * Autonomous diagnostic and remediation engine for server lag spikes.
 * Detects per-chunk tick bottlenecks, provides surgical chunk freezing, and quarantines rogue entities.
 */
public final class LagDoctor {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<Long> FROZEN_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private LagDoctor() {}

    public static record LagReport(double mspt, double tps, int loadedChunks, int entityCount, int frozenChunks) {}

    public static LagReport status(ServerLevel level) {
        double mspt = level.getServer().getAverageTickTimeNanos() / 1_000_000.0;
        double tps = mspt <= 50 ? 20.0 : 1000.0 / mspt;
        int chunks = level.getChunkSource().getLoadedChunksCount();
        int entities = 0;
        for (Entity ignored : level.getAllEntities()) {
            entities++;
        }
        return new LagReport(mspt, tps, chunks, entities, FROZEN_CHUNKS.size());
    }

    public static boolean freeze(int rx, int rz, boolean enable) {
        long key = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
        if (enable) {
            boolean added = FROZEN_CHUNKS.add(key);
            LOG.info("lag doctor: frozen region r.{}.{}", rx, rz);
            return added;
        } else {
            boolean removed = FROZEN_CHUNKS.remove(key);
            LOG.info("lag doctor: un-frozen region r.{}.{}", rx, rz);
            return removed;
        }
    }

    public static boolean isFrozen(BlockPos pos) {
        int rx = pos.getX() >> 9;
        int rz = pos.getZ() >> 9;
        long key = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
        return FROZEN_CHUNKS.contains(key);
    }

    public static int quarantineRegion(ServerLevel level, int rx, int rz) {
        int count = 0;
        int minX = rx << 9;
        int minZ = rz << 9;
        int maxX = minX + 511;
        int maxZ = minZ + 511;
        java.nio.file.Path dir = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mesh").resolve("quarantine");
        for (Entity e : level.getAllEntities()) {
            BlockPos p = e.blockPosition();
            if (p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ) {
                try {
                    net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                    e.save(tag);
                    Quarantine.dump(dir, e.getType().getDescriptionId(), tag);
                } catch (Exception ex) {
                    LOG.warn("could not dump entity {}: {}", e.getType().getDescriptionId(), ex.toString());
                }
                e.discard();
                count++;
            }
        }
        LOG.info("lag doctor: quarantined {} entities in r.{}.{}", count, rx, rz);
        return count;
    }
}
