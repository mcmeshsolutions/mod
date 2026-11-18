package dev.mcmesh.core;

import static dev.mcmesh.core.HandoverAdvisor.Decision.HAND_OVER_NOW;
import static dev.mcmesh.core.HandoverAdvisor.Decision.PREFER_HANDOVER;
import static dev.mcmesh.core.HandoverAdvisor.Decision.STAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.mcmesh.core.HandoverAdvisor.Battery;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandoverAdvisorTest {
    @Test
    void unknownBatteryNeverTriggers() {
        assertEquals(STAY, HandoverAdvisor.decide(null));
        assertEquals(STAY, HandoverAdvisor.decide(new Battery(-1, true)));
        assertEquals(STAY, HandoverAdvisor.decide(new Battery(101, true)));
    }

    @Test
    void chargingOrFullNeverTriggersEvenAtLowPercent() {
        assertEquals(STAY, HandoverAdvisor.decide(new Battery(3, false)));
    }

    @Test
    void dischargingThresholds() {
        assertEquals(STAY, HandoverAdvisor.decide(new Battery(21, true)));
        assertEquals(PREFER_HANDOVER, HandoverAdvisor.decide(new Battery(20, true)));
        assertEquals(PREFER_HANDOVER, HandoverAdvisor.decide(new Battery(9, true)));
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(new Battery(8, true)));
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(new Battery(0, true)));
    }

    private static void bat(Path root, String name, String capacity, String status) throws Exception {
        Path d = Files.createDirectories(root.resolve(name));
        if (capacity != null) {
            Files.writeString(d.resolve("capacity"), capacity + "\n");
        }
        if (status != null) {
            Files.writeString(d.resolve("status"), status + "\n");
        }
    }

    @Test
    void probeReadsSysfsAndIgnoresNonBatteries(@TempDir Path root) throws Exception {
        bat(root, "AC", "100", "Discharging");
        bat(root, "BAT0", "42", "Discharging");
        assertEquals(new Battery(42, true), BatteryProbe.read(root));
    }

    @Test
    void probeTreatsAnythingButDischargingAsNotDischarging(@TempDir Path root) throws Exception {
        bat(root, "BAT0", "15", "Not charging");
        assertEquals(new Battery(15, false), BatteryProbe.read(root));
    }

    @Test
    void probeIsNullWithoutABatteryOrWithGarbage(@TempDir Path root) throws Exception {
        assertNull(BatteryProbe.read(root));
        bat(root, "BAT0", "abc", "Discharging");
        assertNull(BatteryProbe.read(root));
        assertNull(BatteryProbe.read(root.resolve("missing")));
    }

    @Test
    void aBadUplinkAloneCanPreferOrForceAHandover() {
        assertEquals(STAY, HandoverAdvisor.decide(null, -1), "unknown never triggers");
        assertEquals(STAY, HandoverAdvisor.decide(null, 50));
        assertEquals(PREFER_HANDOVER, HandoverAdvisor.decide(null, 1.9));
        assertEquals(STAY, HandoverAdvisor.decide(null, 2.0));
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(null, 0.4));
    }

    @Test
    void theWorseOfBatteryAndLinkWins() {
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(new Battery(5, true), 100));
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(new Battery(80, true), 0.1));
        assertEquals(PREFER_HANDOVER, HandoverAdvisor.decide(new Battery(15, true), 1.0));
        assertEquals(STAY, HandoverAdvisor.decide(new Battery(80, true), 30));
    }

    @Test
    void customBatteryThresholds() {
        assertEquals(STAY, HandoverAdvisor.decide(new Battery(25, true), 10, SleepProbe.Lid.OPEN, 15));
        assertEquals(PREFER_HANDOVER, HandoverAdvisor.decide(new Battery(18, true), 10, SleepProbe.Lid.OPEN, 15));
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(new Battery(15, true), 10, SleepProbe.Lid.OPEN, 15));
        assertEquals(HAND_OVER_NOW, HandoverAdvisor.decide(new Battery(10, true), 10, SleepProbe.Lid.OPEN, 15));
    }
}
