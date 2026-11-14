package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LazyPlanTest {
    private static RevisionFiles.Chunk c(String dim, int cx, int cz) {
        return new RevisionFiles.Chunk(dim, "region", cx, cz, new byte[32]);
    }

    @Test
    void regionsNearAnAnchorAreEagerAndTheRestWait() {
        List<RevisionFiles.Chunk> chunks = new ArrayList<>();
        for (int cx = 0; cx < 320; cx += 32) {
            chunks.add(c("minecraft:overworld", cx, 0));
        }
        var eager = LazyPlan.eagerRegions(chunks, List.of(new LazyPlan.Anchor("minecraft:overworld", 5, 5)), 1, 100);
        var split = LazyPlan.split(chunks, eager);
        assertEquals(2, split.eager().size(), "regions 0 and 1 are within one region of region 0,0");
        assertEquals(8, split.pending().size());
        assertEquals(8, split.pendingChunks());
    }

    @Test
    void aSmallDimensionWithoutAnchorIsEagerAndABigOneIsNot() {
        List<RevisionFiles.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add(c("minecraft:the_nether", i * 32, 0));
        }
        for (int i = 0; i < 300; i++) {
            chunks.add(c("minecraft:the_end", i * 32, 0));
        }
        chunks.add(c("minecraft:overworld", 0, 0));
        var eager = LazyPlan.eagerRegions(chunks, List.of(new LazyPlan.Anchor("minecraft:overworld", 0, 0)), 0, 100);
        var split = LazyPlan.split(chunks, eager);
        assertEquals(11, split.eager().size(), "the nether (10) and the overworld spawn (1)");
        assertEquals(300, split.pendingChunks(), "the big end waits");
    }

    @Test
    void negativeCoordinatesFallInTheRightRegion() {
        assertEquals("d|-1|-1", LazyPlan.regionKey("d", -1, -32));
        assertEquals("d|-2|0", LazyPlan.regionKey("d", -33, 31));
        assertEquals("d|0|1", LazyPlan.regionKey("d", 31, 32));
    }

    @Test
    void everyChunkLandsInExactlyOneSide() {
        List<RevisionFiles.Chunk> chunks = new ArrayList<>();
        for (int i = -50; i < 50; i++) {
            chunks.add(c("minecraft:overworld", i * 7, i * 11));
        }
        var eager = LazyPlan.eagerRegions(chunks, List.of(new LazyPlan.Anchor("minecraft:overworld", 0, 0)), 1, 0);
        var split = LazyPlan.split(chunks, eager);
        assertEquals(chunks.size(), split.eager().size() + split.pendingChunks());
    }

    @Test
    void backdatingRewritesOnlyPresentChunksAndTheFileTime(@TempDir Path tmp) throws Exception {
        byte[] file = new byte[8192];
        file[0] = 0; file[1] = 0; file[2] = 2; file[3] = 1;
        file[4096 + 3] = 9;
        file[4096 + 4] = 7;
        Path f = tmp.resolve("r.0.0.mca");
        Files.write(f, file);
        McaTimestamps.backdate(f, 1_000_000);
        byte[] after = Files.readAllBytes(f);
        int ts0 = ((after[4096] & 255) << 24) | ((after[4097] & 255) << 16) | ((after[4098] & 255) << 8) | (after[4099] & 255);
        assertEquals(1_000_000, ts0);
        assertEquals(7, after[4096 + 4], "an absent chunk's timestamp is untouched");
        assertEquals(1_000_000_000L, Files.getLastModifiedTime(f).toMillis());
        McaTimestamps.backdate(tmp.resolve("missing.mca"), 5);
    }
}
