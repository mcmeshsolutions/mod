package dev.mcmesh.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Reads the first laptop battery from Linux sysfs. Null on any other platform or when nothing is readable. */
public final class BatteryProbe {
    private BatteryProbe() { }

    public static HandoverAdvisor.Battery read() {
        return read(Path.of("/sys/class/power_supply"));
    }

    public static HandoverAdvisor.Battery read(Path root) {
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : (Iterable<Path>) dirs.filter(p -> p.getFileName().toString().startsWith("BAT")).sorted()::iterator) {
                try {
                    int percent = Integer.parseInt(Files.readString(d.resolve("capacity")).trim());
                    String status = Files.readString(d.resolve("status")).trim();
                    return new HandoverAdvisor.Battery(percent, status.equalsIgnoreCase("Discharging"));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
