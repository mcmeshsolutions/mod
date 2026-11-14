package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A server that drops the first connections without answering, like a proxy that closed an idle keep-alive connection. */
class ConnectionRetryTest {
    private ServerSocket server;
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile int dropFirst;

    @BeforeEach
    void start() throws Exception {
        server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    int n = accepted.incrementAndGet();
                    if (n <= dropFirst) {
                        continue;
                    }
                    byte[] buf = new byte[8192];
                    s.getInputStream().read(buf);
                    OutputStream o = s.getOutputStream();
                    o.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }, "retry-test-server");
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private GatewayClient client() {
        return new GatewayClient("http://127.0.0.1:" + server.getLocalPort(), "t");
    }

    @Test
    void aBlobUploadSurvivesTwoDroppedConnections() throws Exception {
        dropFirst = 2;
        byte[] raw = "chunk".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(Bytes.sha256(raw), client().blobPut(raw));
        assertEquals(3, accepted.get());
    }

    @Test
    void aThirdDroppedConnectionIsReported() {
        dropFirst = 10;
        assertThrows(IOException.class, () -> client().blobPut("x".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, accepted.get(), "one try and two retries");
    }

    @Test
    void aWalAppendIsNeverSentTwice() {
        dropFirst = 1;
        assertThrows(IOException.class, () -> client().walAppend("l1", 1, java.util.List.of()));
        assertEquals(1, accepted.get());
    }
}
