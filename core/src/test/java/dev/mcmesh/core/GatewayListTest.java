package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayListTest {
    @Test
    void defaultComesFirstAndBadEntriesAreSkipped() {
        List<String> skipped = new ArrayList<>();
        List<GatewayList.Gateway> l = GatewayList.parse("https://play.example.org/", List.of(
                "friends=https://mesh.example.net",
                "lan=http://127.0.0.1:8080",
                "plain=http://mesh.example.net",
                "default=https://evil.example",
                "friends=https://other.example",
                "dupe=https://play.example.org",
                "bad id=https://x.example",
                "nourl",
                "ftp=ftp://x.example"), skipped::add);
        assertEquals(List.of("default", "friends", "lan"), l.stream().map(GatewayList.Gateway::id).toList());
        assertEquals("https://play.example.org", l.get(0).apiUrl());
        assertTrue(l.get(0).isDefault());
        assertFalse(l.get(1).isDefault());
        assertEquals("mesh.example.net", l.get(1).host());
        assertEquals(7, skipped.size());
    }

    @Test
    void configWithoutGatewaysKeyReadsAsOneDefaultEntry() {
        List<String> skipped = new ArrayList<>();
        List<GatewayList.Gateway> l = GatewayList.parse("http://localhost:8080/", List.of(), skipped::add);
        assertEquals(1, l.size());
        assertTrue(l.get(0).isDefault());
        assertEquals("http://localhost:8080", l.get(0).apiUrl());
        assertTrue(skipped.isEmpty());
    }

    @Test
    void trustIsPerUrlAndPersists(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/trusted.txt");
        assertFalse(GatewayList.trusted(f, "https://a.example"));
        GatewayList.trust(f, "https://a.example/");
        GatewayList.trust(f, "https://a.example");
        assertTrue(GatewayList.trusted(f, "https://a.example"));
        assertFalse(GatewayList.trusted(f, "https://a.example.evil"));
        assertEquals(1, Files.readAllLines(f).size());
    }

    @Test
    void routedTicketUsesTheOwningGatewayAndItsToken() throws Exception {
        List<String> seenA = new ArrayList<>();
        List<String> seenB = new ArrayList<>();
        HttpServer a = fake(seenA, "ticket-a");
        HttpServer b = fake(seenB, "ticket-b");
        try {
            GatewayClient def = new GatewayClient("http://127.0.0.1:" + a.getAddress().getPort(), "token-a");
            GatewayClient other = new GatewayClient("http://127.0.0.1:" + b.getAddress().getPort(), "token-b");
            GatewayClient.ticketRoute("w-b", other);
            assertEquals("ticket-b", def.ticket("w-b"));
            assertEquals("ticket-a", def.ticket("w-a"));
            assertEquals(List.of("/v1/worlds/w-a/ticket Bearer token-a"), seenA);
            assertEquals(List.of("/v1/worlds/w-b/ticket Bearer token-b"), seenB);
            GatewayClient.ticketRoute(null, null);
            assertEquals("ticket-a", def.ticket("w-b"));
        } finally {
            GatewayClient.ticketRoute(null, null);
            a.stop(0);
            b.stop(0);
        }
    }

    private static HttpServer fake(List<String> seen, String ticket) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            seen.add(x.getRequestURI().getPath() + " " + x.getRequestHeaders().getFirst("authorization"));
            byte[] body = ("{\"ticket\":\"" + ticket + "\"}").getBytes();
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        s.start();
        return s;
    }
}
