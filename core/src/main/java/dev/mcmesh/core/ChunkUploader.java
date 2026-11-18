package dev.mcmesh.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects chunk-level writes from the server and ships them to the gateway WAL.
 * Writes to the same key coalesce, so only the newest bytes are uploaded per flush.
 */
public final class ChunkUploader {
    private static final int BATCH = 500;
    private static final int PARALLEL = 16;

    private record Key(String op, String dim, int cx, int cz) { }

    private final GatewayClient gw;
    private final Lease lease;
    private final Chain chain;
    private final Map<Key, byte[]> pending = new LinkedHashMap<>();
    private final Object flushLock = new Object();
    /** SHA-256 of what was last sent per key, so a chunk that is saved again with identical bytes is not sent again. */
    private final Map<Key, java.nio.ByteBuffer> sentHash = new java.util.HashMap<>();

    public ChunkUploader(GatewayClient gw, Lease lease) {
        this.gw = gw;
        this.lease = lease;
        this.chain = new Chain(lease.hmacKey());
    }

    /** Dimensions a mod declared ephemeral (compat manifest) are never uploaded. */
    public static volatile java.util.function.Predicate<String> skipDimension = d -> false;

    /** kind is region, entities or poi. */
    public void put(String dim, String kind, int cx, int cz, byte[] data) {
        if (skipDimension.test(dim)) {
            return;
        }
        record(new Key("put:" + kind, dim, cx, cz), data.clone());
    }

    public void delete(String dim, String kind, int cx, int cz) {
        if (skipDimension.test(dim)) {
            return;
        }
        record(new Key("del:" + kind, dim, cx, cz), null);
    }

    /** Aux files (level.dat, playerdata) use the path as the dim and 0,0 as coordinates. */
    public void auxPut(String path, byte[] data) {
        record(new Key("aux_put", path, 0, 0), data.clone());
    }

    public void auxDelete(String path) {
        record(new Key("aux_del", path, 0, 0), null);
    }

