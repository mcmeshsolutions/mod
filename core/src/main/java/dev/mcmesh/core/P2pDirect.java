package dev.mcmesh.core;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * P2P direct-path hole punching and LAN bypass manager.
 * Evaluates candidate STUN/ICE endpoints and local subnets to establish direct UDP tunnels
 * between host and guests, bypassing the VPS relay whenever possible.
 */
public final class P2pDirect {
    public enum RouteMode {
        GATEWAY_RELAY,
        DIRECT_P2P,
        LAN_LOCAL
    }

    public record Candidate(String peerId, InetSocketAddress socketAddress, boolean isLan) {}
    public record Route(String peerId, RouteMode mode, InetSocketAddress targetAddress, long latencyMs) {}

    private final Map<String, Route> activeRoutes = new ConcurrentHashMap<>();

    public P2pDirect() {}

    /**
     * Evaluates a probe response from a peer candidate.
     * If latency is within budget, establishes DIRECT or LAN route; else defaults to GATEWAY_RELAY.
     */
    public Route evaluateCandidate(String peerId, InetSocketAddress address, boolean isLan, long rttMs) {
        RouteMode mode;
        if (isLan && rttMs < 50) {
            mode = RouteMode.LAN_LOCAL;
        } else if (rttMs < 250) {
            mode = RouteMode.DIRECT_P2P;
        } else {
            mode = RouteMode.GATEWAY_RELAY;
        }
        Route route = new Route(peerId, mode, address, rttMs);
        activeRoutes.put(peerId, route);
        return route;
    }

    public Route getRoute(String peerId) {
        return activeRoutes.getOrDefault(peerId, new Route(peerId, RouteMode.GATEWAY_RELAY, null, -1));
    }

    private final java.util.concurrent.atomic.AtomicLong directBytes = new java.util.concurrent.atomic.AtomicLong();

    public void recordDirectBytes(long bytes) {
        if (bytes > 0) {
            directBytes.addAndGet(bytes);
        }
    }

    public long totalDirectBytes() {
        return directBytes.get();
    }

    public long getAndResetDirectBytes() {
        return directBytes.getAndSet(0);
    }

    public boolean isDirect(String peerId) {
        Route r = activeRoutes.get(peerId);
        return r != null && (r.mode() == RouteMode.DIRECT_P2P || r.mode() == RouteMode.LAN_LOCAL);
    }
}
