package dev.mcmesh.core;

import java.util.List;

public record Heartbeat(List<String> migrateTargets, List<String> targetNames) {
    public Heartbeat(List<String> migrateTargets) {
        this(migrateTargets, List.of());
    }
}
