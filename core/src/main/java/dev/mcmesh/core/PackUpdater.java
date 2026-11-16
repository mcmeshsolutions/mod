package dev.mcmesh.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Downloads a signed pack update into mcmesh-update/staged. Nothing under mods/ or config/ is touched;
 * {@link PackSwap} moves the files into place after the game has exited. Blocking, call off the render thread.
 */
public final class PackUpdater {
    /** ownUpdaterVersion is the standalone updater's own build number, or 0 when it has none to self-update. */
    public record Settings(String packId, String installedVersion, String authorKey, String updateUrl, boolean autoUpdate, Path gameDir,
            int ownUpdaterVersion) { }

    public enum Outcome { DISABLED, UP_TO_DATE, STAGED, ALREADY_STAGED }

    /** The update was refused by policy (bad signature, wrong pack, downgrade, unsafe path, hash mismatch ...). */
    public static final class Rejected extends IOException {
        Rejected(String m) {
            super(m);
        }
    }

    static final byte[] DOMAIN = "mcmesh-pack-v1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int MANIFEST_MAX = 1 << 20;
    private static final int SIG_MAX = 1024;

    private final boolean allowHttp;
    private final long totalCap;
    private final Duration smallTimeout;
    private final Duration fileTimeout;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mesh-update-watchdog");
        t.setDaemon(true);
        return t;
    });

    public PackUpdater() {
        this(false, 512L << 20, Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    /** Tests only: plain http and short limits. */
    PackUpdater(boolean allowHttp, long totalCap, Duration smallTimeout, Duration fileTimeout) {
        this.allowHttp = allowHttp;
        this.totalCap = totalCap;
        this.smallTimeout = smallTimeout;
        this.fileTimeout = fileTimeout;
    }

    private record Entry(String path, String sha256, long size, URI url) { }

    public Outcome run(Settings s) throws IOException {
        try {
            return stage(s);
        } finally {
            watchdog.shutdownNow();
        }
    }

    private Outcome stage(Settings s) throws IOException {
        if (!s.autoUpdate() || s.authorKey() == null || s.authorKey().isBlank() || s.updateUrl() == null || s.updateUrl().isBlank()) {
            return Outcome.DISABLED;
        }
        PublicKey key = key(s.authorKey());
        URI base = URI.create(s.updateUrl().strip());
        checkScheme(base);
        byte[] manifest = get(base, MANIFEST_MAX, smallTimeout);
        byte[] sig = get(URI.create(base + ".sig"), SIG_MAX, smallTimeout);
        verify(key, manifest, sig);

        JsonObject m;
        try {
            m = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Rejected("manifest is not valid json");
        }
        if (!s.packId().equals(str(m, "pack"))) {
            throw new Rejected("manifest is for another pack");
        }
        if (s.ownUpdaterVersion() > 0) {
            maybeStageSelfUpdate(m, base, s.gameDir(), s.ownUpdaterVersion());
        }
        String version = str(m, "version");
        if (!PackCheck.valid(version) || !PackCheck.valid(s.installedVersion())) {
            throw new Rejected("bad version");
        }
        String applied = PackSwap.appliedVersion(s.gameDir());
        String have = applied != null && PackCheck.valid(applied) && PackCheck.compare(applied, s.installedVersion()) > 0 ? applied : s.installedVersion();
        if (PackCheck.compare(version, have) <= 0) {
            return Outcome.UP_TO_DATE;
        }
        List<Entry> files = new ArrayList<>();
        long total = 0;
        for (JsonElement e : arr(m, "files")) {
            JsonObject o = e.getAsJsonObject();
            String path = str(o, "path");
            bad(PackSwap.badPath(s.gameDir(), path), path);
            long size = o.get("size").getAsLong();
            String hash = str(o, "sha256");
            if (size < 0 || !hash.matches("[0-9a-fA-F]{64}")) {
                throw new Rejected("bad size or hash for " + path);
            }
            total += size;
            if (total > totalCap) {
                throw new Rejected("update is larger than the allowed total");
            }
            files.add(new Entry(path, hash.toLowerCase(), size, fileUrl(base, str(o, "url"))));
        }
        List<String> remove = new ArrayList<>();
        for (JsonElement e : arr(m, "remove")) {
            String p = e.getAsString();
            bad(PackSwap.badPath(s.gameDir(), p), p);
            remove.add(p);
        }
        List<String> patches = new ArrayList<>();
        for (JsonElement e : arr(m, "patches")) {
            JsonObject o = e.getAsJsonObject();
            PackPatch.Op op = new PackPatch.Op(str(o, "op"), opt(o, "path"), opt(o, "from"), opt(o, "to"), opt(o, "pattern"), opt(o, "replace"));
            String why = PackPatch.bad(s.gameDir(), op);
            if (why != null) {
                throw new Rejected(why + ": " + o);
            }
            patches.add(PackPatch.encode(op));
        }

        Path upd = PackSwap.updateDir(s.gameDir());
        Path ready = upd.resolve(PackSwap.READY);
        if (Files.isRegularFile(ready) && stagedVersion(ready) != null && PackCheck.compare(stagedVersion(ready), version) >= 0) {
            return Outcome.ALREADY_STAGED;
        }
        Files.deleteIfExists(ready);
        Path staged = upd.resolve("staged");
        PackSwap.deleteTree(staged);
        Files.createDirectories(staged);

        List<String> put = new ArrayList<>();
        for (Entry f : files) {
            Path target = s.gameDir().resolve(f.path());
            if (Files.isRegularFile(target) && Files.size(target) == f.size() && f.sha256().equals(sha256(target))) {
                continue;
            }
            Path dest = staged.resolve(f.path());
            Files.createDirectories(dest.getParent());
            Path part = dest.resolveSibling(dest.getFileName() + ".part");
            try {
                String got = download(f.url(), f.size(), part);
                if (!got.equals(f.sha256())) {
                    throw new Rejected("sha256 mismatch for " + f.path());
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                PackSwap.deleteTree(staged);
                throw e;
            }
            put.add(f.path());
        }

        List<String> lines = new ArrayList<>();
        lines.add("version\t" + version);
        put.forEach(p -> lines.add("put\t" + p));
        remove.forEach(p -> lines.add("rm\t" + p));
        patches.forEach(p -> lines.add("patch\t" + p));
        Path tmp = upd.resolve(PackSwap.READY + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, ready, StandardCopyOption.REPLACE_EXISTING);
        return Outcome.STAGED;
    }

    /**
     * The manifest may carry a newer build of the standalone updater itself (updaterVersion/updaterSha256/
     * updaterSize/updaterUrl); when so, and it is newer than this process, stage it next to the pack update so
     * PackPreLaunch can swap its own jar in on a later run once this one has exited and released the file.
     */
    private void maybeStageSelfUpdate(JsonObject m, URI base, Path gameDir, int ownVersion) throws IOException {
        JsonElement uv = m.get("updaterVersion");
        if (uv == null || !uv.isJsonPrimitive()) {
            return;
        }
        int newVersion;
        try {
            newVersion = uv.getAsInt();
        } catch (NumberFormatException e) {
            throw new Rejected("bad updaterVersion");
        }
        if (newVersion <= ownVersion) {
            return;
        }
        String hash = str(m, "updaterSha256");
        if (!hash.matches("[0-9a-fA-F]{64}")) {
            throw new Rejected("bad updaterSha256");
        }
        hash = hash.toLowerCase();
        long size = m.get("updaterSize") != null ? m.get("updaterSize").getAsLong() : -1;
        if (size < 0 || size > totalCap) {
            throw new Rejected("bad or oversized updaterSize");
        }
        URI url = fileUrl(base, str(m, "updaterUrl"));

        Path upd = PackSwap.updateDir(gameDir);
        Path versionFile = upd.resolve("updater-pending.version");
        try {
            if (Files.isRegularFile(versionFile) && Integer.parseInt(Files.readString(versionFile).strip()) >= newVersion) {
                return;
            }
        } catch (IOException | NumberFormatException ignored) {
            // no usable marker: stage it again below
        }
        Files.createDirectories(upd);
        Path part = upd.resolve("updater-pending.jar.part");
        String got = download(url, size, part);
        if (!got.equals(hash)) {
            Files.deleteIfExists(part);
            throw new Rejected("updater sha256 mismatch");
        }
        Files.move(part, upd.resolve("updater-pending.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(versionFile, Integer.toString(newVersion), StandardCharsets.UTF_8);
    }

    private static String stagedVersion(Path ready) {
        try {
            for (String l : Files.readAllLines(ready, StandardCharsets.UTF_8)) {
                if (l.startsWith("version\t") && PackCheck.valid(l.substring(8))) {
                    return l.substring(8);
                }
            }
        } catch (IOException ignored) {
            // unreadable ready file counts as nothing staged
        }
        return null;
    }

    private static void bad(String why, String path) throws Rejected {
        if (why != null) {
            throw new Rejected(why + ": " + path);
        }
    }

    private static String str(JsonObject o, String k) throws Rejected {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) {
            throw new Rejected("manifest field missing: " + k);
        }
        return e.getAsString();
    }

    /** Like {@link #str}, but null when the field is absent instead of rejecting the manifest. */
    private static String opt(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonArray arr(JsonObject o, String k) throws Rejected {
        JsonElement e = o.get(k);
        if (e == null) {
            return new JsonArray();
        }
        if (!e.isJsonArray()) {
            throw new Rejected("manifest field is not a list: " + k);
        }
        return e.getAsJsonArray();
    }

    private void checkScheme(URI u) throws Rejected {
        String sc = u.getScheme();
        if (u.getHost() == null || u.getUserInfo() != null || !("https".equalsIgnoreCase(sc) || allowHttp && "http".equalsIgnoreCase(sc))) {
            throw new Rejected("update source must be https");
        }
    }

    private static int port(URI u) {
        return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private URI fileUrl(URI manifest, String ref) throws Rejected {
        URI u;
        try {
            u = manifest.resolve(ref);
        } catch (IllegalArgumentException e) {
            throw new Rejected("bad file url");
        }
        checkScheme(u);
        if (!u.getScheme().equalsIgnoreCase(manifest.getScheme()) || !u.getHost().equalsIgnoreCase(manifest.getHost()) || port(u) != port(manifest)) {
            throw new Rejected("file url is not on the update host: " + ref);
        }
        return u;
    }

    static PublicKey key(String b64) throws Rejected {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(b64.strip());
            if (raw.length != 32) {
                throw new Rejected("packAuthorKey must be 32 bytes");
            }
            byte[] spki = new byte[ED25519_SPKI_PREFIX.length + 32];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, 32);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new Rejected("packAuthorKey is not a valid key");
        }
    }

    static void verify(PublicKey key, byte[] manifest, byte[] sigText) throws Rejected {
        try {
            byte[] sig = Base64.getUrlDecoder().decode(new String(sigText, StandardCharsets.UTF_8).strip());
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(DOMAIN);
            v.update(manifest);
            if (!v.verify(sig)) {
                throw new Rejected("manifest signature does not verify");
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new Rejected("manifest signature does not verify");
        }
    }

    private static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) {
                d.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private InputStream open(URI u, Duration timeout, ScheduledFuture<?>[] guard) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(u).GET().timeout(timeout).header("user-agent", "mcmesh-pack-updater").build();
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            InputStream in = r.body();
            if (r.statusCode() != 200) {
                in.close();
                throw new IOException(u + " answered " + r.statusCode());
            }
            // the request timeout stops at the headers, so a stalled body is cut by closing the stream
            guard[0] = watchdog.schedule(() -> {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            return in;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private byte[] get(URI u, int max, Duration timeout) throws IOException {
        ScheduledFuture<?>[] guard = new ScheduledFuture<?>[1];
        try (InputStream in = open(u, timeout, guard)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0;) {
                if (out.size() + n > max) {
                    throw new Rejected("response too large: " + u);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (guard[0] != null) {
                guard[0].cancel(false);
            }
        }
    }

    /** Returns the sha256 of what was written; refuses more bytes than the manifest declared. */
    private String download(URI u, long size, Path dest) throws IOException {
        ScheduledFuture<?>[] guard = new ScheduledFuture<?>[1];
        try (InputStream in = open(u, fileTimeout, guard); var out = Files.newOutputStream(dest)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            long got = 0;
            for (int n; (n = in.read(buf)) > 0;) {
                got += n;
                if (got > size) {
                    throw new Rejected("file is larger than declared: " + u);
                }
                d.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            if (got != size) {
                throw new Rejected("file size does not match manifest: " + u);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            if (guard[0] != null) {
                guard[0].cancel(false);
            }
        }
    }
}
