package dev.mcmesh.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * The hosted server runs offline, so it knows no skins. Looks the player's name up at Mojang and
 * attaches the textures property, so everyone sees real skins. Same idea as SkinsRestorer.
 */
@EventBusSubscriber(modid = "mcmesh")
public final class SkinFix {
    private static final Logger LOG = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<String, Property> CACHE = new ConcurrentHashMap<>();
    private static final Property NONE = new Property("textures", "");
    /** Maps a player to the Mojang account whose skin they chose with /meshskin. */
    static final Map<String, String> CHOSEN = new ConcurrentHashMap<>();

    private SkinFix() { }

    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent e) {
        if (MeshBoot.held() == null || !(e.getEntity() instanceof ServerPlayer p)
                || p.getGameProfile().getProperties().containsKey("textures")) {
            return;
        }
        apply(p, CHOSEN.getOrDefault(p.getGameProfile().getName().toLowerCase(), p.getGameProfile().getName()));
    }

    static void apply(ServerPlayer p, String mojangName) {
        Thread.ofVirtual().start(() -> {
            Property tex = lookup(mojangName);
            if (tex == NONE) {
                return;
            }
            p.server.execute(() -> {
                var props = p.getGameProfile().getProperties();
                props.removeAll("textures");
                props.put("textures", tex);
                var list = p.server.getPlayerList();
                list.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID())));
                list.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
            });
        });
    }

    private static Property lookup(String name) {
        String key = name.toLowerCase();
        Property c = CACHE.get(key);
        if (c != null) {
            return c;
        }
        Property found = NONE;
        try {
            JsonObject id = get("https://api.mojang.com/users/profiles/minecraft/" + java.net.URLEncoder.encode(name, "UTF-8"));
            if (id != null && id.has("id")) {
                JsonObject prof = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id.get("id").getAsString() + "?unsigned=false");
                if (prof != null) {
                    for (var el : prof.getAsJsonArray("properties")) {
                        JsonObject o = el.getAsJsonObject();
                        if ("textures".equals(o.get("name").getAsString())) {
                            found = new Property("textures", o.get("value").getAsString(),
                                    o.has("signature") ? o.get("signature").getAsString() : null);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LOG.debug("skin lookup for {} failed: {}", name, ex.toString());
            return NONE;
        }
        CACHE.put(key, found);
        return found;
    }

    private static JsonObject get(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? JsonParser.parseString(r.body()).getAsJsonObject() : null;
    }
}
