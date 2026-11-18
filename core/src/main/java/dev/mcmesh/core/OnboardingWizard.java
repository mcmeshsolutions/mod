package dev.mcmesh.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * First-launch onboarding wizard state and connectivity verification logic.
 */
public final class OnboardingWizard {
    private static final Path MARKER_FILE = Paths.get("config", "mcmesh", "onboarded.marker");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile boolean firstLaunchCompleted = false;

    public record VerificationResult(
            boolean success,
            String gatewayUrl,
            String motd,
            int latencyMs,
            String error
    ) {}

    public record WizardInput(
            String hostOrUrl,
            String inviteCode,
            boolean isInvite
    ) {}

    private OnboardingWizard() {}

    /**
     * Determines whether the onboarding wizard should be presented to the player.
     */
    public static synchronized boolean shouldShowWizard() {
        if (firstLaunchCompleted) {
            return false;
        }
        try {
            if (Files.exists(MARKER_FILE)) {
                firstLaunchCompleted = true;
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

    /**
     * Marks the onboarding wizard as completed so it does not auto-open on future launches.
     */
    public static synchronized void markFirstLaunchCompleted() {
        firstLaunchCompleted = true;
        try {
            if (MARKER_FILE.getParent() != null) {
                Files.createDirectories(MARKER_FILE.getParent());
            }
            Files.writeString(MARKER_FILE, String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    /**
     * Resets wizard completion state (useful for testing or re-running setup).
     */
    public static synchronized void resetWizard() {
        firstLaunchCompleted = false;
        try {
            Files.deleteIfExists(MARKER_FILE);
        } catch (Exception ignored) {}
    }

    /**
     * Parses user text input from the wizard into a gateway address or invite code.
     */
    public static WizardInput parseInput(String input) {
        if (input == null || input.isBlank()) {
            return new WizardInput("", null, false);
        }
        String text = input.strip();

        // Check for invite links
        InviteLink.Parsed invite = InviteLink.parse(text);
        if (invite != null) {
            String host = invite.host() != null ? invite.host() : "";
            return new WizardInput(host, invite.code(), true);
        }

        // Standard gateway URL or host
        String url = text;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.startsWith("localhost") || url.startsWith("127.0.0.1")) {
                url = "http://" + url;
            } else {
                url = "https://" + url;
            }
        }
        // Normalize trailing slashes
        url = url.replaceAll("/+$", "");
        return new WizardInput(url, null, false);
    }

    /**
     * Probes live gateway connectivity via HTTP GET /v1/status and measures latency.
     */
    public static VerificationResult probeGateway(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return new VerificationResult(false, "", "", 0, "Gateway URL cannot be empty");
        }

        String url = targetUrl.strip().replaceAll("/+$", "");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = (url.startsWith("localhost") || url.startsWith("127.0.0.1")) ? ("http://" + url) : ("https://" + url);
        }

        long start = System.currentTimeMillis();
        try {
            URI uri = URI.create(url + "/v1/status");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "mcmesh-wizard");

            int code = conn.getResponseCode();
            int latency = (int) (System.currentTimeMillis() - start);

            if (code == 200) {
                return new VerificationResult(true, url, "MCMesh Gateway Online", latency, null);
            } else {
                return new VerificationResult(false, url, "", latency, "HTTP error " + code + ": " + conn.getResponseMessage());
            }
        } catch (Exception e) {
            int latency = (int) (System.currentTimeMillis() - start);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new VerificationResult(false, url, "", latency, "Connection failed: " + msg);
        }
    }

    /**
     * Auto-generates a secure device authentication token.
     */
    public static String generateDeviceToken(String playerName) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
