package dev.mcmesh.core;

public record Lease(String id, long epoch, int ttlS, int heartbeatS, int fenceAfterS, String baseRev, byte[] hmacKey) {}
