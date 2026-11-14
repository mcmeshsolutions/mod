package dev.mcmesh.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = "mcmesh")
public final class MeshCommands {
    private MeshCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("meshop")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("list").executes(c -> {
                    String s = String.join(", ", MeshOps.list());
                    c.getSource().sendSuccess(() -> Component.literal("Operators: " + (s.isEmpty() ? "none" : s)
                            + (MeshOps.opAll() ? " (everyone is an operator)" : "")), false);
                    return 1;
                }))
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    boolean added = MeshOps.add(n);
                    c.getSource().sendSuccess(() -> Component.literal(added ? n + " is now an operator" : n + " already is one"), true);
                    refresh(c.getSource());
                    return 1;
                })))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    boolean gone = MeshOps.remove(n);
                    c.getSource().sendSuccess(() -> Component.literal(gone ? n + " is no longer an operator" : n + " was not an operator"), true);
                    refresh(c.getSource());
                    return 1;
                })))
                .then(Commands.literal("all").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> {
                    boolean on = BoolArgumentType.getBool(c, "enabled");
                    MeshOps.opAll(on);
                    c.getSource().sendSuccess(() -> Component.literal("Everyone is an operator by default: " + on), true);
                    refresh(c.getSource());
                    return 1;
                }))));
        d.register(Commands.literal("meshstatus").executes(c -> {
            MeshService cur = MeshService.current();
            String mem = MemWatch.availableMb() < 0 ? "" : ", " + MemWatch.availableMb() + " MB system memory free";
            c.getSource().sendSuccess(() -> Component.literal(cur == null ? "Not hosting a mesh world on this machine" + mem
                    : "Hosting: " + cur.status() + mem), false);
            return 1;
        }));
        d.register(Commands.literal("meshskin")
                .then(Commands.argument("mojangName", StringArgumentType.word()).executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    String n = StringArgumentType.getString(c, "mojangName");
                    SkinFix.CHOSEN.put(p.getGameProfile().getName().toLowerCase(), n);
                    SkinFix.apply(p, n);
                    c.getSource().sendSuccess(() -> Component.literal("Skin set to the one of " + n), false);
                    return 1;
                })));
        d.register(Commands.literal("meshmods")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("publish").executes(c -> {
                    var src = c.getSource();
                    MeshService cur = MeshService.current();
                    String world = cur != null ? cur.worldId() : MeshConfig.WORLD.get();
                    if (world == null || world.isEmpty()) {
                        src.sendFailure(Component.literal("No mesh world: set `world` in the mcmesh config or run this while hosting"));
                        return 0;
                    }
                    Thread.ofVirtual().start(() -> {
                        try {
                            var doc = ModGuard.snapshot();
                            new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), MeshAuth.token()).putWorldMods(world, doc);
                            int n = doc.getAsJsonArray("allow").size();
                            org.slf4j.LoggerFactory.getLogger("mcmesh").info("published {} mods as the allowlist of {}", n, world);
                            src.getServer().execute(() -> src.sendSuccess(() -> Component.literal("Published " + n + " mods as the allowlist of " + world), true));
                        } catch (Exception ex) {
                            org.slf4j.LoggerFactory.getLogger("mcmesh").warn("publishing the mod allowlist of {} failed: {}", world, ex.toString());
                            src.getServer().execute(() -> src.sendFailure(Component.literal("Publish failed (admin of the world only): " + ex.getMessage())));
                        }
                    });
                    return 1;
                })));
        d.register(Commands.literal("meshreport")
                .requires(s -> s.getPlayer() != null && MeshService.current() != null && MeshConfig.REPORT_CHAT_LINES.get() > 0)
                .then(Commands.literal("chat").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> report(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "reason"), true)))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> report(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "reason"), false)))));
        d.register(Commands.literal("meshwl")
                .requires(MeshCommands::canWhitelist)
                .executes(c -> {
                    var p = c.getSource().getPlayer();
                    if (p != null) {
                        WhitelistNet.send(p);
                    }
                    return 1;
                })
                .then(Commands.literal("list").executes(c -> {
                    String s = String.join(", ", MeshWhitelist.list());
                    c.getSource().sendSuccess(() -> Component.literal("Whitelist " + (MeshWhitelist.enabled() ? "on" : "off")
                            + ": " + (s.isEmpty() ? "empty" : s)), false);
                    return 1;
                }))
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    MeshWhitelist.add(n);
                    c.getSource().sendSuccess(() -> Component.literal(n + " added to the mesh whitelist"), false);
                    return 1;
                })))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    MeshWhitelist.remove(n);
                    c.getSource().sendSuccess(() -> Component.literal(n + " removed from the mesh whitelist"), false);
                    return 1;
                })))
                .then(Commands.literal("enabled").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
                    boolean on = BoolArgumentType.getBool(c, "on");
                    MeshWhitelist.enabled(on);
                    c.getSource().sendSuccess(() -> Component.literal("Mesh whitelist " + (on ? "on" : "off")), false);
                    return 1;
                }))));
    }

    private static int report(CommandSourceStack src, String target, String reason, boolean withChat) {
        var me = src.getPlayer();
        MeshService s = MeshService.current();
        if (me == null || s == null) {
            return 0;
        }
        int n = MeshConfig.REPORT_CHAT_LINES.get();
        String evidence = withChat ? MeshEvents.RECENT.last(n, 3900) : "";
        String who = me.getGameProfile().getName();
        src.sendSuccess(() -> Component.literal(withChat
                ? "Sending your report. The last " + n + " chat lines (everyone's) are attached and the world owner will read them."
                : "Sending your report without chat."), false);
        var server = src.getServer();
        Thread.ofVirtual().start(() -> {
            String msg;
            try {
                s.gateway().hostReport(s.lease().id(), s.lease().epoch(), who, target, reason, evidence);
                msg = "Report sent to the world owner. " + target + " is not told.";
            } catch (dev.mcmesh.core.GatewayException ex) {
                msg = "The report was not accepted: " + ex.getMessage();
            } catch (Exception ex) {
                msg = "The report could not be sent: " + ex;
            }
            String text = msg;
            server.execute(() -> me.sendSystemMessage(Component.literal(text)));
        });
        return 1;
    }

    private static boolean canWhitelist(CommandSourceStack s) {
        if (!MeshWhitelist.active()) {
            return false;
        }
        var p = s.getPlayer();
        return p == null ? s.hasPermission(4) : MeshOps.isOp(p.getGameProfile().getName());
    }

    // only listed operators may edit the list, so "everyone is an operator" cannot be used to grab the world
    private static boolean canEdit(CommandSourceStack s) {
        if (!MeshOps.active()) {
            return false;
        }
        var p = s.getPlayer();
        return p == null ? s.hasPermission(4) : MeshOps.isListed(p.getGameProfile().getName());
    }

    private static void refresh(CommandSourceStack s) {
        var list = s.getServer().getPlayerList();
        list.getPlayers().forEach(list::sendPlayerPermissionLevel);
        list.getPlayers().forEach(p -> s.getServer().getCommands().sendCommands(p));
    }
}
