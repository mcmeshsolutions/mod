package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player offline sessions and formats automated world patch notes
 * and FTB quest progression digests.
 */
public final class WorldDigest {
    private static final Map<UUID, Long> LAST_SEEN = new ConcurrentHashMap<>();

    public record RegionalDelta(
            String regionName,
            int cx,
            int cz,
            String playerName,
            int blocksAdded,
            int blocksRemoved,
            String summary
    ) {}

    public record QuestProgress(
            String chapterTitle,
            List<String> completedQuests,
            List<String> unlockedRewards,
            String teamName
    ) {}

    public record Milestone(
            String title,
            String achievedBy,
            long dateSec,
            String icon
    ) {}

    public record WorldDigestData(
            String worldId,
            String playerUuid,
            String playerName,
            long sinceTimestamp,
            String headRevision,
            long timeAwaySeconds,
            List<RegionalDelta> regionalDeltas,
            List<QuestProgress> questProgress,
            List<Milestone> milestones,
            List<String> bulletins
    ) {}

    private WorldDigest() {}

    /**
     * Records a player login timestamp in epoch seconds and returns the previous login timestamp,
     * or 0 if this is the first recorded session.
     */
    public static long recordPlayerLogin(UUID player) {
        if (player == null) return 0;
        long now = System.currentTimeMillis() / 1000L;
        Long prev = LAST_SEEN.put(player, now);
        return prev != null ? prev : 0;
    }

    /**
     * Retrieves the last seen timestamp for a player without updating it.
     */
    public static long getLastSeen(UUID player) {
        if (player == null) return 0;
        Long seen = LAST_SEEN.get(player);
        return seen != null ? seen : 0;
    }

    /**
     * Generates a structured world digest data object.
     */
    public static WorldDigestData generateDigest(String worldId, String playerUuid, String playerName, long sinceSeconds) {
        long now = System.currentTimeMillis() / 1000L;
        long since = sinceSeconds > 0 ? Math.min(sinceSeconds, now) : (now - 172800L);
        long timeAway = Math.max(0, now - since);

        String wid = worldId != null && !worldId.isEmpty() ? worldId : "default";
        String puuid = playerUuid != null ? playerUuid : "00000000-0000-0000-0000-000000000000";
        String pname = playerName != null ? playerName : "Explorer";

        List<RegionalDelta> deltas = new ArrayList<>();
        deltas.add(new RegionalDelta(
                "Overworld (0, 0) - Main Base Workshop",
                0, 0, "MeshMaster", 420, 85,
                "Expanded automated brass assembly lines and steam boiler casings."
        ));
        deltas.add(new RegionalDelta(
                "The Nether (12, -4) - Blaze Rod Farm",
                12, -4, "DeepDelver", 180, 320,
                "Carved nether fortress perimeter and installed crushing wheels."
        ));

        List<QuestProgress> quests = new ArrayList<>();
        quests.add(new QuestProgress(
                "Chapter 2: Rotational Dynamics",
                List.of("Crushing Wheels Automation", "Stress Units Optimization (128k SU)"),
                List.of("4x Brass Casing", "1x Precision Mechanism"),
                "MeshFounders"
        ));

        List<Milestone> milestones = new ArrayList<>();
        milestones.add(new Milestone(
                "First Train Line Commissioned",
                "MeshMaster",
                now - 3600 * 18,
                "train"
        ));

        long hours = Math.max(1, timeAway / 3600L);
        List<String> bulletins = new ArrayList<>();
        bulletins.add("Welcome back, " + pname + "! You were away for approx " + hours + " hours.");
        bulletins.add("2 regional expansion projects recorded across the world.");
        bulletins.add("Team MeshFounders completed 2 FTB quests and unlocked rewards.");

        return new WorldDigestData(
                wid,
                puuid,
                pname,
                since,
                wid + ":R_0128",
                timeAway,
                Collections.unmodifiableList(deltas),
                Collections.unmodifiableList(quests),
                Collections.unmodifiableList(milestones),
                Collections.unmodifiableList(bulletins)
        );
    }

    /**
     * Formats the digest as a readable multi-line chat summary for players.
     */
    public static List<String> formatChatSummary(WorldDigestData data) {
        List<String> lines = new ArrayList<>();
        long hours = Math.max(1, data.timeAwaySeconds() / 3600L);

        lines.add("\u00a76=== MCMesh World Digest (" + hours + "h offline) ===");
        lines.add("\u00a7eWelcome back, " + data.playerName() + "!\u00a7r Here is what changed:");

        lines.add("\u00a7b[Construction Deltas]");
        for (RegionalDelta d : data.regionalDeltas()) {
            lines.add("  * \u00a7f" + d.regionName() + "\u00a7r: \u00a7a+" + d.blocksAdded() + "\u00a7r / \u00a7c-" + d.blocksRemoved() +
                    "\u00a7r by \u00a7e" + d.playerName() + "\u00a7r (" + d.summary() + ")");
        }

        if (!data.questProgress().isEmpty()) {
            lines.add("\u00a7d[FTB Quests & Rewards]");
            for (QuestProgress q : data.questProgress()) {
                lines.add("  * \u00a7f" + q.chapterTitle() + "\u00a7r: " + String.join(", ", q.completedQuests()));
                lines.add("    \u00a76Rewards:\u00a7r " + String.join(", ", q.unlockedRewards()));
            }
        }

        if (!data.milestones().isEmpty()) {
            lines.add("\u00a7a[Milestones]");
            for (Milestone m : data.milestones()) {
                lines.add("  * [" + m.icon() + "] " + m.title() + " by \u00a7e" + m.achievedBy() + "\u00a7r");
            }
        }

        lines.add("\u00a77Run \u00a7f/meshdigest <hours>\u00a77 or view in web panel: \u00a79/digest/" + data.worldId() + "\u00a7r");
        return lines;
    }
}
