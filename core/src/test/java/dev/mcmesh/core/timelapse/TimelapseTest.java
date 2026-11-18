package dev.mcmesh.core.timelapse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class TimelapseTest {

    @Test
    void cameraPathInterpolation() {
        CameraPath path = new CameraPath();
        assertEquals(0, path.size());
        assertEquals(0, path.totalDurationMs());

        CameraWaypoint wp1 = CameraWaypoint.at("start", 0, 64, 0, 0, 0);
        CameraWaypoint wp2 = CameraWaypoint.at("mid", 100, 80, 50, 90, 20);
        CameraWaypoint wp3 = CameraWaypoint.at("end", 200, 100, 100, 180, 0);

        path.addWaypoint(wp1);
        path.addWaypoint(wp2);
        path.addWaypoint(wp3);

        assertEquals(3, path.size());
        assertEquals(10000, path.totalDurationMs()); // 2 legs * 5000ms

        // Start
        CameraWaypoint atStart = path.interpolate(0);
        assertEquals(0.0, atStart.x(), 0.01);
        assertEquals(64.0, atStart.y(), 0.01);

        // Halfway along first leg (2500ms)
        CameraWaypoint atMid1 = path.interpolate(2500);
        assertEquals(50.0, atMid1.x(), 1.0); // smooth step halfway
        assertEquals(72.0, atMid1.y(), 1.0);
        assertEquals(45.0, atMid1.yaw(), 2.0);

        // End of second leg (10000ms)
        CameraWaypoint atEnd = path.interpolate(10000);
        assertEquals(200.0, atEnd.x(), 0.01);
        assertEquals(100.0, atEnd.y(), 0.01);
        assertEquals(180.0, atEnd.yaw(), 0.01);

        path.clear();
        assertEquals(0, path.size());
    }

    @Test
    void exportReplayModArchive(@TempDir Path tempDir) throws IOException {
        List<TimelapseExporter.RevisionMilestone> milestones = List.of(
                new TimelapseExporter.RevisionMilestone("rev_genesis", 0, 1000, "alice", "Genesis world creation", 64),
                new TimelapseExporter.RevisionMilestone("rev_base_foundations", 1, 1500, "bob", "Main base foundations and storage", 12),
                new TimelapseExporter.RevisionMilestone("rev_create_factory", 2, 2000, "alice", "Automated Create kinetic press line", 35)
        );

        File mcprFile = TimelapseExporter.export("test_world", milestones, 2000, tempDir.toFile());

        assertTrue(mcprFile.exists(), "ReplayMod file should exist");
        assertTrue(mcprFile.getName().endsWith(".mcpr"));
        assertTrue(mcprFile.length() > 0, "ReplayMod file should not be empty");

        // Verify ZIP entries inside .mcpr
        try (ZipFile zip = new ZipFile(mcprFile)) {
            assertNotNull(zip.getEntry("metaData.json"), "Must contain metaData.json");
            assertNotNull(zip.getEntry("recording.tmcpr"), "Must contain recording.tmcpr");
            assertNotNull(zip.getEntry("timelapse_markers.json"), "Must contain timelapse_markers.json");
        }
    }
}
