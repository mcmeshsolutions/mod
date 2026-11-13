package dev.mcmesh.core;

import java.io.IOException;

/** Commits for one lease. Stops for good once the sanity gate refuses a revision: the same world state would only be refused again. */
public final class Commits {
    private final GatewayClient gw;
    private final Lease lease;
    private volatile boolean refused;
    private volatile Sender sender;

    public interface Sender {
        Revision send(String baseRev, String kind, boolean last) throws IOException, GatewayException;
    }

    /** Replaces the plain gateway commit, e.g. with a signing one; the refusal latch still applies. */
    public void sendWith(Sender s) {
        sender = s;
    }

    public Commits(GatewayClient gw, Lease lease) {
        this.gw = gw;
        this.lease = lease;
    }

    public boolean refused() {
        return refused;
    }

    /** Null when the sanity gate refused this commit or an earlier one; nothing is sent after a refusal. */
    public synchronized Revision commit(String baseRev, String kind, boolean last) throws IOException, GatewayException {
        if (refused) {
            return null;
        }
        try {
            Sender s = sender;
            return s != null ? s.send(baseRev, kind, last) : gw.commit(lease.id(), lease.epoch(), baseRev, kind, last);
        } catch (GatewayException e) {
            if (e.status == 422 && e.code.equals("REJECTED_BY_SANITY_GATE")) {
                refused = true;
                return null;
            }
            throw e;
        }
    }
}