    private synchronized void record(Key k, byte[] data) {
        pending.remove(k);
        pending.put(k, data);
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    private volatile long lastBarrierSeq = -1;

    /** Seq of the last barrier the gateway acknowledged, -1 when none was sent. */
    public long lastBarrierSeq() {
        return lastBarrierSeq;
    }

    /** Uploads everything recorded so far and, when barrier is set, seals it so a commit can include it. */
    public void flush(boolean barrier) throws IOException, GatewayException {
        synchronized (flushLock) {
            List<Map.Entry<Key, byte[]>> work;
            synchronized (this) {
                work = new ArrayList<>(pending.entrySet());
                pending.clear();
            }
            work = withoutRepeats(work);
            int sent = 0;
            try {
                while (sent < work.size()) {
                    int end = Math.min(work.size(), sent + BATCH);
                    appendBatch(work.subList(sent, end), false);
                    sent = end;
                }
                if (barrier) {
                    appendBatch(List.of(), true);
                }
            } catch (IOException | GatewayException | RuntimeException ex) {
                synchronized (this) {
                    Map<Key, byte[]> merged = new LinkedHashMap<>();
                    for (Map.Entry<Key, byte[]> e : work.subList(sent, work.size())) {
                        merged.put(e.getKey(), e.getValue());
                    }
                    merged.putAll(pending);
                    pending.clear();
                    pending.putAll(merged);
                }
                throw ex;
            }
        }
    }

    private List<Map.Entry<Key, byte[]>> withoutRepeats(List<Map.Entry<Key, byte[]>> work) {
        List<Map.Entry<Key, byte[]>> out = new ArrayList<>(work.size());
        synchronized (sentHash) {
            for (Map.Entry<Key, byte[]> e : work) {
                if (e.getValue() != null && java.nio.ByteBuffer.wrap(Bytes.sha256(e.getValue())).equals(sentHash.get(e.getKey()))) {
                    continue;
                }
                out.add(e);
            }
        }
        return out;
    }

    /** Blob uploads are independent of each other, so overlap them. Issues every upload at once over the
     *  persistent blob-stream connection, if there is one - pipelined on one already-open connection
     *  instead of bound by a thread-count guess the way the HTTP fallback below has to be, matching
     *  WorldDownloader's reasoning on the way down. Anything with no stream to use, or whose pipelined
     *  upload failed, falls back to PARALLEL raw HTTP PUTs. */
    private List<byte[]> putBlobs(List<Map.Entry<Key, byte[]>> items) throws IOException, GatewayException {
        byte[][] out = new byte[items.size()][];
        List<Integer> fallback = new ArrayList<>();
        List<Integer> pipelined = new ArrayList<>();
        List<java.util.concurrent.CompletableFuture<byte[]>> futures = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            byte[] data = items.get(i).getValue();
            if (data == null) {
                continue;
            }
            var f = gw.blobPutAsync(data);
            if (f == null) {
                fallback.add(i);
            } else {
                pipelined.add(i);
                futures.add(f);
            }
        }
        for (int j = 0; j < pipelined.size(); j++) {
            int i = pipelined.get(j);
            try {
                byte[] hash = futures.get(j).get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!Arrays.equals(hash, Bytes.sha256(items.get(i).getValue()))) {
                    throw new IOException("upload stored under an unexpected hash");
                }
                out[i] = hash;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ie);
            } catch (java.util.concurrent.TimeoutException te) {
                gw.closeBlobStream();
                fallback.add(i);
            } catch (java.util.concurrent.ExecutionException ee) {
                if (ee.getCause() instanceof GatewayException g) {
                    throw g;
                }
                fallback.add(i);
            }
        }
        if (!fallback.isEmpty()) {
            putBlobsFallback(items, fallback, out);
        }
        return Arrays.asList(out);
    }

    private void putBlobsFallback(List<Map.Entry<Key, byte[]>> items, List<Integer> indices, byte[][] out) throws IOException, GatewayException {
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(PARALLEL);
        List<java.util.concurrent.Future<byte[]>> fs = new ArrayList<>(indices.size());
        java.util.concurrent.ExecutorService ex = Threads.perTaskExecutor();
        try {
            for (int i : indices) {
                byte[] data = items.get(i).getValue();
                gate.acquireUninterruptibly();
                fs.add(ex.submit(() -> {
                    try {
                        return gw.blobPut(data);
                    } finally {
                        gate.release();
                    }
                }));
            }
        } finally {
            ex.shutdown();
        }
        int k = 0;
        for (var f : fs) {
            try {
                out[indices.get(k++)] = f.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ie);
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable c = ee.getCause();
                if (c instanceof IOException io) {
                    throw io;
                }
                if (c instanceof GatewayException ge) {
                    throw ge;
                }
                throw new IOException(c);
            }
        }
    }

    private void appendBatch(List<Map.Entry<Key, byte[]>> items, boolean barrier) throws IOException, GatewayException {
        Chain.State before = chain.save();
        try {
            List<WalFrame> frames = new ArrayList<>(items.size() + 1);
            List<byte[]> hashes = putBlobs(items);
            for (int i = 0; i < items.size(); i++) {
                Key k = items.get(i).getKey();
                byte[] hash = hashes.get(i);
                frames.add(chain.next(k.op(), k.dim(), k.cx(), k.cz(), hash));
            }
            if (barrier) {
                frames.add(chain.next("barrier", "", 0, 0, null));
            }
            gw.walAppend(lease.id(), lease.epoch(), frames);
            // remembered only now: a batch that failed goes back to pending and must not be skipped as already sent
            synchronized (sentHash) {
                for (int i = 0; i < items.size(); i++) {
                    Key k = items.get(i).getKey();
                    byte[] hash = hashes.get(i);
                    if (k.op().startsWith("del:")) {
                        sentHash.remove(new Key("put:" + k.op().substring(4), k.dim(), k.cx(), k.cz()));
                    } else if (k.op().equals("aux_del")) {
                        sentHash.remove(new Key("aux_put", k.dim(), 0, 0));
                    } else if (hash != null) {
                        sentHash.put(k, java.nio.ByteBuffer.wrap(hash));
                    }
                }
            }
            if (barrier) {
                lastBarrierSeq = frames.get(frames.size() - 1).seq();
            }
        } catch (IOException | GatewayException | RuntimeException ex) {
            chain.restore(before);
            throw ex;
        }
    }
}
