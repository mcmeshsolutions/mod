package dev.mcmesh.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Bytes {
    private Bytes() {}

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HexFormat.of().parseHex(s);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
