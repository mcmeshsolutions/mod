package dev.mcmesh.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
 * Handles skin resolution for hosted mesh servers. Resolves custom skins via gateway /v1/skins/:hash,
 * direct skin URLs or hashes, and falls back to Mojang session server lookups for offline-mode players.
 */
@EventBusSubscriber(modid = "mcmesh")
public final class SkinFix {
    private static final Logger LOG = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<String, Property> CACHE = new ConcurrentHashMap<>();
    private static final Property NONE = new Property("textures", "");
    /** Maps a player to the custom identifier (Mojang name, skin hash, or URL) chosen with /meshskin. */
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

    public static void apply(ServerPlayer p, String identifier) {
        Thread.ofVirtual().start(() -> {
            Property tex = resolve(p.getUUID().toString(), p.getGameProfile().getName(), identifier);
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

    private static Property resolve(String uuid, String name, String identifier) {
        String key = (uuid + ":" + identifier).toLowerCase();
        Property cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        String apiUrl = MeshConfig.API_URL.get();
        if (apiUrl != null && apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }

        // 1. Direct URL
        if (identifier.startsWith("http://") || identifier.startsWith("https://")) {
            Property p = createSkinProperty(identifier, uuid, name);
            CACHE.put(key, p);
            return p;
        }

        // 2. Direct 64-char hex hash
        if (identifier.length() == 64 && isHex(identifier) && apiUrl != null && !apiUrl.isBlank()) {
            Property p = createSkinProperty(apiUrl + "/v1/skins/" + identifier, uuid, name);
            CACHE.put(key, p);
            return p;
        }

        // 3. Check Gateway for custom skin registered to this player's UUID
        if (apiUrl != null && !apiUrl.isBlank()) {
            try {
                JsonObject skinRes = get(apiUrl + "/v1/users/" + uuid + "/skin");
                if (skinRes != null && skinRes.has("skin_url")) {
                    String skinUrl = apiUrl + skinRes.get("skin_url").getAsString();
                    Property p = createSkinProperty(skinUrl, uuid, name);
                    CACHE.put(key, p);
                    return p;
                }
            } catch (Exception ex) {
                LOG.debug("gateway skin check for {} failed: {}", uuid, ex.toString());
            }
        }

        // 4. Fallback to Mojang session server by username
        Property p = lookupMojang(identifier);
        CACHE.put(key, p);
        return p;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static Property createSkinProperty(String skinUrl, String uuid, String name) {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("profileId", uuid.replace("-", ""));
        root.addProperty("profileName", name);
        JsonObject textures = new JsonObject();
        JsonObject skin = new JsonObject();
        skin.addProperty("url", skinUrl);
        textures.add("SKIN", skin);
        root.add("textures", textures);
        String json = root.toString();
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new Property("textures", b64);
    }

    private static Property lookupMojang(String name) {
        Property found = NONE;
        try {
            JsonObject id = get("https://api.mojang.com/users/profiles/minecraft/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
            if (id != null && id.has("id")) {
                JsonObject prof = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id.get("id").getAsString() + "?unsigned=false");
                if (prof != null && prof.has("properties")) {
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
        return found;
    }

    private static JsonObject get(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? JsonParser.parseString(r.body()).getAsJsonObject() : null;
    }
}
