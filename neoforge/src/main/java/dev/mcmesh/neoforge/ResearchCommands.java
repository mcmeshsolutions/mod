package dev.mcmesh.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Only with -Dmcmesh.research=true: generates a chunk rectangle to full status without ticking, and times it. */
final class ResearchCommands {
    private static volatile String state = "idle";

    private ResearchCommands() { }

    // own thread: blocking the server thread here deadlocks with worldgen mods that queue work back to it
    private static void generate(ServerLevel level, int x1, int z1, int x2, int z2) {
        try {
            TileGen.Result r = TileGen.generate(level, x1, z1, x2, z2, n -> state = "running " + n);
            state = "done " + r.ok() + " of " + r.total() + " chunks full in " + r.millis() + " ms";
        } finally {
            level.getServer().submit(() -> level.getServer().tickRateManager().setFrozen(false));
        }
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
