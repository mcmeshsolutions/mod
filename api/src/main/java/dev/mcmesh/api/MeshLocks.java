package dev.mcmesh.api;

import java.time.Duration;
import java.util.Optional;

/**
 * Named locks kept by the gateway and fenced by the host epoch, so a lock held by a host that died is void for the next host.
 * The fence token grows every time a lock changes hands: a service that is fed work by two hosts should refuse the smaller token.
 * Names and owners are 1 to 64 characters of a-z 0-9 . _ -; use your mod id as the owner. These calls block on the network, never
 * call them on the server thread.
 */
public interface MeshLocks {
    record Held(String name, long fenceToken) { }

    /** Takes the lock or renews it when this owner already holds it. Empty when someone else holds it, this machine is not the host, or the gateway cannot be reached. */
    Optional<Held> tryLock(String owner, String name, Duration ttl);

    /** True when this owner held the lock and it is free now. */
    boolean unlock(String owner, String name);
}
