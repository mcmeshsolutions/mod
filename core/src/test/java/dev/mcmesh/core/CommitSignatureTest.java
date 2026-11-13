package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitSignatureTest {
    @Test
    void layoutMatchesContract() {
        byte[] m = CommitSignature.commitMessage("w", "L1", 3, "r0", "AUTOSAVE", true, -1);
        byte[] prefix = "mcmesh-commit-v1\0".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(prefix, java.util.Arrays.copyOf(m, prefix.length));
        int len = prefix.length + 5 + 6 + 8 + 6 + 12 + 1 + 8;
        assertEquals(len, m.length);
        assertEquals(1, m[len - 9]);
        for (int i = len - 8; i < len; i++) {
            assertEquals((byte) 0xff, m[i]);
        }
    }

    @Test
    void signatureVerifiesAndTamperingFails() {
        IdentityKey k = IdentityKey.generate();
        byte[] msg = CommitSignature.commitMessage("w", "L1", 3, "r0", "SHUTDOWN", true, 41);
        byte[] sig = k.signRaw(msg);
        assertEquals(64, sig.length);
        assertTrue(CommitSignature.verify(k.publicRaw(), msg, sig));
        assertFalse(CommitSignature.verify(k.publicRaw(), CommitSignature.commitMessage("w", "L1", 3, "r0", "SHUTDOWN", true, 42), sig));
        assertFalse(CommitSignature.verify(k.publicRaw(), CommitSignature.commitMessage("w", "L1", 4, "r0", "SHUTDOWN", true, 41), sig));
        assertFalse(CommitSignature.verify(IdentityKey.generate().publicRaw(), msg, sig));
    }

    @Test
    void deviceKeyMessageVerifies() {
        IdentityKey k = IdentityKey.generate();
        byte[] msg = CommitSignature.deviceKeyMessage("dev-1", k.publicRaw());
        assertTrue(CommitSignature.verify(k.publicRaw(), msg, k.signRaw(msg)));
        assertFalse(CommitSignature.verify(k.publicRaw(), CommitSignature.deviceKeyMessage("dev-2", k.publicRaw()), k.signRaw(msg)));
    }

    @Test
    void keyIsCreatedOnceAndReused(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg/mcmesh-identity.key");
        IdentityKey a = IdentityKey.loadOrCreate(f);
        IdentityKey b = IdentityKey.loadOrCreate(f);
        assertArrayEquals(a.publicRaw(), b.publicRaw());
        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(f));
        } catch (UnsupportedOperationException ignored) {
            // non posix filesystem
        }
    }

    @Test
    void corruptKeyFileIsReportedNotOverwritten(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("k.key");
        Files.writeString(f, "garbage\n");
        assertThrows(Exception.class, () -> IdentityKey.loadOrCreate(f));
        assertEquals("garbage\n", Files.readString(f));
    }
}
