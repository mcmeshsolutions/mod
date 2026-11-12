package dev.mcmesh.core;

/** Result of a successful gateway login; token goes into GatewayClient. */
public record Identity(String token, String uuid, String name) { }
