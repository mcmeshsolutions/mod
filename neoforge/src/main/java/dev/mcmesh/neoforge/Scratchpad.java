package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.SandboxEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

/**
 * Manages creative scratchpad sessions, holographic blueprint projections, and bill-of-materials (BOM).
 * Allows players to draft contraptions in creative sandbox and overlay them in live survival with a material checklist,
 * working seamlessly both in online mesh worlds and offline singleplayer sandbox mode.
 */
public final class Scratchpad {
    private static final Logger LOG = LogUtils.getLogger();

    public static record ActiveSession(String name, long startedAt, double x, double y, double z, String dim, GameType previousGameType) {}
    public static record Draft(String name, String dimension, double x, double y, double z, Map<String, Integer> bom, boolean hologramActive) {}

    private static final SandboxEngine ENGINE = new SandboxEngine();
    private static final Map<UUID, GameType> PREVIOUS_GAMETYPES = new ConcurrentHashMap<>();

    private Scratchpad() {}

    public static SandboxEngine engine() {
        return ENGINE;
    }

    /** Enters a fresh or named scratchpad session in creative mode, recording departure point. */
    public static ActiveSession start(ServerPlayer player, String name) {
        String draftName = (name != null && !name.isBlank()) ? name : "default";
        GameType prev = player.gameMode.getGameModeForPlayer();
        PREVIOUS_GAMETYPES.put(player.getUUID(), prev != null ? prev : GameType.SURVIVAL);

        var coreSession = ENGINE.startSession(
                player.getUUID(),
                draftName,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.level().dimension().location().toString()
        );

        player.setGameMode(GameType.CREATIVE);
        LOG.info("scratchpad: player {} entered session '{}' (offlineSandbox={})",
                player.getGameProfile().getName(), draftName, ENGINE.isOfflineSandboxActive());

        return new ActiveSession(
                coreSession.name(),
                coreSession.startedAt(),
                coreSession.x(),
                coreSession.y(),
                coreSession.z(),
                coreSession.dimension(),
                prev
        );
    }

    /** Records a block placement into the active scratchpad session BOM. */
    public static void recordBlockPlaced(UUID playerId, String blockId) {
        ENGINE.recordBlockPlaced(playerId, blockId);
    }

    /** Records a block break into the active scratchpad session BOM. */
    public static void recordBlockBroken(UUID playerId, String blockId) {
        ENGINE.recordBlockBroken(playerId, blockId);
    }

    /** Returns from scratchpad back to survival mode, toggling the holographic overlay on. */
    public static Draft returnToSurvival(ServerPlayer player) {
        GameType prev = PREVIOUS_GAMETYPES.remove(player.getUUID());
        var coreDraft = ENGINE.returnFromSession(player.getUUID());
        if (coreDraft == null) {
            return null;
        }

        player.setGameMode(prev != null ? prev : GameType.SURVIVAL);
        LOG.info("scratchpad: player {} returned from '{}', hologram activated",
                player.getGameProfile().getName(), coreDraft.name());

        return new Draft(
                coreDraft.name(),
                coreDraft.dimension(),
                coreDraft.x(),
                coreDraft.y(),
                coreDraft.z(),
                coreDraft.bom(),
                coreDraft.hologramActive()
        );
    }

    public static ActiveSession getSession(UUID uuid) {
        var s = ENGINE.getSession(uuid);
        if (s == null) return null;
        return new ActiveSession(s.name(), s.startedAt(), s.x(), s.y(), s.z(), s.dimension(), PREVIOUS_GAMETYPES.get(uuid));
    }

    public static boolean isInScratchpad(UUID uuid) {
        return ENGINE.isInScratchpad(uuid);
    }

    /** Formats active scratchpad duration as (mm:ss) for player nametags. */
    public static String getTimerText(UUID uuid) {
        return ENGINE.getTimerText(uuid);
    }

    public static Draft saveDraft(String name, Map<String, Integer> bom, String dim, double x, double y, double z) {
        var d = ENGINE.saveDraft(name, dim, x, y, z, bom);
        return new Draft(d.name(), d.dimension(), d.x(), d.y(), d.z(), d.bom(), d.hologramActive());
    }

    public static Draft getDraft(String name) {
        var d = ENGINE.getDraft(name);
        if (d == null) return null;
        return new Draft(d.name(), d.dimension(), d.x(), d.y(), d.z(), d.bom(), d.hologramActive());
    }

    public static Map<String, Draft> listDrafts() {
        Map<String, Draft> out = new HashMap<>();
        for (var e : ENGINE.listDrafts().entrySet()) {
            var d = e.getValue();
            out.put(e.getKey(), new Draft(d.name(), d.dimension(), d.x(), d.y(), d.z(), d.bom(), d.hologramActive()));
        }
        return Collections.unmodifiableMap(out);
    }

    public static boolean shareDraft(String name) {
        return ENGINE.shareBlueprint(name);
    }

    public static boolean isShared(String name) {
        return ENGINE.isShared(name);
    }

    public static Set<String> listSharedBlueprints() {
        return ENGINE.listSharedBlueprints();
    }

    public static boolean toggleHologram(String name, Boolean explicitState) {
        return ENGINE.toggleHologram(name, explicitState);
    }

    /** Exports the draft as a Create-compatible NBT schematic marker. */
    public static void exportSchematic(String name, Path outDir) throws IOException {
        Path target = ENGINE.exportCreateSchematic(name, outDir);
        LOG.info("scratchpad: exported schematic for '{}' to {}", name, target);
    }
}
