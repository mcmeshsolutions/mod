package dev.mcmesh.core;

/** What a shard lease may write besides its chunks, the same list the gateway enforces. */
public final class ShardRules {
    private static final String[] AUX_PREFIXES = {"playerdata/", "advancements/", "stats/"};

    private ShardRules() { }

    public static boolean auxAllowed(String path) {
        if (path.contains("..")) {
            return false;
        }
        for (String p : AUX_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The player a path like playerdata/uuid.dat belongs to, or null. */
    public static String playerOf(String path) {
        if (path.startsWith("playerdata/") && path.endsWith(".dat") && path.indexOf('/', 11) < 0) {
            return path.substring(11, path.length() - 4);
        }
        return null;
    }
}
