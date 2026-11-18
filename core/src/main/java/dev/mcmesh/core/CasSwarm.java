package dev.mcmesh.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2P CAS blob swarming coordinator.
 * Coordinates peer blob manifests to fetch chunks directly from connected players or LAN peers
 * prior to falling back to HTTP GET downloads from the central gateway.
 */
public final class CasSwarm {
    public record PeerSource(String peerId, boolean isLan, long lastSeen) {}

    private final Map<String, Set<String>> blobToPeers = new ConcurrentHashMap<>();
    private final Map<String, PeerSource> peers = new ConcurrentHashMap<>();

    public CasSwarm() {}

    public void registerPeer(String peerId, boolean isLan) {
        peers.put(peerId, new PeerSource(peerId, isLan, System.currentTimeMillis()));
    }

    public void unregisterPeer(String peerId) {
        peers.remove(peerId);
        for (Set<String> set : blobToPeers.values()) {
            set.remove(peerId);
        }
    }

    public void announceBlobs(String peerId, Set<String> blobHashes) {
        if (!peers.containsKey(peerId)) {
            registerPeer(peerId, false);
        }
        for (String hash : blobHashes) {
            blobToPeers.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet()).add(peerId);
        }
    }

    /**
     * Finds the best source for a given blob hash.
     * Prefers LAN peers, then WAN peers; returns null if only central gateway has it.
     */
    public PeerSource selectBestSource(String blobHash) {
        Set<String> holders = blobToPeers.get(blobHash);
        if (holders == null || holders.isEmpty()) {
            return null;
        }

        PeerSource best = null;
        for (String peerId : holders) {
            PeerSource src = peers.get(peerId);
            if (src == null) {
                continue;
            }
            if (src.isLan()) {
                return src; // Immediate best match
            }
            if (best == null) {
                best = src;
            }
        }
        return best;
    }

    public Set<String> getHolders(String blobHash) {
        Set<String> s = blobToPeers.get(blobHash);
        return s != null ? Collections.unmodifiableSet(s) : Collections.emptySet();
    }
}
