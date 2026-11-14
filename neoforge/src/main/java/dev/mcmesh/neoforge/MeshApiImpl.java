package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import dev.mcmesh.api.MeshApi;
import dev.mcmesh.api.MeshAuxStore;
import dev.mcmesh.api.MeshListener;
import dev.mcmesh.api.MeshRevisionBrowser;
import dev.mcmesh.api.MeshStateRegistry;
import dev.mcmesh.api.Peer;
import dev.mcmesh.api.SessionMode;
import dev.mcmesh.core.GatewayClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/** What mods see through {@link MeshApi}. Found by name from the API's discovery code, so keep the class and {@link #instance()} stable. */
public final class MeshApiImpl implements MeshApi {
    private static final Logger LOG = LogUtils.getLogger();
    private static final MeshApiImpl INSTANCE = new MeshApiImpl();
    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final long PEER_CACHE_MS = 30_000;

    private record Reg(Supplier<byte[]> snapshot, Consumer<byte[]> restore) { }

    private final List<MeshListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Reg> states = new ConcurrentHashMap<>();
    private volatile List<Peer> peerCache = List.of();
    private volatile long peerCacheAt;
    private volatile SessionMode lastMode = SessionMode.STANDALONE_LOCAL;

    private MeshApiImpl() { }

    public static MeshApi instance() {
        return INSTANCE;
    }

    static boolean validName(String s) {
        return s != null && NAME.matcher(s).matches() && !s.chars().allMatch(c -> c == '.');
    }

    @Override
    public SessionMode mode() {
        return MeshService.current() != null ? SessionMode.MESH_HOST : SessionMode.STANDALONE_LOCAL;
    }

    @Override
    public String worldId() {
        MeshService s = MeshService.current();
        return s == null ? null : s.worldId();
    }

    @Override
    public List<Peer> peers() {
        MeshService s = MeshService.current();
        if (s == null) {
            return List.of();
        }
        if (System.currentTimeMillis() - peerCacheAt < PEER_CACHE_MS) {
            return peerCache;
        }
        try {
            List<Peer> out = s.gateway().members(s.worldId()).stream()
                    .map(m -> new Peer(m.name(), m.role(), m.cpu(), !m.role().equals("CLIENT_ONLY"))).toList();
            peerCache = out;
            peerCacheAt = System.currentTimeMillis();
            return out;
        } catch (Exception e) {
            LOG.debug("peer list unavailable: {}", e.toString());
            return peerCache;
        }
    }

    @Override
    public boolean requestTakeover() {
        return false;
    }

    private Path root() {
        MeshService s = MeshService.current();
        return s == null ? null : s.worldRoot();
    }

