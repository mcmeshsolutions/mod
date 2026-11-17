package dev.mcmesh.core;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Host side of the reverse tunnel. Dials the gateway, then serves guest TCP streams by connecting to the
 * local server and guest voice packets by relaying to the local voice port. Pure JDK, one virtual thread per stream.
 */
public final class TunnelAgent implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("mcmesh");
    private static final byte[] EOF = new byte[0];

    private final InetSocketAddress gateway;
    private final InetSocketAddress server;
    private final InetSocketAddress voice;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private volatile java.util.List<InetSocketAddress> channels = java.util.List.of();
    private final Map<Integer, DatagramSocket> peers = new ConcurrentHashMap<>();
    /** One writer thread drains this instead of every stream's thread writing straight to the socket
     *  under a shared lock - a `synchronized` write that blocks on a slow or stuck socket pins its
     *  virtual thread to its carrier, and enough of those piling up can starve the carrier pool the
     *  main read loop also needs, silently stalling every new guest stream with nothing to log. */
    private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private volatile Thread writerThread;
    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile boolean closed;

    private record Stream(Socket sock, BlockingQueue<byte[]> inbox) {}

    public TunnelAgent(InetSocketAddress gateway, InetSocketAddress server, InetSocketAddress voice) {
        this.gateway = gateway;
        this.server = server;
        this.voice = voice;
    }

    /** Local targets of the extra gateway channels, in gateway order (channel number = index + 1). */
    public void channels(java.util.List<InetSocketAddress> targets) {
        this.channels = java.util.List.copyOf(targets);
    }

    /** Authenticates and returns once the gateway accepts the tunnel. Throws if it refuses. */
    public void connect(Lease lease, String token) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(gateway, 5000);
        s.setSoTimeout(10_000);
        sock = s;
        out = s.getOutputStream();
        JsonObject h = new JsonObject();
        h.addProperty("lease", lease.id());
        h.addProperty("epoch", lease.epoch());
        if (GatewayClient.tunnelProof) {
            long ts = System.currentTimeMillis() / 1000;
            h.addProperty("ts", ts);
            h.addProperty("proof", proof(token, lease.id(), lease.epoch(), ts));
        } else {
            h.addProperty("token", token);
        }
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        Tunnel.write(out, Tunnel.HELLO, 0, hb, 0, hb.length);
        Tunnel.Frame f = Tunnel.read(new DataInputStream(s.getInputStream()));
        if (f.type() != Tunnel.HELLO_OK) {
            s.close();
            throw new IOException("gateway refused tunnel: " + new String(f.data(), StandardCharsets.UTF_8));
        }
        // the gateway pings every 10 s, so silence for 40 s means the path is dead
        s.setSoTimeout(40_000);
        writerThread = Threads.start("mesh-tunnel-writer", this::writeLoop);
    }

    private void writeLoop() {
        try {
            while (!closed) {
                byte[] b = outbox.take();
                out.write(b);
                out.flush();
            }
        } catch (IOException e) {
            close();
        } catch (InterruptedException ignored) {
            // closing
        }
    }

    static String proof(String token, String lease, long epoch, long ts) throws IOException {
        try {
            byte[] key = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(("mcmesh-tunnel|" + lease + "|" + epoch + "|" + ts).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Blocks until the tunnel dies, then tears everything down. */
    public void run() throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 64 * 1024));
        try {
            while (true) {
                Tunnel.Frame f = Tunnel.read(in);
                switch (f.type()) {
                    case Tunnel.OPEN -> open(f.id(), f.data().length > 0 ? f.data()[0] & 0xff : 0);
                    case Tunnel.DATA -> {
                        Stream st = streams.get(f.id());
                        if (st != null) {
                            put(st.inbox(), f.data());
                        }
                    }
                    case Tunnel.CLOSE -> endStream(f.id(), false);
                    case Tunnel.UDP -> udp(f.id(), voice, f.data(), 0);
                    case Tunnel.CUDP -> {
                        int ch = f.data().length > 0 ? f.data()[0] & 0xff : 0;
                        var cs = channels;
                        if (ch >= 1 && ch <= cs.size()) {
                            udp(f.id(), cs.get(ch - 1), f.data(), 1);
                        }
                    }
                    case Tunnel.PING -> send(Tunnel.PONG, 0, new byte[0], 0);
                    case Tunnel.UDP_CLOSE -> {
                        DatagramSocket d = peers.remove(f.id());
                        if (d != null) {
                            d.close();
                        }
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                throw e;
            }
        } finally {
            close();
        }
    }

    private static void put(BlockingQueue<byte[]> q, byte[] d) throws IOException {
        try {
            q.put(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Queues a frame for the single writer thread; never blocks on the socket itself. */
    private void send(byte type, int id, byte[] d, int len) {
        try {
            outbox.add(Tunnel.frame(type, id, d, 0, len));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "dropped oversized frame type={0} id={1}: {2}", type, id, e.toString());
        }
    }

    private void open(int id, int chan) {
        var cs = channels;
        if (chan > cs.size()) {
            LOG.log(System.Logger.Level.WARNING, "stream {0} requested unknown channel {1} (only {2} configured)", id, chan, cs.size());
            send(Tunnel.CLOSE, id, new byte[0], 0);
            return;
        }
        InetSocketAddress target = chan == 0 ? server : cs.get(chan - 1);
        Stream st = new Stream(new Socket(), new ArrayBlockingQueue<>(32));
        streams.put(id, st);
        Threads.start("mesh-stream-" + id, () -> pump(id, st, target));
    }

    private void pump(int id, Stream st, InetSocketAddress target) {
        boolean connected = false;
        try {
            st.sock().setTcpNoDelay(true);
            st.sock().connect(target, 5000);
            connected = true;
            Threads.start("mesh-stream-w-" + id, () -> {
                try {
                    OutputStream o = st.sock().getOutputStream();
                    while (true) {
                        byte[] d = st.inbox().take();
                        if (d == EOF) {
                            break;
                        }
                        o.write(d);
                    }
                } catch (IOException | InterruptedException e) {
                    // the read side below notices the closed socket
                } finally {
                    closeQuietly(st.sock());
                }
            });
            InputStream in = st.sock().getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                send(Tunnel.DATA, id, buf, n);
            }
        } catch (IOException e) {
            // local server went away or tunnel write failed; distinguish the two since a guest never
            // reaching the local server at all (connect itself failing) looks identical to the guest
            // from the relay's side as any other dropped connection, with nothing in either log otherwise
            if (!connected) {
                LOG.log(System.Logger.Level.WARNING, "stream {0} could not connect to {1}: {2}", id, target, e);
            } else {
                LOG.log(System.Logger.Level.DEBUG, "stream {0} to {1} ended: {2}", id, target, e.toString());
            }
        } finally {
            closeQuietly(st.sock());
            endStream(id, true);
        }
    }

    private void endStream(int id, boolean notify) {
        Stream st = streams.remove(id);
        if (st == null) {
            return;
        }
        st.inbox().offer(EOF);
        closeQuietly(st.sock());
        if (notify && !closed) {
            send(Tunnel.CLOSE, id, new byte[0], 0);
        }
    }

    private void udp(int id, InetSocketAddress target, byte[] data, int off) throws IOException {
        DatagramSocket d = peers.get(id);
        if (d == null) {
            d = new DatagramSocket();
            d.connect(target);
            DatagramSocket fresh = d;
            peers.put(id, fresh);
            Threads.start("mesh-voice-" + id, () -> {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    while (!fresh.isClosed()) {
                        fresh.receive(p);
                        send(Tunnel.UDP, id, buf, p.getLength());
                    }
                } catch (IOException e) {
                    // closed by UDP_CLOSE or shutdown
                }
            });
        }
        d.send(new DatagramPacket(data, off, data.length - off));
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // nothing useful to do
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread w = writerThread;
        if (w != null) {
            w.interrupt();
        }
        Socket s = sock;
        if (s != null) {
            closeQuietly(s);
        }
        for (Integer id : streams.keySet().toArray(new Integer[0])) {
            endStream(id, false);
        }
        peers.values().forEach(DatagramSocket::close);
        peers.clear();
    }
}
