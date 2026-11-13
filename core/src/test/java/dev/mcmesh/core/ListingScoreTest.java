package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ListingScoreTest {
    private HttpServer server;
    private final AtomicReference<String> query = new AtomicReference<>();

    private GatewayClient client() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/worlds", ex -> {
            query.set(ex.getRequestURI().getRawQuery());
            byte[] b = "{\"worlds\":[],\"relay\":\"\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), "t");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        GatewayClient.hwScore = null;
    }

    @Test
    void theListingSendsTheBenchmarkScoreWhenOneIsRegistered() throws Exception {
        GatewayClient.hwScore = () -> 496.0;
        client().listWorlds();
        assertTrue(query.get().contains("score=496.0"), query.get());
    }

    /** The fallback that made a 16 core desktop look weaker than a laptop scoring 155: it must never be what a real install sends. */
    @Test
    void withoutARegisteredScoreTheCoreCountIsUsed() throws Exception {
        GatewayClient.hwScore = null;
        client().listWorlds();
        assertTrue(query.get().contains("score=" + (double) Runtime.getRuntime().availableProcessors()), query.get());
    }
}
