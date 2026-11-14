package dev.mcmesh.api;

/**
 * Runs work on the server thread, a little per tick, so that background chores (index rebuilds, statistics, cleanup) never cost a
 * long tick. Work runs in submission order. Each tick gets a time budget that shrinks when the host is struggling and drops to zero
 * while the server is shedding load heavily, so chores wait instead of making things worse. A task that throws is logged and skipped.
 */
public interface MeshTickScheduler {
    /** Queues work; it runs on the server thread at some later tick. Never runs the work on the calling thread. */
    void submit(Runnable work);

    /** Tasks waiting. */
    int queued();
}
