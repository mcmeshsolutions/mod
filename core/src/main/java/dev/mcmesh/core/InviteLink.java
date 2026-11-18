package dev.mcmesh.core;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The ways an invite reaches a player: the gateway's landing page link, a mcmesh://join link, or the bare code. */
public final class InviteLink {
    private static final Pattern CODE = Pattern.compile("[0-9A-Fa-f]{8,64}");
    private static final Pattern WEB = Pattern.compile("(?i)https?://[^\\s/]+/(?:invite|join)/([0-9A-Za-z_-]{6,64})");
    private static final Pattern SCHEME = Pattern.compile("(?i)mcmesh://join/([^\\s/]+)/([0-9A-Za-z_-]{6,64})");

    private InviteLink() { }

    /** `host` is null for a bare code: it belongs to whichever gateway the player is looking at. */
    public record Parsed(String host, String code) { }

    /** Link to share: the landing page explains how to join, so a code alone is never the only thing sent. */
    public static String link(String apiUrl, String code) {
        return apiUrl.strip().replaceAll("/+$", "") + "/invite/" + code;
    }

    /** Finds an invite anywhere in text a player pasted or copied; null when there is none. */
    public static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        Matcher m = SCHEME.matcher(t);
        if (m.find()) {
            return new Parsed(hostOf(m.group(1)), normalizeCode(m.group(2)));
        }
        m = WEB.matcher(t);
        if (m.find()) {
            String url = t.substring(m.start(), m.end());
            int idx = url.toLowerCase().indexOf("/invite/");
            if (idx < 0) {
                idx = url.toLowerCase().indexOf("/join/");
            }
            return new Parsed(hostOf(url.substring(0, idx)), normalizeCode(m.group(1)));
        }
        String bare = t.replaceAll("^[\"'`<(]+|[\"'`>).,;]+$", "");
        return CODE.matcher(bare).matches() ? new Parsed(null, bare.toUpperCase()) : null;
    }

    private static String normalizeCode(String code) {
        if (code == null) return null;
        if (CODE.matcher(code).matches()) {
            return code.toUpperCase();
        }
        return code;
    }

    private static String hostOf(String hostOrUrl) {
        String s = hostOrUrl.contains("://") ? hostOrUrl : "https://" + hostOrUrl;
        try {
            String h = URI.create(s).getHost();
            return h == null ? null : h.toLowerCase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The configured gateway an invite is for: the one with that host, or null when it is for a gateway the player has not added. */
    public static GatewayList.Gateway gatewayFor(Parsed p, List<GatewayList.Gateway> all, GatewayList.Gateway fallback) {
        if (p.host() == null) {
            return fallback;
        }
        for (GatewayList.Gateway g : all) {
            if (g.host().equalsIgnoreCase(p.host())) {
                return g;
            }
        }
        return null;
    }
}
