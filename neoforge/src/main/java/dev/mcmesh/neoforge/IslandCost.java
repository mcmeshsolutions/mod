package dev.mcmesh.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Research only (-Dmcmesh.research=true, `meshresearch sweep`): what does a server pay for active areas that are far apart?
 * It forces N separate squares of chunks to keep ticking, fills each with mobs, and records the tick time, which is what a
 * host would save if each square could be ticked by another machine (ROADMAP "Dynamic multi-host sharding", board/RESEARCH.md 4).
 * No players are involved: they cost tracking and packets on top of this, and they decide how many areas there are, which is
 * the one thing this cannot say.
 */
final class IslandCost {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final List<EntityType<? extends Mob>> CROWD = List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN, EntityType.VILLAGER, EntityType.ZOMBIE);
    private static final int WARMUP_S = 15;
    private static final int SAMPLES = 30;
    private static volatile String state = "idle";

    private IslandCost() { }

    static String state() {
        return state;
    }

    /** One configuration of the sweep: how many separate active squares, how far apart, how many mobs each. */
    record Config(String label, int islands, int spacing, int mobsPerIsland) { }

    /** Squares are 17 x 17 chunks, the area a player at simulation distance 8 keeps ticking. */
    static final int RADIUS = 8;

    static List<Config> sweep() {
        List<Config> out = new ArrayList<>();
        for (int mobs : new int[] {0, 200}) {
            for (int n : new int[] {1, 2, 4, 8}) {
                out.add(new Config("spread", n, 40, mobs));
            }
        }
        // the same crowd packed into one square: separates the cost of areas from the cost of mobs
        for (int n : new int[] {2, 4, 8}) {
            out.add(new Config("packed", n, 0, 200));
        }
        return out;
    }

    private static int[] origin(Config c, int i) {
        return new int[] {400 + i * c.spacing(), 400};
    }

    static void run(MinecraftServer server, Path resultFile) {
        try {
            ServerLevel level = server.overworld();
            Files.writeString(resultFile, "label,islands,spacing_chunks,mobs_per_island,chunks_ticking,mobs,mspt_mean,mspt_p95,mspt_max\n");
            // night, no spawning, no weather: nothing but what we put there
            server.submit(() -> {
                server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
                server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setDayTime(18000);
                return null;
            }).join();
            // generate every square we may need once, with the tick frozen, so generation never pollutes a measurement
            int most = 8;
            state = "generating";
            for (int i = 0; i < most; i++) {
                int[] o = origin(new Config("spread", most, 40, 0), i);
                TileGen.generate(level, o[0] - RADIUS - 3, o[1] - RADIUS - 3, o[0] + RADIUS + 3, o[1] + RADIUS + 3, n -> { });
            }
            TileGen.release(level, 400 - RADIUS - 3, 400 - RADIUS - 3, 400 + 7 * 40 + RADIUS + 3, 400 + RADIUS + 3);
            for (Config c : sweep()) {
                state = "running " + c.label() + " " + c.islands() + "x" + c.mobsPerIsland();
                String line = measure(server, level, c);
                Files.writeString(resultFile, line + "\n", java.nio.file.StandardOpenOption.APPEND);
                LOG.info("islandcost: {}", line);
            }
            server.submit(() -> {
                server.tickRateManager().setFrozen(false);
                return null;
            }).join();
            state = "sweep done";
        } catch (IOException | RuntimeException e) {
            state = "failed " + e;
            LOG.error("islandcost failed", e);
        }
    }

    private static String measure(MinecraftServer server, ServerLevel level, Config c) {
        List<int[]> forced = new ArrayList<>();
        int[] mobs = {0};
        server.submit(() -> {
            server.tickRateManager().setFrozen(false);
            for (int i = 0; i < c.islands(); i++) {
                int[] o = origin(c, i);
                for (int cx = o[0] - RADIUS; cx <= o[0] + RADIUS; cx++) {
                    for (int cz = o[1] - RADIUS; cz <= o[1] + RADIUS; cz++) {
                        if (level.setChunkForced(cx, cz, true)) {
                            forced.add(new int[] {cx, cz});
                        }
                    }
                }
            }
            return null;
        }).join();
        // let the forced chunks come up to ticking before anything is spawned into them
        sleep(5_000);
        // new chunks come with the animals worldgen put in them, which would count as mobs in the "none" configurations
        server.submit(() -> {
            List<Entity> stray = new ArrayList<>();
            level.getAllEntities().forEach(stray::add);
            for (Entity e : stray) {
                if (!(e instanceof Player)) {
                    e.discard();
                }
            }
            return null;
        }).join();
        server.submit(() -> {
            for (int i = 0; i < c.islands(); i++) {
                int[] o = origin(c, i);
                for (int m = 0; m < c.mobsPerIsland(); m++) {
                    EntityType<? extends Mob> type = CROWD.get(m % CROWD.size());
                    // spread over the middle of the square, deterministically
                    int k = i * c.mobsPerIsland() + m;
                    int bx = o[0] * 16 + 8 + (k * 37) % 192 - 96;
                    int bz = o[1] * 16 + 8 + (k * 53) % 192 - 96;
                    int by = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
                    Mob mob = type.create(level);
                    if (mob != null) {
                        mob.moveTo(bx + 0.5, by, bz + 0.5, (k * 31) % 360, 0);
                        mob.setPersistenceRequired();
                        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(new BlockPos(bx, by, bz)), MobSpawnType.COMMAND, null);
                        if (level.addFreshEntity(mob)) {
                            mobs[0]++;
                        }
                    }
                }
            }
            return null;
        }).join();
        sleep(WARMUP_S * 1000L);
        double[] samples = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            sleep(1_000);
            samples[i] = server.getAverageTickTimeNanos() / 1_000_000.0;
        }
        int[] ticking = {0};
        server.submit(() -> {
            ticking[0] = level.getForcedChunks().size();
            return null;
        }).join();
        // put everything back so the next configuration starts from nothing
        server.submit(() -> {
            for (int[] f : forced) {
                level.setChunkForced(f[0], f[1], false);
            }
            List<Entity> all = new ArrayList<>();
            level.getAllEntities().forEach(all::add);
            for (Entity e : all) {
                if (!(e instanceof Player)) {
                    e.discard();
                }
            }
            return null;
        }).join();
        sleep(8_000);
        Arrays.sort(samples);
        double mean = Arrays.stream(samples).average().orElse(0);
        return String.format("%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f", c.label(), c.islands(), c.spacing(), c.mobsPerIsland(), ticking[0] > 0 ? ticking[0] : forced.size(), mobs[0], mean,
                samples[(int) Math.floor(0.95 * (SAMPLES - 1))], samples[SAMPLES - 1]);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
