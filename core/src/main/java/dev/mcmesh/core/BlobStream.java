package dev.mcmesh.core;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent framed TCP stream for pulling many blobs over one connection without per-file HTTP overhead.
 * See gatewayd/src/blobstream.rs for the wire format.
 */
public final class BlobStream implements AutoCloseable {
    public static final byte HELLO = 1, HELLO_OK = 2, ERR = 3, WANT = 4, BLOB_BEGIN = 5, BLOB_DATA = 6, MISSING = 7, PING = 8, PONG = 9;
    public static final byte PUT_BEGIN = 10, PUT_DATA = 11, PUT_OK = 12, PUT_ERR = 13;
    private static final byte[] STOP = new byte[0];

    private final Socket sock;
    private final OutputStream out;
    private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final Map<Integer, Inflight> inflight = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<byte[]>> puts = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Thread reader;
    private final Thread writer;
    private volatile boolean closed;

    private static final class Inflight {
        final CompletableFuture<byte[]> done = new CompletableFuture<>();
        ByteArrayOutputStream buf;
        long expected = -1;
    }

    private BlobStream(Socket sock, OutputStream out, DataInputStream in) {
        this.sock = sock;
        this.out = out;
        this.writer = Threads.start("mesh-blobstream-w", this::writeLoop);
        this.reader = Threads.start("mesh-blobstream-r", () -> readLoop(in));
    }

