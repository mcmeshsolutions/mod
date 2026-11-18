package dev.mcmesh.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles automatic detection of MCMesh invite and join links from the system clipboard on launch
 * and presents 1-click connect actions without manual copy-paste into dialogs.
 */
public final class ClipboardInvite {

    public record DetectedInvite(String host, String code, String rawUrl) {
        public String displayText() {
            String target = (host != null && !host.isBlank()) ? host : "Mesh World";
            return "Join " + target + " (Invite: " + code + ")";
        }
    }

    private static final Set<String> DISMISSED_CODES = ConcurrentHashMap.newKeySet();
    private static volatile String lastHandledCode = null;

    private ClipboardInvite() {}

    /**
     * Inspects clipboard content for an MCMesh invite link (e.g. https://<gateway>/join/<code_or_hash>).
     * Returns a DetectedInvite if a new, unhandled, non-dismissed invite is found.
     */
    public static DetectedInvite check(String clipboardContent) {
        if (clipboardContent == null || clipboardContent.isBlank()) {
            return null;
        }

        InviteLink.Parsed parsed = InviteLink.parse(clipboardContent);
        if (parsed == null || parsed.code() == null) {
            return null;
        }

        String code = parsed.code();
        if (code.equalsIgnoreCase(lastHandledCode) || DISMISSED_CODES.contains(code.toUpperCase())) {
            return null;
        }

        return new DetectedInvite(parsed.host(), code, clipboardContent.trim());
    }

    public static void markHandled(String code) {
        if (code != null) {
            lastHandledCode = code;
        }
    }

    public static void dismiss(String code) {
        if (code != null) {
            DISMISSED_CODES.add(code.toUpperCase());
            lastHandledCode = code;
        }
    }

    public static boolean isDismissed(String code) {
        return code != null && DISMISSED_CODES.contains(code.toUpperCase());
    }

    public static void reset() {
        DISMISSED_CODES.clear();
        lastHandledCode = null;
    }
}
