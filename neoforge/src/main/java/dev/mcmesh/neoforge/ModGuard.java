package dev.mcmesh.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmesh.core.GatewayClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.ModList;

/** Hosting is refused when the loaded mods differ from the world's allowlist, so saves stay readable by every host. */
public final class ModGuard {
    private static final Set<String> BUILTIN = Set.of("minecraft", "neoforge", "mcmesh");
    private static Map<String, String> loaded;

    /** Why this machine must not host, or null when allowed. */
    public static volatile String problem;

    /** {id, sha256} of loaded mods the world does not allow; what a player can ask an admin to allow. */
    public static volatile java.util.List<String[]> offending = java.util.List.of();

    private ModGuard() {}

    public static boolean allowed(GatewayClient gw, String world) {
        problem = null;
        offending = java.util.List.of();
        JsonObject doc;
        try {
            doc = gw.worldMods(world);
        } catch (Exception e) {
            return true;
        }
        JsonArray allow = doc.has("allow") ? doc.getAsJsonArray("allow") : new JsonArray();
        if (allow.isEmpty()) {
            return true;
        }
        Set<String> ignore = new HashSet<>(BUILTIN);
        if (doc.has("ignore")) {
            for (JsonElement e : doc.getAsJsonArray("ignore")) {
                ignore.add(e.getAsString());
            }
        }
        Map<String, String> have = loaded();
        Set<String> wanted = new HashSet<>();
        java.util.List<String[]> off = new java.util.ArrayList<>();
        for (JsonElement e : allow) {
            JsonObject a = e.getAsJsonObject();
            String id = a.get("id").getAsString();
            wanted.add(id);
            if (ignore.contains(id)) {
                continue;
            }
            String h = have.get(id);
            if (h == null) {
                problem = problem == null ? "Missing mod: " + id : problem;
            } else if (a.has("hash") && !a.get("hash").getAsString().isEmpty() && !a.get("hash").getAsString().equalsIgnoreCase(h)) {
                problem = problem == null ? "Different version of mod: " + id : problem;
                off.add(new String[] {id, h});
            }
        }
        for (Map.Entry<String, String> en : have.entrySet()) {
            if (!wanted.contains(en.getKey()) && !ignore.contains(en.getKey())) {
                problem = problem == null ? "Extra mod not allowed by this world: " + en.getKey() : problem;
                off.add(new String[] {en.getKey(), en.getValue()});
            }
        }
        offending = off;
        if (problem != null) {
            return false;
        }
        return true;
    }

    /** Allowlist document built from the mods this instance has loaded. */
    public static JsonObject snapshot() {
        JsonArray allow = new JsonArray();
        new java.util.TreeMap<>(loaded()).forEach((id, h) -> {
            if (!BUILTIN.contains(id)) {
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                o.addProperty("hash", h);
                allow.add(o);
            }
        });
        JsonObject doc = new JsonObject();
        doc.add("allow", allow);
        doc.add("ignore", new JsonArray());
        return doc;
    }

    private static synchronized Map<String, String> loaded() {
        if (loaded == null) {
            Map<String, String> m = new HashMap<>();
            Map<Path, String> byFile = new HashMap<>();
            for (var mod : ModList.get().getMods()) {
                Path f = mod.getOwningFile().getFile().getFilePath();
                m.put(mod.getModId(), byFile.computeIfAbsent(f, ModGuard::sha256));
            }
            loaded = m;
        }
        return loaded;
    }

    private static String sha256(Path f) {
        try {
            if (!Files.isRegularFile(f)) {
                return "";
            }
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(f)) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) {
                    d.update(b, 0, n);
                }
            }
            return java.util.HexFormat.of().formatHex(d.digest());
        } catch (Exception e) {
            return "";
        }
    }
}
