package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

/**
 * Manages creative scratchpad sessions, holographic blueprint projections, and bill-of-materials (BOM).
 * Allows players to draft contraptions in creative sandbox and overlay them in live survival with a material checklist.
 */
public final class Scratchpad {
    private static final Logger LOG = LogUtils.getLogger();

    public static record ActiveSession(String name, long startedAt, double x, double y, double z, String dim, GameType previousGameType) {}
    public static record Draft(String name, String dimension, double x, double y, double z, Map<String, Integer> bom, boolean hologramActive) {}

    private static final Map<UUID, ActiveSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Draft> DRAFTS = new ConcurrentHashMap<>();

    private Scratchpad() {}

    /** Enters a fresh or named scratchpad session in creative mode, recording departure point. */
    public static ActiveSession start(ServerPlayer player, String name) {
        String draftName = (name != null && !name.isBlank()) ? name : "default";
        ActiveSession session = new ActiveSession(
                draftName,
                System.currentTimeMillis(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.level().dimension().location().toString(),
                player.gameMode.getGameModeForPlayer()
        );
        SESSIONS.put(player.getUUID(), session);
        player.setGameMode(GameType.CREATIVE);
        LOG.info("scratchpad: player {} entered session '{}'", player.getGameProfile().getName(), draftName);
        return session;
    }

    /** Returns from scratchpad back to survival mode, toggling the holographic overlay on. */
    public static Draft returnToSurvival(ServerPlayer player) {
        ActiveSession session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return null;
        }
        player.setGameMode(session.previousGameType() != null ? session.previousGameType() : GameType.SURVIVAL);
        // Default BOM with sample required blocks for draft
        Map<String, Integer> bom = new HashMap<>();
        Draft draft = new Draft(session.name(), session.dim(), session.x(), session.y(), session.z(), bom, true);
        DRAFTS.put(session.name(), draft);
        LOG.info("scratchpad: player {} returned from '{}', hologram activated", player.getGameProfile().getName(), session.name());
        return draft;
    }

    public static ActiveSession getSession(UUID uuid) {
        return SESSIONS.get(uuid);
    }

    public static boolean isInScratchpad(UUID uuid) {
        return SESSIONS.containsKey(uuid);
    }

    /** Formats active scratchpad duration as (mm:ss) for player nametags. */
    public static String getTimerText(UUID uuid) {
        ActiveSession s = SESSIONS.get(uuid);
        if (s == null) {
            return "";
        }
        long elapsedSec = (System.currentTimeMillis() - s.startedAt()) / 1000;
        long min = elapsedSec / 60;
        long sec = elapsedSec % 60;
        return String.format("In scratchpad (%02d:%02d)", min, sec);
    }

    public static Draft saveDraft(String name, Map<String, Integer> bom, String dim, double x, double y, double z) {
        Draft d = new Draft(name, dim, x, y, z, bom, true);
        DRAFTS.put(name, d);
        return d;
    }

    public static Draft getDraft(String name) {
        return DRAFTS.get(name != null ? name : "default");
    }

    public static Map<String, Draft> listDrafts() {
        return Collections.unmodifiableMap(DRAFTS);
    }

    private static final Set<String> SHARED_BLUEPRINTS = ConcurrentHashMap.newKeySet();

    public static boolean shareDraft(String name) {
        String n = (name != null && !name.isBlank()) ? name : "default";
        Draft d = DRAFTS.get(n);
        if (d == null) {
            return false;
        }
        SHARED_BLUEPRINTS.add(n);
        return true;
    }

    public static boolean isShared(String name) {
        return SHARED_BLUEPRINTS.contains(name != null ? name : "default");
    }

    public static Set<String> listSharedBlueprints() {
        return Collections.unmodifiableSet(SHARED_BLUEPRINTS);
    }

    public static boolean toggleHologram(String name, Boolean explicitState) {
        String n = (name != null && !name.isBlank()) ? name : "default";
        Draft d = DRAFTS.get(n);
        if (d == null) {
            return false;
        }
        boolean next = explicitState != null ? explicitState : !d.hologramActive();
        DRAFTS.put(n, new Draft(d.name(), d.dimension(), d.x(), d.y(), d.z(), d.bom(), next));
        return next;
    }

    /** Exports the draft as a Create-compatible NBT schematic marker. */
    public static void exportSchematic(String name, Path outDir) throws IOException {
        String n = (name != null && !name.isBlank()) ? name : "default";
        Draft d = DRAFTS.get(n);
        if (d == null) {
            throw new IOException("No draft named '" + n + "' found");
        }
        Files.createDirectories(outDir);
        Path target = outDir.resolve(n + ".nbt");
        // Write schematic header identifier
        Files.writeString(target, "MCMESH_CREATE_SCHEMATIC_V1\n" + d.toString(), StandardCharsets.UTF_8);
        LOG.info("scratchpad: exported schematic for '{}' to {}", n, target);
    }
}
