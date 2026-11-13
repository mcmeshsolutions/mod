package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeartbeatBodyTest {
    private HttpServer server;
    private final AtomicReference<JsonObject> seen = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/leases/l1/heartbeat", ex -> {
            seen.set(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] b = "{\"ok\":true,\"directives\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        GatewayClient.playerCount = () -> -1;
        GatewayClient.loadLevel = () -> -1;
    }

    private GatewayClient client() {
        return new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), "t");
    }

    @Test
    void playersAndLoadAreSentWhenKnown() throws Exception {
        GatewayClient.playerCount = () -> 3;
        GatewayClient.loadLevel = () -> 2;
        client().heartbeat("l1", 4);
        assertEquals(3, seen.get().get("players").getAsInt());
        assertEquals(2, seen.get().get("load").getAsInt());
        assertEquals(4, seen.get().get("epoch").getAsLong());
    }

    @Test
    void unknownValuesAreLeftOutSoTheGatewayKeepsWhatItHas() throws Exception {
        client().heartbeat("l1", 4);
        assertFalse(seen.get().has("players"));
        assertFalse(seen.get().has("load"));
        GatewayClient.playerCount = () -> 0;
        client().heartbeat("l1", 4);
        assertEquals(0, seen.get().get("players").getAsInt(), "zero players is a real value");
    }
}
