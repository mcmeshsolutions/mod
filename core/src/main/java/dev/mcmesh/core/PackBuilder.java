package dev.mcmesh.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns a pack folder into what the auto-update expects: manifest.json, manifest.json.sig and the files themselves,
 * ready to upload next to each other. Manifest format and signature domain are the ones PackUpdater verifies.
 */
public final class PackBuilder {
    private static final long MANIFEST_MAX = 1 << 20;

    private PackBuilder() { }

    public record Built(byte[] manifest, String signature, int files, long bytes, List<String> removed) { }

    /** Creates the author key if the file is missing and returns the value for the pack's `packAuthorKey` setting. */
    public static String publicKey(Path keyFile) throws IOException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(IdentityKey.loadOrCreate(keyFile).publicRaw());
    }

    /**
     * @param previous a manifest.json of the last release, or null; files in it that are gone now go into `remove`
     */
    public static Built build(Path packDir, String packId, String version, IdentityKey key, Path outDir, Path previous) throws IOException {
        return build(packDir, packId, version, key, outDir, previous, null, 0);
    }

    /**
     * @param previous a manifest.json of the last release, or null; files in it that are gone now go into `remove`
     * @param updaterJar mcmesh-updater.jar to embed for self-update, or null to leave the manifest without one
     * @param updaterVersion the embedded jar's build number; required (and must increase release over release) when updaterJar is set
     */
    public static Built build(Path packDir, String packId, String version, IdentityKey key, Path outDir, Path previous,
            Path updaterJar, int updaterVersion) throws IOException {
        if (!packId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("pack id must be 1 to 64 characters of letters, digits, dot, dash, underscore");
        }
        if (!PackCheck.valid(version)) {
            throw new IllegalArgumentException("version must be dot separated numbers like 1.4.0");
        }
        List<String> rels = new ArrayList<>();
        for (String top : List.of("mods", "config")) {
            Path root = packDir.resolve(top);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isSymbolicLink(p)) {
                        throw new IOException("symbolic links are not allowed in a pack: " + p);
                    }
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || hidden(packDir, p)) {
                        continue;
                    }
                    String rel = packDir.relativize(p).toString().replace('\\', '/');
                    String bad = PackSwap.badPath(packDir, rel);
                    if (bad != null) {
                        throw new IOException(bad + ": " + rel);
                    }
                    rels.add(rel);
                }
            }
        }
        rels.sort(null);

        JsonArray files = new JsonArray();
        long total = 0;
        for (String rel : rels) {
            Path src = packDir.resolve(rel);
            JsonObject o = new JsonObject();
            o.addProperty("path", rel);
            o.addProperty("sha256", sha256(src));
            o.addProperty("size", Files.size(src));
            o.addProperty("url", relativeUrl("files/" + rel));
            files.add(o);
            total += Files.size(src);
            Path dst = outDir.resolve("files").resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        List<String> removed = new ArrayList<>();
        if (previous != null) {
            Set<String> now = new HashSet<>(rels);
            for (JsonElement e : JsonParser.parseString(Files.readString(previous, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("files")) {
                String p = e.getAsJsonObject().get("path").getAsString();
                if (!now.contains(p)) {
                    removed.add(p);
                }
            }
            removed.sort(null);
        }
        JsonArray remove = new JsonArray();
        removed.forEach(remove::add);

        JsonObject m = new JsonObject();
        m.addProperty("pack", packId);
        m.addProperty("version", version);
        m.add("files", files);
        m.add("remove", remove);
        if (updaterJar != null) {
            if (updaterVersion <= 0) {
                throw new IllegalArgumentException("updaterVersion must be a positive, increasing build number when embedding an updater jar");
            }
            m.addProperty("updaterVersion", updaterVersion);
            m.addProperty("updaterSha256", sha256(updaterJar));
            m.addProperty("updaterSize", Files.size(updaterJar));
            m.addProperty("updaterUrl", relativeUrl("updater/mcmesh-updater.jar"));
            Path dst = outDir.resolve("updater/mcmesh-updater.jar");
            Files.createDirectories(dst.getParent());
            Files.copy(updaterJar, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        byte[] manifest = m.toString().getBytes(StandardCharsets.UTF_8);
        if (manifest.length > MANIFEST_MAX) {
            throw new IOException("manifest is " + manifest.length + " bytes, the client accepts at most " + MANIFEST_MAX);
        }
        byte[] msg = new byte[PackUpdater.DOMAIN.length + manifest.length];
        System.arraycopy(PackUpdater.DOMAIN, 0, msg, 0, PackUpdater.DOMAIN.length);
        System.arraycopy(manifest, 0, msg, PackUpdater.DOMAIN.length, manifest.length);
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(key.signRaw(msg));
        Files.createDirectories(outDir);
        Files.write(outDir.resolve("manifest.json"), manifest);
        Files.writeString(outDir.resolve("manifest.json.sig"), sig + "\n", StandardCharsets.UTF_8);
        return new Built(manifest, sig, rels.size(), total, removed);
    }

    private static boolean hidden(Path packDir, Path p) {
        for (Path part : packDir.relativize(p)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** So a release that ships an updater also tells whoever is packaging the instance how to wire it in. */
    private static void writeSetupInstructions(Path outDir, String packId) throws IOException {
        String text = """
                # Preconfiguring a PrismLauncher instance with the mcmesh updater

                1. Copy updater/mcmesh-updater.jar into the instance's root folder (the one that contains
                   `minecraft/` or `.minecraft/`, next to instance.cfg) - NOT into mods/ or .minecraft/.
                2. Instance Settings -> Custom Commands -> check "Enable custom commands" and set
                   "Pre-launch command" to (one line, keep the quotes exactly as shown):

                   "$INST_JAVA" -jar "$INST_DIR/mcmesh-updater.jar" "$INST_MC_DIR" %s <packAuthorKey> <packUpdateUrl>

                   PrismLauncher substitutes $INST_JAVA/$INST_DIR/$INST_MC_DIR itself; this is the same on
                   Windows and Linux since Prism runs `java` directly, not a shell script.

                   If you are baking this into instance.cfg directly instead of through the UI (e.g. to ship
                   a pre-configured instance.zip), escape every quote as \\" or Qt's QSettings strips them and
                   the launch fails ("execve: No such file or directory") because the spaced paths get merged
                   into one token:
                   PreLaunchCommand=\\"$INST_JAVA\\" -jar \\"$INST_DIR/mcmesh-updater.jar\\" \\"$INST_MC_DIR\\"
                3. Ship the instance with that pre-launch command and the jar already in place so players never
                   have to configure anything - the updater runs once before every launch, applies any pending
                   mods/config diff and breaking-change patch, tunes the JVM heap for that machine, and quietly
                   does nothing (exit 0) if the gateway is unreachable.
                4. The updater can replace itself: ship a new build by passing it to `packBuilder build` again
                   with a higher version number as the last argument; players get it automatically, no reinstall.
                """.formatted(packId);
        Files.writeString(outDir.resolve("SETUP.md"), text, StandardCharsets.UTF_8);
    }

    private static String relativeUrl(String path) throws IOException {
        try {
            return new URI(null, null, path, null).getRawPath();
        } catch (URISyntaxException e) {
            throw new IOException("cannot make a url for " + path, e);
        }
    }

    private static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                d.update(buf, 0, n);
            }
            StringBuilder o = new StringBuilder();
            for (byte b : d.digest()) {
                o.append(String.format("%02x", b));
            }
            return o.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] a) throws Exception {
        if (a.length == 2 && a[0].equals("keygen")) {
            System.out.println("packAuthorKey = " + publicKey(Path.of(a[1])));
            System.out.println("Keep " + a[1] + " private; it signs every release of this pack.");
            return;
        }
        if (a.length >= 6 && a.length <= 9 && a[0].equals("build")) {
            boolean hasUpdater = a.length == 8 || a.length == 9;
            boolean hasPrevious = a.length == 7 || a.length == 9;
            Path previous = hasPrevious ? Path.of(a[6]) : null;
            Path updaterJar = hasUpdater ? Path.of(a[a.length - 2]) : null;
            int updaterVersion = hasUpdater ? Integer.parseInt(a[a.length - 1]) : 0;
            Built b = build(Path.of(a[1]), a[2], a[3], IdentityKey.loadOrCreate(Path.of(a[4])), Path.of(a[5]), previous, updaterJar, updaterVersion);
            System.out.println(b.files() + " files, " + b.bytes() + " bytes, " + b.removed().size() + " removed"
                    + (hasUpdater ? ", updater v" + updaterVersion + " embedded" : ""));
            System.out.println("Upload " + a[5] + " so that manifest.json is at your packUpdateUrl and files/ sits next to it.");
            if (hasUpdater) {
                System.out.println("Also upload " + a[5] + "/updater/mcmesh-updater.jar next to manifest.json.");
                writeSetupInstructions(Path.of(a[5]), a[2]);
                System.out.println("Wrote " + a[5] + "/SETUP.md: how to preconfigure a PrismLauncher instance to run the updater.");
            }
            return;
        }
        System.err.println("usage: PackBuilder keygen <keyfile>\n       PackBuilder build <packDir> <packId> <version> <keyfile> <outDir> "
                + "[<previous manifest.json>] [<updater jar> <updater version>]");
        System.exit(2);
    }
}
