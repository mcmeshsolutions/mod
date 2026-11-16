package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackUpdaterTest {
    @TempDir Path game;
    HttpServer srv;
    String root;
    KeyPair kp;
    String pub;
    final Map<String, byte[]> served = new ConcurrentHashMap<>();
    static final byte[] JAR = "new jar bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void up() throws Exception {
        kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = kp.getPublic().getEncoded();
        pub = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            byte[] b = served.get(ex.getRequestURI().getPath());
            if (b == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        srv.start();
        root = "http://127.0.0.1:" + srv.getAddress().getPort();
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(game.resolve("config"));
    }

    @AfterEach
    void down() {
        srv.stop(0);
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    JsonObject file(String path, byte[] body, String url) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("path", path);
        o.addProperty("sha256", sha(body));
        o.addProperty("size", body.length);
        o.addProperty("url", url);
        return o;
    }

    JsonObject manifest(String pack, String version, JsonObject... files) {
        JsonObject m = new JsonObject();
        m.addProperty("pack", pack);
        m.addProperty("version", version);
        JsonArray fa = new JsonArray();
        Arrays.stream(files).forEach(fa::add);
        m.add("files", fa);
        JsonArray rm = new JsonArray();
        rm.add("mods/old.jar");
        m.add("remove", rm);
        return m;
    }

    void publish(JsonObject m, byte[] jar) throws Exception {
        byte[] mb = m.toString().getBytes(StandardCharsets.UTF_8);
        served.put("/pack.json", mb);
        served.put("/pack.json.sig", sign(mb));
        if (jar != null) {
            served.put("/files/foo.jar", jar);
        }
    }

    byte[] sign(byte[] mb) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(PackUpdater.DOMAIN);
        s.update(mb);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign()).getBytes(StandardCharsets.UTF_8);
    }

    PackUpdater.Settings settings(String key, boolean auto) {
        return settings(key, auto, 0);
    }

    PackUpdater.Settings settings(String key, boolean auto, int ownUpdaterVersion) {
        return new PackUpdater.Settings("mypack", "1.0", key, root + "/pack.json", auto, game, ownUpdaterVersion);
    }

    PackUpdater updater() {
        return new PackUpdater(true, 1 << 20, Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    void assertNothingStaged() {
        assertFalse(Files.exists(game.resolve("mcmesh-update/ready.txt")));
        assertFalse(Files.exists(game.resolve("mcmesh-update/staged/mods/foo-2.jar")));
    }

    void assertRejected(String fragment) throws Exception {
        var e = assertThrows(PackUpdater.Rejected.class, () -> updater().run(settings(pub, true)));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
        assertNothingStaged();
    }

    @Test
    void goodUpdateIsStagedAndNothingLiveChanges() throws Exception {
        Files.writeString(game.resolve("mods/old.jar"), "old");
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        assertEquals(PackUpdater.Outcome.STAGED, updater().run(settings(pub, true)));
        assertTrue(Files.isRegularFile(game.resolve("mcmesh-update/ready.txt")));
        assertEquals("new jar bytes", Files.readString(game.resolve("mcmesh-update/staged/mods/foo-2.jar")));
        assertFalse(Files.exists(game.resolve("mods/foo-2.jar")));
        assertEquals("old", Files.readString(game.resolve("mods/old.jar")));
        assertEquals(PackUpdater.Outcome.ALREADY_STAGED, updater().run(settings(pub, true)));

        PackSwap.apply(game);
        assertEquals("new jar bytes", Files.readString(game.resolve("mods/foo-2.jar")));
        assertFalse(Files.exists(game.resolve("mods/old.jar")));
        assertEquals("old", Files.readString(game.resolve("mcmesh-update/backup/1.1/mods/old.jar")));
        assertEquals("1.1", PackSwap.appliedVersion(game));
        assertFalse(PackSwap.hasReady(game));
        // applied version counts as installed: same manifest is no longer an update
        assertEquals(PackUpdater.Outcome.UP_TO_DATE, updater().run(settings(pub, true)));
    }

    @Test
    void badSignatureIsRejected() throws Exception {
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        kp = other;
        served.put("/pack.json.sig", sign(served.get("/pack.json")));
        assertRejected("signature");
    }

    @Test
    void tamperedManifestIsRejected() throws Exception {
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        String t = new String(served.get("/pack.json"), StandardCharsets.UTF_8).replace("1.1", "1.2");
        served.put("/pack.json", t.getBytes(StandardCharsets.UTF_8));
        assertRejected("signature");
    }

    @Test
    void wrongPackIdIsRejected() throws Exception {
        publish(manifest("otherpack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        assertRejected("another pack");
    }

    @Test
    void downgradeAndSameVersionAreNotUpdates() throws Exception {
        publish(manifest("mypack", "0.9", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        assertEquals(PackUpdater.Outcome.UP_TO_DATE, updater().run(settings(pub, true)));
        publish(manifest("mypack", "1.0.0", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        assertEquals(PackUpdater.Outcome.UP_TO_DATE, updater().run(settings(pub, true)));
        assertNothingStaged();
    }

    @Test
    void crossHostFileUrlIsRejected() throws Exception {
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "http://localhost:" + srv.getAddress().getPort() + "/files/foo.jar")), JAR);
        assertRejected("update host");
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "http://127.0.0.1:1/files/foo.jar")), JAR);
        assertRejected("update host");
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "https://127.0.0.1:" + srv.getAddress().getPort() + "/files/foo.jar")), JAR);
        assertRejected("update host");
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        for (String p : new String[] {"../evil.jar", "mods/../../evil.jar", "/etc/passwd", "mods//x.jar", "mods\\x.jar", "other/x.jar",
            "mods/./x.jar", "C:/x.jar", "mods"}) {
            publish(manifest("mypack", "1.1", file(p, JAR, "files/foo.jar")), JAR);
            var e = assertThrows(PackUpdater.Rejected.class, () -> updater().run(settings(pub, true)), p);
            assertFalse(e.getMessage().isEmpty());
            assertNothingStaged();
        }
        JsonObject m = manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar"));
        m.getAsJsonArray("remove").add("../../home/x");
        publish(m, JAR);
        assertRejected("../../home/x");
    }

    @Test
    void hashOrSizeMismatchLeavesNothingStaged() throws Exception {
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), "tampered bytes".getBytes());
        assertRejected("foo.jar");
        assertFalse(Files.exists(game.resolve("mcmesh-update/staged/mods")) && Files.list(game.resolve("mcmesh-update/staged/mods")).findAny().isPresent());
        byte[] same = "new jar byte".getBytes();
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), same);
        assertRejected("foo.jar");
        byte[] samelen = "new jar bytes".replace('b', 'c').getBytes();
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), samelen);
        assertRejected("sha256");
    }

    @Test
    void failedDownloadIsRetriedNextTime() throws Exception {
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), null);
        assertThrows(java.io.IOException.class, () -> updater().run(settings(pub, true)));
        assertNothingStaged();
        served.put("/files/foo.jar", JAR);
        assertEquals(PackUpdater.Outcome.STAGED, updater().run(settings(pub, true)));
    }

    @Test
    void optOutAndEmptyKeyDownloadNothing() throws Exception {
        publish(manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar")), JAR);
        srv.stop(0);
        assertEquals(PackUpdater.Outcome.DISABLED, updater().run(settings(pub, false)));
        assertEquals(PackUpdater.Outcome.DISABLED, updater().run(settings("", true)));
        assertEquals(PackUpdater.Outcome.DISABLED, updater().run(settings("  ", true)));
        assertFalse(Files.exists(game.resolve("mcmesh-update")));
    }

    @Test
    void httpIsRefusedInProduction() {
        var e = assertThrows(PackUpdater.Rejected.class, () -> new PackUpdater().run(settings(pub, true)));
        assertTrue(e.getMessage().contains("https"));
    }

    @Test
    void swapRollsBackWhenAStagedFileIsMissing() throws Exception {
        Files.writeString(game.resolve("mods/a.jar"), "A");
        Files.createDirectories(game.resolve("mcmesh-update/staged/mods"));
        Files.writeString(game.resolve("mcmesh-update/staged/mods/a.jar"), "A2");
        Files.writeString(game.resolve("mcmesh-update/ready.txt"), "version\t2.0\nput\tmods/a.jar\nput\tmods/b.jar\n");
        assertThrows(java.io.IOException.class, () -> PackSwap.apply(game));
        assertEquals("A", Files.readString(game.resolve("mods/a.jar")));
        assertTrue(PackSwap.hasReady(game));
    }

    @Test
    void manifestPatchDeletesAFileOnApply() throws Exception {
        Files.writeString(game.resolve("config/old-key.toml"), "legacy=true");
        JsonObject m = manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar"));
        JsonArray patches = new JsonArray();
        JsonObject op = new JsonObject();
        op.addProperty("op", "delete");
        op.addProperty("path", "config/old-key.toml");
        patches.add(op);
        m.add("patches", patches);
        publish(m, JAR);
        assertEquals(PackUpdater.Outcome.STAGED, updater().run(settings(pub, true)));
        assertTrue(Files.readString(game.resolve("mcmesh-update/ready.txt")).contains("patch\tdelete\t"));

        PackSwap.apply(game);
        assertFalse(Files.exists(game.resolve("config/old-key.toml")));
        assertEquals("new jar bytes", Files.readString(game.resolve("mods/foo-2.jar")));
    }

    @Test
    void manifestPatchWithUnsafePathIsRejected() throws Exception {
        JsonObject m = manifest("mypack", "1.1", file("mods/foo-2.jar", JAR, "files/foo.jar"));
        JsonArray patches = new JsonArray();
        JsonObject op = new JsonObject();
        op.addProperty("op", "delete");
        op.addProperty("path", "../../evil");
        patches.add(op);
        m.add("patches", patches);
        publish(m, JAR);
        assertRejected("path outside mods/ and config/");
    }

    @Test
    void newerUpdaterBuildIsStagedButOldOneIsIgnored() throws Exception {
        byte[] newJar = "new updater jar bytes".getBytes(StandardCharsets.UTF_8);
        served.put("/updater.jar", newJar);
        JsonObject m = manifest("mypack", "1.0", file("mods/foo-2.jar", JAR, "files/foo.jar"));
        m.addProperty("updaterVersion", 2);
        m.addProperty("updaterSha256", sha(newJar));
        m.addProperty("updaterSize", newJar.length);
        m.addProperty("updaterUrl", "updater.jar");
        publish(m, JAR);

        // installedVersion equals manifest version (1.0), so the pack itself is UP_TO_DATE...
        assertEquals(PackUpdater.Outcome.UP_TO_DATE, updater().run(settings(pub, true, 1)));
        // ...but the self-update is independent and still staged
        assertEquals("new updater jar bytes", Files.readString(game.resolve("mcmesh-update/updater-pending.jar")));
        assertEquals("2", Files.readString(game.resolve("mcmesh-update/updater-pending.version")));

        Files.delete(game.resolve("mcmesh-update/updater-pending.jar"));
        assertEquals(PackUpdater.Outcome.UP_TO_DATE, updater().run(settings(pub, true, 2)));
        assertFalse(Files.exists(game.resolve("mcmesh-update/updater-pending.jar")), "already-current updater should not be restaged");
    }

    @Test
    void swapRefusesTraversalInReadyFile() throws Exception {
        Files.createDirectories(game.resolve("mcmesh-update"));
        Files.writeString(game.resolve("mcmesh-update/ready.txt"), "version\t2.0\nrm\tmods/../../x\n");
        assertThrows(java.io.IOException.class, () -> PackSwap.apply(game));
    }
}
