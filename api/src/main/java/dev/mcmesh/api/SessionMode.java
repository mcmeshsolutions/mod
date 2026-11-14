package dev.mcmesh.api;

/** What this process is doing with respect to a mesh world. */
public enum SessionMode {
    /** No mesh: a plain local game, or the mcmesh mod is not installed. */
    STANDALONE_LOCAL,
    /** This machine holds the lease and runs the world. */
    MESH_HOST,
    /** This machine plays on a world hosted elsewhere. */
    MESH_GUEST,
    /** Reserved: a standby that has the world loaded but does not tick it. */
    GHOST_READONLY
}
