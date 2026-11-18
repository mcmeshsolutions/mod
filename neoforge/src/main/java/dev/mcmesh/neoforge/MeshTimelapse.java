package dev.mcmesh.neoforge;

import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.timelapse.CameraPath;
import dev.mcmesh.core.timelapse.CameraWaypoint;
import dev.mcmesh.core.timelapse.TimelapseExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages in-game camera rig playback and ReplayMod timelapse synthesis.
 */
public class MeshTimelapse {
    private static final Logger LOG = LoggerFactory.getLogger(MeshTimelapse.class);

    private static final CameraPath ACTIVE_PATH = new CameraPath();
    private static final Map<String, CameraWaypoint> SAVED_WAYPOINTS = new ConcurrentHashMap<>();

    private static volatile boolean isPlaying = false;
    private static volatile long playbackStartMs = 0;

    public static CameraWaypoint recordWaypoint(String name, double x, double y, double z, float yaw, float pitch) {
        String n = (name != null && !name.isBlank()) ? name : "wp_" + (SAVED_WAYPOINTS.size() + 1);
        CameraWaypoint wp = CameraWaypoint.at(n, x, y, z, yaw, pitch);
        SAVED_WAYPOINTS.put(n, wp);
        LOG.info("timelapse: recorded waypoint '{}' at [{}, {}, {}]", n, x, y, z);
        return wp;
    }

    public static boolean addWaypointToPath(String name, long durationMs) {
        CameraWaypoint wp = SAVED_WAYPOINTS.get(name);
        if (wp == null) {
            return false;
        }
        long dur = durationMs > 0 ? durationMs : wp.durationMs();
        CameraWaypoint leg = new CameraWaypoint(wp.name(), wp.x(), wp.y(), wp.z(), wp.yaw(), wp.pitch(), wp.roll(), wp.fov(), dur);
        ACTIVE_PATH.addWaypoint(leg);
        LOG.info("timelapse: added leg '{}' ({} ms) to active path", name, dur);
        return true;
    }

    public static void clearPath() {
        ACTIVE_PATH.clear();
        stopPlayback();
    }

    public static CameraPath getActivePath() {
        return ACTIVE_PATH;
    }

    public static Map<String, CameraWaypoint> getSavedWaypoints() {
        return Collections.unmodifiableMap(SAVED_WAYPOINTS);
    }

    public static boolean startPlayback() {
        if (ACTIVE_PATH.size() < 2) {
            return false;
        }
        isPlaying = true;
        playbackStartMs = System.currentTimeMillis();
        LOG.info("timelapse: camera playback started (total duration {} ms)", ACTIVE_PATH.totalDurationMs());
        return true;
    }

    public static void stopPlayback() {
        isPlaying = false;
        playbackStartMs = 0;
    }

    public static boolean isPlaying() {
        if (!isPlaying) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - playbackStartMs;
        if (elapsed > ACTIVE_PATH.totalDurationMs()) {
            stopPlayback();
            return false;
        }
        return true;
    }

    public static CameraWaypoint getCurrentCamera() {
        if (!isPlaying()) {
            return null;
        }
        long elapsed = System.currentTimeMillis() - playbackStartMs;
        return ACTIVE_PATH.interpolate(elapsed);
    }

    /**
     * Synthesizes the world's revision history into a ReplayMod (.mcpr) file.
     */
    public static void exportReplayMod(
            String worldId,
            Path outputDir,
            long intervalMs,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        Thread.ofVirtual().name("mesh-timelapse-export").start(() -> {
            try {
                GatewayClient gw = new GatewayClient(MeshConfig.API_URL.get(), MeshAuth.token());
                var revResult = gw.revisions(worldId, 200);

                List<TimelapseExporter.RevisionMilestone> milestones = new ArrayList<>();
                var revList = new ArrayList<>(revResult.revisions());
                // Sort ascending in timeline order
                revList.sort((a, b) -> Long.compare(a.seq(), b.seq()));

                for (var r : revList) {
                    milestones.add(new TimelapseExporter.RevisionMilestone(
                            r.id(),
                            r.seq(),
                            r.at(),
                            r.by() != null ? r.by() : "system",
                            r.kind(),
                            r.chunks()
                    ));
                }

                if (milestones.isEmpty()) {
                    milestones.add(new TimelapseExporter.RevisionMilestone(
                            revResult.head(),
                            0,
                            System.currentTimeMillis() / 1000,
                            "system",
                            "HEAD",
                            0
                    ));
                }

                File mcpr = TimelapseExporter.export(worldId, milestones, intervalMs, outputDir.toFile());
                LOG.info("timelapse: synthesized ReplayMod file '{}' with {} milestones", mcpr.getAbsolutePath(), milestones.size());
                onSuccess.accept("Exported ReplayMod file: " + mcpr.getName() + " (" + (mcpr.length() / 1024) + " KB, " + milestones.size() + " revisions)");
            } catch (Exception ex) {
                LOG.error("timelapse: export failed", ex);
                onFailure.accept("Timelapse export failed: " + ex.getMessage());
            }
        });
    }
}
