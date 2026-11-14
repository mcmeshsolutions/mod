package dev.mcmesh.core;

/**
 * Keeps score of how often two peers agreed on a tile. A pack whose worldgen is not reproducible (most modded packs,
 * see board/RESEARCH.md section 1) would otherwise have every tile rejected forever while workers keep burning CPU.
 */
public final class WorldgenLedger {
    /** Below this many verdicts the rate means nothing, so distribution stays on. */
    public static final int MIN_SAMPLE = 8;
    /** Vanilla disagrees on spawn-area tiles too, so some mismatch is normal; a third is already hopeless. */
    public static final double MAX_MISMATCH = 0.33;

    private int accepted;
    private int mismatched;
    private boolean off;

    public synchronized void record(WorldgenTiles.Verdict v) {
        if (v == WorldgenTiles.Verdict.ACCEPT) {
            accepted++;
        } else {
            mismatched++;
        }
        if (!off && accepted + mismatched >= MIN_SAMPLE && mismatchRate() > MAX_MISMATCH) {
            off = true;
        }
    }

    /** False once the pack has shown it cannot reproduce tiles: the host generates everything itself from then on. */
    public synchronized boolean distributing() {
        return !off;
    }

    public synchronized double mismatchRate() {
        int total = accepted + mismatched;
        return total == 0 ? 0 : (double) mismatched / total;
    }

    public synchronized int accepted() {
        return accepted;
    }

    public synchronized int mismatched() {
        return mismatched;
    }

    /** After a pack change the old verdicts say nothing. */
    public synchronized void reset() {
        accepted = 0;
        mismatched = 0;
        off = false;
    }

    public synchronized String describe() {
        return "worldgen tiles: " + accepted + " accepted, " + mismatched + " rejected"
                + (off ? ", distribution off (this pack does not reproduce tiles)" : "");
    }
}
