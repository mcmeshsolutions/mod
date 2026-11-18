package dev.mcmesh.core;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InventoryLedgerTest {

    @Test
    void recordAndDrainDeltas() {
        InventoryLedger ledger = new InventoryLedger();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        var d1 = ledger.recordDelta(player1, 0, "minecraft:diamond", 64);
        var d2 = ledger.recordDelta(player1, 1, "minecraft:iron_ingot", 32);
        var d3 = ledger.recordDelta(player2, 0, "minecraft:golden_apple", 8);

        assertEquals(1, d1.sequence());
        assertEquals(2, d2.sequence());
        assertEquals(1, d3.sequence());

        List<InventoryLedger.Delta> p1Deltas = ledger.drainDeltas(player1);
        assertEquals(2, p1Deltas.size());
        assertEquals("minecraft:diamond", p1Deltas.get(0).itemId());
        assertEquals(0, ledger.drainDeltas(player1).size());

        List<InventoryLedger.Delta> all = ledger.drainAll();
        assertEquals(1, all.size());
        assertEquals("minecraft:golden_apple", all.get(0).itemId());
    }
}
