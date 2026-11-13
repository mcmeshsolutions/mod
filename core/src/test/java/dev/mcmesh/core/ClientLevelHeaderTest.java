package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientLevelHeaderTest {
    @Test
    void everyGatewayCallCarriesTheProtocolLevel() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/auth/challenge", ex -> {
            seen.set(ex.getRequestHeaders().getFirst("x-mesh-client"));
            byte[] b = "{\"challenge\":\"c\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        try {
            new GatewayClient("http://127.0.0.1:" + s.getAddress().getPort(), "t").authChallenge();
            assertEquals(String.valueOf(GatewayClient.PROTOCOL), seen.get());
        } finally {
            s.stop(0);
        }
    }
}
