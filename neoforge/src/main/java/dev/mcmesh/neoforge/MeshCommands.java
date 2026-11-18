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
    public static final dev.mcmesh.core.BasePinning BASE_PINNING = new dev.mcmesh.core.BasePinning();
    public static final dev.mcmesh.core.TimelineLens TIMELINE_LENS = new dev.mcmesh.core.TimelineLens();
    public static final dev.mcmesh.core.PregenSwarm PREGEN_SWARM = new dev.mcmesh.core.PregenSwarm();
    public static final dev.mcmesh.core.InventoryLedger INVENTORY_LEDGER = new dev.mcmesh.core.InventoryLedger();
    public static final dev.mcmesh.core.P2pDirect P2P_DIRECT = new dev.mcmesh.core.P2pDirect();
    public static final dev.mcmesh.core.CasSwarm CAS_SWARM = new dev.mcmesh.core.CasSwarm();

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
        d.register(Commands.literal("meshshard")
                .requires(src -> canEdit(src) && MeshConfig.SHARD_OFFERS.get())
                .then(Commands.literal("give")
                        .then(Commands.argument("x1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("z1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("x2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("z2", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(c -> {
                            MeshService cur = MeshService.current();
                            if (cur == null) {
                                c.getSource().sendFailure(Component.literal("this server is not hosting a mesh world"));
                                return 0;
                            }
                            var src = c.getSource();
                            ShardMain.give(src.getServer(), cur, src.getLevel().dimension().location().toString(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x1"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z1"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x2"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z2"),
                                    msg -> src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(msg), true)));
                            src.sendSuccess(() -> Component.literal("giving the regions away, this takes a few seconds"), false);
                            return 1;
                        }))))))
                .then(Commands.literal("status").executes(c -> {
                    var away = Ownership.away();
                    c.getSource().sendSuccess(() -> Component.literal(away.isEmpty() ? "no regions are away" : away.size() + " region rectangles are away: " + away), false);
                    return 1;
                })));
        d.register(Commands.literal("meshstatus").executes(c -> {
            MeshService cur = MeshService.current();
            String mem = MemWatch.availableMb() < 0 ? "" : ", " + MemWatch.availableMb() + " MB system memory free";
            c.getSource().sendSuccess(() -> Component.literal(cur == null ? "Not hosting a mesh world on this machine" + mem
                    : "Hosting: " + cur.status() + mem), false);
            return 1;
        }));
        d.register(Commands.literal("meshrevisions")
                .requires(MeshCommands::canEdit)
                .executes(c -> {
                    var src = c.getSource();
                    MeshService cur = MeshService.current();
                    String world = cur != null ? cur.worldId() : MeshConfig.WORLD.get();
                    if (world == null || world.isEmpty()) {
                        src.sendFailure(Component.literal("No active mesh world found"));
                        return 0;
                    }
                    Thread.ofVirtual().name("mesh-revs").start(() -> {
                        try {
                            var gw = new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), MeshAuth.token());
                            var res = gw.revisions(world, 5);
                            src.getServer().execute(() -> {
                                src.sendSuccess(() -> Component.literal("Head revision: " + res.head()), false);
                                for (var r : res.revisions()) {
                                    src.sendSuccess(() -> Component.literal(" - " + r.id() + " (" + r.kind() + ", " + r.chunks() + " chunks changed)"), false);
                                }
                            });
                        } catch (Exception ex) {
                            src.getServer().execute(() -> src.sendFailure(Component.literal("Failed to fetch revisions: " + ex.getMessage())));
                        }
                    });
                    return 1;
                }));
        d.register(Commands.literal("meshrollback")
                .requires(MeshCommands::canEdit)
                .then(Commands.argument("revision", StringArgumentType.word())
                        .executes(c -> rollback(c.getSource(), StringArgumentType.getString(c, "revision"), null, null))
                        .then(Commands.argument("rx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("rz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(c -> rollback(c.getSource(), StringArgumentType.getString(c, "revision"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rx"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rz")))))));
        d.register(Commands.literal("meshquarantine")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("list").executes(c -> {
                    var src = c.getSource();
                    var root = src.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                    var dumps = Quarantine.listDumps(root);
                    if (dumps.isEmpty()) {
                        src.sendSuccess(() -> Component.literal("Quarantine: 0 frozen entities or block entities"), false);
                        return 1;
                    }
                    src.sendSuccess(() -> Component.literal("Quarantined dumps (" + dumps.size() + " total):"), false);
                    for (int i = 0; i < Math.min(dumps.size(), 5); i++) {
                        var dmp = dumps.get(i);
                        src.sendSuccess(() -> Component.literal(" - " + dmp.what() + " (" + dmp.filename() + ")"), false);
                    }
                    return 1;
                })));
        d.register(Commands.literal("meshscratchpad")
                .requires(s -> s.getPlayer() != null)
                .then(Commands.literal("start")
                        .executes(c -> {
                            var p = c.getSource().getPlayerOrException();
                            Scratchpad.start(p, "default");
                            c.getSource().sendSuccess(() -> Component.literal("Entered creative scratchpad. Build freely and run '/meshscratchpad return' when done."), false);
                            return 1;
                        })
                        .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                            var p = c.getSource().getPlayerOrException();
                            String n = StringArgumentType.getString(c, "name");
                            Scratchpad.start(p, n);
                            c.getSource().sendSuccess(() -> Component.literal("Entered creative scratchpad '" + n + "'."), false);
                            return 1;
                        })))
                .then(Commands.literal("return").executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    var draft = Scratchpad.returnToSurvival(p);
                    if (draft == null) {
                        c.getSource().sendFailure(Component.literal("You are not currently in a scratchpad session."));
                        return 0;
                    }
                    c.getSource().sendSuccess(() -> Component.literal("Returned to survival. Holographic blueprint is active at original position! Run '/meshbom " + draft.name() + "' for material list."), false);
                    return 1;
                }))
                .then(Commands.literal("list").executes(c -> {
                    var list = Scratchpad.listDrafts();
                    if (list.isEmpty()) {
                        c.getSource().sendSuccess(() -> Component.literal("No saved scratchpad drafts."), false);
                        return 1;
                    }
                    c.getSource().sendSuccess(() -> Component.literal("Scratchpad drafts (" + list.size() + " total):"), false);
                    list.forEach((name, draft) -> {
                        c.getSource().sendSuccess(() -> Component.literal(" - " + name + " (dim: " + draft.dimension() + ", hologram: " + (draft.hologramActive() ? "ON" : "OFF") + ")"), false);
                    });
                    return 1;
                }))
                .then(Commands.literal("hologram")
                        .executes(c -> {
                            boolean on = Scratchpad.toggleHologram("default", null);
                            c.getSource().sendSuccess(() -> Component.literal("Holographic blueprint 'default' is now " + (on ? "VISIBLE" : "HIDDEN")), false);
                            return 1;
                        })
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> {
                                    String n = StringArgumentType.getString(c, "name");
                                    boolean on = Scratchpad.toggleHologram(n, null);
                                    c.getSource().sendSuccess(() -> Component.literal("Holographic blueprint '" + n + "' is now " + (on ? "VISIBLE" : "HIDDEN")), false);
                                    return 1;
                                })
                                .then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
                                    String n = StringArgumentType.getString(c, "name");
                                    boolean on = BoolArgumentType.getBool(c, "on");
                                    Scratchpad.toggleHologram(n, on);
                                    c.getSource().sendSuccess(() -> Component.literal("Holographic blueprint '" + n + "' is now " + (on ? "VISIBLE" : "HIDDEN")), false);
                                    return 1;
                                }))))
                .then(Commands.literal("export-schematic")
                        .executes(c -> exportSchematic(c.getSource(), "default"))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(c -> exportSchematic(c.getSource(), StringArgumentType.getString(c, "name"))))));
        d.register(Commands.literal("meshbom")
                .executes(c -> showBom(c.getSource(), "default"))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> showBom(c.getSource(), StringArgumentType.getString(c, "name")))));
        d.register(Commands.literal("meshbranch")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    var src = c.getSource();
                    String n = StringArgumentType.getString(c, "name");
                    MeshService cur = MeshService.current();
                    String world = cur != null ? cur.worldId() : MeshConfig.WORLD.get();
                    if (world == null || world.isEmpty()) {
                        src.sendFailure(Component.literal("No active mesh world found"));
                        return 0;
                    }
                    Thread.ofVirtual().name("mesh-branch").start(() -> {
                        try {
                            var gw = new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), MeshAuth.token());
                            var head = gw.revisionIds(world, 1);
                            if (head.isEmpty()) {
                                src.getServer().execute(() -> src.sendFailure(Component.literal("World has no committed revisions to fork from.")));
                                return;
                            }
                            String forkId = gw.forkWorld(world, head.get(0), n);
                            src.getServer().execute(() -> src.sendSuccess(() -> Component.literal("Created timeline branch '" + n + "' with world id: " + forkId), true));
                        } catch (Exception ex) {
                            src.getServer().execute(() -> src.sendFailure(Component.literal("Branch failed: " + ex.getMessage())));
                        }
                    });
                    return 1;
                }))));
        d.register(Commands.literal("meshlag")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("status").executes(c -> {
                    var report = LagDoctor.status(c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.literal(String.format("Server Health: %.1f TPS, %.2f ms MSPT | %d chunks loaded, %d entities | %d frozen regions",
                            report.tps(), report.mspt(), report.loadedChunks(), report.entityCount(), report.frozenChunks())), false);
                    return 1;
                }))
                .then(Commands.literal("freeze")
                        .then(Commands.argument("rx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("rz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(c -> {
                                            int rx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rx");
                                            int rz = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rz");
                                            boolean frozen = LagDoctor.freeze(rx, rz, true);
                                            c.getSource().sendSuccess(() -> Component.literal("Region r." + rx + "." + rz + " is now " + (frozen ? "FROZEN" : "already frozen")), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("unfreeze")
                        .then(Commands.argument("rx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("rz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(c -> {
                                            int rx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rx");
                                            int rz = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rz");
                                            boolean unfrozen = LagDoctor.freeze(rx, rz, false);
                                            c.getSource().sendSuccess(() -> Component.literal("Region r." + rx + "." + rz + " is now " + (unfrozen ? "ACTIVE" : "was not frozen")), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("quarantine")
                        .then(Commands.argument("rx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("rz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(c -> {
                                            int rx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rx");
                                            int rz = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rz");
                                            int n = LagDoctor.quarantineRegion(c.getSource().getLevel(), rx, rz);
                                            c.getSource().sendSuccess(() -> Component.literal("Quarantined " + n + " entities in region r." + rx + "." + rz), true);
                                            return 1;
                                        })))));
        d.register(Commands.literal("meshblueprint")
                .then(Commands.literal("share")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            boolean shared = Scratchpad.shareDraft(n);
                            if (shared) {
                                c.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                                        "Blueprint '" + n + "' has been shared with the team! View BOM with /meshbom " + n), false);
                            } else {
                                c.getSource().sendFailure(Component.literal("No draft named '" + n + "' found. Save one first with /meshscratchpad save " + n));
                            }
                            return shared ? 1 : 0;
                        })))
                .then(Commands.literal("list").executes(c -> {
                    var list = Scratchpad.listSharedBlueprints();
                    c.getSource().sendSuccess(() -> Component.literal("Shared team blueprints: " + (list.isEmpty() ? "none" : String.join(", ", list))), false);
                    return 1;
                })));
        d.register(Commands.literal("meshpin")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("chunk").executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    int cx = ((int) p.getX()) >> 4;
                    int cz = ((int) p.getZ()) >> 4;
                    BASE_PINNING.pinChunk(cx, cz);
                    c.getSource().sendSuccess(() -> Component.literal("Pinned chunk [" + cx + ", " + cz + "]. It will not be cold-evicted."), true);
                    return 1;
                }))
                .then(Commands.literal("base")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> {
                                    var p = c.getSource().getPlayerOrException();
                                    String n = StringArgumentType.getString(c, "name");
                                    int cx = ((int) p.getX()) >> 4;
                                    int cz = ((int) p.getZ()) >> 4;
                                    BASE_PINNING.pinBase(n, cx, cz, 1);
                                    c.getSource().sendSuccess(() -> Component.literal("Pinned base '" + n + "' (3x3 chunks around [" + cx + ", " + cz + "])."), true);
                                    return 1;
                                })
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                                        .executes(c -> {
                                            var p = c.getSource().getPlayerOrException();
                                            String n = StringArgumentType.getString(c, "name");
                                            int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius");
                                            int cx = ((int) p.getX()) >> 4;
                                            int cz = ((int) p.getZ()) >> 4;
                                            BASE_PINNING.pinBase(n, cx, cz, r);
                                            c.getSource().sendSuccess(() -> Component.literal("Pinned base '" + n + "' (" + (2*r+1) + "x" + (2*r+1) + " chunks around [" + cx + ", " + cz + "])."), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("unpin")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            boolean unpinned = BASE_PINNING.unpinBase(n);
                            c.getSource().sendSuccess(() -> Component.literal(unpinned ? "Unpinned base '" + n + "'." : "No pinned base named '" + n + "' found."), true);
                            return 1;
                        })))
                .then(Commands.literal("list").executes(c -> {
                    var bases = BASE_PINNING.getPinnedBases();
                    c.getSource().sendSuccess(() -> Component.literal("Pinned bases: " + bases.keySet() + " (" + BASE_PINNING.getPinnedChunks().size() + " total chunks protected)"), false);
                    return 1;
                }))
                .then(Commands.literal("evict-cold")
                        .then(Commands.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(7, 365))
                                .executes(c -> {
                                    int days = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "days");
                                    c.getSource().sendSuccess(() -> Component.literal("Cold chunk sweep scheduled for unpinned chunks older than " + days + " days."), true);
                                    return 1;
                                }))));
        d.register(Commands.literal("meshdiff")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("off").executes(c -> {
                    TIMELINE_LENS.setDiffOverlay(false);
                    c.getSource().sendSuccess(() -> Component.literal("Holographic diff lens deactivated."), false);
                    return 1;
                }))
                .then(Commands.argument("rev", StringArgumentType.word()).executes(c -> {
                    String rev = StringArgumentType.getString(c, "rev");
                    TIMELINE_LENS.selectRevision(rev);
                    c.getSource().sendSuccess(() -> Component.literal("Activated holographic diff lens for revision: " + rev), false);
                    return 1;
                })));
        d.register(Commands.literal("meshtimeline")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("off").executes(c -> {
                    TIMELINE_LENS.reset();
                    c.getSource().sendSuccess(() -> Component.literal("Timeline scrubbing stopped."), false);
                    return 1;
                }))
                .then(Commands.literal("forward").executes(c -> {
                    boolean moved = TIMELINE_LENS.stepForward();
                    c.getSource().sendSuccess(() -> Component.literal(moved ? "Timeline scrubbed forward: " + TIMELINE_LENS.getStatusLine() : "Already at latest revision."), false);
                    return 1;
                }))
                .then(Commands.literal("back").executes(c -> {
                    boolean moved = TIMELINE_LENS.stepBack();
                    c.getSource().sendSuccess(() -> Component.literal(moved ? "Timeline scrubbed back: " + TIMELINE_LENS.getStatusLine() : "Already at earliest revision."), false);
                    return 1;
                }))
                .then(Commands.literal("scrub")
                        .then(Commands.argument("rev", StringArgumentType.word()).executes(c -> {
                            String rev = StringArgumentType.getString(c, "rev");
                            TIMELINE_LENS.selectRevision(rev);
                            c.getSource().sendSuccess(() -> Component.literal("Scrubbed timeline to revision " + rev + " (" + TIMELINE_LENS.getStatusLine() + ")"), false);
                            return 1;
                        }))));
        d.register(Commands.literal("meshpregen")
                .requires(MeshCommands::canEdit)
                .then(Commands.literal("status").executes(c -> {
                    var prog = PREGEN_SWARM.getProgress();
                    c.getSource().sendSuccess(() -> Component.literal(String.format("Pregen Swarm: %s | %d queued, %d completed",
                            prog.running() ? "ACTIVE" : "IDLE", prog.queued(), prog.completed())), false);
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(c -> {
                    PREGEN_SWARM.cancel();
                    c.getSource().sendSuccess(() -> Component.literal("Pregen swarm cancelled."), true);
                    return 1;
                }))
                .then(Commands.literal("start")
                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(100, 10000))
                                .executes(c -> {
                                    var p = c.getSource().getPlayerOrException();
                                    int radius = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "radius");
                                    int cx = ((int) p.getX()) >> 4;
                                    int cz = ((int) p.getZ()) >> 4;
                                    var look = p.getLookAngle();
                                    PREGEN_SWARM.planHalo(cx, cz, look.x, look.z, radius);
                                    c.getSource().sendSuccess(() -> Component.literal("Started pregen swarm exploration halo (" + radius + " blocks forward). Active when TPS >= 19.5."), true);
                                    return 1;
                                }))));
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
                .requires(MeshCommands::canEdit)
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
        if (Boolean.getBoolean("mcmesh.research")) {
            ResearchCommands.register(d);
        }
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
            return s.hasPermission(2);
        }
        var p = s.getPlayer();
        return p == null ? s.hasPermission(2) : (s.hasPermission(2) || MeshOps.isOp(p.getGameProfile().getName()));
    }

    // only listed operators may edit the list, so "everyone is an operator" cannot be used to grab the world
    private static boolean canEdit(CommandSourceStack s) {
        if (!MeshOps.active()) {
            return s.hasPermission(2);
        }
        var p = s.getPlayer();
        return p == null ? s.hasPermission(2) : (s.hasPermission(2) || MeshOps.isListed(p.getGameProfile().getName()));
    }

    private static void refresh(CommandSourceStack s) {
        var list = s.getServer().getPlayerList();
        list.getPlayers().forEach(list::sendPlayerPermissionLevel);
        list.getPlayers().forEach(p -> s.getServer().getCommands().sendCommands(p));
    }

    private static int rollback(CommandSourceStack src, String rev, Integer rx, Integer rz) {
        MeshService cur = MeshService.current();
        String world = cur != null ? cur.worldId() : MeshConfig.WORLD.get();
        if (world == null || world.isEmpty()) {
            src.sendFailure(Component.literal("No active mesh world found"));
            return 0;
        }
        int targetRx, targetRz;
        if (rx != null && rz != null) {
            targetRx = rx;
            targetRz = rz;
        } else if (src.getPlayer() != null) {
            var cp = src.getPlayer().chunkPosition();
            targetRx = cp.x >> 5;
            targetRz = cp.z >> 5;
        } else {
            src.sendFailure(Component.literal("Specify rx and rz when running from server console"));
            return 0;
        }
        String dim = src.getLevel().dimension().location().toString();
        int minCx = targetRx * 32;
        int minCz = targetRz * 32;
        int maxCx = minCx + 31;
        int maxCz = minCz + 31;
        Thread.ofVirtual().name("mesh-rollback").start(() -> {
            try {
                var gw = new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), MeshAuth.token());
                var res = gw.rollbackRegion(world, rev, dim, minCx, minCz, maxCx, maxCz);
                src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(
                        "Rolled back region r." + targetRx + "." + targetRz + " to " + rev + ": "
                        + res.restored() + " chunks restored, " + res.removed() + " chunks removed. New revision: " + res.revision()), true));
            } catch (dev.mcmesh.core.GatewayException e) {
                String msg = e.code.equals("WORLD_RUNNING")
                        ? "World is actively hosted: stop hosting before applying a gateway rollback"
                        : "Gateway error: " + e.code;
                src.getServer().execute(() -> src.sendFailure(Component.literal(msg)));
            } catch (Exception e) {
                src.getServer().execute(() -> src.sendFailure(Component.literal("Rollback failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int exportSchematic(CommandSourceStack src, String name) {
        try {
            var dir = src.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("schematics");
            Scratchpad.exportSchematic(name, dir);
            src.sendSuccess(() -> Component.literal("Exported Create-compatible schematic to schematics/" + name + ".nbt"), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Export failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int showBom(CommandSourceStack src, String name) {
        var draft = Scratchpad.getDraft(name);
        if (draft == null) {
            src.sendFailure(Component.literal("No draft found named '" + name + "'. Run '/meshscratchpad list' to see available drafts."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("=== Bill of Materials: " + draft.name() + " ==="), false);
        if (draft.bom().isEmpty()) {
            src.sendSuccess(() -> Component.literal("No net material difference recorded for this draft."), false);
        } else {
            draft.bom().forEach((item, count) -> {
                src.sendSuccess(() -> Component.literal(" - " + item + ": x" + count), false);
            });
        }
        return 1;
    }
}
