package dev.mcmesh.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mod allowlist check. Rules are {"allow":[{"id","hash"}],"ignore":["id"]}, the same document for a world and for a pack. */
public final class ModRules {
    /** problem is null when hosting is allowed; offending holds {id, sha256} of loaded mods the rules do not allow. */
    public record Result(String problem, List<String[]> offending) { }

    private ModRules() { }

    /** A world's own allowlist wins outright, so a local pack file can never loosen what a world admin set. Null means unrestricted. */
    public static JsonObject effective(JsonObject world, JsonObject pack) {
        if (restricts(world)) {
            return world;
        }
        return restricts(pack) ? pack : null;
    }

    private static boolean restricts(JsonObject doc) {
        return doc != null && doc.has("allow") && !doc.getAsJsonArray("allow").isEmpty();
    }

    /** have maps loaded mod id to the sha256 of its jar; always are ids that never count. */
    public static Result check(JsonObject rules, Map<String, String> have, Set<String> always) {
        if (!restricts(rules)) {
            return new Result(null, List.of());
        }
        JsonArray allow = rules.getAsJsonArray("allow");
        Set<String> ignore = new HashSet<>(always);
        if (rules.has("ignore")) {
            for (JsonElement e : rules.getAsJsonArray("ignore")) {
                ignore.add(e.getAsString());
            }
        }
        String problem = null;
        Set<String> wanted = new HashSet<>();
        List<String[]> off = new ArrayList<>();
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
        for (Map.Entry<String, String> en : new java.util.TreeMap<>(have).entrySet()) {
            if (!wanted.contains(en.getKey()) && !ignore.contains(en.getKey())) {
                problem = problem == null ? "Extra mod not allowed: " + en.getKey() : problem;
                off.add(new String[] {en.getKey(), en.getValue()});
            }
        }
        return new Result(problem, off);
    }
}
