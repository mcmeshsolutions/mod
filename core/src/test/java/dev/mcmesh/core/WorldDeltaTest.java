package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldDeltaTest {
    private HttpServer server;
    private final Map<String, byte[]> blobs = new HashMap<>();
    private final List<String> fetched = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> listings = new HashMap<>();

    private byte[] blob(String text) {
        byte[] d = text.getBytes(StandardCharsets.UTF_8);
        blobs.put(Bytes.hex(Bytes.sha256(d)), d);
        return Bytes.sha256(d);
    }

    private static String chunkJson(String dim, int cx, int cz, byte[] hash) {
        return "{\"dim\":\"" + dim + "\",\"kind\":\"region\",\"cx\":" + cx + ",\"cz\":" + cz + ",\"hash\":\"" + Bytes.hex(hash) + "\"}";
    }

    private static String auxJson(String path, byte[] hash) {
        return "{\"path\":\"" + path + "\",\"hash\":\"" + Bytes.hex(hash) + "\"}";
    }

    private void listing(String rev, List<String> chunks, List<String> aux) {
        listings.put(rev, "{\"rev\":\"" + rev + "\",\"root\":\"x\",\"chunks\":[" + String.join(",", chunks) + "],\"aux\":[" + String.join(",", aux) + "]}");
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/revisions/", ex -> {
            String rev = ex.getRequestURI().getPath().replace("/v1/revisions/", "").replace("/files", "");
            String body = listings.get(rev);
            byte[] b = (body == null ? "{\"error\":\"REVISION_PRUNED\"}" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 410 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/v1/blobs/", ex -> {
            String h = ex.getRequestURI().getPath().replace("/v1/blobs/", "");
            fetched.add(h);
            byte[] d = blobs.get(h);
            ex.sendResponseHeaders(d == null ? 400 : 200, d == null ? 0 : d.length);
            if (d != null) {
                ex.getResponseBody().write(d);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GatewayClient gw() {
        return new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), "t");
    }

    private static final class Recorder implements WorldDownloader.Sink {
        final List<String> chunks = Collections.synchronizedList(new ArrayList<>());
        final List<String> aux = Collections.synchronizedList(new ArrayList<>());
        final List<String> removed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void chunk(RevisionFiles.Chunk c, byte[] data) {
            chunks.add(c.cx() + "," + c.cz() + "=" + new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public void aux(RevisionFiles.Aux a, byte[] data) {
            aux.add(a.path() + "=" + new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public void removeChunk(RevisionFiles.Chunk c) {
            removed.add(c.cx() + "," + c.cz());
        }
    }

    private static final String OW = "minecraft:overworld";

    @Test
    void planFindsChangedNewAndRemovedEntriesOnly() {
        byte[] a = Bytes.sha256(new byte[] {1}), b = Bytes.sha256(new byte[] {2}), c = Bytes.sha256(new byte[] {3});
        var have = new RevisionFiles("r1", List.of(new RevisionFiles.Chunk(OW, "region", 0, 0, a), new RevisionFiles.Chunk(OW, "region", 1, 0, a), new RevisionFiles.Chunk(OW, "region", 2, 0, a)),
                List.of(new RevisionFiles.Aux("level.dat", a), new RevisionFiles.Aux("data/x.dat", a)));
        var want = new RevisionFiles("r2", List.of(new RevisionFiles.Chunk(OW, "region", 0, 0, a), new RevisionFiles.Chunk(OW, "region", 1, 0, b), new RevisionFiles.Chunk(OW, "region", 5, 5, c),
                new RevisionFiles.Chunk("minecraft:the_nether", "region", 0, 0, a)), List.of(new RevisionFiles.Aux("level.dat", b), new RevisionFiles.Aux("data/x.dat", a)));
        var d = WorldDownloader.plan(have, want);
        assertEquals(List.of("1,0", "5,5", "0,0"), d.chunks().stream().map(x -> x.cx() + "," + x.cz()).toList(), "changed, new, and the same coordinates in another dimension");
        assertEquals(List.of("level.dat"), d.aux().stream().map(RevisionFiles.Aux::path).toList());
        assertEquals(List.of("2,0"), d.removedChunks().stream().map(x -> x.cx() + "," + x.cz()).toList());
        assertEquals(4, d.fetches());
        assertEquals(0, WorldDownloader.plan(want, want).fetches(), "identical revisions need nothing");
    }

    @Test
    void onlyTheDifferenceIsFetchedAndGoneChunksAreRemoved() throws Exception {
        byte[] same = blob("same"), old = blob("old"), fresh = blob("fresh"), added = blob("added"), lvl = blob("level v2"), lvl1 = blob("level v1"), gone = blob("gone");
        listing("r1", List.of(chunkJson(OW, 0, 0, same), chunkJson(OW, 1, 0, old), chunkJson(OW, 2, 0, gone)), List.of(auxJson("level.dat", lvl1)));
        listing("r2", List.of(chunkJson(OW, 0, 0, same), chunkJson(OW, 1, 0, fresh), chunkJson(OW, 3, 0, added)), List.of(auxJson("level.dat", lvl)));
        Recorder sink = new Recorder();
        var d = WorldDownloader.downloadDelta(gw(), "r1", "r2", sink, null);
        assertEquals(3, d.fetches());
        assertEquals(List.of("1,0=fresh", "3,0=added"), sink.chunks.stream().sorted().toList());
        assertEquals(List.of("level.dat=level v2"), sink.aux);
        assertEquals(List.of("2,0"), sink.removed);
        assertEquals(3, fetched.size(), "the unchanged chunk was never downloaded");
        assertTrue(!fetched.contains(Bytes.hex(same)));
    }

    @Test
    void aPrunedOldRevisionSurfacesAsAGatewayErrorSoTheCallerCanDoAFullDownload() throws Exception {
        listing("r2", List.of(), List.of());
        try {
            WorldDownloader.downloadDelta(gw(), "r1", "r2", new Recorder(), null);
            throw new AssertionError("expected a GatewayException");
        } catch (GatewayException e) {
            assertEquals(410, e.status);
        }
    }

    @Test
    void aFullDownloadStillFetchesEverythingAndNeverRemoves() throws Exception {
        byte[] a = blob("a"), b = blob("b");
        listing("r2", List.of(chunkJson(OW, 0, 0, a), chunkJson(OW, 1, 0, b)), List.of());
        Recorder sink = new Recorder();
        assertEquals(2, WorldDownloader.download(gw(), "r2", sink));
        assertEquals(2, sink.chunks.size());
        assertTrue(sink.removed.isEmpty());
        assertArrayEquals(Bytes.sha256("a".getBytes(StandardCharsets.UTF_8)), a);
    }
}
