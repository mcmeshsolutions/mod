package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class IslandPlanTest {
    private static IslandPlan.Anchor at(String id, int x, int z) {
        return new IslandPlan.Anchor(id, x, z);
    }

    @Test
    void playersFarApartGetTheirOwnIslandAndNoChunkIsContested() {
        var islands = IslandPlan.of(List.of(at("alice", 0, 0), at("bob", 1000, 1000)));
        assertEquals(2, islands.size());
        assertEquals(List.of("alice"), List.copyOf(islands.get(0).members()));
        assertTrue(IslandPlan.contested(islands).isEmpty());
    }

    @Test
    void playersStandingTogetherShareOneIsland() {
        var islands = IslandPlan.of(List.of(at("alice", 0, 0), at("bob", 2, 1), at("carol", 4, 0)));
        assertEquals(1, islands.size());
        assertEquals(3, islands.get(0).members().size());
    }

    @Test
    void theMergeDistanceLeavesNoOverlapBetweenTwoIslands() {
        // just too far to merge: the owned sets plus their halos must still not touch, or two hosts would write the same chunk
        int d = IslandPlan.MERGE_DISTANCE + 1;
        var islands = IslandPlan.of(List.of(at("alice", 0, 0), at("bob", d, 0)));
        assertEquals(2, islands.size());
        assertTrue(IslandPlan.contested(islands).isEmpty(), "islands one chunk past the merge distance must not share a halo");
    }

    @Test
    void aChainOfPlayersLinksIntoOneIsland() {
        int step = IslandPlan.MERGE_DISTANCE;
        var islands = IslandPlan.of(List.of(at("a", 0, 0), at("b", step, 0), at("c", 2 * step, 0), at("d", 3 * step, 0)));
        assertEquals(1, islands.size(), "single linkage: each hop is within the merge distance");
    }

    @Test
    void ownedChunksCoverTheViewRangeAndOwnershipIsUnique() {
        var islands = IslandPlan.of(List.of(at("alice", 0, 0)));
        int span = 2 * IslandPlan.VIEW + 1;
        assertEquals(span * span, islands.get(0).chunkCount());
        assertEquals(0, IslandPlan.ownerOf(islands, WorldgenTiles.pack(IslandPlan.VIEW, IslandPlan.VIEW)));
        assertEquals(-1, IslandPlan.ownerOf(islands, WorldgenTiles.pack(IslandPlan.VIEW + 1, 0)));
    }

    @Test
    void aPlayerOnTheSeamDoesNotFlapBetweenHosts() {
        var tracker = new IslandPlan.Tracker();
        // together first, so the pair is one island
        assertEquals(1, tracker.update(List.of(at("alice", 0, 0), at("bob", 4, 0))).size());
        // drifting just past the merge distance would split without hysteresis
        int justApart = IslandPlan.MERGE_DISTANCE + 2;
        assertEquals(1, tracker.update(List.of(at("alice", 0, 0), at("bob", justApart, 0))).size(),
                "a pair that was together stays together until the split distance");
        assertEquals(0, tracker.splits());
        // well beyond it they finally split
        assertEquals(2, tracker.update(List.of(at("alice", 0, 0), at("bob", IslandPlan.SPLIT_DISTANCE + 1, 0))).size());
        assertEquals(1, tracker.splits());
    }

    @Test
    void approachingPlayersMergeAtOnce() {
        var tracker = new IslandPlan.Tracker();
        assertEquals(2, tracker.update(List.of(at("alice", 0, 0), at("bob", 1000, 0))).size());
        assertEquals(1, tracker.update(List.of(at("alice", 0, 0), at("bob", 3, 0))).size(), "merging never waits");
        assertEquals(1, tracker.merges());
    }

    @Test
    void aTrackerThatSplitsEasierThanItMergesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new IslandPlan.Tracker(20, 10));
    }
}
