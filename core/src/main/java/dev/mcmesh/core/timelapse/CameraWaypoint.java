package dev.mcmesh.core.timelapse;

/**
 * An immutable camera waypoint in 3D space with orientation and field-of-view.
 */
public record CameraWaypoint(
        String name,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float roll,
        float fov,
        long durationMs
) {
    public CameraWaypoint {
        if (name == null || name.isBlank()) {
            name = "wp";
        }
        if (durationMs <= 0) {
            durationMs = 5000; // 5 seconds default per leg
        }
    }

    public static CameraWaypoint at(String name, double x, double y, double z, float yaw, float pitch) {
        return new CameraWaypoint(name, x, y, z, yaw, pitch, 0.0f, 70.0f, 5000);
    }
}
