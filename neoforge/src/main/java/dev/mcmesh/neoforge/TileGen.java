package dev.mcmesh.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The one procedure that generates a rectangle of chunks to full status. Two peers only agree on a tile when they
 * ran exactly this, so research and workers share it and nobody changes it without bumping GEN_VERSION on the gateway.
 */
final class TileGen {
    private static final TicketType<ChunkPos> HOLD = TicketType.create("mesh_tilegen", Comparator.comparingLong(ChunkPos::toLong));

    private TileGen() { }

    record Result(long ok, long total, long millis) { }

    /**
     * Blocks, so never call it on the server thread (worldgen mods queue work back to it and it deadlocks).
     * Ticking while the area generates is what makes a run unreproducible (F-038), and the one-tick ticket that
     * getChunkFuture adds expires as soon as the server thread is free, so the tick is frozen and every chunk is held.
     * Every future is requested up front, on the server thread, in one fixed order (row by row waiting changes which
     * chunks generate next to each other and the features spilling between them stop matching, F-038).
     */
    static Result generate(ServerLevel level, int x1, int z1, int x2, int z2, IntConsumer progress) {
        long t0 = System.nanoTime();
        var cache = level.getChunkSource();
        List<CompletableFuture<ChunkResult<ChunkAccess>>> all = level.getServer().submit(() -> {
            level.getServer().tickRateManager().setFrozen(true);
            List<CompletableFuture<ChunkResult<ChunkAccess>>> out = new ArrayList<>();
            for (int cx = x1; cx <= x2; cx++) {
                for (int cz = z1; cz <= z2; cz++) {
                    ChunkPos p = new ChunkPos(cx, cz);
                    cache.addRegionTicket(HOLD, p, 0, p);
                    out.add(cache.getChunkFuture(cx, cz, ChunkStatus.FULL, true));
                }
            }
            return out;
        }).join();
        long ok = 0;
        long total = 0;
        for (var f : all) {
            total++;
            if (f.join().isSuccess()) {
                ok++;
            }
            if (total % 64 == 0) {
                progress.accept((int) total);
            }
        }
        return new Result(ok, total, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Runs the game's ticket and unload bookkeeping without ticking any chunk, which a frozen tick does not do. A chunk
     * that is not finished is only written to disk when it unloads, so without this a save has none of the partially
     * generated frontier around a tile.
     */
    static void pump(ServerLevel level, int rounds) {
        level.getServer().submit(() -> {
            for (int i = 0; i < rounds; i++) {
                level.getChunkSource().tick(() -> true, false);
            }
        }).join();
    }

    /** Lets the game unload the chunks again, once they are saved. */
    static void release(ServerLevel level, int x1, int z1, int x2, int z2) {
        level.getServer().submit(() -> {
            var cache = level.getChunkSource();
            for (int cx = x1; cx <= x2; cx++) {
                for (int cz = z1; cz <= z2; cz++) {
                    ChunkPos p = new ChunkPos(cx, cz);
                    cache.removeRegionTicket(HOLD, p, 0, p);
                }
            }
        }).join();
    }
}
