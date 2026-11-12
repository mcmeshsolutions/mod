package dev.mcmesh.neoforge.client;

import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.SyncProgress;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;

/** Installs or updates a Prism instance from a configured zip link into a sibling instance, never the running one. */
final class ModpackSync {
    private static final String STAMP = "mcmesh-modpack.txt";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(10)).build();

    enum State { NO_LINK, NOT_INSTALLED, UP_TO_DATE, UPDATE }

    record Status(State state, String remote) { }

    private ModpackSync() { }

    static Path target() throws IOException {
        Path run = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        Path instances = run.getParent() == null ? null : run.getParent().getParent();
        if (instances == null) {
            throw new IOException("cannot locate the Prism instances folder from " + run);
        }
        String name = MeshConfig.MODPACK_INSTANCE.get().strip();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IOException("bad modpackInstance name");
        }
        Path t = instances.resolve(name).normalize();
        if (t.equals(run.getParent())) {
            throw new IOException("modpackInstance is the running instance, pick another folder name");
        }
        return t;
    }

    static Status check() throws Exception {
        String url = MeshConfig.MODPACK_URL.get().strip();
        if (url.isEmpty()) {
            return new Status(State.NO_LINK, "");
        }
        HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("modpack link answered " + r.statusCode());
        }
        String remote = r.headers().firstValue("etag").orElse("") + "|" + r.headers().firstValue("last-modified").orElse("")
                + "|" + r.headers().firstValue("content-length").orElse("");
        Path stamp = target().resolve(STAMP);
        if (!Files.isRegularFile(stamp)) {
            return new Status(State.NOT_INSTALLED, remote);
        }
        return new Status(Files.readString(stamp).strip().equals(remote) ? State.UP_TO_DATE : State.UPDATE, remote);
    }

    /** Returns a short result line. Progress goes to SyncProgress. */
    static String install(Status st) throws Exception {
        Path target = target();
        Path instances = target.getParent();
        Path zip = Files.createTempFile(instances, ".mcmesh-modpack", ".zip");
        Path staging = instances.resolve(".mcmesh-modpack-staging");
        try {
            download(MeshConfig.MODPACK_URL.get().strip(), zip);
            SyncProgress.begin("Unpacking", true);
            deleteTree(staging);
            Files.createDirectories(staging);
            long size = Files.size(zip);
            unzip(zip, staging, size);
            Path root = packRoot(staging);
            Path oldMods = target.resolve("minecraft").resolve("mods");
            Files.createDirectories(target);
            deleteTree(oldMods);
            copyTree(root, target);
            Files.writeString(target.resolve(STAMP), st.remote());
        } finally {
            SyncProgress.end();
            Files.deleteIfExists(zip);
            deleteTree(staging);
        }
        return "Installed into the instance \"" + target.getFileName() + "\". Restart the launcher to see it.";
    }

    private static void download(String url, Path dest) throws Exception {
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("modpack link answered " + r.statusCode());
        }
        long total = r.headers().firstValueAsLong("content-length").orElse(-1);
        SyncProgress.begin("Downloading modpack", true);
        try (InputStream in = r.body(); var out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                SyncProgress.update(0, Math.max(total, got), got);
            }
        }
    }

    private static void unzip(Path zip, Path into, long size) throws IOException {
        Path base = into.toAbsolutePath().normalize();
        long done = 0;
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path p = base.resolve(e.getName()).normalize();
                if (!p.startsWith(base)) {
                    throw new IOException("zip entry escapes the target: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(p);
                    continue;
                }
                Files.createDirectories(p.getParent());
                Files.copy(z, p, StandardCopyOption.REPLACE_EXISTING);
                done += Math.max(0, e.getCompressedSize());
                SyncProgress.update(0, size, Math.min(done, size));
            }
        }
    }

    // exports either hold the instance files at the top or inside one folder
    private static Path packRoot(Path dir) throws IOException {
        if (Files.exists(dir.resolve("mmc-pack.json"))) {
            return dir;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path c : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(c) && Files.exists(c.resolve("mmc-pack.json"))) {
                    return c;
                }
            }
        }
        throw new IOException("the zip is not a Prism instance export (no mmc-pack.json)");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(d);
                } else {
                    Files.createDirectories(d.getParent());
                    Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(f);
            }
        }
    }
}
