package dev.mcmesh.neoforge;

import dev.mcmesh.core.HostHealth;
import java.util.function.BooleanSupplier;

/** Read-only view of this machine's own hosting numbers for the client UI. */
public final class HostStatus {
    private static volatile String guestWorld;
    private static volatile BooleanSupplier takeoverRequester;

    private HostStatus() { }

    public static HostHealth snapshot() {
        return new HostHealth(LoadShed.reportedLevel(), LoadShed.reportedPlayers(), LoadShed.reportedTps());
    }

    public static double tps() {
        return LoadShed.reportedTps();
    }

    /** Last measured uplink in Mbit/s, or -1. */
    public static double uploadMbps() {
        return HandoverWatch.uploadMbps();
    }

    public static String guestWorld() {
        return guestWorld;
    }

    public static void setGuestWorld(String world, BooleanSupplier requester) {
        guestWorld = world;
        takeoverRequester = requester;
    }

    public static boolean requestTakeover() {
        BooleanSupplier r = takeoverRequester;
        return r != null && r.getAsBoolean();
    }
}
