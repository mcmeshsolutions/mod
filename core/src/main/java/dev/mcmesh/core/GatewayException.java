package dev.mcmesh.core;

public final class GatewayException extends Exception {
    public final int status;
    public final String code;

    public GatewayException(int status, String code) {
        super(status + " " + code);
        this.status = status;
        this.code = code;
    }

    /** The lease is gone or superseded; the host must stop simulating. */
    public boolean leaseLost() {
        return code.equals("STALE_EPOCH") || code.equals("NOT_LEASE_HOLDER");
    }
}
