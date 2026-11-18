package dev.mcmesh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Core helper for converting local singleplayer worlds to hosted mesh worlds
 * and generating instant shareable invite links.
 */
public final class HostConverter {

    public record ConvertPlan(String displayName, String mcVersion, String loader, String inviteRole) {}

    public record ConversionSummary(String worldId, String inviteCode, String inviteUrl) {
        public String formatBanner() {
            return "World '" + worldId + "' ready! Invite: " + inviteUrl;
        }

        public String discordMarkdown(String communityName) {
            String comm = (communityName != null && !communityName.isBlank()) ? communityName : "MCMesh";
            return "**" + comm + "** has launched a new co-op mesh world!\n"
                 + "> Instant Join: " + inviteUrl + "\n"
                 + "> Invite Code: `" + inviteCode + "`";
        }
    }

    private HostConverter() {}

    /**
     * Validates that a save folder contains a valid Minecraft level before starting upload.
     */
    public static boolean isValidWorldSave(Path saveDir) {
        if (saveDir == null || !Files.isDirectory(saveDir)) {
            return false;
        }
        Path levelDat = saveDir.resolve("level.dat");
        Path regionDir = saveDir.resolve("region");
        return Files.isRegularFile(levelDat) || Files.isDirectory(regionDir);
    }

    /**
     * Formats an instant shareable HTTP or custom scheme invite link.
     */
    public static String formatInviteLink(String gatewayUrl, String inviteCode) {
        if (gatewayUrl == null || inviteCode == null) {
            return "";
        }
        String cleanUrl = gatewayUrl.strip().replaceAll("/+$", "");
        return cleanUrl + "/join/" + inviteCode;
    }
}
