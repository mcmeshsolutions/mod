package dev.mcmesh.neoforge;

import dev.mcmesh.core.ShardRegion;
import dev.mcmesh.core.ShardRules;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which part of the world this process may write to the gateway. A whole world host owns everything, minus what it has given to shard
 * hosts; a shard host owns only the regions of its lease. The gateway enforces the same rule on every append, so this exists to keep
 * a refused frame from failing a whole batch, not to be the safeguard.
 */
final class Ownership {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static volatile List<ShardRegion> owned;
    private static volatile List<ShardRegion> away = List.of();
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private Ownership() { }

    static boolean shard() {
        return owned != null;
    }

    static List<ShardRegion> owned() {
        return owned;
    }

    static void becomeShard(List<ShardRegion> regions) {
        owned = List.copyOf(regions);
    }

    static void giveAway(List<ShardRegion> regions) {
        away = List.copyOf(regions);
    }

    static List<ShardRegion> away() {
        return away;
    }

    /** True when this process may upload the chunk at (cx, cz). */
    static boolean writes(String dim, int cx, int cz) {
        List<ShardRegion> o = owned;
        if (o != null) {
            return ShardRegion.any(o, dim, cx >> 5, cz >> 5);
        }
        List<ShardRegion> a = away;
        return a.isEmpty() || !ShardRegion.any(a, dim, cx >> 5, cz >> 5);
    }

    /** True when this process may upload the aux file; a shard host only carries player files. */
    static boolean writesAux(String rel) {
        return owned == null || ShardRules.auxAllowed(rel);
    }

    /** A region file outside the set was opened on a shard host: the game is generating or loading terrain it does not own. Logged once per region. */
    static void touchedForeign(String dim, int cx, int cz) {
        if (owned != null && !writes(dim, cx, cz) && reported.add(dim + "|" + (cx >> 5) + "|" + (cz >> 5))) {
            LOG.warn("shard host: the game opened region {} {},{}, which is not part of this shard; nothing from it is uploaded", dim, cx >> 5, cz >> 5);
        }
    }
}
