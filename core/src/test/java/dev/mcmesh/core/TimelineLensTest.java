package dev.mcmesh.core;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TimelineLensTest {

    @Test
    void revisionNavigation() {
        TimelineLens lens = new TimelineLens();
        lens.setRevisions(List.of("rev-1", "rev-2", "rev-3"));

        assertEquals("rev-1", lens.getActiveRevision());
        assertFalse(lens.stepBack());

        assertTrue(lens.stepForward());
        assertEquals("rev-2", lens.getActiveRevision());

        assertTrue(lens.stepForward());
        assertEquals("rev-3", lens.getActiveRevision());
        assertFalse(lens.stepForward());

        assertTrue(lens.stepBack());
        assertEquals("rev-2", lens.getActiveRevision());
    }

    @Test
    void directSelectionAndStatus() {
        TimelineLens lens = new TimelineLens();
        lens.setRevisions(List.of("rev-1", "rev-2"));
        assertNull(lens.getStatusLine());

        lens.selectRevision("rev-2");
        assertTrue(lens.isDiffOverlayActive());
        assertEquals("Timeline Diff: rev-2 (2/2)", lens.getStatusLine());

        lens.reset();
        assertFalse(lens.isDiffOverlayActive());
        assertNull(lens.getStatusLine());
    }
}
