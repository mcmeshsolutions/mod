package dev.mcmesh.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Only with -Dmcmesh.research=true: generates a chunk rectangle to full status without ticking, and times it. */
final class ResearchCommands {
    private static final TicketType<ChunkPos> HOLD = TicketType.create("mesh_research", Comparator.comparingLong(ChunkPos::toLong));
    private static volatile String state = "idle";

    private ResearchCommands() { }

    // own thread: blocking the server thread here deadlocks with worldgen mods that queue work back to it
    private static void generate(ServerLevel level, int x1, int z1, int x2, int z2) {
        long t0 = System.nanoTime();
        var cache = level.getChunkSource();
        // ticking while the area generates is what makes a run unreproducible (F-038), and the one-tick ticket
        // getChunkFuture adds expires as soon as the server thread is free, so freeze the tick and hold the chunks
        // every future is requested up front, on the server thread, in one fixed order: waiting row by row instead
        // changes which chunks generate next to each other and the features spilling between them stop matching (F-038)
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
        try {
            for (var f : all) {
                total++;
                if (f.join().isSuccess()) {
                    ok++;
                }
                if (total % 64 == 0) {
                    state = "running " + total;
                }
            }
        } finally {
            level.getServer().submit(() -> level.getServer().tickRateManager().setFrozen(false));
        }
        state = "done " + ok + " of " + total + " chunks full in " + (System.nanoTime() - t0) / 1_000_000 + " ms";
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("meshresearch").requires(s -> s.hasPermission(4))
                .then(Commands.literal("status").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("researchgen " + state), false);
                    return 1;
                }))
                .then(Commands.literal("gen")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer()).executes(c -> {
                                                    int x1 = IntegerArgumentType.getInteger(c, "x1");
                                                    int z1 = IntegerArgumentType.getInteger(c, "z1");
                                                    int x2 = IntegerArgumentType.getInteger(c, "x2");
                                                    int z2 = IntegerArgumentType.getInteger(c, "z2");
                                                    var level = c.getSource().getLevel();
                                                    state = "running 0";
                                                    Thread t = new Thread(() -> generate(level, x1, z1, x2, z2), "mesh-research-gen");
                                                    t.setDaemon(true);
                                                    t.start();
                                                    c.getSource().sendSuccess(() -> Component.literal("researchgen started"), false);
                                                    return 1;
                                                })))))));
    }
}
