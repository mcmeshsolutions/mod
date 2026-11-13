package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.GatewayList;
import dev.mcmesh.core.GatewayList.Gateway;
import dev.mcmesh.core.Identity;
import dev.mcmesh.core.IdentityKey;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** One session per gateway. A token never leaves the gateway that issued it. */
final class Gateways {
    private static final Map<String, Identity> SESSIONS = new ConcurrentHashMap<>();

    private Gateways() { }

    static Gateway main() {
        return MeshConfig.gateways().get(0);
    }

    static Identity identity(Gateway g) throws Exception {
        if (g.isDefault()) {
            return MeshAuth.cached();
        }
        Identity id = SESSIONS.get(g.apiUrl());
        if (id == null) {
            // key login only, with a key of its own: a mojang challenge or a shared key signature
            // could be replayed by this gateway against another one
            var key = IdentityKey.loadOrCreate(FMLPaths.CONFIGDIR.get().resolve("mcmesh-identity-" + g.id() + ".key"));
            id = new GatewayClient(g.apiUrl(), "").loginKey(key, ClientSession.name());
            SESSIONS.put(g.apiUrl(), id);
        }
        return id;
    }

    static void forget(Gateway g) {
        SESSIONS.remove(g.apiUrl());
    }

    static GatewayClient client(Gateway g) throws Exception {
        return new GatewayClient(g.apiUrl(), identity(g).token());
    }

    static boolean trusted(Gateway g) {
        try {
            return GatewayList.trusted(MeshConfig.trustedGatewaysFile(), g.apiUrl());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Asks once whether this install may host on the default gateway, then reports the answer. Call on the render thread. */
    static void confirmHosting(Screen back, java.util.function.Consumer<Boolean> then) {
        Gateway g = main();
        if (trusted(g)) {
            then.accept(true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(yes -> {
            mc.setScreen(back);
            if (yes) {
                try {
                    GatewayList.trust(MeshConfig.trustedGatewaysFile(), g.apiUrl());
                } catch (java.io.IOException e) {
                    LogUtils.getLogger().warn("could not remember the trusted gateway: {}", e.toString());
                }
            }
            then.accept(yes);
        }, Component.literal("Host worlds through " + g.host() + "?"),
                Component.literal("Hosting uploads your world to this gateway, lets it move the world to other players' machines"
                        + " and opens a tunnel from it to your game. Only continue if you trust whoever runs it.")));
    }
}
