package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeaseSessionTest {
    private static final long S = 1_000_000_000L;
    private final Lease lease = new Lease("ls_w1_1", 1, 45, 15, 35, "w1:R_0000", new byte[32]);
    private long now = 0;
    private int calls;
    private final List<String> fenced = new ArrayList<>();

    private LeaseSession session(LeaseSession.Beat b) {
        return new LeaseSession(lease, b, () -> now, fenced::add, h -> { });
    }

    @Test
    void heartbeatsOnScheduleAndNeverFencesWhileAcked() {
        LeaseSession s = session((l, e) -> {
            calls++;
            return new Heartbeat(List.of());
        });
        for (int t = 0; t <= 120; t++) {
            now = t * S;
            s.tick();
        }
        assertEquals(9, calls, "one beat per 15s");
        assertTrue(fenced.isEmpty());
    }

    @Test
    void fencesBasedOnSendTimeOfLastAckedBeat() {
        boolean[] up = {true};
        LeaseSession s = session((l, e) -> {
            if (!up[0]) {
                throw new IOException("down");
            }
            return new Heartbeat(List.of());
        });
        now = 0;
        s.tick();
        now = 15 * S;
        s.tick();
        up[0] = false;
        for (int t = 16; t <= 49; t++) {
            now = t * S;
            s.tick();
        }
        assertTrue(fenced.isEmpty(), "last ack was sent at 15s, so the fence is at 50s");
        now = 51 * S;
        s.tick();
        assertEquals(1, fenced.size());
        assertTrue(s.fenced());
    }

    @Test
    void staleEpochFencesImmediately() {
        LeaseSession s = session((l, e) -> {
            throw new GatewayException(409, "STALE_EPOCH");
        });
        s.tick();
        assertEquals(List.of("lease lost: STALE_EPOCH"), fenced);
        now = 100 * S;
        s.tick();
        assertEquals(1, fenced.size(), "fences once");
    }

    @Test
    void passesMigrateDirectivesThrough() {
        List<Heartbeat> got = new ArrayList<>();
        LeaseSession s = new LeaseSession(lease, (l, e) -> new Heartbeat(List.of("dev-b")), () -> now, fenced::add, got::add);
        s.tick();
        assertEquals(List.of("dev-b"), got.get(0).migrateTargets());
    }
}
