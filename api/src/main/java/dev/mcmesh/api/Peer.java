package dev.mcmesh.api;

/** A member of the world as the gateway knows it. {@code benchScore} is the CPU score of its last benchmark, -1 when it never ran one. */
public record Peer(String name, String role, double benchScore, boolean eligible) { }
