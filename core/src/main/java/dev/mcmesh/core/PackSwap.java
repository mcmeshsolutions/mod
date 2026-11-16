package dev.mcmesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a staged pack update. Runs in a helper process after the game exited, so this class must stay
 * self-contained (JDK only, no nested classes): it is copied out of the mod jar as a bare class file.
 */
public final class PackSwap {
    static final String READY = "ready.txt";

    private PackSwap() { }

    public static Path updateDir(Path gameDir) {
        return gameDir.resolve("mcmesh-update");
    }

    /** Relative game-dir path under mods/ or config/ that cannot leave them; returns null when the path is fine. */
    public static String badPath(Path gameDir, String p) {
        if (p == null || p.isEmpty() || p.indexOf('\\') >= 0 || p.indexOf(':') >= 0 || p.indexOf('\0') >= 0 || p.startsWith("/")) {
            return "unsafe path";
        }
        String[] seg = p.split("/", -1);
        if (seg.length < 2 || !(seg[0].equals("mods") || seg[0].equals("config"))) {
            return "path outside mods/ and config/";
        }
        for (String s : seg) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                return "path traversal";
            }
        }
        Path root = gameDir.toAbsolutePath().normalize().resolve(seg[0]);
        if (!root.resolve(p.substring(seg[0].length() + 1)).normalize().startsWith(root)) {
            return "path traversal";
        }
        return null;
    }

    /** True when an existing parent directory of the target resolves outside its mods/ or config/ root. */
    static boolean escapesBySymlink(Path game, String p) throws IOException {
        Path root = game.resolve(p.substring(0, p.indexOf('/')));
        Path parent = game.resolve(p).getParent();
        while (parent != null && !Files.exists(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            parent = parent.getParent();
        }
        return parent != null && Files.exists(root) && !parent.toRealPath().startsWith(root.toRealPath());
    }

    static String versionOk(String v) {
        return v != null && v.matches("\\d{1,9}(\\.\\d{1,9})*") ? null : "bad version";
    }

    /** Version recorded by the last applied update, null when none. */
    public static String appliedVersion(Path gameDir) {
        try {
            return Files.readString(updateDir(gameDir).resolve("installed.txt")).strip();
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean hasReady(Path gameDir) {
        return Files.isRegularFile(updateDir(gameDir).resolve(READY));
    }

    /** Moves staged files into place, old files go to backup/&lt;version&gt;. Rolls back and rethrows on failure. */
    public static void apply(Path gameDir) throws IOException {
        Path game = gameDir.toAbsolutePath().normalize();
        Path upd = updateDir(game);
        Path staged = upd.resolve("staged");
        String version = null;
        List<String> put = new ArrayList<>();
        List<String> rm = new ArrayList<>();
        List<PackPatch.Op> patches = new ArrayList<>();
        for (String line : Files.readAllLines(upd.resolve(READY), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", 2);
            if (f.length != 2) {
                throw new IOException("bad ready file");
            }
            switch (f[0]) {
                case "version" -> version = f[1];
                case "put" -> put.add(f[1]);
                case "rm" -> rm.add(f[1]);
                case "patch" -> patches.add(PackPatch.decode(f[1]));
                default -> throw new IOException("bad ready file");
            }
        }
        for (PackPatch.Op op : patches) {
            String why = PackPatch.bad(game, op);
            if (why != null) {
                throw new IOException("unsafe patch op: " + why);
            }
        }
        if (versionOk(version) != null) {
            throw new IOException("bad version in ready file");
        }
        for (String p : put) {
            if (badPath(game, p) != null || !Files.isRegularFile(staged.resolve(p))) {
                throw new IOException("staged file missing or unsafe: " + p);
            }
        }
        for (String p : rm) {
            if (badPath(game, p) != null) {
                throw new IOException("unsafe path: " + p);
            }
        }
        for (String p : put) {
            if (escapesBySymlink(game, p)) {
                throw new IOException("symlink escape: " + p);
            }
        }
        for (String p : rm) {
            if (escapesBySymlink(game, p)) {
                throw new IOException("symlink escape: " + p);
            }
        }
        Path backup = upd.resolve("backup").resolve(version);
        List<String> backedUp = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        try {
            List<String> touched = new ArrayList<>(put);
            touched.addAll(rm);
            for (String p : touched) {
                Path target = game.resolve(p);
                if (Files.isRegularFile(target)) {
                    Path b = backup.resolve(p);
                    Files.createDirectories(b.getParent());
                    Files.move(target, b, StandardCopyOption.REPLACE_EXISTING);
                    backedUp.add(p);
                }
            }
            for (String p : put) {
                Path target = game.resolve(p);
                Files.createDirectories(target.getParent());
                Files.move(staged.resolve(p), target, StandardCopyOption.REPLACE_EXISTING);
                placed.add(p);
            }
            // breaking-change mitigations the manifest asked for; rollback on failure is partial (see put/rm
            // above) since delete/regex aren't naturally reversible, but every op stays inside mods/ or config/
            for (PackPatch.Op op : patches) {
                PackPatch.apply(game, op);
            }
            Path tmp = upd.resolve("installed.txt.tmp");
            Files.writeString(tmp, version, StandardCharsets.UTF_8);
            Files.move(tmp, upd.resolve("installed.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            for (String p : placed) {
                Files.move(game.resolve(p), staged.resolve(p), StandardCopyOption.REPLACE_EXISTING);
            }
            for (String p : backedUp) {
                Files.move(backup.resolve(p), game.resolve(p), StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
        Files.deleteIfExists(upd.resolve(READY));
        deleteTree(staged);
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Helper entry point: args are the game process id and the game directory. */
    public static void main(String[] a) throws Exception {
        long pid = Long.parseLong(a[0]);
        Path game = Path.of(a[1]);
        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get(10, java.util.concurrent.TimeUnit.MINUTES);
            } catch (Exception e) {
                System.exit(2);
            }
        });
        if (hasReady(game)) {
            apply(game);
        }
    }
}
