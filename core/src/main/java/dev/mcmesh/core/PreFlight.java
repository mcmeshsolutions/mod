package dev.mcmesh.core;

/** Checks that cost nothing and save a player from a crash twenty minutes into a session. */
public final class PreFlight {
    private PreFlight() { }

    /**
     * A message when the JVM's maximum heap is below what the pack declares it needs, else null. `minMb` of 0 or less means
     * the pack declares nothing. A little slack is allowed because the JVM reports slightly less than the -Xmx value.
     */
    public static String heapProblem(long maxHeapBytes, int minMb) {
        if (minMb <= 0 || maxHeapBytes <= 0) {
            return null;
        }
        long haveMb = maxHeapBytes / (1024 * 1024);
        if (haveMb >= minMb * 95L / 100) {
            return null;
        }
        return "This modpack needs at least " + minMb + " MB of memory for the game, but it is set to " + haveMb + " MB. "
                + "Raise the maximum memory of this instance in your launcher (the -Xmx setting) and start the game again.";
    }
}
