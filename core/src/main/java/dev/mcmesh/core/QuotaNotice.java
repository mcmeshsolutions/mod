package dev.mcmesh.core;

/** Turns a gateway storage-quota refusal into one plain message, at most once per interval, so a paused autosave is not a silent stack trace every 2 minutes. */
public final class QuotaNotice {
    public static final long INTERVAL_MS = 10 * 60_000;

    private long lastMs = -INTERVAL_MS;

    /** The message to show, or null when this is not a quota refusal or one was shown less than INTERVAL_MS ago. */
    public synchronized String message(Exception ex, long nowMs) {
        if (!(ex instanceof GatewayException ge)) {
            return null;
        }
        String who = switch (ge.code) {
            case "QUOTA_STORAGE_WORLD" -> "This world is over its storage limit on the gateway.";
            case "QUOTA_STORAGE_USER" -> "The owner of this world is over their storage limit on the gateway.";
            default -> null;
        };
        if (who == null || nowMs - lastMs < INTERVAL_MS) {
            return null;
        }
        lastMs = nowMs;
        return who + " Saving to the gateway is paused. Nothing is lost: the world stays on this machine and the final save when the world stops is still sent. "
                + "Free some space or ask the gateway operator to raise the limit.";
    }

    /** True for a quota refusal, shown or not. */
    public static boolean isQuota(Exception ex) {
        return ex instanceof GatewayException ge && ge.code.startsWith("QUOTA_");
    }
}