    private final MeshAuxStore aux = new MeshAuxStore() {
        private Path file(Scope scope, String ns, String key) {
            Path root = root();
            if (root == null || scope == null || !validName(ns) || !validName(key)) {
                return null;
            }
            return root.resolve("mesh").resolve("aux").resolve(scope.name().toLowerCase()).resolve(ns).resolve(key);
        }

        @Override
        public Optional<byte[]> get(Scope scope, String namespace, String key) {
            Path f = file(scope, namespace, key);
            try {
                return f != null && Files.isRegularFile(f) ? Optional.of(Files.readAllBytes(f)) : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean put(Scope scope, String namespace, String key, byte[] value) {
            Path f = file(scope, namespace, key);
            if (f == null || value == null) {
                return false;
            }
            try {
                writeAtomically(f, value);
                return true;
            } catch (IOException e) {
                LOG.warn("aux write failed for {}/{}: {}", namespace, key, e.toString());
                return false;
            }
        }

        @Override
        public boolean remove(Scope scope, String namespace, String key) {
            Path f = file(scope, namespace, key);
            try {
                return f != null && Files.deleteIfExists(f);
            } catch (IOException e) {
                return false;
            }
        }
    };

    static void writeAtomically(Path f, byte[] value) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".mesh-tmp");
        Files.write(tmp, value);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public MeshAuxStore aux() {
        return aux;
    }

    @Override
    public dev.mcmesh.api.MeshLocks locks() {
        return new dev.mcmesh.api.MeshLocks() {
            @Override
            public Optional<Held> tryLock(String owner, String name, java.time.Duration ttl) {
                MeshService s = MeshService.current();
                if (s == null || !validName(owner) || !validName(name)) {
                    return Optional.empty();
                }
                try {
                    var r = s.gateway().lock(s.lease().id(), s.lease().epoch(), name, owner, (int) Math.max(1, Math.min(3600, ttl.toSeconds())));
                    return r.granted() ? Optional.of(new Held(name, r.token())) : Optional.empty();
                } catch (Exception e) {
                    LOG.debug("lock {} failed: {}", name, e.toString());
                    return Optional.empty();
                }
            }

            @Override
            public boolean unlock(String owner, String name) {
                MeshService s = MeshService.current();
                if (s == null || !validName(owner) || !validName(name)) {
                    return false;
                }
                try {
                    return s.gateway().unlock(s.lease().id(), s.lease().epoch(), name, owner);
                } catch (Exception e) {
                    LOG.debug("unlock {} failed: {}", name, e.toString());
                    return false;
                }
            }
        };
    }

    static final dev.mcmesh.core.BudgetedQueue TICKS = new dev.mcmesh.core.BudgetedQueue(System::nanoTime,
            t -> LOG.warn("a scheduled mod task failed: {}", t.toString()));

    @Override
    public dev.mcmesh.api.MeshTickScheduler scheduler() {
        return new dev.mcmesh.api.MeshTickScheduler() {
            @Override
            public void submit(Runnable work) {
                if (work != null) {
                    TICKS.submit(work);
                }
            }

            @Override
            public int queued() {
                return TICKS.size();
            }
        };
    }

    @Override
    public MeshStateRegistry state() {
        return (id, snapshot, restore) -> {
            if (validName(id) && snapshot != null && restore != null) {
                states.put(id, new Reg(snapshot, restore));
            }
        };
    }

    @Override
    public MeshRevisionBrowser revisions() {
        return new MeshRevisionBrowser() {
            @Override
            public List<String> revisionIds(int limit) {
                MeshService s = MeshService.current();
                if (s == null || limit <= 0) {
                    return List.of();
                }
                try {
                    return s.gateway().revisionIds(s.worldId(), limit);
                } catch (Exception e) {
                    return List.of();
                }
            }

            @Override
            public Optional<byte[]> readAux(String revisionId, String path) {
                MeshService s = MeshService.current();
                if (s == null || revisionId == null || path == null) {
                    return Optional.empty();
                }
                try {
                    GatewayClient gw = s.gateway();
                    for (var a : gw.listRevision(revisionId).aux()) {
                        if (a.path().equals(path)) {
                            return Optional.of(gw.blobGet(a.hash()));
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("readAux failed: {}", e.toString());
                }
                return Optional.empty();
            }
        };
    }

    @Override
    public void addListener(MeshListener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    @Override
    public void removeListener(MeshListener l) {
        listeners.remove(l);
    }

    /** Writes every registered snapshot into the world, so the next save carries it. A failing mod never blocks a save. */
    static void snapshotAll(Path root) {
        for (var e : INSTANCE.states.entrySet()) {
            try {
                byte[] data = e.getValue().snapshot().get();
                if (data != null) {
                    writeAtomically(root.resolve("mesh").resolve("state").resolve(e.getKey() + ".bin"), data);
                }
            } catch (Throwable t) {
                LOG.warn("state snapshot {} failed: {}", e.getKey(), t.toString());
            }
        }
    }

    /** Hands each registered state back to its mod on the machine that hosts now. */
    static void restoreAll(Path root) {
        for (var e : INSTANCE.states.entrySet()) {
            Path f = root.resolve("mesh").resolve("state").resolve(e.getKey() + ".bin");
            try {
                if (Files.isRegularFile(f)) {
                    e.getValue().restore().accept(Files.readAllBytes(f));
                }
            } catch (Throwable t) {
                LOG.warn("state restore {} failed: {}", e.getKey(), t.toString());
            }
        }
    }

    static void firePreMigrate(String target) {
        for (MeshListener l : INSTANCE.listeners) {
            try {
                l.preMigrate(target);
            } catch (Throwable t) {
                LOG.warn("listener failed in preMigrate: {}", t.toString());
            }
        }
    }

    static void firePostPromote(String worldId) {
        for (MeshListener l : INSTANCE.listeners) {
            try {
                l.postPromote(worldId);
            } catch (Throwable t) {
                LOG.warn("listener failed in postPromote: {}", t.toString());
            }
        }
    }

    /** Call after the mode may have changed; fires only on a real change. */
    static void checkRole() {
        SessionMode now = INSTANCE.mode();
        SessionMode before = INSTANCE.lastMode;
        if (now == before) {
            return;
        }
        INSTANCE.lastMode = now;
        for (MeshListener l : INSTANCE.listeners) {
            try {
                l.roleChanged(before, now);
            } catch (Throwable t) {
                LOG.warn("listener failed in roleChanged: {}", t.toString());
            }
        }
    }
}
