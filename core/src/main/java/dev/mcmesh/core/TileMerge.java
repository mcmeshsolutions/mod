package dev.mcmesh.core;

import java.util.List;

/**
 * The rules for putting a worker's chunks into a host world without ever making it worse. A tile brings its own region
 * and the partially generated frontier around it (board/RESEARCH.md 1e: without the frontier the first chunks the host
 * generates next to a tile miss the spill of the tile's edge features, about 400 blocks per chunk). The frontier lands in
 * neighbouring regions that may hold anything, so every decision here errs on the side of leaving the world alone.
 */
public final class TileMerge {
    /** Generation statuses in the order a chunk goes through them. */
    private static final List<String> ORDER = List.of("structure_starts", "structure_references", "biomes", "noise", "surface", "carvers", "liquid_carvers",
            "features", "initialize_light", "light", "spawn", "full");

    /** What a region file is to the installer. */
    public enum Region {
        /** no file of any kind: nothing has ever looked at it */
        ABSENT,
        /** created by an earlier install and not opened by the game since: ours to add to */
        MINE,
        /** anything else: the game or a previous session may have changed it, so it is never touched */
        THEIRS
    }

    private TileMerge() { }

    /** Position in the generation order, or -1 for something that is not a status of ours (a chunk we do not understand is never installed). */
    public static int rank(String status) {
        if (status == null) {
            return -1;
        }
        int i = ORDER.indexOf(status.startsWith("minecraft:") ? status.substring("minecraft:".length()) : status);
        return i;
    }

    /** An incoming chunk replaces an existing one only when it is strictly further along. Equal never replaces: the existing one may already have been built on. */
    public static boolean replaces(String existing, String incoming) {
        int in = rank(incoming);
        if (in < 0) {
            return false;
        }
        return existing == null || in > rank(existing);
    }

    /**
     * @param anyFile  any of the region, entities or poi files exists
     * @param ours     an earlier install created this region
     * @param touched  the game opened it at least once since (this session or a recorded earlier one)
     * @param pending  a lazy bootstrap still owes this region to the gateway and will overwrite whatever is there
     */
    public static Region classify(boolean anyFile, boolean ours, boolean touched, boolean pending) {
        if (pending || touched) {
            return Region.THEIRS;
        }
        if (!anyFile) {
            return Region.ABSENT;
        }
        return ours ? Region.MINE : Region.THEIRS;
    }

    /** The tile's own region must be one we may write to; the frontier is written wherever it can be and skipped elsewhere. */
    public static boolean tileCanBeInstalled(Region own) {
        return own != Region.THEIRS;
    }
}
