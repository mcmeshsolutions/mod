package dev.mcmesh.neoforge;

import dev.mcmesh.core.GatewayClient;
import java.nio.file.Path;

/** Headless: downloads a revision into a folder, used to check an import round-trips. */
public final class ExportTool {
    private ExportTool() { }

    public static void main(String[] a) throws Exception {
        WorldSync.sync(new GatewayClient(a[0], a[1]), Path.of(a[4]), a[3]);
    }
}
