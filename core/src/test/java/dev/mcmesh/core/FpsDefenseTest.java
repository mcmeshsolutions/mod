package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FpsDefenseTest {
    @Test
    void normalFpsStaysAtZero() {
        FpsDefense d = new FpsDefense();
        assertEquals(0, d.level());
        assertEquals(0, d.update(60, 30, 1000));
        assertEquals(0, d.update(45, 30, 20000));
    }

    @Test
    void lowFpsStepsUpAfterDwell() {
        FpsDefense d = new FpsDefense();
        assertEquals(0, d.update(25, 30, 1000));
        assertEquals(0, d.update(25, 30, 5000)); // 4s < 10s dwell
        assertEquals(1, d.update(25, 30, 12000)); // 11s >= 10s dwell -> level 1
        assertEquals(1, d.update(25, 30, 18000)); // 6s < 10s dwell for level 2
        assertEquals(2, d.update(25, 30, 23000)); // 11s >= 10s dwell -> level 2
        assertEquals(2, d.update(15, 30, 40000)); // max level is 2
    }

    @Test
    void recoversAfterExitDwell() {
        FpsDefense d = new FpsDefense();
        d.update(20, 30, 0);
        d.update(20, 30, 11000); // level 1
        assertEquals(1, d.level());

        // FPS recovers to 36 (>= 30 + 5)
        d.update(36, 30, 15000);
        assertEquals(1, d.level()); // 0s into exit dwell
        d.update(36, 30, 30000);
        assertEquals(1, d.level()); // 15s < 20s
        d.update(36, 30, 36000);
        assertEquals(0, d.level()); // 21s >= 20s -> level 0
    }

    @Test
    void resetClearsState() {
        FpsDefense d = new FpsDefense();
        d.update(20, 30, 0);
        d.update(20, 30, 11000);
        assertEquals(1, d.level());
        d.reset();
        assertEquals(0, d.level());
    }
}
