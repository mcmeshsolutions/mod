package dev.mcmesh.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Pulls every blob of a revision and verifies its hash before handing it to the sink. */
public final class WorldDownloader {
    public interface Sink {
        void chunk(RevisionFiles.Chunk c, byte[] data) throws IOException;

        void aux(RevisionFiles.Aux a, byte[] data) throws IOException;

        /** The chunk exists in the old revision but not in the new one. Full downloads never call this. */
        default void removeChunk(RevisionFiles.Chunk c) throws IOException { }
    }

    /** What differs between two revisions: everything to fetch, and the chunks that disappeared. */
    public record Delta(List<RevisionFiles.Chunk> chunks, List<RevisionFiles.Aux> aux, List<RevisionFiles.Chunk> removedChunks) {
        public int fetches() {
            return chunks.size() + aux.size();
        }
    }

    private static String key(RevisionFiles.Chunk c) {
        return c.dim() + "|" + c.kind() + "|" + c.cx() + "," + c.cz();
    }

    /** Pure comparison of two listings: new or changed entries of `want`, and chunks of `have` that `want` lacks. */
    public static Delta plan(RevisionFiles have, RevisionFiles want) {
        java.util.Map<String, byte[]> oldChunks = new java.util.HashMap<>();
        have.chunks().forEach(c -> oldChunks.put(key(c), c.hash()));
        java.util.Map<String, byte[]> oldAux = new java.util.HashMap<>();
        have.aux().forEach(a -> oldAux.put(a.path(), a.hash()));
        List<RevisionFiles.Chunk> chunks = new ArrayList<>();
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (RevisionFiles.Chunk c : want.chunks()) {
            wanted.add(key(c));
            if (!Arrays.equals(oldChunks.get(key(c)), c.hash())) {
                chunks.add(c);
            }
        }
        List<RevisionFiles.Aux> aux = new ArrayList<>();
        for (RevisionFiles.Aux a : want.aux()) {
            if (!Arrays.equals(oldAux.get(a.path()), a.hash())) {
                aux.add(a);
            }
        }
        List<RevisionFiles.Chunk> removed = new ArrayList<>();
        for (RevisionFiles.Chunk c : have.chunks()) {
            if (!wanted.contains(key(c))) {
                removed.add(c);
            }
        }
        return new Delta(chunks, aux, removed);
    }

    /**
     * Brings a local copy that matches `haveRev` up to `rev` by fetching only what changed. The caller must know the local
     * copy really is `haveRev`; any GatewayException (for example a pruned old revision) means: do a full download instead.
     */
    public static Delta downloadDelta(GatewayClient gw, String haveRev, String rev, Sink sink, Progress progress) throws IOException, GatewayException {
        Delta d = plan(gw.listRevision(haveRev), gw.listRevision(rev));
        for (RevisionFiles.Chunk c : d.removedChunks()) {
            sink.removeChunk(c);
        }
        fetchAll(gw, d.chunks(), d.aux(), sink, progress);
        return d;
    }

    public interface Progress {
        void update(long doneFiles, long totalFiles, long doneBytes);
    }

    /** How many fetches run at once over the HTTP fallback - each one is its own TCP connection there, so
     *  a flaky network or a busy gateway may need this lower. Only applies when no persistent blob-stream
     *  connection is available (or a specific blob's pipelined fetch failed and is being retried): the
     *  normal path pipelines every fetch of a sync over the one blob-stream connection at once with no
     *  concurrency cap of its own, so it saturates whatever the link can actually do instead of being
     *  bounded by a guess tuned for one particular network. Mutable, same pattern as
     *  GatewayClient.syncProgress and friends below, so the mod's config (syncThreads) can tune it
     *  without this module depending on NeoForge's config system. */
    public static volatile java.util.function.IntSupplier threads = () -> 4;

    private WorldDownloader() { }

    public static int download(GatewayClient gw, String rev, Sink sink) throws IOException, GatewayException {
        return download(gw, rev, sink, null);
    }

    public static int download(GatewayClient gw, String rev, Sink sink, Progress progress) throws IOException, GatewayException {
        RevisionFiles files = gw.listRevision(rev);
        return fetchAll(gw, files.chunks(), files.aux(), sink, progress);
    }

