package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshTap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

/** Keeps a normal Multiplayer list entry for the gateway, so joining looks like joining any server. */
final class ServerEntry {
    private static volatile boolean running;

    private ServerEntry() { }

    static void ensure() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            try {
                var id = MeshAuth.cached();
                var l = new GatewayClient(MeshConfig.API_URL.get(), id.token()).listWorlds();
                if (l.relay().isEmpty() || l.worlds().isEmpty()) {
                    return;
                }
                var w = l.worlds().stream().filter(x -> x.id().equals(MeshConfig.WORLD.get())).findFirst().orElse(l.worlds().get(0));
                MeshTap.defaultWorld = w.id();
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    ServerList list = new ServerList(mc);
                    list.load();
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).ip.equalsIgnoreCase(l.relay())) {
                            return;
                        }
                    }
                    list.add(new ServerData(w.name(), l.relay(), ServerData.Type.OTHER), false);
                    list.save();
                    if (mc.screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen m) {
                        mc.setScreen(new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new net.minecraft.client.gui.screens.TitleScreen()));
                    }
                });
            } catch (Exception e) {
                LogUtils.getLogger().debug("server list entry skipped: {}", e.toString());
            } finally {
                running = false;
            }
        }, "mesh-serverlist");
        t.setDaemon(true);
        t.start();
    }
}
