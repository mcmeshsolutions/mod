package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ThreadsTest {
    @Test
    void startRunsTheBodyUnderTheGivenName() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        Thread t = Threads.start("probe-name", () -> seen.set(Thread.currentThread().getName()));
        t.join(5000);
        assertEquals("probe-name", seen.get());
        assertTrue(t.isDaemon(), "background threads never keep the JVM alive");
    }

    @Test
    void theExecutorRunsEachTaskAndCanBeShutDown() throws Exception {
        var ex = Threads.perTaskExecutor();
        assertEquals(7, ex.submit(() -> 7).get());
        ex.shutdown();
        assertTrue(ex.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
}
