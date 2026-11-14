package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import dev.mcmesh.api.MeshApi;
import dev.mcmesh.api.MeshAuxStore;
import dev.mcmesh.api.MeshListener;
import dev.mcmesh.api.SessionMode;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;

/** Exercises the public mod API from inside the game when started with -Dmcmesh.apiselftest. Used by scripts/headless-e2e.sh. */
final class MeshApiSelfTest {
    private static final Logger LOG = LogUtils.getLogger();

    private MeshApiSelfTest() { }

    static void register() {
        if (System.getProperty("mcmesh.apiselftest") == null) {
            return;
        }
        MeshApi api = MeshApi.current();
        api.state().register("selftest", () -> ("state-of-" + api.worldId()).getBytes(StandardCharsets.UTF_8),
                data -> LOG.info("selftest restored: {}", new String(data, StandardCharsets.UTF_8)));
        api.addListener(new MeshListener() {
            @Override
            public void postPromote(String worldId) {
                boolean put = api.aux().put(MeshAuxStore.Scope.WORLD_GLOBAL, "selftest", "hello", "hello".getBytes(StandardCharsets.UTF_8));
                String back = api.aux().get(MeshAuxStore.Scope.WORLD_GLOBAL, "selftest", "hello").map(b -> new String(b, StandardCharsets.UTF_8)).orElse("none");
                LOG.info("selftest postPromote {} aux put {} read back {}", worldId, put, back);
                LOG.info("selftest invalid name refused: {}", !api.aux().put(MeshAuxStore.Scope.WORLD_GLOBAL, "../x", "k", new byte[] {1}));
                Thread.ofVirtual().start(() -> {
                    var ids = api.revisions().revisionIds(5);
                    String old = ids.isEmpty() ? "none" : api.revisions().readAux(ids.get(0), "mesh/aux/world_global/selftest/hello")
                            .map(b -> new String(b, StandardCharsets.UTF_8)).orElse("missing");
                    LOG.info("selftest history: {} revisions, newest holds aux {}, peers {}", ids.size(), old, api.peers().size());
                });
            }

            @Override
            public void roleChanged(SessionMode from, SessionMode to) {
                LOG.info("selftest roleChanged {} -> {}", from, to);
            }

            @Override
            public void preMigrate(String target) {
                LOG.info("selftest preMigrate to {}", target);
            }
        });
        LOG.info("selftest registered, mode {}", api.mode());
    }
}
