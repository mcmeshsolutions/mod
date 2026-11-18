package dev.mcmesh.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TunnelTest {
    @Test
    void frameRoundtripDataAndBroadcast() throws Exception {
        byte[] payload = "multicast broadcast packet".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = Tunnel.frame(Tunnel.BCAST, 0, payload, 0, payload.length);
        assertEquals(9 + payload.length, encoded.length);

        Tunnel.Frame f = Tunnel.read(new DataInputStream(new ByteArrayInputStream(encoded)));
        assertEquals(Tunnel.BCAST, f.type());
        assertEquals(0, f.id());
        assertArrayEquals(payload, f.data());

        byte[] filtered = Tunnel.frame(Tunnel.BCAST, 42, payload, 0, payload.length);
        Tunnel.Frame f2 = Tunnel.read(new DataInputStream(new ByteArrayInputStream(filtered)));
        assertEquals(Tunnel.BCAST, f2.type());
        assertEquals(42, f2.id());
        assertArrayEquals(payload, f2.data());
    }

    @Test
    void frameRejectsOversizedPayload() {
        byte[] big = new byte[Tunnel.MAX_PAYLOAD + 1];
        assertThrows(Exception.class, () -> Tunnel.frame(Tunnel.DATA, 1, big, 0, big.length));
    }
}
