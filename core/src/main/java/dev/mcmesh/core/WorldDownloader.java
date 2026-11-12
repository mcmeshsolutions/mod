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
    }

    public interface Progress {
        void update(long doneFiles, long totalFiles, long doneBytes);
    }

    private static final int THREADS = 8;

    private WorldDownloader() { }

    public static int download(GatewayClient gw, String rev, Sink sink) throws IOException, GatewayException {
        return download(gw, rev, sink, null);
    }

    public static int download(GatewayClient gw, String rev, Sink sink, Progress progress) throws IOException, GatewayException {
        RevisionFiles files = gw.listRevision(rev);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            long total = files.chunks().size() + files.aux().size();
            AtomicLong done = new AtomicLong();
            AtomicLong bytes = new AtomicLong();
            if (progress != null) {
                progress.update(0, total, 0);
            }
            for (RevisionFiles.Chunk c : files.chunks()) {
                jobs.add(pool.submit(() -> {
                    byte[] d = fetch(gw, c.hash());
                    sink.chunk(c, d);
                    tick(progress, done, bytes, total, d.length);
                    return null;
                }));
            }
            for (RevisionFiles.Aux a : files.aux()) {
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
