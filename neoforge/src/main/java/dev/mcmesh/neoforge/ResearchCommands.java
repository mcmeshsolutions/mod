package dev.mcmesh.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Only with -Dmcmesh.research=true: generates a chunk rectangle to full status without ticking, and times it. */
final class ResearchCommands {
    private ResearchCommands() { }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("meshresearch").requires(s -> s.hasPermission(4))
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
                                                    var cache = level.getChunkSource();
                                                    long t0 = System.nanoTime();
                                                    List<CompletableFuture<ChunkResult<ChunkAccess>>> all = new ArrayList<>();
                                                    for (int cx = x1; cx <= x2; cx++) {
                                                        for (int cz = z1; cz <= z2; cz++) {
                                                            all.add(cache.getChunkFuture(cx, cz, ChunkStatus.FULL, true));
                                                        }
                                                    }
                                                    c.getSource().getServer().managedBlock(() -> all.stream().allMatch(CompletableFuture::isDone));
                                                    long ms = (System.nanoTime() - t0) / 1_000_000;
                                                    long ok = all.stream().filter(f -> f.join().isSuccess()).count();
                                                    String msg = "researchgen " + ok + " of " + all.size() + " chunks full in " + ms + " ms";
                                                    c.getSource().sendSuccess(() -> Component.literal(msg), false);
                                                    return 1;
                                                })))))));
    }
}
