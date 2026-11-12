package dev.mcmesh.neoforge;

import dev.mcmesh.core.GatewayClient;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/** Tails latest.log to the gateway once this machine has a mesh login. */
final class LogShipper {
    private static final int FIRST = 300_000;
    private static final int CHUNK = 512_000;

    private LogShipper() { }

    static void start() {
        Thread t = new Thread(LogShipper::run, "mesh-logs");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
        long off = -1;
        while (true) {
            try {
                Thread.sleep(5000);
                var id = MeshAuth.peek();
                if (id == null || !MeshConfig.SHIP_LOGS.get() || !Files.exists(log)) {
                    continue;
                }
                long len = Files.size(log);
                if (off < 0 || len < off) {
                    off = off < 0 ? Math.max(0, len - FIRST) : 0;
                }
                if (len == off) {
                    continue;
                }
                int n = (int) Math.min(CHUNK, len - off);
                byte[] buf = new byte[n];
                try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
                    f.seek(off);
                    f.readFully(buf);
                }
                new GatewayClient(MeshConfig.API_URL.get(), id.token()).logs(buf);
                off += n;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // gateway unreachable or log rotated mid-read, retry next round
            }
        }
    }
}
