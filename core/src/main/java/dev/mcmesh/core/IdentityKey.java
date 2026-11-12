package dev.mcmesh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Ed25519 identity for players without a Mojang session. JDK-only, so it works on every platform. */
public final class IdentityKey {
    private static final byte[] DOMAIN = "mcmesh-auth-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final KeyPair pair;

    private IdentityKey(KeyPair pair) {
        this.pair = pair;
    }

    /** File holds PKCS8 private then X509 public, base64, one per line. */
    public static IdentityKey loadOrCreate(Path file) throws IOException {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            if (Files.exists(file)) {
                String[] l = Files.readString(file).trim().split("\\R");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(l[0])));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(l[1])));
                return new IdentityKey(new KeyPair(pub, priv));
            }
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(tmp, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n"
                    + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n");
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // windows: relies on the profile directory ACL
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new IdentityKey(kp);
        } catch (GeneralSecurityException e) {
            throw new IOException("ed25519 unavailable", e);
        }
    }

    public static IdentityKey generate() {
        try {
            return new IdentityKey(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raw 32-byte public key: the tail of the X509 encoding. */
    public byte[] publicRaw() {
        byte[] enc = pair.getPublic().getEncoded();
        return Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    }

    public byte[] sign(String challenge) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            byte[] c = challenge.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] msg = Arrays.copyOf(DOMAIN, DOMAIN.length + c.length);
            System.arraycopy(c, 0, msg, DOMAIN.length, c.length);
            s.update(msg);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