    public static BlobStream connect(InetSocketAddress target, String token) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(target, 5_000);
        s.setSoTimeout(30_000);
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
        JsonObject h = new JsonObject();
        h.addProperty("token", token);
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        Tunnel.write(out, HELLO, 0, hb, 0, hb.length);
        Tunnel.Frame reply = Tunnel.read(in);
        if (reply.type() != HELLO_OK) {
            s.close();
            throw new IOException("blobstream refused: " + new String(reply.data(), StandardCharsets.UTF_8));
        }
        return new BlobStream(s, out, in);
    }

    public boolean isAlive() {
        return !closed && !sock.isClosed();
    }

    /** Queues a WANT frame and returns immediately, without verifying the hash or capping how many
     *  requests may be outstanding at once. A caller fetching many blobs (a full world sync) should issue
     *  them all up front instead of trickling them through a fixed-size thread pool one at a time - this
     *  keeps the single connection saturated by its own flow control (bounded server-side, see
     *  blobstream.rs's MAX_INFLIGHT) and by TCP itself, instead of being capped by a concurrency guess
     *  that has to be re-tuned for whatever the network's bandwidth-delay product happens to be. */
    public CompletableFuture<byte[]> fetchAsync(byte[] hash) {
        if (closed) {
            return CompletableFuture.failedFuture(new IOException("blobstream closed"));
        }
        int id = nextId.getAndIncrement();
        Inflight inf = new Inflight();
        inflight.put(id, inf);
        byte[] frame;
        try {
            frame = Tunnel.frame(WANT, id, hash, 0, hash.length);
        } catch (IOException e) {
            inflight.remove(id);
            return CompletableFuture.failedFuture(e);
        }
        outbox.add(frame);
        return inf.done.whenComplete((d, err) -> inflight.remove(id));
    }

    /** Requests one blob over the persistent stream and verifies its SHA-256 hash. */
    public byte[] fetch(byte[] hash) throws IOException, GatewayException {
        try {
            byte[] data = fetchAsync(hash).get(30, TimeUnit.SECONDS);
            if (!Arrays.equals(Bytes.sha256(data), hash)) {
                throw new IOException("blob " + Bytes.hex(hash) + " failed hash check");
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (TimeoutException e) {
            close();
            throw new IOException("blobstream timed out waiting for " + Bytes.hex(hash), e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GatewayException g) {
                throw g;
            }
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        }
    }

    /** Queues an upload and returns immediately with a future for the hash the gateway stored it under,
     *  with no cap on how many may be outstanding at once - the mirror image of fetchAsync, for a caller
     *  (ChunkUploader) uploading many small blobs that would otherwise pay one HTTP request's overhead
     *  each. The gateway computes the hash itself; the caller should still compare it against its own, as
     *  fetch()/put() do, since nothing here stops a corrupted frame from arriving looking well-formed. */
    public CompletableFuture<byte[]> putAsync(byte[] data) {
        if (closed) {
            return CompletableFuture.failedFuture(new IOException("blobstream closed"));
        }
        int id = nextId.getAndIncrement();
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        puts.put(id, f);
        try {
            outbox.add(Tunnel.frame(PUT_BEGIN, id, longBytes(data.length), 0, 8));
            for (int off = 0; off < data.length; off += Tunnel.MAX_PAYLOAD) {
                int len = Math.min(Tunnel.MAX_PAYLOAD, data.length - off);
                outbox.add(Tunnel.frame(PUT_DATA, id, data, off, len));
            }
        } catch (IOException e) {
            puts.remove(id);
            return CompletableFuture.failedFuture(e);
        }
        return f.whenComplete((d, err) -> puts.remove(id));
    }

    private static byte[] longBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    /** Uploads one blob over the persistent stream and verifies the hash the gateway reports storing it under. */
    public byte[] put(byte[] data) throws IOException, GatewayException {
        byte[] want = Bytes.sha256(data);
        try {
            byte[] hash = putAsync(data).get(30, TimeUnit.SECONDS);
            if (!Arrays.equals(hash, want)) {
                throw new IOException("upload stored under an unexpected hash");
            }
            return hash;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (TimeoutException e) {
            close();
            throw new IOException("blobstream timed out uploading " + Bytes.hex(want), e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GatewayException g) {
                throw g;
            }
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        }
    }

    private void writeLoop() {
        try {
            while (!closed) {
                byte[] f = outbox.take();
                if (f == STOP) {
                    break;
                }
                out.write(f);
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException | InterruptedException e) {
            failAll(e instanceof IOException io ? io : new IOException("blobstream writer stopped", e));
        }
    }

    private void readLoop(DataInputStream in) {
        try {
            while (!closed) {
                Tunnel.Frame f = Tunnel.read(in);
                switch (f.type()) {
                    case BLOB_BEGIN -> {
                        Inflight inf = inflight.get(f.id());
                        if (inf != null && f.data().length == 8) {
                            long total = ByteBuffer.wrap(f.data()).getLong();
                            if (total < 0 || total > 64L * 1024 * 1024) {
                                inf.done.completeExceptionally(new IOException("invalid blob size: " + total));
                            } else {
                                inf.expected = total;
                                inf.buf = new ByteArrayOutputStream((int) total);
                                if (total == 0) {
                                    inf.done.complete(new byte[0]);
                                }
                            }
                        }
                    }
                    case BLOB_DATA -> {
                        Inflight inf = inflight.get(f.id());
                        if (inf != null && inf.buf != null) {
                            inf.buf.write(f.data());
                            if (inf.buf.size() >= inf.expected) {
                                inf.done.complete(inf.buf.toByteArray());
                            }
                        }
                    }
                    case MISSING -> {
                        Inflight inf = inflight.get(f.id());
                        if (inf != null) {
                            inf.done.completeExceptionally(new GatewayException(400, "NO_SUCH_BLOB"));
                        }
                    }
                    case PUT_OK -> {
                        CompletableFuture<byte[]> p = puts.get(f.id());
                        if (p != null && f.data().length == 32) {
                            p.complete(f.data());
                        }
                    }
                    case PUT_ERR -> {
                        CompletableFuture<byte[]> p = puts.get(f.id());
                        if (p != null) {
                            p.completeExceptionally(new GatewayException(400, new String(f.data(), StandardCharsets.UTF_8)));
                        }
                    }
                    case PONG -> { }
                    default -> { }
                }
            }
        } catch (IOException e) {
            failAll(e);
        }
    }

    private void failAll(IOException e) {
        closed = true;
        for (Inflight inf : inflight.values()) {
            inf.done.completeExceptionally(e);
        }
        inflight.clear();
        for (CompletableFuture<byte[]> p : puts.values()) {
            p.completeExceptionally(e);
        }
        puts.clear();
        try {
            sock.close();
        } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        closed = true;
        outbox.offer(STOP);
        failAll(new IOException("blobstream closed"));
    }
}
