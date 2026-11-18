package dev.mcmesh.core.timelapse;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthesizes CAS chunk revision history into native ReplayMod (.mcpr) files.
 */
public class TimelapseExporter {

    public record RevisionMilestone(
            String revisionId,
            long sequence,
            long timestampSec,
            String author,
            String description,
            int changedChunks
    ) {}

    /**
     * Synthesizes an .mcpr zip archive from a list of historical revisions.
     */
    public static File export(
            String worldId,
            List<RevisionMilestone> milestones,
            long intervalPerRevisionMs,
            File outputDir
    ) throws IOException {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String fileName = worldId + "_timelapse_" + System.currentTimeMillis() + ".mcpr";
        File target = new File(outputDir, fileName);

        long interval = intervalPerRevisionMs > 0 ? intervalPerRevisionMs : 2000;
        int count = milestones != null ? milestones.size() : 0;
        long durationMs = Math.max(1000, count * interval);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target))) {
            // 1. metaData.json
            zos.putNextEntry(new ZipEntry("metaData.json"));
            String meta = buildMetadataJson(worldId, durationMs);
            zos.write(meta.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. recording.tmcpr
            zos.putNextEntry(new ZipEntry("recording.tmcpr"));
            byte[] packetStream = buildPacketStream(milestones, interval);
            zos.write(packetStream);
            zos.closeEntry();

            // 3. timelapse_markers.json
            zos.putNextEntry(new ZipEntry("timelapse_markers.json"));
            String markers = buildMarkersJson(milestones, interval);
            zos.write(markers.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        return target;
    }

    private static String buildMetadataJson(String worldId, long durationMs) {
        return "{\n"
                + "  \"singleplayer\": false,\n"
                + "  \"serverName\": \"MCMesh: " + worldId + "\",\n"
                + "  \"duration\": " + durationMs + ",\n"
                + "  \"date\": " + System.currentTimeMillis() + ",\n"
                + "  \"mcversion\": \"1.21.1\",\n"
                + "  \"fileFormat\": \"MCPR\",\n"
                + "  \"fileFormatVersion\": 14,\n"
                + "  \"generator\": \"mcmesh-timelapse-v1\",\n"
                + "  \"selfId\": -1,\n"
                + "  \"players\": [\"MCMeshCamera\"]\n"
                + "}";
    }

    private static String buildMarkersJson(List<RevisionMilestone> milestones, long intervalMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        if (milestones != null) {
            for (int i = 0; i < milestones.size(); i++) {
                RevisionMilestone m = milestones.get(i);
                long offsetMs = i * intervalMs;
                sb.append("  {\n")
                        .append("    \"time\": ").append(offsetMs).append(",\n")
                        .append("    \"rev\": \"").append(m.revisionId()).append("\",\n")
                        .append("    \"seq\": ").append(m.sequence()).append(",\n")
                        .append("    \"by\": \"").append(m.author() != null ? m.author() : "system").append("\",\n")
                        .append("    \"chunks\": ").append(m.changedChunks()).append(",\n")
                        .append("    \"desc\": \"").append(m.description() != null ? m.description() : "").append("\"\n")
                        .append("  }").append(i < milestones.size() - 1 ? "," : "").append("\n");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static byte[] buildPacketStream(List<RevisionMilestone> milestones, long intervalMs) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Header Packet at t=0: Login / Join game packet
        writePacket(dos, 0, createSyntheticLoginPacket());

        // Initial chunks cache center at t=0
        writePacket(dos, 0, createSyntheticChunkCenterPacket(0, 0));

        // Time updates and revision milestone packets
        if (milestones != null) {
            for (int i = 0; i < milestones.size(); i++) {
                int timestampMs = (int) (i * intervalMs);
                // Time update packet (e.g. advance 1000 ticks per revision)
                long worldTime = i * 24000L;
                writePacket(dos, timestampMs, createSyntheticTimeUpdatePacket(worldTime));

                // Milestone marker packet (ClientboundSystemChat)
                RevisionMilestone m = milestones.get(i);
                String msg = String.format("§6[MCMesh] §fRevision §e#%d §7(%s) §aby §f%s",
                        m.sequence(), m.revisionId(), m.author());
                writePacket(dos, timestampMs, createSyntheticChatPacket(msg));
            }
        }

        dos.flush();
        return baos.toByteArray();
    }

    private static void writePacket(DataOutputStream dos, int timestampMs, byte[] packetData) throws IOException {
        dos.writeInt(timestampMs);
        dos.writeInt(packetData.length);
        dos.write(packetData);
    }

    private static byte[] createSyntheticLoginPacket() {
        // Simple synthetic GameJoin packet ID (0x2B)
        return new byte[] { 0x2B, 0x00, 0x00, 0x00, 0x01, 0x03, 0x00 };
    }

    private static byte[] createSyntheticChunkCenterPacket(int chunkX, int chunkZ) {
        // SetChunkCacheCenter (0x54)
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x54);
        writeVarInt(b, chunkX);
        writeVarInt(b, chunkZ);
        return b.toByteArray();
    }

    private static byte[] createSyntheticTimeUpdatePacket(long time) {
        // SetTime (0x64)
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x64);
        for (int i = 7; i >= 0; i--) {
            b.write((int) (time >> (i * 8)) & 0xFF);
        }
        for (int i = 7; i >= 0; i--) {
            b.write((int) (time >> (i * 8)) & 0xFF);
        }
        return b.toByteArray();
    }

    private static byte[] createSyntheticChatPacket(String text) {
        // SystemChat (0x6C)
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x6C);
        byte[] strBytes = ("{\"text\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8);
        writeVarInt(b, strBytes.length);
        b.writeBytes(strBytes);
        b.write(0x00); // false (action bar = false)
        return b.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
