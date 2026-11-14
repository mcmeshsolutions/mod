package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompatRulesTest {
    private static CompatManifest.Parsed parse(String json) {
        var r = CompatManifest.parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(r.ok(), r.problems().toString());
        return r.manifest();
    }

    @Test
    void aTickLimitBelongsToTheNamespaceOfItsOwnMod() {
        CompatRules r = CompatRules.of(Map.of("create", parse("{\"schema\":1,\"quarantine_rules\":{\"max_tick_micros\":50000}}"), "jei", parse("{\"schema\":1}")));
        assertTrue(r.hasTickLimits());
        assertEquals(50_000_000L, r.maxTickNanos("create"));
        assertEquals(0L, r.maxTickNanos("jei"));
        assertEquals(0L, r.maxTickNanos("minecraft"));
    }

    @Test
    void ephemeralDimensionsAreExactMatches() {
        CompatRules r = CompatRules.of(Map.of("mymod", parse("{\"schema\":1,\"dimension_policies\":[{\"dimension\":\"mymod:mine\",\"policy\":\"EPHEMERAL_NO_CAS\"}]}")));
        assertTrue(r.ephemeral("mymod:mine"));
        assertFalse(r.ephemeral("mymod:mine2"));
        assertFalse(r.ephemeral("minecraft:overworld"));
        assertFalse(r.ephemeral(null));
    }

    @Test
    void noManifestsMeansNoRules() {
        assertFalse(CompatRules.NONE.hasTickLimits());
        assertFalse(CompatRules.of(Map.of()).ephemeral("a:b"));
    }

    @Test
    void oneNondeterministicWorldgenModSwitchesDistributionOff() {
        assertTrue(CompatRules.NONE.worldgenDistributable());
        CompatRules ok = CompatRules.of(Map.of("terralith", parse("{\"schema\":1,\"worldgen\":\"DETERMINISTIC\"}"), "silent", parse("{\"schema\":1}")));
        assertTrue(ok.worldgenDistributable());
        assertTrue(ok.declaredDeterministic("terralith"));
        assertFalse(ok.declaredDeterministic("silent"));
        CompatRules bad = CompatRules.of(Map.of("terralith", parse("{\"schema\":1,\"worldgen\":\"DETERMINISTIC\"}"), "wild", parse("{\"schema\":1,\"worldgen\":\"NONDETERMINISTIC\"}")));
        assertFalse(bad.worldgenDistributable());
        assertEquals(java.util.Set.of("wild"), bad.nondeterministicWorldgenMods());
    }

    @Test
    void anUnknownWorldgenValueRefusesTheManifest() {
        var r = CompatManifest.parse(JsonParser.parseString("{\"schema\":1,\"worldgen\":\"MAYBE\"}").getAsJsonObject());
        assertFalse(r.ok());
    }

    @Test
    void neverDeleteIdsAreRemembered() {
        CompatRules r = CompatRules.of(Map.of("create", parse("{\"schema\":1,\"quarantine_rules\":{\"never_delete\":[\"create:bearing\"]}}")));
        assertTrue(r.neverDelete("create:bearing"));
        assertFalse(r.neverDelete("create:other"));
    }
}
