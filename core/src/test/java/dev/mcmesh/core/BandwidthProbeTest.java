package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BandwidthProbeTest {
    private static HttpServer server(AtomicLong received, long delayMs) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/bandwidth", ex -> {
            received.set(ex.getRequestBody().readAllBytes().length);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) { }
            byte[] b = "{\"bytes\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void theProbeSendsItsBodyAndReportsAPositiveSpeed() throws Exception {
        AtomicLong got = new AtomicLong();
        HttpServer s = server(got, 0);
        try {
            double mbps = BandwidthProbe.measure(new GatewayClient("http://127.0.0.1:" + s.getAddress().getPort(), "t"));
            assertEquals(BandwidthProbe.PROBE_BYTES, got.get());
            assertTrue(mbps > 0, "measured " + mbps);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aSlowGatewayLooksSlower() throws Exception {
        HttpServer s = server(new AtomicLong(), 800);
        try {
            double mbps = BandwidthProbe.measure(new GatewayClient("http://127.0.0.1:" + s.getAddress().getPort(), "t"));
            assertTrue(mbps > 0 && mbps < 6, "0.5 MiB in at least 0.8 s is at most about 5 Mbit/s, got " + mbps);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aFailedProbeIsUnknownNotZero() {
        assertEquals(-1, BandwidthProbe.measure(new GatewayClient("http://127.0.0.1:1", "t")));
    }

    @Test
    void arithmetic() {
        assertEquals(8.0, BandwidthProbe.mbps(1_000_000, 1_000_000_000L), 1e-9);
        assertEquals(-1, BandwidthProbe.mbps(1_000_000, 0));
    }
}
