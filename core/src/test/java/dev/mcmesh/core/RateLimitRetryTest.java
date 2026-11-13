package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitRetryTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int refusals;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/challenge", ex -> {
            ex.getRequestBody().readAllBytes();
            boolean refuse = calls.incrementAndGet() <= refusals;
            byte[] b = (refuse ? "{\"error\":\"RATE_LIMITED\"}" : "{\"challenge\":\"c1\"}").getBytes(StandardCharsets.UTF_8);
            if (refuse) {
                ex.getResponseHeaders().add("Retry-After", "0");
            }
            ex.sendResponseHeaders(refuse ? 429 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GatewayClient client() {
        return new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), "t");
    }

    @Test
    void aRefusedRequestIsResentAndSucceeds() throws Exception {
        refusals = 2;
        assertEquals("c1", client().authChallenge());
        assertEquals(3, calls.get());
    }

    @Test
    void persistentRefusalGivesUpAfterThreeRetries() {
        refusals = 100;
        GatewayException e = assertThrows(GatewayException.class, () -> client().authChallenge());
        assertEquals(429, e.status);
        assertEquals("RATE_LIMITED", e.code);
        assertEquals(4, calls.get());
    }
}
