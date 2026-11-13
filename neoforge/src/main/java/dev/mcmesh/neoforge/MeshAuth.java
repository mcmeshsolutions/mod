package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.Identity;
import dev.mcmesh.core.IdentityKey;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Mojang first, then the local Ed25519 key. A configured static token skips both. */
public final class MeshAuth {
    private static final Logger LOG = LogUtils.getLogger();

    private static Identity cached;

    private MeshAuth() { }

    public static synchronized Identity peek() {
        return cached;
    }

    public static synchronized Identity cached() throws Exception {
        if (cached == null) {
            cached = login();
        }
        return cached;
    }

    public static Path keyFile() {
        return FMLPaths.CONFIGDIR.get().resolve("mcmesh-identity.key");
    }

    public static Identity login() throws Exception {
        String mode = MeshConfig.AUTH_MODE.get();
        GatewayClient anon = new GatewayClient(MeshConfig.API_URL.get(), "");
        boolean client = FMLEnvironment.dist.isClient();
        String name = client ? dev.mcmesh.neoforge.client.ClientSession.name() : MeshConfig.PLAYER_NAME.get();
        if (client && !mode.equals("key")) {
            try {
                // mojang rejects some random serverIds, a fresh challenge almost always passes
                for (int attempt = 1; ; attempt++) {
                    String challenge = anon.authChallenge();
                    try {
                        dev.mcmesh.neoforge.client.ClientSession.joinServer(challenge);
                    } catch (Exception e) {
                        if (attempt < 6 && String.valueOf(e.getMessage()).contains("Invalid serverId")) {
                            continue;
                        }
                        throw e;
                    }
                    Identity id = anon.loginMojang(challenge, name);
                    LOG.info("mesh login via Mojang as {}", id.name());
                    return id;
                }
            } catch (Exception e) {
                if (mode.equals("mojang")) {
                    throw e;
                }
                LOG.info("Mojang login unavailable ({}), using local key", e.toString());
            }
        }
        Identity id = anon.loginKey(IdentityKey.loadOrCreate(keyFile()), name);
        LOG.info("mesh login via key as {}", id.name());
        return id;
    }
}
