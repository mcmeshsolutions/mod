package dev.mcmesh.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.ModRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Hosting is refused when the loaded mods differ from the world's allowlist, or the pack's when the world has none, so saves stay readable by every host. */
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
        JsonObject doc = null;
        try {
            doc = gw.worldMods(world);
        } catch (Exception e) {
            // gateway without the mods route, or unreachable: the pack rules still apply
        }
        JsonObject pack;
        try {
            pack = packRules();
        } catch (Exception e) {
            // an unreadable pack file must not quietly lift the restriction it was meant to carry
            problem = "Pack config mcmesh-allowlist.json is unreadable";
            return false;
        }
        ModRules.Result r = ModRules.check(ModRules.effective(doc, pack), loaded(), BUILTIN);
        offending = r.offending();
        problem = r.problem();
        return problem == null;
    }

    /** The pack author's allowlist: the whole config/mcmesh-allowlist.json. Null when the pack ships none. */
    static JsonObject packRules() throws java.io.IOException {
        Path f = FMLPaths.CONFIGDIR.get().resolve("mcmesh-allowlist.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new java.io.IOException(e);
        }
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
