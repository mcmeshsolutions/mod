package dev.mcmesh.api;

/** Lifecycle events. Every method has an empty default, so implement only the ones you need. Handlers must be quick and must not throw. */
public interface MeshListener {
    /** The gateway asked this host to give the world away; the server stops shortly after. Last chance to write state. */
    default void preMigrate(String targetPlayer) { }

    /** This machine just became the host of {@code worldId}. */
    default void postPromote(String worldId) { }

    /** The session mode changed. */
    default void roleChanged(SessionMode from, SessionMode to) { }
}
