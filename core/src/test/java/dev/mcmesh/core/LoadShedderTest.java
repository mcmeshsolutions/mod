package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoadShedderTest {
    private static int feed(LoadShedder s, double tps, long fromMs, long toMs) {
        int level = s.level();
        for (long t = fromMs; t <= toMs; t += 1_000) {
            level = s.update(tps, t);
        }
        return level;
    }

    @Test
    void steadyGoodTpsStaysAtZero() {
        assertEquals(0, feed(new LoadShedder(), 20.0, 0, 600_000));
    }

    @Test
    void aSingleLagSpikeDoesNotEscalate() {
        LoadShedder s = new LoadShedder();
        feed(s, 20.0, 0, 10_000);
        assertEquals(0, feed(s, 5.0, 11_000, 20_000));
        assertEquals(0, feed(s, 20.0, 21_000, 120_000));
    }

    @Test
    void sustainedLowTpsEscalatesOneStepAtATime() {
        LoadShedder s = new LoadShedder();
        assertEquals(0, feed(s, 10.0, 0, 29_000));
        assertEquals(1, feed(s, 10.0, 30_000, 30_000));
        assertEquals(1, feed(s, 10.0, 31_000, 59_000));
        assertEquals(2, feed(s, 10.0, 60_000, 60_000));
        assertEquals(3, feed(s, 10.0, 61_000, 90_000));
        assertEquals(3, feed(s, 1.0, 91_000, 400_000), "never above the maximum");
    }

    @Test
    void recoveryStepsDownOnlyAfterTheDwellTime() {
        LoadShedder s = new LoadShedder();
        feed(s, 10.0, 0, 90_000);
        assertEquals(3, s.level());
        assertEquals(3, feed(s, 20.0, 91_000, 180_000));
        assertEquals(2, feed(s, 20.0, 181_000, 181_000));
        assertEquals(2, feed(s, 20.0, 182_000, 270_000));
        assertEquals(1, feed(s, 20.0, 271_000, 271_000));
        assertEquals(0, feed(s, 20.0, 272_000, 361_000));
    }

    @Test
    void tpsInsideTheHysteresisBandHoldsTheLevel() {
        LoadShedder s = new LoadShedder();
        feed(s, 15.0, 0, 30_000);
        assertEquals(1, s.level());
        assertEquals(1, feed(s, 18.5, 31_000, 300_000), "17.5 + 1.5 margin not reached");
        assertEquals(0, feed(s, 19.0, 301_000, 400_000));
    }

    @Test
    void brokenStreakRestartsTheDwellClock() {
        LoadShedder s = new LoadShedder();
        feed(s, 10.0, 0, 20_000);
        feed(s, 19.0, 21_000, 22_000);
        assertEquals(0, feed(s, 10.0, 23_000, 50_000), "only 28 s below since the break");
        assertEquals(1, feed(s, 10.0, 51_000, 53_000));
    }
}
