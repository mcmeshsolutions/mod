package dev.mcmesh.core;

import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobStreamTest {
    @Test
    void streamsMultipleBlobsAndMissingResponsesOverOneSocket() throws Exception {
        byte[] blobA = "first chunk payload".getBytes(StandardCharsets.UTF_8);
        byte[] hashA = Bytes.sha256(blobA);
        byte[] blobB = new byte[100_000];
        for (int i = 0; i < blobB.length; i++) {
            blobB[i] = (byte) (i * 31);
        }
        byte[] hashB = Bytes.sha256(blobB);
        byte[] hashMissing = Bytes.sha256("missing".getBytes(StandardCharsets.UTF_8));

        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    OutputStream out = s.getOutputStream();
                    Tunnel.Frame hello = Tunnel.read(in);
                    assertEquals(BlobStream.HELLO, hello.type());
                    String tok = JsonParser.parseString(new String(hello.data(), StandardCharsets.UTF_8)).getAsJsonObject().get("token").getAsString();
                    assertEquals("tok-123", tok);
                    Tunnel.write(out, BlobStream.HELLO_OK, 0, new byte[0], 0, 0);

                    for (int n = 0; n < 3; n++) {
                        Tunnel.Frame want = Tunnel.read(in);
                        assertEquals(BlobStream.WANT, want.type());
                        int id = want.id();
                        byte[] h = want.data();
                        byte[] payload = java.util.Arrays.equals(h, hashA) ? blobA : java.util.Arrays.equals(h, hashB) ? blobB : null;
                        if (payload == null) {
                            Tunnel.write(out, BlobStream.MISSING, id, new byte[0], 0, 0);
                        } else {
                            byte[] len = ByteBuffer.allocate(8).putLong(payload.length).array();
                            Tunnel.write(out, BlobStream.BLOB_BEGIN, id, len, 0, 8);
                            for (int off = 0; off < payload.length; off += Tunnel.MAX_PAYLOAD) {
                                int chunk = Math.min(Tunnel.MAX_PAYLOAD, payload.length - off);
                                Tunnel.write(out, BlobStream.BLOB_DATA, id, payload, off, chunk);
                            }
                        }
                    }
                } catch (Exception ignored) { }
            });
            peer.setDaemon(true);
            peer.start();

            try (BlobStream bs = BlobStream.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), "tok-123")) {
                var pool = Executors.newFixedThreadPool(2);
                try {
                    List<Callable<byte[]>> tasks = List.of(() -> bs.fetch(hashA), () -> bs.fetch(hashB));
                    List<Future<byte[]>> res = new ArrayList<>();
                    for (var t : tasks) {
                        res.add(pool.submit(t));
                    }
                    assertArrayEquals(blobA, res.get(0).get());
                    assertArrayEquals(blobB, res.get(1).get());
                } finally {
                    pool.shutdownNow();
                }
                GatewayException ex = assertThrows(GatewayException.class, () -> bs.fetch(hashMissing));
                assertEquals("NO_SUCH_BLOB", ex.code);
            }
        }
    }

    @Test
    void putsAnUploadAndVerifiesTheHashTheServerReports() throws Exception {
        byte[] data = "uploaded chunk bytes".getBytes(StandardCharsets.UTF_8);
        byte[] hash = Bytes.sha256(data);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    OutputStream out = s.getOutputStream();
                    Tunnel.read(in);
                    Tunnel.write(out, BlobStream.HELLO_OK, 0, new byte[0], 0, 0);

                    Tunnel.Frame begin = Tunnel.read(in);
                    assertEquals(BlobStream.PUT_BEGIN, begin.type());
                    long total = ByteBuffer.wrap(begin.data()).getLong();
                    byte[] got = new byte[(int) total];
                    int off = 0;
                    while (off < total) {
                        Tunnel.Frame d = Tunnel.read(in);
                        assertEquals(BlobStream.PUT_DATA, d.type());
                        assertEquals(begin.id(), d.id());
                        System.arraycopy(d.data(), 0, got, off, d.data().length);
                        off += d.data().length;
                    }
                    assertArrayEquals(data, got);
                    Tunnel.write(out, BlobStream.PUT_OK, begin.id(), hash, 0, hash.length);
                } catch (Exception ignored) { }
            });
            peer.setDaemon(true);
            peer.start();

            try (BlobStream bs = BlobStream.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), "tok-123")) {
                assertArrayEquals(hash, bs.put(data));
            }
        }
    }
}
