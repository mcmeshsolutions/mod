package dev.mcmesh.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-config local LAN discovery via UDP multicast (224.0.2.60:4445).
 * Enables seamless peer discovery and LAN play without requiring cloud gateway infrastructure.
 */
public final class LanDiscovery {
    public static final String MULTICAST_GROUP = "224.0.2.60";
    public static final int MULTICAST_PORT = 4445;
    public static final long DEFAULT_TTL_MS = 6000L;

    private static final Pattern MOTD_PATTERN = Pattern.compile("\\[MOTD\\](.*?)\\[/MOTD\\]");
    private static final Pattern AD_PATTERN = Pattern.compile("\\[AD\\](\\d+)\\[/AD\\]");
    private static final Pattern MCMESH_PATTERN = Pattern.compile("\\[MCMESH\\](.*?)\\[/MCMESH\\]");

    private LanDiscovery() { }

    public record DiscoveredWorld(
            String worldId,
            String worldName,
            String packId,
            String hostPlayer,
            InetAddress address,
            int port,
            long lastSeenMs
    ) {
        public boolean isExpired(long ttlMs) {
            return (System.currentTimeMillis() - lastSeenMs) > ttlMs;
        }
    }

    public static String formatAnnouncement(String worldId, String worldName, String packId, String hostPlayer, int port) {
        String safeName = (worldName != null && !worldName.isBlank()) ? worldName : "Shared World";
        String safePack = (packId != null && !packId.isBlank()) ? packId : "default";
        String safeHost = (hostPlayer != null && !hostPlayer.isBlank()) ? hostPlayer : "Host";
        return String.format("[MOTD]§b[MCMesh LAN]§r %s §7(%s)§r[/MOTD][AD]%d[/AD][MCMESH]id=%s&pack=%s&host=%s&v=1[/MCMESH]",
                safeName, safeHost, port, worldId, safePack, safeHost);
    }

    public static DiscoveredWorld parseAnnouncement(String packet, InetAddress sender) {
        if (packet == null || sender == null) {
            return null;
        }
        Matcher adMatcher = AD_PATTERN.matcher(packet);
        if (!adMatcher.find()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(adMatcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }

        String worldName = "LAN World";
        Matcher motdMatcher = MOTD_PATTERN.matcher(packet);
        if (motdMatcher.find()) {
            String raw = motdMatcher.group(1).replaceAll("§[0-9a-fk-or]", "").trim();
            if (raw.startsWith("[MCMesh LAN]")) {
                raw = raw.substring("[MCMesh LAN]".length()).trim();
            }
            int lastParen = raw.lastIndexOf('(');
            if (lastParen > 0 && raw.endsWith(")")) {
                raw = raw.substring(0, lastParen).trim();
            }
            worldName = raw.isEmpty() ? "LAN World" : raw;
        }

        String worldId = "lan-" + sender.getHostAddress() + "-" + port;
        String packId = "default";
        String hostPlayer = "Host";

        Matcher mcMatcher = MCMESH_PATTERN.matcher(packet);
        if (mcMatcher.find()) {
            String params = mcMatcher.group(1);
            for (String part : params.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String k = part.substring(0, eq).trim();
                    String v = part.substring(eq + 1).trim();
                    switch (k) {
                        case "id" -> worldId = v;
                        case "pack" -> packId = v;
                        case "host" -> hostPlayer = v;
                    }
                }
            }
        }

        return new DiscoveredWorld(worldId, worldName, packId, hostPlayer, sender, port, System.currentTimeMillis());
    }

    /**
     * Broadcasts discovery beacons periodically over UDP multicast.
     */
    public static class Broadcaster implements AutoCloseable {
        private final String message;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread workerThread;

        public Broadcaster(String worldId, String worldName, String packId, String hostPlayer, int port) {
            this.message = formatAnnouncement(worldId, worldName, packId, hostPlayer, port);
        }

        public synchronized void start() {
            if (running.getAndSet(true)) {
                return;
            }
            workerThread = new Thread(this::runBroadcast, "mcmesh-lan-broadcaster");
            workerThread.setDaemon(true);
            workerThread.start();
        }

        private void runBroadcast() {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, group, MULTICAST_PORT);

                while (running.get()) {
                    try {
                        socket.send(packet);
                    } catch (IOException ignored) {
                        // network interface transient failure
                    }
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                // socket init failure
            } finally {
                running.set(false);
            }
        }

        public synchronized void stop() {
            running.set(false);
            if (workerThread != null) {
                workerThread.interrupt();
                workerThread = null;
            }
        }

        @Override
        public void close() {
            stop();
        }

        public boolean isRunning() {
            return running.get();
        }
    }

    /**
     * Listens for local LAN beacons and aggregates active peer worlds.
     */
    public static class Listener implements AutoCloseable {
        private final Map<String, DiscoveredWorld> discovered = new ConcurrentHashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread workerThread;
        private MulticastSocket socket;

        public synchronized void start() {
            if (running.getAndSet(true)) {
                return;
            }
            workerThread = new Thread(this::runListen, "mcmesh-lan-listener");
            workerThread.setDaemon(true);
            workerThread.start();
        }

        private void runListen() {
            try {
                socket = new MulticastSocket(MULTICAST_PORT);
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                try {
                    socket.joinGroup(new InetSocketAddress(group, MULTICAST_PORT), null);
                } catch (Exception e) {
                    socket.joinGroup(group);
                }

                byte[] buf = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);

                while (running.get()) {
                    try {
                        socket.receive(packet);
                        String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                        DiscoveredWorld world = parseAnnouncement(raw, packet.getAddress());
                        if (world != null) {
                            discovered.put(world.worldId(), world);
                        }
                    } catch (IOException e) {
                        if (!running.get()) break;
                    }
                }
            } catch (Exception e) {
                // Multicast socket bind failure
            } finally {
                running.set(false);
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        }

        public List<DiscoveredWorld> getDiscovered() {
            return getDiscovered(DEFAULT_TTL_MS);
        }

        public List<DiscoveredWorld> getDiscovered(long ttlMs) {
            long now = System.currentTimeMillis();
            discovered.entrySet().removeIf(entry -> (now - entry.getValue().lastSeenMs()) > ttlMs);
            return new ArrayList<>(discovered.values());
        }

        public synchronized void stop() {
            running.set(false);
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (workerThread != null) {
                workerThread.interrupt();
                workerThread = null;
            }
        }

        @Override
        public void close() {
            stop();
        }

        public boolean isRunning() {
            return running.get();
        }
    }
}
