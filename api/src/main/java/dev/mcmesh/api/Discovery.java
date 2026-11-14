package dev.mcmesh.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Finds the implementation the mcmesh mod provides, or falls back to a no-op. Package-private on purpose. */
final class Discovery {
    private static final MeshApi NOOP = new NoOp();
    private static volatile MeshApi found;

    private Discovery() { }

    static MeshApi get() {
        MeshApi f = found;
        if (f != null) {
            return f;
        }
        try {
            Class<?> impl = Class.forName("dev.mcmesh.neoforge.MeshApiImpl", true, Discovery.class.getClassLoader());
            Object o = impl.getMethod("instance").invoke(null);
            if (o instanceof MeshApi api) {
                found = api;
                return api;
            }
        } catch (Throwable ignored) {
            // mcmesh is not installed, or an incompatible build: stay a no-op
        }
        return NOOP;
    }

    /** Visible for tests. */
    static MeshApi noop() {
        return NOOP;
    }

    private static final class NoOp implements MeshApi {
        private final MeshAuxStore aux = new MeshAuxStore() {
            public Optional<byte[]> get(Scope scope, String namespace, String key) { return Optional.empty(); }
            public boolean put(Scope scope, String namespace, String key, byte[] value) { return false; }
            public boolean remove(Scope scope, String namespace, String key) { return false; }
        };
        private final MeshStateRegistry state = new MeshStateRegistry() {
            public void register(String id, Supplier<byte[]> snapshot, Consumer<byte[]> restore) { }
        };
        private final MeshRevisionBrowser revisions = new MeshRevisionBrowser() {
            public List<String> revisionIds(int limit) { return List.of(); }
            public Optional<byte[]> readAux(String revisionId, String path) { return Optional.empty(); }
        };

        private final MeshLocks locks = new MeshLocks() {
            public Optional<Held> tryLock(String owner, String name, java.time.Duration ttl) { return Optional.empty(); }
            public boolean unlock(String owner, String name) { return false; }
        };
        private final MeshTickScheduler scheduler = new MeshTickScheduler() {
            public void submit(Runnable work) { }
            public int queued() { return 0; }
        };

        public SessionMode mode() { return SessionMode.STANDALONE_LOCAL; }
        public String worldId() { return null; }
        public List<Peer> peers() { return List.of(); }
        public boolean requestTakeover() { return false; }
        public MeshAuxStore aux() { return aux; }
        public MeshLocks locks() { return locks; }
        public MeshTickScheduler scheduler() { return scheduler; }
        public MeshStateRegistry state() { return state; }
        public MeshRevisionBrowser revisions() { return revisions; }
        public void addListener(MeshListener listener) { }
        public void removeListener(MeshListener listener) { }
    }
}
