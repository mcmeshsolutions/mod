package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackBuilderTest {
    private static void put(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static Path pack(Path dir) throws Exception {
        Path p = dir.resolve("pack");
        put(p, "mods/a.jar", "jar a");
        put(p, "mods/sub/b c.jar", "jar b");
        put(p, "config/x.toml", "x = 1");
        put(p, "config/.secret", "never published");
        put(p, "saves/world/level.dat", "not part of a pack");
        return p;
    }

    private static String b64(IdentityKey k) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k.publicRaw());
    }

    @Test
    void theManifestIsSortedHashedAndSignedForTheRealVerifier(@TempDir Path dir) throws Exception {
        IdentityKey key = IdentityKey.generate();
        Path out = dir.resolve("out");
        PackBuilder.Built b = PackBuilder.build(pack(dir), "mypack", "1.4.0", key, out, null);
        assertEquals(3, b.files());

        byte[] manifest = Files.readAllBytes(out.resolve("manifest.json"));
        assertArrayEquals(b.manifest(), manifest);
        PackUpdater.verify(PackUpdater.key(b64(key)), manifest, Files.readAllBytes(out.resolve("manifest.json.sig")));

        JsonObject m = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("mypack", m.get("pack").getAsString());
        assertEquals("1.4.0", m.get("version").getAsString());
        JsonArray files = m.getAsJsonArray("files");
        assertEquals("config/x.toml", files.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("mods/a.jar", files.get(1).getAsJsonObject().get("path").getAsString());
        JsonObject spaced = files.get(2).getAsJsonObject();
        assertEquals("mods/sub/b c.jar", spaced.get("path").getAsString());
        assertEquals("files/mods/sub/b%20c.jar", spaced.get("url").getAsString());
        assertEquals(5, spaced.get("size").getAsLong());
        byte[] sha = MessageDigest.getInstance("SHA-256").digest("jar b".getBytes(StandardCharsets.UTF_8));
        assertEquals(java.util.HexFormat.of().formatHex(sha), spaced.get("sha256").getAsString());
        assertEquals("jar b", Files.readString(out.resolve("files/mods/sub/b c.jar")));
        assertFalse(Files.exists(out.resolve("files/config/.secret")), "hidden files are not published");
        assertFalse(Files.exists(out.resolve("files/saves")), "only mods and config are part of a pack");
        assertEquals(0, m.getAsJsonArray("remove").size());
    }

    @Test
    void aTamperedManifestOrAnotherKeyFailsTheRealVerifier(@TempDir Path dir) throws Exception {
        IdentityKey key = IdentityKey.generate();
        Path out = dir.resolve("out");
        PackBuilder.build(pack(dir), "mypack", "1.4.0", key, out, null);
        byte[] manifest = Files.readAllBytes(out.resolve("manifest.json"));
        byte[] sig = Files.readAllBytes(out.resolve("manifest.json.sig"));
        byte[] tampered = new String(manifest, StandardCharsets.UTF_8).replace("1.4.0", "9.9.9").getBytes(StandardCharsets.UTF_8);
        assertThrows(PackUpdater.Rejected.class, () -> PackUpdater.verify(PackUpdater.key(b64(key)), tampered, sig));
        assertThrows(PackUpdater.Rejected.class, () -> PackUpdater.verify(PackUpdater.key(b64(IdentityKey.generate())), manifest, sig));
    }

    @Test
    void filesGoneSinceTheLastReleaseAreListedForRemoval(@TempDir Path dir) throws Exception {
        IdentityKey key = IdentityKey.generate();
        Path p = pack(dir);
        PackBuilder.build(p, "mypack", "1.0.0", key, dir.resolve("v1"), null);
        Files.delete(p.resolve("mods/a.jar"));
        put(p, "mods/a-2.jar", "jar a two");
        PackBuilder.Built b = PackBuilder.build(p, "mypack", "1.1.0", key, dir.resolve("v2"), dir.resolve("v1/manifest.json"));
        assertEquals(java.util.List.of("mods/a.jar"), b.removed());
        JsonObject m = JsonParser.parseString(new String(b.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("mods/a.jar", m.getAsJsonArray("remove").get(0).getAsString());
    }

    @Test
    void badInputIsRefusedBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        IdentityKey key = IdentityKey.generate();
        Path p = pack(dir);
        Path out = dir.resolve("out");
        assertThrows(IllegalArgumentException.class, () -> PackBuilder.build(p, "mypack", "latest", key, out, null));
        assertThrows(IllegalArgumentException.class, () -> PackBuilder.build(p, "bad pack!", "1.0", key, out, null));
        assertFalse(Files.exists(out.resolve("manifest.json")));
    }

    @Test
    void embeddingAnUpdaterJarAddsSelfUpdateFieldsAndShipsTheJarAndSetupDoc(@TempDir Path dir) throws Exception {
        IdentityKey key = IdentityKey.generate();
        Path updaterJar = dir.resolve("mcmesh-updater.jar");
        Files.writeString(updaterJar, "fake updater jar bytes");
        Path out = dir.resolve("out");
        PackBuilder.Built b = PackBuilder.build(pack(dir), "mypack", "1.4.0", key, out, null, updaterJar, 3);

        JsonObject m = JsonParser.parseString(new String(b.manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(3, m.get("updaterVersion").getAsInt());
        assertEquals("updater/mcmesh-updater.jar", m.get("updaterUrl").getAsString());
        assertEquals(Files.size(updaterJar), m.get("updaterSize").getAsLong());
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(updaterJar));
        assertEquals(java.util.HexFormat.of().formatHex(sha), m.get("updaterSha256").getAsString());
        assertEquals("fake updater jar bytes", Files.readString(out.resolve("updater/mcmesh-updater.jar")));
    }

    @Test
    void embeddingAnUpdaterJarWithoutAPositiveVersionIsRejected(@TempDir Path dir) throws Exception {
        IdentityKey key = IdentityKey.generate();
        Path updaterJar = dir.resolve("mcmesh-updater.jar");
        Files.writeString(updaterJar, "fake updater jar bytes");
        assertThrows(IllegalArgumentException.class, () -> PackBuilder.build(pack(dir), "mypack", "1.0", key, dir.resolve("out"), null, updaterJar, 0));
    }

    @Test
    void keygenIsStableAcrossRuns(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("author.key");
        String first = PackBuilder.publicKey(f);
        assertEquals(43, first.length());
        assertEquals(first, PackBuilder.publicKey(f), "the same file gives the same key");
        assertTrue(Files.exists(f));
    }
}
