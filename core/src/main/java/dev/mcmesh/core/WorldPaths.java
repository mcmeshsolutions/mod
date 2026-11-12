package dev.mcmesh.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Maps dimension ids to vanilla save folders and back, and keeps downloaded paths inside the world folder. */
public final class WorldPaths {
    private static final Pattern DIM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private WorldPaths() { }

    public static Path dirOf(Path root, String dim) throws IOException {
        if (!DIM.matcher(dim).matches() || dim.contains("..")) {
            throw new IOException("bad dimension id " + dim);
        }
        return switch (dim) {
            case "minecraft:overworld" -> root;
            case "minecraft:the_nether" -> root.resolve("DIM-1");
            case "minecraft:the_end" -> root.resolve("DIM1");
            default -> root.resolve("dimensions").resolve(dim.replace(':', '/'));
        };
    }

    /** Returns null when the folder is not a dimension folder under root. */
    public static String dimOf(Path root, Path dimDir) {
        Path rel = root.toAbsolutePath().normalize().relativize(dimDir.toAbsolutePath().normalize());
        String s = rel.toString().replace('\\', '/');
        if (s.isEmpty()) {
            return "minecraft:overworld";
        }
        if (s.equals("DIM-1")) {
            return "minecraft:the_nether";
        }
        if (s.equals("DIM1")) {
            return "minecraft:the_end";
        }
        if (s.startsWith("dimensions/") && rel.getNameCount() >= 3) {
            return rel.getName(1) + ":" + rel.subpath(2, rel.getNameCount()).toString().replace('\\', '/');
        }
        return null;
    }

    public static java.util.List<Path> dimDirs(Path root) throws IOException {
        java.util.List<Path> dims = new java.util.ArrayList<>(java.util.List.of(root, root.resolve("DIM-1"), root.resolve("DIM1")));
        Path custom = root.resolve("dimensions");
        if (java.nio.file.Files.isDirectory(custom)) {
            try (java.util.stream.Stream<Path> ns = java.nio.file.Files.list(custom)) {
                for (Path n : (Iterable<Path>) ns::iterator) {
                    try (java.util.stream.Stream<Path> d = java.nio.file.Files.list(n)) {
                        d.forEach(dims::add);
                    }
                }
            }
        }
        return dims;
    }

    public static Path auxPath(Path root, String path) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path p = base.resolve(path).normalize();
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || !p.startsWith(base) || p.equals(base)) {
            throw new IOException("aux path escapes the world folder: " + path);
        }
        return p;
    }
}
