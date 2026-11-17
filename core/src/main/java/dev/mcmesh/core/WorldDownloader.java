package dev.mcmesh.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    /** How many chunk/aux fetches run at once; each one is its own TCP connection since the gateway client
     *  is pinned to HTTP/1.1 (no multiplexing). Mutable, same pattern as GatewayClient.syncProgress and
     *  friends below, so the mod's config (syncThreads) can tune it without this module depending on
     *  NeoForge's config system. Defaulted down from an earlier 8 after intermittent "HTTP connect timed
     *  out" failures during a full resync (2026-11-17) - lower concurrency avoids straining the connection
     *  limit on some networks, at the cost of a slower sync on networks that were never the problem. */
    public static volatile java.util.function.IntSupplier threads = () -> 2;

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

    private static int fetchAll(GatewayClient gw, List<RevisionFiles.Chunk> chunks, List<RevisionFiles.Aux> aux, Sink sink, Progress progress) throws IOException, GatewayException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads.getAsInt()));
        try {
            List<Future<?>> jobs = new ArrayList<>();
            long total = chunks.size() + aux.size();
            AtomicLong done = new AtomicLong();
            AtomicLong bytes = new AtomicLong();
            if (progress != null) {
                progress.update(0, total, 0);
            }
            for (RevisionFiles.Chunk c : chunks) {
                jobs.add(pool.submit(() -> {
                    byte[] d = fetch(gw, c.hash());
                    sink.chunk(c, d);
                    tick(progress, done, bytes, total, d.length);
                    return null;
                }));
            }
            for (RevisionFiles.Aux a : aux) {
                jobs.add(pool.submit(() -> {
                    byte[] d = fetch(gw, a.hash());
                    sink.aux(a, d);
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
            return jobs.size();
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
