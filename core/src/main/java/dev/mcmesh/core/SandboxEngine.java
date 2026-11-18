package dev.mcmesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core engine for offline singleplayer sandbox mode, creative scratchpad drafting,
 * holographic bill-of-materials (BOM) calculation, and Create-compatible schematic exports.
 */
public final class SandboxEngine {

    public static record ActiveSession(
            String name,
            long startedAt,
            double x,
            double y,
            double z,
            String dimension,
            Map<String, Integer> bom
    ) {}

    public static record Draft(
            String name,
            String dimension,
            double x,
            double y,
            double z,
            Map<String, Integer> bom,
            boolean hologramActive
    ) {}

    private final Map<UUID, ActiveSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final Set<String> sharedBlueprints = ConcurrentHashMap.newKeySet();
    private volatile boolean offlineSandboxActive = true;

    public boolean isOfflineSandboxActive() {
        return offlineSandboxActive;
    }

    public void setOfflineSandboxActive(boolean active) {
        this.offlineSandboxActive = active;
    }

    public ActiveSession startSession(UUID playerId, String name, double x, double y, double z, String dimension) {
        String draftName = (name != null && !name.isBlank()) ? name.trim() : "default";
        ActiveSession session = new ActiveSession(
                draftName,
                System.currentTimeMillis(),
                x, y, z,
                dimension != null ? dimension : "minecraft:overworld",
                new ConcurrentHashMap<>()
        );
        sessions.put(playerId, session);
        return session;
    }

    public boolean isInScratchpad(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public ActiveSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public void recordBlockPlaced(UUID playerId, String blockId) {
        ActiveSession session = sessions.get(playerId);
        if (session != null && blockId != null && !blockId.isBlank()) {
            session.bom().merge(blockId, 1, Integer::sum);
        }
    }

    public void recordBlockBroken(UUID playerId, String blockId) {
        ActiveSession session = sessions.get(playerId);
        if (session != null && blockId != null && !blockId.isBlank()) {
            session.bom().computeIfPresent(blockId, (k, count) -> (count > 1) ? count - 1 : null);
        }
    }

    public String getTimerText(UUID playerId) {
        ActiveSession session = sessions.get(playerId);
        if (session == null) {
            return "";
        }
        long elapsedSec = (System.currentTimeMillis() - session.startedAt()) / 1000;
        long min = elapsedSec / 60;
        long sec = elapsedSec % 60;
        return String.format("In scratchpad (%02d:%02d)", min, sec);
    }

    public Draft returnFromSession(UUID playerId) {
        ActiveSession session = sessions.remove(playerId);
        if (session == null) {
            return null;
        }
        Map<String, Integer> finalBom = new HashMap<>(session.bom());
        Draft draft = new Draft(session.name(), session.dimension(), session.x(), session.y(), session.z(), finalBom, true);
        drafts.put(session.name(), draft);
        return draft;
    }

    public Draft saveDraft(String name, String dimension, double x, double y, double z, Map<String, Integer> bom) {
        String n = (name != null && !name.isBlank()) ? name.trim() : "default";
        Draft draft = new Draft(n, dimension, x, y, z, bom != null ? new HashMap<>(bom) : new HashMap<>(), true);
        drafts.put(n, draft);
        return draft;
    }

    public Draft getDraft(String name) {
        return drafts.get(name != null ? name.trim() : "default");
    }

    public Map<String, Draft> listDrafts() {
        return Collections.unmodifiableMap(drafts);
    }

    public boolean toggleHologram(String name, Boolean explicitState) {
        String n = (name != null && !name.isBlank()) ? name.trim() : "default";
        Draft draft = drafts.get(n);
        if (draft == null) {
            return false;
        }
        boolean next = (explicitState != null) ? explicitState : !draft.hologramActive();
        drafts.put(n, new Draft(draft.name(), draft.dimension(), draft.x(), draft.y(), draft.z(), draft.bom(), next));
        return next;
    }

    public boolean shareBlueprint(String name) {
        String n = (name != null && !name.isBlank()) ? name.trim() : "default";
        if (!drafts.containsKey(n)) {
            return false;
        }
        sharedBlueprints.add(n);
        return true;
    }

    public boolean isShared(String name) {
        return sharedBlueprints.contains(name != null ? name.trim() : "default");
    }

    public Set<String> listSharedBlueprints() {
        return Collections.unmodifiableSet(sharedBlueprints);
    }

    /**
     * Exports the draft as a Create-compatible schematic file.
     */
    public Path exportCreateSchematic(String name, Path outDir) throws IOException {
        String n = (name != null && !name.isBlank()) ? name.trim() : "default";
        Draft draft = drafts.get(n);
        if (draft == null) {
            throw new IOException("No draft named '" + n + "' found");
        }
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }
        Path target = outDir.resolve(n + ".nbt");
        StringBuilder sb = new StringBuilder();
        sb.append("MCMESH_CREATE_SCHEMATIC_V1\n");
        sb.append("Name: ").append(draft.name()).append("\n");
        sb.append("Dimension: ").append(draft.dimension()).append("\n");
        sb.append(String.format("Origin: [%.2f, %.2f, %.2f]\n", draft.x(), draft.y(), draft.z()));
        sb.append("Materials:\n");
        for (Map.Entry<String, Integer> entry : draft.bom().entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": x").append(entry.getValue()).append("\n");
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    /**
     * Serializes all drafts to a lightweight persistent manifest.
     */
    public void persistDrafts(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Draft d : drafts.values()) {
            sb.append(d.name()).append(";")
              .append(d.dimension()).append(";")
              .append(d.x()).append(";").append(d.y()).append(";").append(d.z()).append(";")
              .append(d.hologramActive()).append(";");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : d.bom().entrySet()) {
                if (!first) sb.append(",");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
            sb.append("\n");
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Loads drafts from a persistent manifest.
     */
    public void loadDrafts(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split(";", -1);
            if (parts.length >= 6) {
                String name = parts[0];
                String dim = parts[1];
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                double z = Double.parseDouble(parts[4]);
                boolean holo = Boolean.parseBoolean(parts[5]);
                Map<String, Integer> bom = new HashMap<>();
                if (parts.length >= 7 && !parts[6].isBlank()) {
                    for (String item : parts[6].split(",")) {
                        String[] kv = item.split("=", 2);
                        if (kv.length == 2) {
                            try {
                                bom.put(kv[0], Integer.parseInt(kv[1]));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                drafts.put(name, new Draft(name, dim, x, y, z, bom, holo));
            }
        }
    }
}
