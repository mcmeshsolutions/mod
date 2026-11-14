package dev.mcmesh.api;

import java.util.Optional;

/**
 * A small key-value store that travels with the world: values are files under the world folder, so they reach the gateway with
 * every save and every new host has them. Keys and namespaces are 1 to 64 characters of a-z 0-9 . _ -.
 */
public interface MeshAuxStore {
    enum Scope { WORLD_GLOBAL, TEAM, PLAYER_PRIVATE }

    Optional<byte[]> get(Scope scope, String namespace, String key);

    /** False when there is no mesh world, the name is invalid or the write failed. */
    boolean put(Scope scope, String namespace, String key, byte[] value);

    boolean remove(Scope scope, String namespace, String key);
}
