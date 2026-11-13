package dev.mcmesh.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Byte layouts from PROTOCOL.md "Commit signature field and key registration". */
public final class CommitSignature {
    private static final byte[] X509_ED25519_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private CommitSignature() { }

    private static void lp(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
        o.writeBytes(b);
    }

    public static byte[] commitMessage(String world, String lease, long epoch, String baseRev, String kind, boolean last, long barrierSeq) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("mcmesh-commit-v1\0".getBytes(StandardCharsets.US_ASCII));
        lp(o, world);
        lp(o, lease);
        o.writeBytes(ByteBuffer.allocate(8).putLong(epoch).array());
        lp(o, baseRev);
        lp(o, kind);
        o.write(last ? 1 : 0);
        o.writeBytes(ByteBuffer.allocate(8).putLong(barrierSeq).array());
        return o.toByteArray();
    }

    public static byte[] deviceKeyMessage(String deviceId, byte[] pubkeyRaw) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("mcmesh-devkey-v1\0".getBytes(StandardCharsets.US_ASCII));
        lp(o, deviceId);
        o.writeBytes(pubkeyRaw);
        return o.toByteArray();
    }

    public static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** What the gateway does; here so tests can prove the signature round-trips. */
    public static boolean verify(byte[] pubkeyRaw, byte[] msg, byte[] sig) {
        try {
            byte[] enc = new byte[X509_ED25519_PREFIX.length + 32];
            System.arraycopy(X509_ED25519_PREFIX, 0, enc, 0, X509_ED25519_PREFIX.length);
            System.arraycopy(pubkeyRaw, 0, enc, X509_ED25519_PREFIX.length, 32);
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(enc)));
            v.update(msg);
            return v.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
