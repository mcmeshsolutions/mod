package dev.mcmesh.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/** Tasks that run a few at a time under a per-tick time budget. At least one task runs per call when the budget is above zero. */
public final class BudgetedQueue {
    public interface Failure {
        void failed(Throwable t);
    }

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final LongSupplier nanos;
    private final Failure onFailure;

    public BudgetedQueue(LongSupplier nanos, Failure onFailure) {
        this.nanos = nanos;
        this.onFailure = onFailure;
    }

    public void submit(Runnable r) {
        tasks.add(r);
    }

    public int size() {
        return tasks.size();
    }

    /** Microseconds of work allowed per tick for a load-shed level (0 healthy to 3 worst; -1 unknown is treated as healthy). */
    public static long budgetMicros(int shedLevel) {
        return switch (shedLevel) {
            case 1 -> 500;
            case 2, 3 -> 0;
            default -> 2000;
        };
    }

    /** Runs queued tasks until the budget is used up. Returns how many ran. A task that throws counts as run. */
    public int run(long budgetMicros) {
        if (budgetMicros <= 0) {
            return 0;
        }
        long end = nanos.getAsLong() + budgetMicros * 1000;
        int ran = 0;
        Runnable r;
        while ((r = tasks.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                onFailure.failed(t);
            }
            ran++;
            if (nanos.getAsLong() >= end) {
                break;
            }
        }
        return ran;
    }
}
