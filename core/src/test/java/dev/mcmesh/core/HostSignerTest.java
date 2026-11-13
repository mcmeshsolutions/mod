package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostSignerTest {
    private HttpServer srv;
    private final List<JsonObject> commits = new ArrayList<>();
    private final List<JsonObject> keys = new ArrayList<>();
    private volatile boolean hasMe = true;
    private volatile int keyStatus = 200;
    private volatile String commitError;
    private volatile int commitErrorsLeft;
    private volatile int commitStatus = 403;

    private String base() {
        return "http://127.0.0.1:" + srv.getAddress().getPort();
    }

    private void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @BeforeEach
    void up() throws Exception {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/devices/me", ex -> {
            if (hasMe) {
                reply(ex, 200, "{\"device\":\"dev-1\"}");
            } else {
                reply(ex, 404, "{\"error\":\"NOT_FOUND\"}");
            }
        });
        srv.createContext("/v1/devices/key", ex -> {
            keys.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes())).getAsJsonObject());
            reply(ex, keyStatus, keyStatus == 200 ? "{}" : "{\"error\":\"DEVICE_KEY_SET\"}");
        });
        srv.createContext("/v1/leases/L1/commit", ex -> {
            commits.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes())).getAsJsonObject());
            if (commitError != null && commitErrorsLeft-- > 0) {
                reply(ex, commitStatus, "{\"error\":\"" + commitError + "\"}");
            } else {
                reply(ex, 200, "{\"rev_id\":\"r1\",\"parent\":\"r0\",\"seq\":1}");
            }
        });
        srv.start();
    }

    @AfterEach
    void down() {
        srv.stop(0);
    }

    private static boolean sigOk(IdentityKey k, JsonObject c, long barrier) {
        byte[] sig = Base64.getDecoder().decode(c.get("sig").getAsString());
        return CommitSignature.verify(k.publicRaw(),
                CommitSignature.commitMessage("w", "L1", 7, "r0", "AUTOSAVE", false, barrier), sig);
    }

    @Test
    void registersThenSignsEveryCommit() throws Exception {
        IdentityKey k = IdentityKey.generate();
        HostSigner h = new HostSigner(base(), "t", k);
        assertTrue(h.register());
        JsonObject reg = keys.get(0);
        byte[] pub = Base64.getDecoder().decode(reg.get("pubkey").getAsString());
        assertTrue(CommitSignature.verify(pub, CommitSignature.deviceKeyMessage("dev-1", pub), Base64.getDecoder().decode(reg.get("sig").getAsString())));

        Revision r = h.commit("w", "L1", 7, "r0", "AUTOSAVE", false, () -> 12, () -> { });
        assertEquals("r1", r.id());
        assertTrue(sigOk(k, commits.get(0), 12));
        assertFalse(sigOk(k, commits.get(0), 13));
    }

    @Test
    void oldGatewayMeansUnsignedCommit() throws Exception {
        hasMe = false;
        HostSigner h = new HostSigner(base(), "t", IdentityKey.generate());
        assertFalse(h.register());
        h.commit("w", "L1", 7, "r0", "AUTOSAVE", false, () -> 0, () -> { });
        assertFalse(commits.get(0).has("sig"));
    }

    @Test
    void otherKeyAlreadyRegisteredMeansUnsigned() throws Exception {
        keyStatus = 409;
        HostSigner h = new HostSigner(base(), "t", IdentityKey.generate());
        assertFalse(h.register());
        h.commit("w", "L1", 7, "r0", "AUTOSAVE", false, () -> 0, () -> { });
        assertNull(commits.get(0).get("sig"));
    }

    @Test
    void badSignatureResealsAndRetriesOnce() throws Exception {
        IdentityKey k = IdentityKey.generate();
        HostSigner h = new HostSigner(base(), "t", k);
        h.register();
        commitError = "BAD_SIGNATURE";
        commitErrorsLeft = 1;
        long[] barrier = {5};
        h.commit("w", "L1", 7, "r0", "AUTOSAVE", false, () -> barrier[0], () -> barrier[0] = 9);
        assertEquals(2, commits.size());
        assertTrue(sigOk(k, commits.get(1), 9));
    }

    @Test
    void persistentRejectionStillCommitsUnsigned() throws Exception {
        HostSigner h = new HostSigner(base(), "t", IdentityKey.generate());
        h.register();
        commitError = "NO_DEVICE_KEY";
        commitErrorsLeft = 1;
        Revision r = h.commit("w", "L1", 7, "r0", "AUTOSAVE", false, () -> 0, () -> { });
        assertEquals("r1", r.id());
        assertEquals(2, commits.size());
        assertFalse(commits.get(1).has("sig"));
    }

    @Test
    void otherErrorsPropagate() throws Exception {
        HostSigner h = new HostSigner(base(), "t", IdentityKey.generate());
        h.register();
        commitError = "STALE_EPOCH";
        commitErrorsLeft = 5;
        try {
            h.commit("w", "L1", 7, "r0", "AUTOSAVE", false, () -> 0, () -> { });
            throw new AssertionError("expected exception");
        } catch (GatewayException e) {
            assertTrue(e.leaseLost());
        }
        assertEquals(1, commits.size());
    }

    @Test
    void sanityRefusalOnASignedCommitTripsTheLatchOnceAndIsNeverResentUnsigned() throws Exception {
        HostSigner h = new HostSigner(base(), "t", IdentityKey.generate());
        h.register();
        commitError = "REJECTED_BY_SANITY_GATE";
        commitStatus = 422;
        commitErrorsLeft = 5;
        GatewayClient gw = new GatewayClient(base(), "t");
        Commits latch = new Commits(gw, new Lease("L1", 7, 30, 5, 20, "r0", new byte[32]));
        latch.sendWith((base, kind, last) -> h.commit("w", "L1", 7, base, kind, last, () -> 0, () -> { }));
        assertNull(latch.commit("r0", "AUTOSAVE", false));
        assertTrue(latch.refused());
        assertEquals(1, commits.size());
        assertTrue(commits.get(0).has("sig"));
        assertNull(latch.commit("r0", "AUTOSAVE", false));
        assertEquals(1, commits.size());
    }
}
