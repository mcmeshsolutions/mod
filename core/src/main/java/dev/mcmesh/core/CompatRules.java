package dev.mcmesh.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The parts of the loaded compat manifests that the host acts on, indexed for cheap lookups on the tick path. */
public final class CompatRules {
    public static final CompatRules NONE = new CompatRules(Map.of(), Set.of(), Set.of(), Set.of(), Set.of());

    private final Map<String, Long> maxTickNanos;
    private final Set<String> ephemeralDimensions;
    private final Set<String> neverDelete;
    private final Set<String> deterministicWorldgen;
    private final Set<String> nondeterministicWorldgen;

    private CompatRules(Map<String, Long> maxTickNanos, Set<String> ephemeralDimensions, Set<String> neverDelete,
                        Set<String> deterministicWorldgen, Set<String> nondeterministicWorldgen) {
        this.maxTickNanos = maxTickNanos;
        this.ephemeralDimensions = ephemeralDimensions;
        this.neverDelete = neverDelete;
        this.deterministicWorldgen = deterministicWorldgen;
        this.nondeterministicWorldgen = nondeterministicWorldgen;
    }

    /** A manifest only speaks for its own mod: its tick limit applies to that mod's namespace. */
    public static CompatRules of(Map<String, CompatManifest.Parsed> byMod) {
        Map<String, Long> ticks = new HashMap<>();
        Set<String> dims = new HashSet<>();
        Set<String> keep = new HashSet<>();
        Set<String> det = new HashSet<>();
        Set<String> nondet = new HashSet<>();
        for (var e : byMod.entrySet()) {
            CompatManifest.Parsed p = e.getValue();
            if ("DETERMINISTIC".equals(p.worldgen())) {
                det.add(e.getKey());
            } else if ("NONDETERMINISTIC".equals(p.worldgen())) {
                nondet.add(e.getKey());
            }
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
        return new CompatRules(Map.copyOf(ticks), Set.copyOf(dims), Set.copyOf(keep), Set.copyOf(det), Set.copyOf(nondet));
    }

    /** False as soon as one loaded mod declares NONDETERMINISTIC worldgen: a second peer could never reproduce its chunks. */
    public boolean worldgenDistributable() {
        return nondeterministicWorldgen.isEmpty();
    }

    /** A mod that says nothing is not assumed deterministic: tiles are second-peer hash checked either way. */
    public boolean declaredDeterministic(String modId) {
        return deterministicWorldgen.contains(modId);
    }

    public Set<String> nondeterministicWorldgenMods() {
        return nondeterministicWorldgen;
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