    /** Fetches an explicit subset of a listing, for callers that decide themselves what is needed now. */
    public static int downloadParts(GatewayClient gw, List<RevisionFiles.Chunk> chunks, List<RevisionFiles.Aux> aux, Sink sink, Progress progress) throws IOException, GatewayException {
        return fetchAll(gw, chunks, aux, sink, progress);
    }

    /** One fetch to apply once its bytes arrive, independent of whether it came from a chunk or an aux file. */
    private record Item(byte[] hash, SinkApply apply) { }

    private interface SinkApply {
        void accept(byte[] data) throws IOException;
    }

    private static int fetchAll(GatewayClient gw, List<RevisionFiles.Chunk> chunks, List<RevisionFiles.Aux> aux, Sink sink, Progress progress) throws IOException, GatewayException {
        long total = chunks.size() + aux.size();
        AtomicLong done = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        if (progress != null) {
            progress.update(0, total, 0);
        }
        List<Item> items = new ArrayList<>((int) total);
        for (RevisionFiles.Chunk c : chunks) {
            items.add(new Item(c.hash(), d -> sink.chunk(c, d)));
        }
        for (RevisionFiles.Aux a : aux) {
            items.add(new Item(a.hash(), d -> sink.aux(a, d)));
        }

        // Issue every WANT at once over the persistent stream, if there is one: pipelined requests on one
        // already-open connection are regulated by the gateway's own concurrency limit and by TCP itself,
        // so this stays saturated on any link instead of being capped by a thread-count guess tuned for
        // one particular network. Anything with no stream to use, or whose pipelined fetch failed, falls
        // back to the thread-pool-bounded per-file HTTP path below.
        List<Item> fallback = new ArrayList<>();
        List<Item> pipelined = new ArrayList<>(items.size());
        List<CompletableFuture<byte[]>> futures = new ArrayList<>(items.size());
        for (Item it : items) {
            CompletableFuture<byte[]> f = gw.blobGetAsync(it.hash());
            if (f == null) {
                fallback.add(it);
            } else {
                pipelined.add(it);
                futures.add(f);
            }
        }
        for (int i = 0; i < pipelined.size(); i++) {
            Item it = pipelined.get(i);
            try {
                // Idle timeout: waiting up to 30s for the next chunk to arrive. As long as chunks keep
                // streaming in, each get() returns quickly and large transfers can take as long as needed
                // at full link speed without being aborted by an arbitrary absolute deadline.
                byte[] d = futures.get(i).get(30, TimeUnit.SECONDS);
                if (!Arrays.equals(Bytes.sha256(d), it.hash())) {
                    throw new IOException("blob " + Bytes.hex(it.hash()) + " failed hash check");
                }
                it.apply().accept(d);
                tick(progress, done, bytes, total, d.length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (TimeoutException e) {
                // If a chunk genuinely stalls for 30s with zero data, close the stream immediately.
                // failAll() exceptionally completes all remaining futures at once, preventing any
                // sequential 30s pile-up across the rest of the batch.
                gw.closeBlobStream();
                fallback.add(it);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof GatewayException g) {
                    throw g;
                }
                fallback.add(it);
            }
        }

        if (!fallback.isEmpty()) {
            fetchFallback(gw, fallback, progress, done, bytes, total);
        }
        return items.size();
    }

    private static void fetchFallback(GatewayClient gw, List<Item> items, Progress progress, AtomicLong done, AtomicLong bytes, long total) throws IOException, GatewayException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads.getAsInt()));
        try {
            List<Future<?>> jobs = new ArrayList<>(items.size());
            for (Item it : items) {
                jobs.add(pool.submit(() -> {
                    byte[] d = fetch(gw, it.hash());
                    it.apply().accept(d);
                    tick(progress, done, bytes, total, d.length);
                    return null;
                }));
            }
            for (Future<?> j : jobs) {
                try {
                    j.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
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
        } finally {
            pool.shutdownNow();
        }
    }

    private static void tick(Progress p, AtomicLong done, AtomicLong bytes, long total, int n) {
        long b = bytes.addAndGet(n);
        long d = done.incrementAndGet();
        if (p != null) {
            p.update(d, total, b);
        }
    }

    private static byte[] fetch(GatewayClient gw, byte[] hash) throws IOException, GatewayException {
        byte[] data = gw.blobGet(hash);
        if (!Arrays.equals(Bytes.sha256(data), hash)) {
            throw new IOException("blob " + Bytes.hex(hash) + " failed hash check");
        }
        return data;
    }
}
