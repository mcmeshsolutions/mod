package dev.mcmesh.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class NoOpTest {
    @Test
    void withoutMcmeshEveryCallIsHarmless() {
        MeshApi api = MeshApi.current();
        assertNotNull(api);
        assertEquals(SessionMode.STANDALONE_LOCAL, api.mode());
        assertNull(api.worldId());
        assertTrue(api.peers().isEmpty());
        assertFalse(api.requestTakeover());
        assertEquals(Optional.empty(), api.aux().get(MeshAuxStore.Scope.WORLD_GLOBAL, "mymod", "k"));
        assertFalse(api.aux().put(MeshAuxStore.Scope.WORLD_GLOBAL, "mymod", "k", new byte[] {1}));
        assertFalse(api.aux().remove(MeshAuxStore.Scope.TEAM, "mymod", "k"));
        api.state().register("x", () -> new byte[0], b -> { });
        assertTrue(api.revisions().revisionIds(5).isEmpty());
        assertTrue(api.revisions().readAux("w:R_1", "level.dat").isEmpty());
        MeshListener l = new MeshListener() { };
        api.addListener(l);
        api.removeListener(l);
        l.preMigrate("x");
        l.postPromote("w");
        l.roleChanged(SessionMode.STANDALONE_LOCAL, SessionMode.MESH_HOST);
    }

    @Test
    void theSameObjectComesBackEachTime() {
        assertSame(MeshApi.current(), MeshApi.current());
    }
}
