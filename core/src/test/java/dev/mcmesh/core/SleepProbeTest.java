package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SleepProbeTest {
    private static Path lidDir(String state) throws Exception {
        Path dir = Files.createTempDirectory("lid");
        Path button = Files.createDirectories(dir.resolve("LID0"));
        Files.writeString(button.resolve("state"), state);
        return dir;
    }

    @Test
    void readsTheKernelLidState() throws Exception {
        assertEquals(SleepProbe.Lid.CLOSED, SleepProbe.lid(lidDir("state:      closed\n")));
        assertEquals(SleepProbe.Lid.OPEN, SleepProbe.lid(lidDir("state:      open\n")));
    }

    @Test
    void anythingUnreadableIsUnknownRatherThanClosed() throws Exception {
        assertEquals(SleepProbe.Lid.UNKNOWN, SleepProbe.lid(Path.of("/does/not/exist")));
        assertEquals(SleepProbe.Lid.UNKNOWN, SleepProbe.lid(lidDir("state: whatever\n")));
        assertEquals(SleepProbe.Lid.UNKNOWN, SleepProbe.lid(Files.createTempDirectory("empty")));
    }

    @Test
    void aClosedLidOnBatteryHandsOverButADockedMachineDoesNot() {
        var discharging = new HandoverAdvisor.Battery(90, true);
        var charging = new HandoverAdvisor.Battery(90, false);
        assertEquals(HandoverAdvisor.Decision.HAND_OVER_NOW,
                HandoverAdvisor.decide(discharging, 50, SleepProbe.Lid.CLOSED));
        assertEquals(HandoverAdvisor.Decision.STAY,
                HandoverAdvisor.decide(charging, 50, SleepProbe.Lid.CLOSED),
                "a closed lid while charging is a docked desktop, not a machine going to sleep");
        assertEquals(HandoverAdvisor.Decision.STAY,
                HandoverAdvisor.decide(discharging, 50, SleepProbe.Lid.OPEN));
        assertEquals(HandoverAdvisor.Decision.STAY,
                HandoverAdvisor.decide(discharging, 50, SleepProbe.Lid.UNKNOWN),
                "a machine with no lid sensor must never hand over for this reason");
    }

    @Test
    void theWorstSignalStillWins() {
        var flat = new HandoverAdvisor.Battery(5, true);
        assertEquals(HandoverAdvisor.Decision.HAND_OVER_NOW,
                HandoverAdvisor.decide(flat, 50, SleepProbe.Lid.OPEN));
        assertEquals(HandoverAdvisor.Decision.PREFER_HANDOVER,
                HandoverAdvisor.decide(new HandoverAdvisor.Battery(100, false), 1.0, SleepProbe.Lid.OPEN));
    }

    @Test
    void aSuspendShowsUpAsWallClockRunningAheadOfTheMonotonicClock() {
        var w = new SleepProbe.SleepWatch(1000, 1000);
        assertEquals(0, w.check(2000, 2000), "a normal tick is no sleep");
        // an hour on the wall, two seconds of running: the machine was suspended
        assertEquals(3_600_000 - 2000, w.check(2000 + 3_600_000, 4000));
        assertEquals(0, w.check(2000 + 3_601_000, 5000), "and it is only reported once");
    }

    @Test
    void shortGapsAreNotCalledSleep() {
        var w = new SleepProbe.SleepWatch(0, 0);
        assertEquals(0, w.check(30_000, 0), "a 30 s stall is a GC pause or a stopped debugger, not a suspend");
        assertTrue(new SleepProbe.SleepWatch(0, 0).check(SleepProbe.SleepWatch.MIN_SLEEP_MS, 0) > 0);
    }
}
