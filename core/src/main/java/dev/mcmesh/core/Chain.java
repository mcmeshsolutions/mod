package dev.mcmesh.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 chain over the chunk stream; layout must match gatewayd/src/chain.rs. */
public final class Chain {
    private static final byte[] NO_HASH = new byte[32];
    private final byte[] key;
    private byte[] prev = new byte[32];
    private long seq;

    public Chain(byte[] key) {
        this.key = key.clone();
    }

    public synchronized State save() {
        return new State(prev.clone(), seq);
    }

    public synchronized void restore(State s) {
        prev = s.prev().clone();
        seq = s.seq();
    }

    public record State(byte[] prev, long seq) { }

    /** Op is "put:region", "del:poi", "aux_put", "aux_del" or "barrier"; dim doubles as the aux path. */
    public synchronized WalFrame next(String op, String dim, int cx, int cz, byte[] hash) {
        byte[] h = hash == null ? NO_HASH : hash;
        byte[] d = dim.getBytes(StandardCharsets.UTF_8);
        byte[] k = op.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(32 + 8 + 4 + d.length + 4 + k.length + 4 + 4 + 32);
        b.put(prev).putLong(seq).putInt(d.length).put(d).putInt(k.length).put(k).putInt(cx).putInt(cz).put(h);
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            prev = m.doFinal(b.array());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return new WalFrame(seq++, op, dim, cx, cz, h, prev);
    }
}
