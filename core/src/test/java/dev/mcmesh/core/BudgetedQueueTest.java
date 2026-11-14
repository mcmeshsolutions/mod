package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BudgetedQueueTest {
    @Test
    void stopsWhenTheBudgetIsUsedAndKeepsOrder() {
        long[] clock = {0};
        List<Integer> order = new ArrayList<>();
        BudgetedQueue q = new BudgetedQueue(() -> clock[0], t -> { });
        for (int i = 0; i < 5; i++) {
            int n = i;
            q.submit(() -> {
                order.add(n);
                clock[0] += 400_000;
            });
        }
        assertEquals(3, q.run(1000), "400 us each: the third task reaches the 1000 us budget");
        assertEquals(List.of(0, 1, 2), order);
        assertEquals(2, q.size());
        assertEquals(2, q.run(1000));
        assertEquals(List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    void aSlowFirstTaskStillCountsAndZeroBudgetRunsNothing() {
        long[] clock = {0};
        BudgetedQueue q = new BudgetedQueue(() -> clock[0], t -> { });
        q.submit(() -> clock[0] += 10_000_000);
        q.submit(() -> { });
        assertEquals(0, q.run(0));
        assertEquals(1, q.run(100), "one task per tick even when it overruns");
        assertEquals(1, q.size());
    }

    @Test
    void aThrowingTaskIsReportedAndTheRestStillRun() {
        List<Throwable> failures = new ArrayList<>();
        BudgetedQueue q = new BudgetedQueue(System::nanoTime, failures::add);
        int[] done = {0};
        q.submit(() -> { throw new IllegalStateException("boom"); });
        q.submit(() -> done[0]++);
        assertEquals(2, q.run(5000));
        assertEquals(1, failures.size());
        assertEquals(1, done[0]);
    }

    @Test
    void budgetsShrinkWithLoadAndVanishWhenShedding() {
        assertEquals(2000, BudgetedQueue.budgetMicros(0));
        assertEquals(2000, BudgetedQueue.budgetMicros(-1));
        assertEquals(500, BudgetedQueue.budgetMicros(1));
        assertEquals(0, BudgetedQueue.budgetMicros(2));
        assertEquals(0, BudgetedQueue.budgetMicros(3));
    }
}
