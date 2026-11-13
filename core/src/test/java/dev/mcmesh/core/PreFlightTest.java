package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PreFlightTest {
    private static final long MB = 1024L * 1024;

    @Test
    void aPackThatDeclaresNothingNeverBlocks() {
        assertNull(PreFlight.heapProblem(256 * MB, 0));
        assertNull(PreFlight.heapProblem(256 * MB, -1));
        assertNull(PreFlight.heapProblem(0, 4096), "an unknown heap size is not a reason to refuse");
    }

    @Test
    void enoughMemoryPassesIncludingTheJvmRoundingDown() {
        assertNull(PreFlight.heapProblem(8192 * MB, 6144));
        assertNull(PreFlight.heapProblem(6144 * MB, 6144));
        assertNull(PreFlight.heapProblem(5900 * MB, 6144), "the JVM reports a bit less than -Xmx");
    }

    @Test
    void tooLittleMemoryExplainsWhatToDo() {
        String m = PreFlight.heapProblem(4096 * MB, 6144);
        assertTrue(m.contains("6144 MB") && m.contains("4096 MB") && m.contains("-Xmx"), m);
        assertTrue(PreFlight.heapProblem(2048 * MB, 4096) != null);
    }
}
