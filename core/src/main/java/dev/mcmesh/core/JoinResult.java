package dev.mcmesh.core;

public sealed interface JoinResult {
    record Host(Lease lease) implements JoinResult {}

    record Guest(long epoch, String hostDevice) implements JoinResult {}

    record Wait(String phase, int retryMs) implements JoinResult {}
}
