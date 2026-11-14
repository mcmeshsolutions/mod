package dev.mcmesh.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The parts of the loaded compat manifests that the host acts on, indexed for cheap lookups on the tick path. */
public final class CompatRules {
    public static final CompatRules NONE = new CompatRules(Map.of(), Set.of(), Set.of());

    private final Map<String, Long> maxTickNanos;
    private final Set<String> ephemeralDimensions;
    private final Set<String> neverDelete;

    private CompatRules(Map<String, Long> maxTickNanos, Set<String> ephemeralDimensions, Set<String> neverDelete) {
        this.maxTickNanos = maxTickNanos;
        this.ephemeralDimensions = ephemeralDimensions;
        this.neverDelete = neverDelete;
    }

    /** A manifest only speaks for its own mod: its tick limit applies to that mod's namespace. */
    public static CompatRules of(Map<String, CompatManifest.Parsed> byMod) {
        Map<String, Long> ticks = new HashMap<>();
        Set<String> dims = new HashSet<>();
        Set<String> keep = new HashSet<>();
        for (var e : byMod.entrySet()) {
            CompatManifest.Parsed p = e.getValue();
            if (p.quarantine().maxTickMicros() > 0) {
                ticks.put(e.getKey(), p.quarantine().maxTickMicros() * 1_000);
            }
            keep.addAll(p.quarantine().neverDelete());
            for (CompatManifest.DimensionPolicy d : p.dimensionPolicies()) {
                if (d.policy().equals("EPHEMERAL_NO_CAS")) {
                    dims.add(d.dimension());
                }
            }
        }
        return new CompatRules(Map.copyOf(ticks), Set.copyOf(dims), Set.copyOf(keep));
    }

    public boolean hasTickLimits() {
        return !maxTickNanos.isEmpty();
    }

    /** 0 when the namespace has no limit. */
    public long maxTickNanos(String namespace) {
        return maxTickNanos.getOrDefault(namespace, 0L);
    }

    /** Chunks of this dimension are never uploaded and are not part of the stored world. */
    public boolean ephemeral(String dimension) {
        return dimension != null && ephemeralDimensions.contains(dimension);
    }

    /** Ids a mod asks never to be removed. The quarantine freezes things, it never deletes, so this is informational. */
    public boolean neverDelete(String id) {
        return neverDelete.contains(id);
    }
}
