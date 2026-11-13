package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommitsTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status;
    private volatile String body;
    private Commits commits;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/leases/l1/commit", ex -> {
            calls.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        GatewayClient gw = new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), "t");
        commits = new Commits(gw, new Lease("l1", 3, 30, 5, 20, "R_0001", new byte[32]));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void reply(int status, String body) {
        this.status = status;
        this.body = body;
    }

    @Test
    void acceptedCommitReturnsRevision() throws Exception {
        reply(200, "{\"rev_id\":\"R_0002\",\"parent\":\"R_0001\",\"seq\":7}");
        assertEquals("R_0002", commits.commit("R_0001", "AUTOSAVE", false).id());
        assertFalse(commits.refused());
    }

    @Test
    void sanityGateRefusalStopsFurtherCommits() throws Exception {
        reply(422, "{\"error\":\"REJECTED_BY_SANITY_GATE\"}");
        assertNull(commits.commit("R_0001", "AUTOSAVE", false));
        assertTrue(commits.refused());
        // even a commit the gateway would now take is not sent: the refused state is still on disk
        reply(200, "{\"rev_id\":\"R_0002\",\"parent\":\"R_0001\",\"seq\":7}");
        assertNull(commits.commit("R_0001", "AUTOSAVE", false));
        assertNull(commits.commit("R_0001", "SHUTDOWN", true));
        assertEquals(1, calls.get());
    }

    @Test
    void otherErrorsPropagateAndKeepRetrying() throws Exception {
        reply(409, "{\"error\":\"HEAD_MOVED\"}");
        assertThrows(GatewayException.class, () -> commits.commit("R_0001", "AUTOSAVE", false));
        reply(422, "{\"error\":\"SOMETHING_ELSE\"}");
        assertThrows(GatewayException.class, () -> commits.commit("R_0001", "AUTOSAVE", false));
        reply(500, "oops");
        assertThrows(GatewayException.class, () -> commits.commit("R_0001", "AUTOSAVE", false));
        assertFalse(commits.refused());
        assertEquals(3, calls.get());
    }

    @Test
    void signingSenderStillTripsTheRefusalLatch() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        commits.sendWith((base, kind, last) -> {
            sent.incrementAndGet();
            throw new GatewayException(422, "REJECTED_BY_SANITY_GATE");
        });
        assertNull(commits.commit("R_0001", "AUTOSAVE", false));
        assertTrue(commits.refused());
        assertNull(commits.commit("R_0001", "SHUTDOWN", true));
        assertEquals(1, sent.get());
        assertEquals(0, calls.get());
    }
}
