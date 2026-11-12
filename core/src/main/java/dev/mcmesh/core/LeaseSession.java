package dev.mcmesh.core;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Keeps a host lease alive and fences the host when the gateway can no longer be trusted to still honour it.
 * The fence clock runs from the time the last acknowledged heartbeat was sent, never from when the ack arrived.
 */
public final class LeaseSession implements AutoCloseable {
    public interface Beat {
        Heartbeat send(String lease, long epoch) throws IOException, GatewayException;
    }

    private final Lease lease;
    private final Beat beat;
    private final LongSupplier nanos;
    private final Consumer<String> onFence;
    private final Consumer<Heartbeat> onDirectives;
    private long lastAckedSend;
    private long lastSent;
    private boolean fenced;
    private Thread thread;

    public LeaseSession(Lease lease, Beat beat, LongSupplier nanos, Consumer<String> onFence, Consumer<Heartbeat> onDirectives) {
        this.lease = lease;
        this.beat = beat;
        this.nanos = nanos;
        this.onFence = onFence;
        this.onDirectives = onDirectives;
        long now = nanos.getAsLong();
        this.lastAckedSend = now;
        this.lastSent = Long.MIN_VALUE;
    }

    public static LeaseSession over(GatewayClient gw, Lease lease, Consumer<String> onFence, Consumer<Heartbeat> onDirectives) {
        return new LeaseSession(lease, gw::heartbeat, System::nanoTime, onFence, onDirectives);
    }

    public synchronized boolean fenced() {
        return fenced;
    }

    /** One scheduler step: heartbeat when due, then fence if the last ack is too old. */
    synchronized void tick() {
        if (fenced) {
            return;
        }
        long now = nanos.getAsLong();
        if (lastSent == Long.MIN_VALUE || now - lastSent >= lease.heartbeatS() * 1_000_000_000L) {
            lastSent = now;
            try {
                Heartbeat h = beat.send(lease.id(), lease.epoch());
                lastAckedSend = now;
                if (!h.migrateTargets().isEmpty()) {
                    onDirectives.accept(h);
                }
            } catch (GatewayException e) {
                if (e.leaseLost()) {
                    fence("lease lost: " + e.code);
                    return;
                }
            } catch (IOException e) {
                // unreachable gateway, the fence timer below decides
            }
        }
        if (now - lastAckedSend > lease.fenceAfterS() * 1_000_000_000L) {
            fence("no heartbeat ack for " + lease.fenceAfterS() + "s");
        }
    }

    private void fence(String why) {
        fenced = true;
        onFence.accept(why);
    }

    public void start() {
        thread = Thread.ofVirtual().name("mesh-lease").start(() -> {
            try {
                while (!fenced()) {
                    try {
                        tick();
                    } catch (RuntimeException e) {
                        System.getLogger("mcmesh").log(System.Logger.Level.ERROR, "lease tick failed", e);
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void close() {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
