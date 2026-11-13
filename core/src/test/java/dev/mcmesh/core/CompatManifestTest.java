package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class CompatManifestTest {
    private static JsonObject doc(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aFullManifestParses() {
        CompatManifest.Result r = CompatManifest.parse(doc("""
            {"schema":1,
             "aux_sync_paths":[{"glob":"data/xaerominimap/*.txt","scope":"PLAYER_PRIVATE"}],
             "chunk_couplings":[{"block_tag":"create:contraption_anchor","mode":"HARD_PIN"}],
             "udp_channels":[{"id":"voicechat","port":24454}],
             "quarantine_rules":{"never_delete":["create:mechanical_bearing"],"max_tick_micros":50000},
             "dimension_policies":[{"dimension":"mymod:mining","policy":"EPHEMERAL_NO_CAS"}],
             "future_key":{"ignored":true}}
            """));
        assertTrue(r.ok(), r.problems().toString());
        CompatManifest.Parsed m = r.manifest();
        assertEquals("PLAYER_PRIVATE", m.auxSyncPaths().get(0).scope());
        assertEquals(24454, m.udpChannels().get(0).port());
        assertEquals(50000, m.quarantine().maxTickMicros());
        assertEquals("create:mechanical_bearing", m.quarantine().neverDelete().get(0));
    }

    @Test
    void anEmptyV1ManifestIsValid() {
        assertTrue(CompatManifest.parse(doc("{\"schema\":1}")).ok());
    }

    @Test
    void aWrongOrMissingSchemaVersionIsRefused() {
        assertFalse(CompatManifest.parse(doc("{}")).ok());
        assertFalse(CompatManifest.parse(doc("{\"schema\":2}")).ok());
        assertFalse(CompatManifest.parse(doc("{\"schema\":\"1\"}")).ok());
    }

    @Test
    void auxGlobsCannotEscapeTheWorldFolder() {
        for (String glob : new String[] {"../level.dat", "a/../../b", "/etc/passwd", "a\\\\b", ""}) {
            JsonObject d = doc("{\"schema\":1,\"aux_sync_paths\":[{\"scope\":\"TEAM\"}]}");
            d.getAsJsonArray("aux_sync_paths").get(0).getAsJsonObject().addProperty("glob", glob);
            assertFalse(CompatManifest.parse(d).ok(), "should refuse " + glob);
        }
    }

    @Test
    void oneBadEntryRefusesTheWholeManifestAndEveryProblemIsListed() {
        CompatManifest.Result r = CompatManifest.parse(doc("""
            {"schema":1,
             "aux_sync_paths":[{"glob":"ok.txt","scope":"EVERYONE"}],
             "udp_channels":[{"id":"x","port":70000}],
             "quarantine_rules":{"max_tick_micros":-5}}
            """));
        assertNull(r.manifest());
        assertEquals(3, r.problems().size(), r.problems().toString());
    }

    @Test
    void wrongShapesAreProblemsNotExceptions() {
        assertFalse(CompatManifest.parse(doc("{\"schema\":1,\"aux_sync_paths\":{}}")).ok());
        assertFalse(CompatManifest.parse(doc("{\"schema\":1,\"chunk_couplings\":[1]}")).ok());
        assertFalse(CompatManifest.parse(doc("{\"schema\":1,\"quarantine_rules\":[]}")).ok());
        assertFalse(CompatManifest.parse(doc("{\"schema\":1,\"quarantine_rules\":{\"never_delete\":[1]}}")).ok());
    }
}
