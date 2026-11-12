package dev.mcmesh.core;

public record WalFrame(long seq, String op, String dim, int cx, int cz, byte[] hash, byte[] mac) {}
