package dev.mcmesh.neoforge;

import dev.mcmesh.core.HostHealth;

/** Read-only view of this machine's own hosting numbers for the client UI. */
public final class HostStatus {
    private HostStatus() { }

    public static HostHealth snapshot() {
        return new HostHealth(LoadShed.reportedLevel(), LoadShed.reportedPlayers());
    }

    /** Last measured uplink in Mbit/s, or -1. */
    public static double uploadMbps() {
        return HandoverWatch.uploadMbps();
    }
}
