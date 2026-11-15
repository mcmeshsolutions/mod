package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcmesh.core.TileMerge.Region;
import org.junit.jupiter.api.Test;

class TileMergeTest {
    @Test
    void statusesAreOrderedAsTheGameGeneratesThem() {
        assertTrue(TileMerge.rank("minecraft:structure_starts") < TileMerge.rank("minecraft:biomes"));
        assertTrue(TileMerge.rank("minecraft:carvers") < TileMerge.rank("minecraft:features"));
        assertTrue(TileMerge.rank("minecraft:initialize_light") < TileMerge.rank("minecraft:full"));
        assertEquals(TileMerge.rank("full"), TileMerge.rank("minecraft:full"), "the namespace is optional");
        assertEquals(-1, TileMerge.rank("minecraft:something_new"));
        assertEquals(-1, TileMerge.rank(null));
    }

    @Test
    void aChunkIsOnlyEverReplacedByOneFurtherAlong() {
        assertTrue(TileMerge.replaces(null, "minecraft:biomes"), "nothing there yet");
        assertTrue(TileMerge.replaces("minecraft:carvers", "minecraft:full"));
        assertFalse(TileMerge.replaces("minecraft:full", "minecraft:carvers"), "never downgrade");
        assertFalse(TileMerge.replaces("minecraft:full", "minecraft:full"), "equal never replaces, the existing one may have been built on");
        assertFalse(TileMerge.replaces("minecraft:biomes", "minecraft:biomes"));
    }

    @Test
    void aCheckedTileChunkWinsButTheFrontierNeverDowngrades() {
        assertTrue(TileMerge.shouldWrite(true, "minecraft:full", "minecraft:full"), "the verified version beats an unchecked halo of the same status");
        assertTrue(TileMerge.shouldWrite(true, null, "minecraft:full"));
        assertFalse(TileMerge.shouldWrite(false, "minecraft:full", "minecraft:full"), "unchecked chunks never replace an equal one");
        assertFalse(TileMerge.shouldWrite(false, "minecraft:full", "minecraft:carvers"));
        assertTrue(TileMerge.shouldWrite(false, "minecraft:biomes", "minecraft:carvers"));
        assertFalse(TileMerge.shouldWrite(true, "minecraft:full", "mod:custom"), "a status we do not understand is never installed, checked or not");
    }

    @Test
    void aChunkWeDoNotUnderstandIsNeverInstalled() {
        assertFalse(TileMerge.replaces(null, "minecraft:something_new"));
        assertFalse(TileMerge.replaces("minecraft:biomes", null));
        assertFalse(TileMerge.replaces("minecraft:biomes", "mod:custom"));
    }

    @Test
    void aRegionIsOnlyWrittenToWhenNobodyCouldHaveChangedIt() {
        assertEquals(Region.ABSENT, TileMerge.classify(false, false, false, false));
        assertEquals(Region.MINE, TileMerge.classify(true, true, false, false));
        assertEquals(Region.THEIRS, TileMerge.classify(true, false, false, false), "a file we did not create");
        assertEquals(Region.THEIRS, TileMerge.classify(true, true, true, false), "the game opened it since");
        assertEquals(Region.THEIRS, TileMerge.classify(false, false, true, false), "opened and never written is still opened");
        assertEquals(Region.THEIRS, TileMerge.classify(false, false, false, true), "a lazy bootstrap still owes it to the gateway");
        assertEquals(Region.THEIRS, TileMerge.classify(true, true, false, true));
    }

    @Test
    void theTilesOwnRegionDecidesWhetherItIsInstalledAtAll() {
        assertTrue(TileMerge.tileCanBeInstalled(Region.ABSENT));
        assertTrue(TileMerge.tileCanBeInstalled(Region.MINE));
        assertFalse(TileMerge.tileCanBeInstalled(Region.THEIRS));
    }
}
