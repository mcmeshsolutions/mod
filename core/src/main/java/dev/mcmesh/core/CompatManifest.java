package dev.mcmesh.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reads a mod's data/&lt;modid&gt;/mcmesh_compat.json (schema version 1). Unknown keys are ignored so newer manifests still load. */
public final class CompatManifest {
    public static final int SCHEMA = 1;
    private static final Set<String> SCOPES = Set.of("WORLD_GLOBAL", "TEAM", "PLAYER_PRIVATE");
    private static final Set<String> COUPLINGS = Set.of("HARD_PIN", "SOFT_AFFINITY", "DYNAMIC_TRACK");
    private static final Set<String> DIM_POLICIES = Set.of("EPHEMERAL_NO_CAS");

    public record AuxPath(String glob, String scope) { }

    public record Coupling(String blockTag, String mode) { }

    public record UdpChannel(String id, int port) { }

    public record Quarantine(List<String> neverDelete, long maxTickMicros) { }

    public record DimensionPolicy(String dimension, String policy) { }

    public record Parsed(List<AuxPath> auxSyncPaths, List<Coupling> chunkCouplings, List<UdpChannel> udpChannels,
                         Quarantine quarantine, List<DimensionPolicy> dimensionPolicies) { }

    public record Result(Parsed manifest, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private CompatManifest() { }

    /** Every problem is collected; manifest is null when there is any. A manifest that is wrong is ignored whole, never half applied. */
    public static Result parse(JsonObject doc) {
        List<String> bad = new ArrayList<>();
        if (!doc.has("schema") || !doc.get("schema").isJsonPrimitive() || !doc.get("schema").getAsJsonPrimitive().isNumber()
                || doc.get("schema").getAsInt() != SCHEMA) {
            return new Result(null, List.of("schema must be " + SCHEMA));
        }
        List<AuxPath> aux = new ArrayList<>();
        for (JsonObject o : objects(doc, "aux_sync_paths", bad)) {
            String glob = str(o, "glob");
            String scope = str(o, "scope");
            if (glob == null || glob.isEmpty() || glob.startsWith("/") || glob.contains("\\") || List.of(glob.split("/")).contains("..")) {
                bad.add("aux_sync_paths: glob must be a relative path without '..' or backslashes: " + glob);
            } else if (scope == null || !SCOPES.contains(scope)) {
                bad.add("aux_sync_paths: unknown scope " + scope);
            } else {
                aux.add(new AuxPath(glob, scope));
            }
        }
        List<Coupling> couplings = new ArrayList<>();
        for (JsonObject o : objects(doc, "chunk_couplings", bad)) {
            String tag = str(o, "block_tag");
            String mode = str(o, "mode");
            if (tag == null || tag.isEmpty() || mode == null || !COUPLINGS.contains(mode)) {
                bad.add("chunk_couplings: needs block_tag and a mode of " + COUPLINGS);
            } else {
                couplings.add(new Coupling(tag, mode));
            }
        }
        List<UdpChannel> udp = new ArrayList<>();
        for (JsonObject o : objects(doc, "udp_channels", bad)) {
            String id = str(o, "id");
            int port = o.has("port") && o.get("port").isJsonPrimitive() && o.get("port").getAsJsonPrimitive().isNumber() ? o.get("port").getAsInt() : -1;
            if (id == null || id.isEmpty() || port < 1 || port > 65535) {
                bad.add("udp_channels: needs an id and a port from 1 to 65535");
            } else {
                udp.add(new UdpChannel(id, port));
            }
        }
        Quarantine quarantine = new Quarantine(List.of(), 0);
        if (doc.has("quarantine_rules")) {
            if (!doc.get("quarantine_rules").isJsonObject()) {
                bad.add("quarantine_rules must be an object");
            } else {
                JsonObject q = doc.getAsJsonObject("quarantine_rules");
                List<String> never = new ArrayList<>();
                if (q.has("never_delete")) {
                    if (!q.get("never_delete").isJsonArray()) {
                        bad.add("quarantine_rules.never_delete must be an array of ids");
                    } else {
                        for (JsonElement e : q.getAsJsonArray("never_delete")) {
                            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                                never.add(e.getAsString());
                            } else {
                                bad.add("quarantine_rules.never_delete holds only strings");
                            }
                        }
                    }
                }
                long micros = 0;
                if (q.has("max_tick_micros")) {
                    JsonElement m = q.get("max_tick_micros");
                    if (m.isJsonPrimitive() && m.getAsJsonPrimitive().isNumber() && m.getAsLong() > 0) {
                        micros = m.getAsLong();
                    } else {
                        bad.add("quarantine_rules.max_tick_micros must be a positive number");
                    }
                }
                quarantine = new Quarantine(List.copyOf(never), micros);
            }
        }
        List<DimensionPolicy> dims = new ArrayList<>();
        for (JsonObject o : objects(doc, "dimension_policies", bad)) {
            String dim = str(o, "dimension");
            String policy = str(o, "policy");
            if (dim == null || dim.isEmpty() || policy == null || !DIM_POLICIES.contains(policy)) {
                bad.add("dimension_policies: needs a dimension and a policy of " + DIM_POLICIES);
            } else {
                dims.add(new DimensionPolicy(dim, policy));
            }
        }
        if (!bad.isEmpty()) {
            return new Result(null, List.copyOf(bad));
        }
        return new Result(new Parsed(List.copyOf(aux), List.copyOf(couplings), List.copyOf(udp), quarantine, List.copyOf(dims)), List.of());
    }

    private static List<JsonObject> objects(JsonObject doc, String key, List<String> bad) {
        List<JsonObject> out = new ArrayList<>();
        if (!doc.has(key)) {
            return out;
        }
        if (!doc.get(key).isJsonArray()) {
            bad.add(key + " must be an array");
            return out;
        }
        JsonArray a = doc.getAsJsonArray(key);
        for (JsonElement e : a) {
            if (e.isJsonObject()) {
                out.add(e.getAsJsonObject());
            } else {
                bad.add(key + " holds only objects");
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString() ? o.get(key).getAsString() : null;
    }
}
