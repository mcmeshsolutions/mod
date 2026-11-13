package dev.mcmesh.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class GatewayClient {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final String token;
    /** Modpack id sent with every request; worlds of other packs are invisible and refuse joins. */
    public static volatile String pack = "default";
    public static volatile String packVersion = "0";

    public GatewayClient(String baseUrl, String token) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    private static final int RATE_LIMIT_RETRIES = 3;

    private static long retryAfterMs(HttpResponse<byte[]> r) {
        try {
            return Math.min(5, Math.max(0, Long.parseLong(r.headers().firstValue("retry-after").orElse("1").trim()))) * 1000;
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    private byte[] exec(String path, String method, HttpRequest.BodyPublisher body, String contentType) throws IOException, GatewayException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).method(method, body).header("authorization", "Bearer " + token)
                .header("x-mesh-pack", pack).header("x-mesh-pack-version", packVersion).header("content-type", contentType).timeout(Duration.ofSeconds(20)).build();
        HttpResponse<byte[]> r;
        int limited = 0;
        while (true) {
            try {
                r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                // a 429 is refused before any handler runs, so resending is always safe
                if (r.statusCode() == 429 && limited++ < RATE_LIMIT_RETRIES) {
                    Thread.sleep(retryAfterMs(r));
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            break;
        }
        if (r.statusCode() / 100 != 2) {
            String code = "HTTP_" + r.statusCode();
            try {
                code = JsonParser.parseString(new String(r.body())).getAsJsonObject().get("error").getAsString();
            } catch (RuntimeException ignored) {
                // non-json error body, keep the status code
            }
            throw new GatewayException(r.statusCode(), code);
        }
        return r.body();
    }

    public void logs(byte[] text) throws IOException, GatewayException {
        exec("/v1/logs", "POST", HttpRequest.BodyPublishers.ofByteArray(text), "text/plain");
    }

    /** The load level and real player count the world's host last reported, or UNKNOWN. */
    public HostHealth hostHealth(String worldId) throws IOException, GatewayException {
        byte[] r = exec("/v1/worlds", "GET", HttpRequest.BodyPublishers.noBody(), "application/json");
        for (JsonElement e : JsonParser.parseString(new String(r)).getAsJsonObject().getAsJsonArray("worlds")) {
            JsonObject o = e.getAsJsonObject();
            if (worldId.equals(o.get("id").getAsString())) {
                return new HostHealth(intOr(o, "host_load"), intOr(o, "host_players"));
            }
        }
        return HostHealth.UNKNOWN;
    }

    private static int intOr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : -1;
    }

    /** Uploads a probe body the gateway discards, so the caller can time its own uplink. */
    public void bandwidthProbe(byte[] body) throws IOException, GatewayException {
        exec("/v1/bandwidth", "POST", HttpRequest.BodyPublishers.ofByteArray(body), "application/octet-stream");
    }

    public void crashReport(String world, String text) throws IOException, GatewayException {
        JsonObject b = new JsonObject();
        b.addProperty("text", text);
        post("/v1/worlds/" + world + "/crash-report", b);
    }

    private JsonObject post(String path, JsonObject body) throws IOException, GatewayException {
        byte[] r = exec(path, "POST", HttpRequest.BodyPublishers.ofString(body.toString()), "application/json");
        return JsonParser.parseString(new String(r)).getAsJsonObject();
    }

    public String authChallenge() throws IOException, GatewayException {
        return post("/v1/auth/challenge", new JsonObject()).get("challenge").getAsString();
    }

    private static Identity identity(JsonObject r) {
        return new Identity(r.get("token").getAsString(), r.get("uuid").getAsString(), r.get("name").getAsString());
    }

    /** Call after Mojang's joinServer was made with the challenge as serverId. */
    public Identity loginMojang(String challenge, String name) throws IOException, GatewayException {
        JsonObject b = new JsonObject();
        b.addProperty("challenge", challenge);
        b.addProperty("name", name);
        return identity(post("/v1/auth/mojang", b));
    }

    public Identity loginKey(IdentityKey key, String name) throws IOException, GatewayException {
        String challenge = authChallenge();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        JsonObject b = new JsonObject();
        b.addProperty("challenge", challenge);
        b.addProperty("name", name);
        b.addProperty("pubkey", enc.encodeToString(key.publicRaw()));
        b.addProperty("sig", enc.encodeToString(key.sign(challenge)));
        return identity(post("/v1/auth/key", b));
    }

    /** One-time, 60 s ticket the relay checks in the Minecraft handshake. */
    public record WorldInfo(String id, String name, String mcVersion, String role, String state, String host, long takeoverInS,
            boolean hostReady, long syncDone, long syncTotal, String packVersion) {
        public WorldInfo(String id, String name, String mcVersion, String role, String state, String host, long takeoverInS,
                boolean hostReady, long syncDone, long syncTotal) {
            this(id, name, mcVersion, role, state, host, takeoverInS, hostReady, syncDone, syncTotal, null);
        }
    }

    /** relay is the host:port guests connect to, empty when the gateway has none. */
    public record Listing(List<WorldInfo> worlds, String relay) { }

    public String createWorld(String name, String mcVersion, String loader) throws IOException, GatewayException {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        b.addProperty("mc_version", mcVersion);
        b.addProperty("loader", loader);
        return post("/v1/worlds", b).get("id").getAsString();
    }

    /** Set from the world list: the gateway accepts an HMAC proof in the tunnel hello, so the token never crosses the wire. */
    public static volatile boolean tunnelProof;

    public Listing listWorlds() throws IOException, GatewayException {
        java.util.function.Supplier<Double> hw = hwScore;
        return listWorlds(1, hw == null ? Runtime.getRuntime().availableProcessors() : hw.get());
    }

    /** takeoverInS is -1 unless this machine, with the given hardware, could take the world over from its host. */
    public Listing listWorlds(int tier, double score) throws IOException, GatewayException {
        byte[] r = exec("/v1/worlds?tier=" + tier + "&score=" + score, "GET", HttpRequest.BodyPublishers.noBody(), "application/json");
        JsonObject root = JsonParser.parseString(new String(r)).getAsJsonObject();
        tunnelProof = root.has("tunnel_proof") && root.get("tunnel_proof").getAsBoolean();
        List<WorldInfo> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("worlds")) {
            JsonObject o = e.getAsJsonObject();
            out.add(new WorldInfo(o.get("id").getAsString(), o.get("name").getAsString(), o.get("mc_version").getAsString(),
                    o.get("role").getAsString(), o.get("state").getAsString(),
                    o.get("host").isJsonNull() ? null : o.get("host").getAsString(),
                    o.has("takeover_in_s") && !o.get("takeover_in_s").isJsonNull() ? o.get("takeover_in_s").getAsLong() : -1,
                    !o.has("host_ready") || o.get("host_ready").getAsBoolean(),
                    syncOf(o, "done"), syncOf(o, "total"),
                    o.has("pack_version") && !o.get("pack_version").isJsonNull() ? o.get("pack_version").getAsString() : null));
        }
        return new Listing(out, root.has("relay") ? root.get("relay").getAsString() : "");
    }

    private static long syncOf(JsonObject o, String k) {
        JsonElement s = o.get("sync");
        return s == null || s.isJsonNull() ? 0 : s.getAsJsonObject().get(k).getAsLong();
    }

    public String createInvite(String world, String role) throws IOException, GatewayException {
        JsonObject b = new JsonObject();
        b.addProperty("role", role);
        return post("/v1/worlds/" + world + "/invite", b).get("code").getAsString();
    }

    public String redeemInvite(String code) throws IOException, GatewayException {
        JsonObject b = new JsonObject();
        b.addProperty("code", code);
        return post("/v1/invites/redeem", b).get("world_id").getAsString();
    }

    /** Allowlist document: {"allow":[{"id","hash"}],"ignore":["id"]}; empty object means unrestricted. */
    public JsonObject worldMods(String world) throws IOException, GatewayException {
        byte[] r = exec("/v1/worlds/" + world + "/mods", "GET", HttpRequest.BodyPublishers.noBody(), "application/json");
        return JsonParser.parseString(new String(r)).getAsJsonObject();
    }

    public void putWorldMods(String world, JsonObject doc) throws IOException, GatewayException {
        exec("/v1/worlds/" + world + "/mods", "PUT", HttpRequest.BodyPublishers.ofString(doc.toString()), "application/json");
    }

    /** One-time code for signing in to the web panel. */
    public String panelCode() throws IOException, GatewayException {
        return post("/v1/panel/code", new JsonObject()).get("code").getAsString();
    }

    private record Route(String world, GatewayClient via) { }

    private static volatile Route route;

    /**
     * World on a non-default gateway the player is joining. The handshake hook only knows the default gateway,
     * so ticket() for that world is sent to the gateway that owns it, with that gateway's own token. Null clears it.
     */
    public static void ticketRoute(String world, GatewayClient via) {
        route = world == null || via == null ? null : new Route(world, via);
    }

    public String ticket(String world) throws IOException, GatewayException {
        Route r = route;
        if (r != null && r.via != this && r.world.equals(world)) {
            return r.via.ticket(world);
        }
        return post("/v1/worlds/" + world + "/ticket", new JsonObject()).get("ticket").getAsString();
    }

    public void requestMods(String world, java.util.List<String[]> mods) throws IOException, GatewayException {
        JsonArray arr = new JsonArray();
        for (String[] m : mods) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m[0]);
            o.addProperty("hash", m[1]);
            arr.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("mods", arr);
        post("/v1/worlds/" + world + "/mod-requests", body);
    }

    public JoinResult join(String world, int tier, double score, boolean wantsHost) throws IOException, GatewayException {
        return join(world, tier, score, wantsHost, null);
    }

    public JoinResult join(String world, int tier, double score, boolean wantsHost, JsonObject bench) throws IOException, GatewayException {
        return join(world, tier, score, wantsHost, bench, false);
    }

    /** takeover is true only for an explicit request to replace a healthy host. */
    public JoinResult join(String world, int tier, double score, boolean wantsHost, JsonObject bench, boolean takeover) throws IOException, GatewayException {
        JsonObject hw = new JsonObject();
        if (bench != null) {
            hw.add("bench", bench);
        }
        hw.addProperty("tier", tier);
        hw.addProperty("score", score);
        hw.addProperty("wants_host", wantsHost);
        hw.addProperty("takeover", takeover);
        JsonObject body = new JsonObject();
        body.add("hw", hw);
        JsonObject r = post("/v1/worlds/" + world + "/join", body);
        return switch (r.get("decision").getAsString()) {
            case "HOST" -> {
                JsonObject l = r.getAsJsonObject("lease");
                yield new JoinResult.Host(new Lease(l.get("id").getAsString(), l.get("epoch").getAsLong(), l.get("ttl_s").getAsInt(),
                        l.get("heartbeat_s").getAsInt(), l.get("fence_after_s").getAsInt(), l.get("base_rev").getAsString(),
                        Base64.getUrlDecoder().decode(l.get("hmac_key").getAsString())));
            }
            case "GUEST" -> new JoinResult.Guest(r.get("epoch").getAsLong(), r.getAsJsonObject("host").get("device").getAsString());
            default -> new JoinResult.Wait(r.get("phase").getAsString(), r.get("retry_ms").getAsInt());
        };
    }

    private static JsonObject epoch(long epoch) {
        JsonObject o = new JsonObject();
        o.addProperty("epoch", epoch);
        return o;
    }

    /** Host download progress as {done, total}, or null when idle; reported with each heartbeat. */
    /** This machine's benchmark score, the same number join() bids with. */
    public static volatile java.util.function.Supplier<Double> hwScore;

    public static volatile java.util.function.Supplier<long[]> syncProgress;

    /** Real players online right now, or -1 when unknown; reported with each heartbeat. */
    public static volatile java.util.function.IntSupplier playerCount = () -> -1;

    /** Load-shedding level 0 to 3, or -1 when unknown; reported with each heartbeat. */
    public static volatile java.util.function.IntSupplier loadLevel = () -> -1;

    public Heartbeat heartbeat(String lease, long epoch) throws IOException, GatewayException {
        JsonObject body = epoch(epoch);
        var p = syncProgress;
        long[] sp = p == null ? null : p.get();
        if (sp != null && sp[1] > 0) {
            body.addProperty("sync_done", sp[0]);
            body.addProperty("sync_total", sp[1]);
        }
        int players = playerCount.getAsInt();
        if (players >= 0) {
            body.addProperty("players", players);
        }
        int load = loadLevel.getAsInt();
        if (load >= 0) {
            body.addProperty("load", load);
        }
        JsonObject r = post("/v1/leases/" + lease + "/heartbeat", body);
        List<String> targets = new ArrayList<>();
        List<String> names = new ArrayList<>();
        JsonArray dirs = r.getAsJsonArray("directives");
        for (JsonElement d : dirs) {
            JsonElement t = d.getAsJsonObject().get("target_device");
            targets.add(t == null || t.isJsonNull() ? null : t.getAsString());
            JsonElement n = d.getAsJsonObject().get("target_name");
            names.add(n == null || n.isJsonNull() ? "Another player" : n.getAsString());
        }
        return new Heartbeat(targets, names);
    }

    public void hostReady(String lease, long epoch) throws IOException, GatewayException {
        post("/v1/leases/" + lease + "/host-ready", epoch(epoch));
    }

    public void release(String lease, long epoch, boolean guestsPresent) throws IOException, GatewayException {
        JsonObject o = epoch(epoch);
        o.addProperty("guests_present", guestsPresent);
        post("/v1/leases/" + lease + "/release", o);
    }

    public Revision commit(String lease, long epoch, String baseRev, String kind, boolean last) throws IOException, GatewayException {
        JsonObject o = epoch(epoch);
        o.addProperty("base_rev", baseRev);
        o.addProperty("kind", kind);
        o.addProperty("final", last);
        JsonObject r = post("/v1/leases/" + lease + "/commit", o);
        return new Revision(r.get("rev_id").getAsString(), r.get("parent").getAsString(), r.get("seq").getAsLong());
    }

    /** Returns the next expected seq. The batch is all or nothing on the server. */
    public long walAppend(String lease, long epoch, List<WalFrame> frames) throws IOException, GatewayException {
        JsonArray arr = new JsonArray();
        for (WalFrame f : frames) {
            JsonObject o = new JsonObject();
            o.addProperty("seq", f.seq());
            o.addProperty("op", f.op());
            o.addProperty("dim", f.dim());
            o.addProperty("cx", f.cx());
            o.addProperty("cz", f.cz());
            o.addProperty("hash", "sha256:" + Bytes.hex(f.hash()));
            o.addProperty("mac", Bytes.hex(f.mac()));
            arr.add(o);
        }
        JsonObject body = epoch(epoch);
        body.add("frames", arr);
        return post("/v1/leases/" + lease + "/wal", body).get("next_seq").getAsLong();
    }

    /** Uploads raw bytes under their SHA-256; returns the hash. */
    public byte[] blobPut(byte[] raw) throws IOException, GatewayException {
        byte[] h = Bytes.sha256(raw);
        exec("/v1/blobs/" + Bytes.hex(h), "PUT", HttpRequest.BodyPublishers.ofByteArray(raw), "application/octet-stream");
        return h;
    }

    public RevisionFiles listRevision(String rev) throws IOException, GatewayException {
        byte[] r = exec("/v1/revisions/" + java.net.URLEncoder.encode(rev, java.nio.charset.StandardCharsets.UTF_8) + "/files", "GET",
                HttpRequest.BodyPublishers.noBody(), "application/json");
        JsonObject o = JsonParser.parseString(new String(r)).getAsJsonObject();
        List<RevisionFiles.Chunk> chunks = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("chunks")) {
            JsonObject c = e.getAsJsonObject();
            chunks.add(new RevisionFiles.Chunk(c.get("dim").getAsString(), c.get("kind").getAsString(), c.get("cx").getAsInt(),
                    c.get("cz").getAsInt(), Bytes.unhex(c.get("hash").getAsString())));
        }
        List<RevisionFiles.Aux> aux = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("aux")) {
            JsonObject a = e.getAsJsonObject();
            aux.add(new RevisionFiles.Aux(a.get("path").getAsString(), Bytes.unhex(a.get("hash").getAsString())));
        }
        return new RevisionFiles(o.get("rev").getAsString(), chunks, aux);
    }

    public byte[] blobGet(byte[] hash) throws IOException, GatewayException {
        return exec("/v1/blobs/" + Bytes.hex(hash), "GET", HttpRequest.BodyPublishers.noBody(), "application/octet-stream");
    }
}
