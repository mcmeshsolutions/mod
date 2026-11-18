package dev.mcmesh.core.timelapse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Camera path sequencer and interpolator for cinematic time-lapses.
 */
public class CameraPath {
    private final List<CameraWaypoint> waypoints = new ArrayList<>();

    public synchronized void addWaypoint(CameraWaypoint waypoint) {
        if (waypoint != null) {
            waypoints.add(waypoint);
        }
    }

    public synchronized void clear() {
        waypoints.clear();
    }

    public synchronized List<CameraWaypoint> getWaypoints() {
        return Collections.unmodifiableList(new ArrayList<>(waypoints));
    }

    public synchronized int size() {
        return waypoints.size();
    }

    public synchronized long totalDurationMs() {
        if (waypoints.size() < 2) {
            return 0;
        }
        long total = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            total += waypoints.get(i).durationMs();
        }
        return total;
    }

    /**
     * Interpolates camera state at elapsed time offset from start.
     */
    public synchronized CameraWaypoint interpolate(long elapsedMs) {
        if (waypoints.isEmpty()) {
            return CameraWaypoint.at("origin", 0, 64, 0, 0, 0);
        }
        if (waypoints.size() == 1 || elapsedMs <= 0) {
            return waypoints.get(0);
        }

        long accum = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            CameraWaypoint prev = waypoints.get(i - 1);
            CameraWaypoint next = waypoints.get(i);
            long leg = next.durationMs();

            if (elapsedMs <= accum + leg) {
                double t = (double) (elapsedMs - accum) / (double) leg;
                // Smooth step
                t = t * t * (3 - 2 * t);
                return lerp(prev, next, t);
            }
            accum += leg;
        }

        return waypoints.get(waypoints.size() - 1);
    }

    private static CameraWaypoint lerp(CameraWaypoint a, CameraWaypoint b, double t) {
        double x = a.x() + (b.x() - a.x()) * t;
        double y = a.y() + (b.y() - a.y()) * t;
        double z = a.z() + (b.z() - a.z()) * t;

        float yaw = (float) (a.yaw() + diffAngle(b.yaw(), a.yaw()) * t);
        float pitch = (float) (a.pitch() + (b.pitch() - a.pitch()) * t);
        float roll = (float) (a.roll() + (b.roll() - a.roll()) * t);
        float fov = (float) (a.fov() + (b.fov() - a.fov()) * t);

        return new CameraWaypoint("interpolated", x, y, z, yaw, pitch, roll, fov, 0);
    }

    private static double diffAngle(double target, double source) {
        double diff = (target - source) % 360.0;
        if (diff > 180.0) {
            diff -= 360.0;
        } else if (diff < -180.0) {
            diff += 360.0;
        }
        return diff;
    }
}
