package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HostHealthTest {
    @Test
    void levelsMapToPlainStatusesAndUnknownHasNoLabel() {
        assertEquals(HostHealth.Status.SMOOTH, new HostHealth(0, 2).status());
        assertEquals(HostHealth.Status.STRAINED, new HostHealth(1, 2).status());
        assertEquals(HostHealth.Status.OVERLOADED, new HostHealth(2, 2).status());
        assertEquals(HostHealth.Status.OVERLOADED, new HostHealth(3, 2).status());
        assertFalse(HostHealth.UNKNOWN.known());
        assertNull(HostHealth.UNKNOWN.label());
        assertNull(new HostHealth(-1, 4).label());
    }

    @Test
    void labelsAreReadableAndSingularIsRespected() {
        assertEquals("Host running smoothly (1 player)", new HostHealth(0, 1).label());
        assertEquals("Host under load (3 players)", new HostHealth(1, 3).label());
        assertEquals("Host running smoothly", new HostHealth(0, -1).label());
        assertEquals("Host overloaded, a stronger machine may take over (0 players)", new HostHealth(2, 0).label());
    }

    @Test
    void theClientReadsLoadAndPlayersForOneWorldFromTheListing() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/worlds", ex -> {
            byte[] b = ("{\"worlds\":[{\"id\":\"a\",\"host_load\":null,\"host_players\":null},"
                    + "{\"id\":\"w1\",\"host_load\":2,\"host_players\":5},{\"id\":\"old\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        try {
            GatewayClient gw = new GatewayClient("http://127.0.0.1:" + s.getAddress().getPort(), "t");
            assertEquals(new HostHealth(2, 5), gw.hostHealth("w1"));
            assertEquals(HostHealth.UNKNOWN, gw.hostHealth("a"), "an unreported host is unknown, not zero");
            assertEquals(HostHealth.UNKNOWN, gw.hostHealth("old"), "an older gateway has no such fields");
            assertEquals(HostHealth.UNKNOWN, gw.hostHealth("missing"));
        } finally {
            s.stop(0);
        }
    }
}
