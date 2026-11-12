package dev.mcmesh.core;

import java.util.List;

public record RevisionFiles(String rev, List<Chunk> chunks, List<Aux> aux) {
    public record Chunk(String dim, String kind, int cx, int cz, byte[] hash) {}

    public record Aux(String path, byte[] hash) {}
}
