package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Drives the real Rust gateway binary, so wire-format drift between Java and Rust fails here. */
class GatewayIT {
    private static Process gateway;
    private static Path dir;
    private static int apiPort, relayPort, publicPort, voicePort;
    private static final String TOKEN = "tok-alice";

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void run(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = System.getProperty("gatewayd.bin");
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("MCMESH_DB", dir.resolve("g.db").toString());
        pb.environment().put("MCMESH_CAS", dir.resolve("cas").toString());
        Process p = pb.start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), String.join(" ", args));
    }

    @BeforeAll
    static void start() throws Exception {
        File bin = new File(System.getProperty("gatewayd.bin"));
        assumeTrue(bin.canExecute(), "build gatewayd first (cargo build): " + bin);
        dir = Files.createTempDirectory("mcmesh-it");
        apiPort = freePort();
        relayPort = freePort();
        publicPort = freePort();
        voicePort = freePort();
        run(dir, "create-world", "w1", "Test", "1.21.1", "neoforge");
        run(dir, "create-world", "w2", "Test2", "1.21.1", "neoforge");
        run(dir, "add-member", "w2", "00000000-0000-0000-0000-000000000002", "bob", "dev-b", "HOST_ELIGIBLE", "tok-bob");
        run(dir, "add-member", "w1", "00000000-0000-0000-0000-000000000001", "alice", "dev-a", "HOST_ELIGIBLE", TOKEN);
        ProcessBuilder pb = new ProcessBuilder(bin.getPath(), "serve").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("MCMESH_DB", dir.resolve("g.db").toString());
        pb.environment().put("MCMESH_CAS", dir.resolve("cas").toString());
        pb.environment().put("MCMESH_ADDR", "127.0.0.1:" + apiPort);
        pb.environment().put("MCMESH_GUESTS", "open");
        pb.environment().put("MCMESH_WORLD", "w1");
        pb.environment().put("MCMESH_RELAY_ADDR", "127.0.0.1:" + relayPort);
        pb.environment().put("MCMESH_PUBLIC_ADDR", "127.0.0.1:" + publicPort);
        pb.environment().put("MCMESH_VOICE_ADDR", "127.0.0.1:" + voicePort);
        gateway = pb.start();
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", apiPort)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("gateway did not start");
    }

    @AfterAll
    static void stop() {
        if (gateway != null) {
            gateway.destroy();
        }
    }

    private static Thread upperCaseServer(ServerSocket ss) {
        return Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Socket s = ss.accept();
                    Thread.ofVirtual().start(() -> {
                        try (s) {
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = s.getInputStream().read(b)) > 0) {
                                for (int i = 0; i < n; i++) {
                                    b[i] = (byte) Character.toUpperCase(b[i]);
                                }
                                s.getOutputStream().write(b, 0, n);
                            }
                        } catch (IOException ignored) {
                            // peer left
                        }
                    });
                }
            } catch (IOException ignored) {
                // server closed
            }
        });
    }

    @Test
    void hostFlowEndToEnd() throws Exception {
        GatewayClient gw = new GatewayClient("http://127.0.0.1:" + apiPort, TOKEN);
        JoinResult j = gw.join("w1", 2, 5.0, true);
        Lease lease = ((JoinResult.Host) j).lease();
        assertEquals(1, lease.epoch());
        assertEquals(32, lease.hmacKey().length);
        gw.heartbeat(lease.id(), lease.epoch());

        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (ServerSocket mc = new ServerSocket(0, 50, loopback);
                DatagramSocket voice = new DatagramSocket(0, loopback)) {
            upperCaseServer(mc);
            Thread.ofVirtual().start(() -> {
                byte[] b = new byte[2048];
                DatagramPacket p = new DatagramPacket(b, b.length);
                try {
                    while (true) {
                        voice.receive(p);
                        voice.send(new DatagramPacket(b, p.getLength(), p.getSocketAddress()));
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
            TunnelAgent agent = new TunnelAgent(new InetSocketAddress("127.0.0.1", relayPort),
                    new InetSocketAddress("127.0.0.1", mc.getLocalPort()), new InetSocketAddress("127.0.0.1", voice.getLocalPort()));
            agent.connect(lease, TOKEN);
            Thread agentThread = Thread.ofVirtual().start(() -> {
                try {
                    agent.run();
                } catch (IOException ignored) {
                    // torn down at the end of the test
                }
            });

            try (Socket g = new Socket("127.0.0.1", publicPort)) {
                g.setSoTimeout(5000);
                g.getOutputStream().write("hello guest".getBytes(StandardCharsets.UTF_8));
                assertEquals("HELLO GUEST", new String(g.getInputStream().readNBytes(11), StandardCharsets.UTF_8));
                byte[] big = new byte[200_000];
                java.util.Arrays.fill(big, (byte) 'a');
                Thread w = Thread.ofVirtual().start(() -> {
                    try {
                        g.getOutputStream().write(big);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                byte[] back = g.getInputStream().readNBytes(big.length);
                w.join();
                assertEquals(big.length, back.length);
                assertEquals('A', back[big.length - 1]);
            }

            try (DatagramSocket v = new DatagramSocket()) {
                v.setSoTimeout(5000);
                v.send(new DatagramPacket(new byte[] {1, 2, 3}, 3, InetAddress.getLoopbackAddress(), voicePort));
                DatagramPacket r = new DatagramPacket(new byte[16], 16);
                v.receive(r);
                assertArrayEquals(new byte[] {1, 2, 3}, java.util.Arrays.copyOf(r.getData(), r.getLength()));
            }
            agent.close();
            agentThread.join(5000);
        }

        byte[] chunk = "chunk nbt bytes".getBytes(StandardCharsets.UTF_8);
        byte[] h = gw.blobPut(chunk);
        assertArrayEquals(chunk, gw.blobGet(h));
        Chain chain = new Chain(lease.hmacKey());
        WalFrame put = chain.next("put:region", "minecraft:overworld", 3, -4, h);
        WalFrame bar = chain.next("barrier", "", 0, 0, null);
        assertEquals(2, gw.walAppend(lease.id(), lease.epoch(), List.of(put, bar)));

        Chain wrongKey = new Chain(new byte[32]);
        wrongKey.next("barrier", "", 0, 0, null);
        wrongKey.next("barrier", "", 0, 0, null);
        WalFrame forged = wrongKey.next("put:region", "minecraft:overworld", 9, 9, h);
        GatewayException bad = assertThrows(GatewayException.class, () -> gw.walAppend(lease.id(), lease.epoch(), List.of(forged)));
        assertEquals("BAD_MAC", bad.code);

        Revision rev = gw.commit(lease.id(), lease.epoch(), lease.baseRev(), "AUTOSAVE", false);
        assertEquals(1, rev.seq());
        GatewayException moved = assertThrows(GatewayException.class, () -> gw.commit(lease.id(), lease.epoch(), lease.baseRev(), "AUTOSAVE", false));
        assertEquals("HEAD_MOVED", moved.code);
        gw.commit(lease.id(), lease.epoch(), rev.id(), "SHUTDOWN", true);
        GatewayException stale = assertThrows(GatewayException.class, () -> gw.heartbeat(lease.id(), lease.epoch()));
        assertTrue(stale.leaseLost());
    }

    @Test
    void uploaderCoalescesAndCommitsOnlyBarrieredWrites() throws Exception {
        GatewayClient gw = new GatewayClient("http://127.0.0.1:" + apiPort, "tok-bob");
        JoinResult j = gw.join("w2", 2, 5.0, true);
        Lease lease = ((JoinResult.Host) j).lease();
        ChunkUploader up = new ChunkUploader(gw, lease);
        up.put("minecraft:overworld", "region", 0, 0, "v1".getBytes(StandardCharsets.UTF_8));
        up.put("minecraft:overworld", "region", 0, 0, "v2".getBytes(StandardCharsets.UTF_8));
        up.put("minecraft:overworld", "poi", 1, 1, "p".getBytes(StandardCharsets.UTF_8));
        up.auxPut("level.dat", "lvl".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, up.pendingCount());
        up.flush(true);
        assertEquals(0, up.pendingCount());
        up.put("minecraft:overworld", "region", 5, 5, "late".getBytes(StandardCharsets.UTF_8));
        up.flush(false);
        Revision rev = gw.commit(lease.id(), lease.epoch(), lease.baseRev(), "AUTOSAVE", false);
        assertEquals(1, rev.seq());
        up.put("minecraft:overworld", "region", 6, 6, "x".getBytes(StandardCharsets.UTF_8));
        up.flush(true);
        Revision rev2 = gw.commit(lease.id(), lease.epoch(), rev.id(), "AUTOSAVE", false);
        assertEquals(2, rev2.seq());

        java.util.Map<String, String> got = new java.util.concurrent.ConcurrentHashMap<>();
        int n = WorldDownloader.download(gw, rev2.id(), new WorldDownloader.Sink() {
            public void chunk(RevisionFiles.Chunk c, byte[] d) {
                got.put(c.dim() + "/" + c.kind() + "/" + c.cx() + "," + c.cz(), new String(d, StandardCharsets.UTF_8));
            }

            public void aux(RevisionFiles.Aux a, byte[] d) {
                got.put(a.path(), new String(d, StandardCharsets.UTF_8));
            }
        });
        assertEquals(5, n);
        assertEquals("v2", got.get("minecraft:overworld/region/0,0"));
        assertEquals("p", got.get("minecraft:overworld/poi/1,1"));
        assertEquals("x", got.get("minecraft:overworld/region/6,6"));
        assertEquals("lvl", got.get("level.dat"));
        assertEquals("late", got.get("minecraft:overworld/region/5,5"), "unbarriered write rides in with the next barrier");
    }

    @Test
    void keyIdentityLogsInGetsGrantedAndHosts() throws Exception {
        IdentityKey key = IdentityKey.generate();
        GatewayClient anon = new GatewayClient("http://127.0.0.1:" + apiPort, "");
        Identity id = anon.loginKey(key, "CrackedCarl");
        assertEquals("CrackedCarl", id.name());
        GatewayClient gw = new GatewayClient("http://127.0.0.1:" + apiPort, id.token());
        GatewayException nm = assertThrows(GatewayException.class, () -> gw.join("w1", 1, 1.0, false));
        assertEquals("NOT_MEMBER", nm.code);
        run(dir, "grant", "w1", "CrackedCarl", "CLIENT_ONLY");
        assertInstanceOf(JoinResult.class, gw.join("w1", 1, 1.0, false));

        GatewayException taken = assertThrows(GatewayException.class, () -> anon.loginKey(IdentityKey.generate(), "crackedcarl"));
        assertEquals("NAME_TAKEN", taken.code);
        assertEquals(id.uuid(), anon.loginKey(key, "CrackedCarl").uuid());
    }
}
