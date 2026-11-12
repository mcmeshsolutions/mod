package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;
import org.slf4j.Logger;

/** Things whose tick threw while this machine hosts. They are skipped, never removed, so their saved data stays intact. */
public final class Quarantine {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<Object> frozen = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private Quarantine() { }

    public static boolean hosting() {
        return MeshBoot.held() != null || MeshService.current() != null;
    }

    public static boolean frozen(Object o) {
        return frozen.contains(o);
    }

    public static void freeze(Object o, String what, Throwable t) {
        if (frozen.add(o)) {
            LOG.error("quarantined {} after a tick failure, it stays in the world but stops ticking until its chunk reloads", what, t);
        }
    }
}
