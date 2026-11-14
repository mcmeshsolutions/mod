package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Splits the active parts of a world into islands: groups of players (and forced chunks) that can be ticked by
 * different hosts at the same time. Pure geometry, no game types, so it can be unit tested and replayed over a
 * recording of a real session. See ROADMAP.md "Dynamic multi-host sharding" and board/RESEARCH.md.
 */
public final class IslandPlan {
    /** Chunks around a player that the host has to keep loaded; the owned set is built from this. */
    public static final int VIEW = 8;
    /** Chunks of overlap that neither host may write: a contraption or a mob can reach this far across the seam. */
    public static final int HALO = 3;
    /** Two groups closer than this merge. */
    public static final int MERGE_DISTANCE = 2 * VIEW + 2 * HALO;
    /** They only split again past this, so a player walking along a seam does not flap hosts. */
    public static final int SPLIT_DISTANCE = MERGE_DISTANCE * 2;

    public record Anchor(String id, int chunkX, int chunkZ) { }

    public record Island(int id, Set<String> members, Set<Long> owned) {
        public boolean owns(long chunk) {
            return owned.contains(chunk);
        }

        public int chunkCount() {
            return owned.size();
        }
    }

    private IslandPlan() { }

    /** Single-linkage grouping: two anchors share an island when a chain of anchors no further apart than distance links them. */
    public static List<Island> of(Collection<Anchor> anchors, int distance) {
        List<Anchor> list = new ArrayList<>(anchors);
        int[] parent = new int[list.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                if (chebyshev(list.get(i), list.get(j)) <= distance) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, Set<String>> members = new HashMap<>();
        Map<Integer, Set<Long>> owned = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            int root = find(parent, i);
            members.computeIfAbsent(root, k -> new TreeSet<>()).add(list.get(i).id());
            Set<Long> set = owned.computeIfAbsent(root, k -> new HashSet<>());
            Anchor a = list.get(i);
            for (int cx = a.chunkX() - VIEW; cx <= a.chunkX() + VIEW; cx++) {
                for (int cz = a.chunkZ() - VIEW; cz <= a.chunkZ() + VIEW; cz++) {
                    set.add(WorldgenTiles.pack(cx, cz));
                }
            }
        }
        List<Island> out = new ArrayList<>();
        for (int root : members.keySet()) {
            out.add(new Island(0, Set.copyOf(members.get(root)), Set.copyOf(owned.get(root))));
        }
        // stable ids: order by the first member name, so the same layout always numbers the same way
        out.sort(Comparator.comparing(i -> i.members().iterator().next()));
        List<Island> numbered = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            numbered.add(new Island(i, out.get(i).members(), out.get(i).owned()));
        }
        return numbered;
    }

    public static List<Island> of(Collection<Anchor> anchors) {
        return of(anchors, MERGE_DISTANCE);
    }

    /**
     * Chunks that more than one island would have to touch. A plan with any of these is not safe to hand to two
     * hosts: the islands have to be merged first, which is why MERGE_DISTANCE already covers two view ranges
     * plus two halos. Non-empty means a bug in the distance, or anchors supplied by a caller that skipped merging.
     */
    public static Set<Long> contested(List<Island> islands) {
        Map<Long, Integer> seen = new HashMap<>();
        Set<Long> bad = new HashSet<>();
        for (Island island : islands) {
            for (long c : island.owned()) {
                for (int dx = -HALO; dx <= HALO; dx++) {
                    for (int dz = -HALO; dz <= HALO; dz++) {
                        long near = WorldgenTiles.pack((int) (c >> 32) + dx, (int) (long) c + dz);
                        Integer other = seen.get(near);
                        if (other != null && other != island.id()) {
                            bad.add(near);
                        }
                    }
                }
            }
            for (long c : island.owned()) {
                seen.put(c, island.id());
            }
        }
        return bad;
    }

    /** Which island owns a chunk, or -1. A chunk is never owned by two islands in a plan that has no contested chunks. */
    public static int ownerOf(List<Island> islands, long chunk) {
        for (Island i : islands) {
            if (i.owns(chunk)) {
                return i.id();
            }
        }
        return -1;
    }

    private static int chebyshev(Anchor a, Anchor b) {
        return Math.max(Math.abs(a.chunkX() - b.chunkX()), Math.abs(a.chunkZ() - b.chunkZ()));
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    /**
     * Keeps the previous grouping so islands only split once the groups are well apart (SPLIT_DISTANCE), while they
     * still merge as soon as they come within MERGE_DISTANCE. Without this a player standing on a seam would move
     * between hosts every few seconds.
     */
    public static final class Tracker {
        private final int mergeDistance;
        private final int splitDistance;
        private List<Set<String>> previous = List.of();
        private int merges;
        private int splits;

        public Tracker() {
            this(MERGE_DISTANCE, SPLIT_DISTANCE);
        }

        public Tracker(int mergeDistance, int splitDistance) {
            if (splitDistance < mergeDistance) {
                throw new IllegalArgumentException("splitting must need more distance than merging, or islands flap");
            }
            this.mergeDistance = mergeDistance;
            this.splitDistance = splitDistance;
        }

        public List<Island> update(Collection<Anchor> anchors) {
            List<Island> fresh = of(anchors, mergeDistance);
            // anything grouped last time stays grouped until the wider distance also pulls it apart
            List<Island> wide = of(anchors, splitDistance);
            List<Island> result = new ArrayList<>();
            for (Island island : fresh) {
                result.add(island);
            }
            for (Set<String> old : previous) {
                for (Island w : wide) {
                    if (w.members().containsAll(old) && countOverlapping(result, old) > 1) {
                        result = mergeGroupsOf(anchors, result, old);
                        break;
                    }
                }
            }
            countTransitions(result);
            previous = result.stream().map(Island::members).toList();
            return result;
        }

        private static int countOverlapping(List<Island> islands, Set<String> group) {
            int n = 0;
            for (Island i : islands) {
                if (i.members().stream().anyMatch(group::contains)) {
                    n++;
                }
            }
            return n;
        }

        private static List<Island> mergeGroupsOf(Collection<Anchor> anchors, List<Island> islands, Set<String> group) {
            Set<String> together = new HashSet<>();
            for (Island i : islands) {
                if (i.members().stream().anyMatch(group::contains)) {
                    together.addAll(i.members());
                }
            }
            List<Island> out = new ArrayList<>();
            Set<String> mergedMembers = new TreeSet<>(together);
            Set<Long> mergedOwned = new HashSet<>();
            for (Island i : islands) {
                if (i.members().stream().anyMatch(group::contains)) {
                    mergedOwned.addAll(i.owned());
                } else {
                    out.add(i);
                }
            }
            out.add(new Island(0, Set.copyOf(mergedMembers), Set.copyOf(mergedOwned)));
            out.sort(Comparator.comparing(i -> i.members().iterator().next()));
            List<Island> numbered = new ArrayList<>(out.size());
            for (int i = 0; i < out.size(); i++) {
                numbered.add(new Island(i, out.get(i).members(), out.get(i).owned()));
            }
            return numbered;
        }

        private void countTransitions(List<Island> now) {
            if (previous.isEmpty()) {
                return;
            }
            if (now.size() < previous.size()) {
                merges += previous.size() - now.size();
            } else if (now.size() > previous.size()) {
                splits += now.size() - previous.size();
            }
        }

        public int merges() {
            return merges;
        }

        public int splits() {
            return splits;
        }
    }
}
