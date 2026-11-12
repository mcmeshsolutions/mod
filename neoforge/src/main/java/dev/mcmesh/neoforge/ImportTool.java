package dev.mcmesh.neoforge;

import dev.mcmesh.core.ChunkUploader;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.JoinResult;
import dev.mcmesh.core.LeaseSession;
import java.nio.file.Path;

/** Headless one-shot: uploads a local world folder as the first revision of an existing, empty shared world. */
public final class ImportTool {
    private ImportTool() { }

    public static void main(String[] a) throws Exception {
        if (a.length != 4) {
            System.err.println("usage: ImportTool <api url> <token> <world id> <world folder>");
            System.exit(2);
        }
        Path root = Path.of(a[3]);
        GatewayClient gw = new GatewayClient(a[0], a[1]);
        if (!(gw.join(a[2], 0, 0, true) instanceof JoinResult.Host h)) {
            throw new IllegalStateException("not granted the host role, is the world already hosted?");
        }
        var existing = gw.listRevision(h.lease().baseRev());
        if (!existing.chunks().isEmpty() || !existing.aux().isEmpty()) {
            System.err.println("world already has revision " + h.lease().baseRev() + ", refusing to import over it");
            gw.release(h.lease().id(), h.lease().epoch(), false);
            System.exit(1);
        }
        LeaseSession s = LeaseSession.over(gw, h.lease(), why -> {
            System.err.println("lease fenced: " + why);
            System.exit(1);
        }, hb -> { });
        s.start();
        try {
            ChunkUploader up = new ChunkUploader(gw, h.lease());
            int n = WorldSeeder.seed(root, up, null);
            new AuxSync().scan(root, up);
            up.flush(true);
            String rev = gw.commit(h.lease().id(), h.lease().epoch(), h.lease().baseRev(), "SHUTDOWN", true).id();
            System.out.println("imported " + n + " chunk entries, revision " + rev);
        } catch (Exception e) {
            try {
                gw.release(h.lease().id(), h.lease().epoch(), false);
            } catch (Exception ignored) { }
            throw e;
        } finally {
            s.close();
        }
    }
}
