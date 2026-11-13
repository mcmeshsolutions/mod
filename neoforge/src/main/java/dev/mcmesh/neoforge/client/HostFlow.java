package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.Identity;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshBoot;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshService;
import dev.mcmesh.neoforge.MeshTap;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Takes the host lease for a synced world, syncs its folder into saves/ and opens it as the integrated server. */
public final class HostFlow {
    private HostFlow() { }

    /** Name of the host a guest session is connected to, for the pause menu. */
    static volatile String guestOf;

    public static String saveName(String worldId) {
        return "mesh-" + worldId;
    }

    /** Returns a message when this machine could not become the host, null when the world is opening. */
    public static String host(GatewayClient.WorldInfo w, Screen back, Consumer<String> status) throws Exception {
        String memory = dev.mcmesh.core.PreFlight.heapProblem(Runtime.getRuntime().maxMemory(), MeshConfig.PACK_MIN_HEAP_MB.get());
        if (memory != null) {
            return memory;
        }
        Minecraft mc = Minecraft.getInstance();
        Standby.stop();
        MeshService.afterHandoff = Standby::rejoin;
        MeshService.handoffWarning = HostFlow::warnHandoff;
        status.accept("Signing in");
        Identity id = MeshAuth.cached();
        Path root = mc.gameDirectory.toPath().resolve("saves").resolve(saveName(w.id())).toAbsolutePath().normalize();
        status.accept("Taking the host lease and syncing the world");
        MeshService s = MeshService.join(root, w.id(), id.token(), id.name(), 1, Runtime.getRuntime().availableProcessors());
        if (s == null) {
            String why = dev.mcmesh.neoforge.ModGuard.problem;
            if (why != null) {
                return "This world only allows hosts with its exact mods. " + why + ".";
            }
            String addr = MeshConfig.PUBLIC_HOST.get();
            return "Another player is hosting this world. Join " + (addr.isEmpty() ? "the gateway address" : addr) + " from Multiplayer.";
        }
        MeshBoot.hold(s, root);
        mc.execute(() -> mc.createWorldOpenFlows().openWorld(saveName(w.id()), () -> {
            MeshBoot.clear();
            s.shutdown();
            mc.setScreen(back);
        }));
        return null;
    }

    private static void warnHandoff(String name, Runnable go) {
        Minecraft mc = Minecraft.getInstance();
        Standby.markSwitching(90_000);
        mc.execute(() -> mc.setScreen(new HandoverScreen(name + " wants to play together",
                "Hosting moves to their machine for better performance. You will be reconnected automatically.", 8_000, go)));
    }

    /** Opens a world this machine just became host of, leaving any guest session first. */
    static void takeover(MeshService s, Path root, String wid) {
        Minecraft mc = Minecraft.getInstance();
        MeshTap.joinHost = "";
        MeshTap.joinWorld = "";
        MeshBoot.hold(s, root);
        mc.execute(() -> {
            if (mc.level != null) {
                mc.disconnect();
            }
            mc.createWorldOpenFlows().openWorld(saveName(wid), () -> {
                MeshBoot.clear();
                s.shutdown();
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            });
        });
    }

    /** Registers a new empty world at the gateway and lets vanilla generate it as the first revision. */
    public static String create(String name, Screen back, Consumer<String> status) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        Standby.stop();
        MeshService.afterHandoff = Standby::rejoin;
        MeshService.handoffWarning = HostFlow::warnHandoff;
        status.accept("Signing in");
        Identity id = MeshAuth.cached();
        status.accept("Creating the world at the gateway");
        GatewayClient gw = new GatewayClient(MeshConfig.API_URL.get(), id.token());
        String wid = gw.createWorld(name, net.minecraft.SharedConstants.getCurrentVersion().getName(), "neoforge");
        Path root = mc.gameDirectory.toPath().resolve("saves").resolve(saveName(wid)).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(root);
        MeshService s = MeshService.join(root, wid, id.token(), id.name(), 1, Runtime.getRuntime().availableProcessors());
        if (s == null) {
            return "Could not take the host lease for the new world.";
        }
        MeshBoot.hold(s, root);
        mc.execute(() -> mc.createWorldOpenFlows().createFreshLevel(saveName(wid),
                new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.SURVIVAL, false,
                        net.minecraft.world.Difficulty.NORMAL, false, new net.minecraft.world.level.GameRules(),
                        net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, back));
        return null;
    }

    /** Connects to a world another player is hosting, through the gateway relay. */
    public static String join(GatewayClient.WorldInfo w, String relay, Screen back) {
        String memory = dev.mcmesh.core.PreFlight.heapProblem(Runtime.getRuntime().maxMemory(), MeshConfig.PACK_MIN_HEAP_MB.get());
        if (memory != null) {
            return memory;
        }
        if (relay.isEmpty()) {
            return "This gateway has no relay, so guests cannot connect.";
        }
        Minecraft mc = Minecraft.getInstance();
        var addr = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(relay);
        MeshTap.joinHost = addr.getHost();
        MeshTap.joinWorld = w.id();
        guestOf = w.host();
        if (!w.role().equals("CLIENT_ONLY")) {
            Standby.start(w, relay);
        }
        mc.execute(() -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(back, mc, addr,
                new net.minecraft.client.multiplayer.ServerData(w.name(), relay, net.minecraft.client.multiplayer.ServerData.Type.OTHER),
                false, null));
        return null;
    }

    public static void publish(net.minecraft.server.MinecraftServer server) throws Exception {
        var is = (net.minecraft.client.server.IntegratedServer) server;
        java.util.concurrent.CompletableFuture<Boolean> ok = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            boolean r;
            try {
                r = is.publishServer(is.getDefaultGameType(), false, MeshConfig.LOCAL_PORT.get());
            } catch (NullPointerException e) {
                // the listener is already up; vanilla only trips on the not yet created local player
                r = is.isPublished();
            }
            ok.complete(r);
        });
        if (!ok.get()) {
            throw new IllegalStateException("could not open the local port " + MeshConfig.LOCAL_PORT.get());
        }
    }
}
