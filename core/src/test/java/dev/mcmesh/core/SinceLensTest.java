package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SinceLensTest {

    @Test
    public void testActivationAndDeactivation() {
        SinceLens lens = new SinceLens();
        assertFalse(lens.isActive());
        assertEquals(SinceLens.Mode.OFF, lens.getMode());

        lens.setDeltas(SinceLens.mockRecentDeltas(1000));
        lens.activate(SinceLens.Mode.PAST_24H, 1000);

        assertTrue(lens.isActive());
        assertEquals(SinceLens.Mode.PAST_24H, lens.getMode());
        assertEquals(1000, lens.getWindowStartSeconds());
        assertFalse(lens.getActiveBoxes().isEmpty(), "Active boxes should be populated after activation");

        lens.deactivate();
        assertFalse(lens.isActive());
        assertEquals(SinceLens.Mode.OFF, lens.getMode());
        assertTrue(lens.getActiveBoxes().isEmpty());
    }

    @Test
    public void testSpatialClusteringGroupsNearbyDeltas() {
        List<SinceLens.BlockDelta> deltas = List.of(
                new SinceLens.BlockDelta(0, 64, 0, "air", "stone", "Alice", 100, SinceLens.DeltaType.ADDED),
                new SinceLens.BlockDelta(1, 64, 2, "air", "stone", "Alice", 100, SinceLens.DeltaType.ADDED),
                new SinceLens.BlockDelta(2, 65, 1, "air", "stone", "Alice", 100, SinceLens.DeltaType.ADDED),
                // Far away cluster by Bob
                new SinceLens.BlockDelta(100, 64, 100, "stone", "air", "Bob", 150, SinceLens.DeltaType.REMOVED),
                new SinceLens.BlockDelta(101, 64, 102, "stone", "air", "Bob", 150, SinceLens.DeltaType.REMOVED)
        );

        List<SinceLens.BoundingBox3D> boxes = SinceLens.clusterDeltas(deltas, 10);
        assertEquals(2, boxes.size(), "Should produce 2 clusters");

        SinceLens.BoundingBox3D aliceBox = boxes.stream().filter(b -> b.builder().equals("Alice")).findFirst().orElse(null);
        assertNotNull(aliceBox);
        assertEquals(3, aliceBox.blockCount());
        assertEquals(0, aliceBox.minX());
        assertEquals(2, aliceBox.maxX());
        assertEquals(SinceLens.DeltaType.ADDED, aliceBox.primaryType());
        assertTrue(aliceBox.contains(1, 64, 2));
        assertFalse(aliceBox.contains(50, 50, 50));
        assertTrue(aliceBox.getTooltip().contains("Alice"));

        SinceLens.BoundingBox3D bobBox = boxes.stream().filter(b -> b.builder().equals("Bob")).findFirst().orElse(null);
        assertNotNull(bobBox);
        assertEquals(2, bobBox.blockCount());
        assertEquals(SinceLens.DeltaType.REMOVED, bobBox.primaryType());
        assertTrue(bobBox.getTooltip().contains("Bob"));
    }
}
