package dev.mcmesh.neoforge;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records where players and forced chunks are during a real session, so dynamic multi-host sharding can be judged
 * on real movement instead of a guess (board/RESEARCH.md "Not measurable here"). Off unless
 * -Dmcmesh.islandprobe=true; writes one CSV line per sample to mcmesh-islands.csv in the world folder.
 * Replay it with `gradle :neoforge:islands -PtoolArgs=<file>`.
 */
public final class IslandProbe {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final int EVERY_TICKS = 40;
    private static IslandProbe active;

    private final Writer out;
    private long samples;

    private IslandProbe(Writer out) {
        this.out = out;
    }

    static void start(MinecraftServer server) {
        if (!Boolean.getBoolean("mcmesh.islandprobe") || active != null) {
            return;
        }
        try {
            Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcmesh-islands.csv");
            boolean fresh = !Files.exists(file);
            Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                w.write("# tick,dimension,kind,id,chunkX,chunkZ  (kind mspt: id is the average tick time in microseconds; kind entity: id is how many entities are in that chunk)\n");
            }
            active = new IslandProbe(w);
            LOG.info("island probe recording to {}", file);
        } catch (IOException e) {
            LOG.warn("island probe: could not open the recording: {}", e.toString());
        }
    }

    static void stop() {
        IslandProbe p = active;
        active = null;
        if (p != null) {
            try {
                p.out.close();
                LOG.info("island probe wrote {} samples", p.samples);
            } catch (IOException e) {
                LOG.warn("island probe: could not close the recording: {}", e.toString());
            }
        }
    }

    static void sample(MinecraftServer server) {
        IslandProbe p = active;
        if (p == null || server.getTickCount() % EVERY_TICKS != 0) {
            return;
        }
        long tick = server.getTickCount();
        try {
            for (var player : server.getPlayerList().getPlayers()) {
                p.line(tick, player.level().dimension().location().toString(), "player",
                        player.getGameProfile().getName(), player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
            }
            // how hard the host was working, and where the entities it ticks were: what turns "players were apart" into "splitting would have helped"
            p.line(tick, "minecraft:overworld", "mspt", String.valueOf(server.getAverageTickTimeNanos() / 1000), 0, 0);
            for (ServerLevel level : server.getAllLevels()) {
                java.util.Map<Long, Integer> perChunk = new java.util.HashMap<>();
                for (var e : level.getAllEntities()) {
                    if (!(e instanceof net.minecraft.world.entity.player.Player)) {
                        perChunk.merge(e.chunkPosition().toLong(), 1, Integer::sum);
                    }
                }
                String edim = level.dimension().location().toString();
                for (var c : perChunk.entrySet()) {
                    p.line(tick, edim, "entity", String.valueOf(c.getValue()), net.minecraft.world.level.ChunkPos.getX(c.getKey()), net.minecraft.world.level.ChunkPos.getZ(c.getKey()));
                }
            }
            for (ServerLevel level : server.getAllLevels()) {
                String dim = level.dimension().location().toString();
                var it = level.getForcedChunks().iterator();
                while (it.hasNext()) {
                    long c = it.nextLong();
                    // forced chunks keep contraptions and farms ticking with nobody near them, so they anchor an island too
                    p.line(tick, dim, "forced", "-", net.minecraft.world.level.ChunkPos.getX(c), net.minecraft.world.level.ChunkPos.getZ(c));
                }
            }
            LazyWorld lazy = LazyWorld.active;
            if (lazy != null) {
                for (String key : lazy.pendingKeys()) {
                    String[] parts = key.split("\\|");   // dim|rx|rz
                    if (parts.length == 3) {
                        p.line(tick, parts[0], "pending-region", "-", Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    }
                }
            }
            p.samples++;
            if (p.samples % 50 == 0) {
                p.out.flush();
            }
        } catch (IOException e) {
            LOG.warn("island probe: stopping, could not write: {}", e.toString());
            stop();
        }
    }

    private void line(long tick, String dim, String kind, String id, int cx, int cz) throws IOException {
        out.write(tick + "," + dim + "," + kind + "," + id.replace(',', '_') + "," + cx + "," + cz + "\n");
    }
}
