package dev.mcmesh.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Signs the host's commits and talks to the two signature endpoints. Kept apart from GatewayClient (client stream);
 * it never throws for a signing problem: the caller falls back to an unsigned commit.
 */
public final class HostSigner {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final String token;
    private final IdentityKey key;
    private volatile boolean registered;

    public HostSigner(String baseUrl, String token, IdentityKey key) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.key = key;
    }

    public boolean registered() {
        return registered;
    }

    private HttpResponse<String> call(String method, String path, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("authorization", "Bearer " + token)
                .header("x-mesh-pack", GatewayClient.pack).header("x-mesh-client", String.valueOf(GatewayClient.PROTOCOL)).timeout(Duration.ofSeconds(20));
        if (body == null) {
            b.GET();
        } else {
            b.header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String errorCode(HttpResponse<String> r) {
        try {
            return JsonParser.parseString(r.body()).getAsJsonObject().get("error").getAsString();
        } catch (RuntimeException e) {
            return "HTTP_" + r.statusCode();
        }
    }

    /**
     * Learns the device id and registers the public key. Returns true when the gateway holds exactly this key afterwards.
     * Any other outcome (old gateway, other key already set, network) leaves the host signing nothing.
     */
    public boolean register() {
        try {
            HttpResponse<String> me = call("GET", "/v1/devices/me", null);
            if (me.statusCode() / 100 != 2) {
                return false;
            }
            String device = JsonParser.parseString(me.body()).getAsJsonObject().get("device").getAsString();
            byte[] pub = key.publicRaw();
            JsonObject b = new JsonObject();
            b.addProperty("pubkey", CommitSignature.b64(pub));
            b.addProperty("sig", CommitSignature.b64(key.signRaw(CommitSignature.deviceKeyMessage(device, pub))));
            HttpResponse<String> r = call("POST", "/v1/devices/key", b.toString());
            registered = r.statusCode() / 100 == 2;
            return registered;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Base64 signature for a commit, or null when this device has no registered key. */
    public String sign(String world, String lease, long epoch, String baseRev, String kind, boolean last, long barrierSeq) {
        if (!registered) {
            return null;
        }
        return CommitSignature.b64(key.signRaw(CommitSignature.commitMessage(world, lease, epoch, baseRev, kind, last, barrierSeq)));
    }

    /** Same wire call as GatewayClient.commit plus the optional sig field. */
    public Revision commit(String lease, long epoch, String baseRev, String kind, boolean last, String sig) throws IOException, GatewayException {
        JsonObject o = new JsonObject();
        o.addProperty("epoch", epoch);
        o.addProperty("base_rev", baseRev);
        o.addProperty("kind", kind);
        o.addProperty("final", last);
        if (sig != null) {
            o.addProperty("sig", sig);
        }
        HttpResponse<String> r = call("POST", GatewayClient.leasePath(lease) + "/commit", o.toString());
        if (r.statusCode() / 100 != 2) {
            throw new GatewayException(r.statusCode(), errorCode(r));
        }
        JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
        return new Revision(j.get("rev_id").getAsString(), j.get("parent").getAsString(), j.get("seq").getAsLong());
    }

    /** Re-sends the missing WAL frames and seals a fresh barrier. */
    public interface Reseal {
        void run() throws IOException, GatewayException;
    }

    /**
     * Signed commit with fallbacks that never lose the commit: a wrong signature gets one reseal and retry,
     * and a rejected or missing key falls back to an unsigned commit, which the gateway still accepts.
     */
    public Revision commit(String world, String lease, long epoch, String baseRev, String kind, boolean last,
            java.util.function.LongSupplier barrierSeq, Reseal reseal) throws IOException, GatewayException {
        String sig = sign(world, lease, epoch, baseRev, kind, last, barrierSeq.getAsLong());
        if (sig == null) {
            return commit(lease, epoch, baseRev, kind, last, null);
        }
        try {
            return commit(lease, epoch, baseRev, kind, last, sig);
        } catch (GatewayException e) {
            if (!e.code.equals("BAD_SIGNATURE") && !e.code.equals("NO_DEVICE_KEY")) {
                throw e;
            }
            if (e.code.equals("BAD_SIGNATURE")) {
                try {
                    reseal.run();
                    return commit(lease, epoch, baseRev, kind, last,
                            sign(world, lease, epoch, baseRev, kind, last, barrierSeq.getAsLong()));
                } catch (GatewayException again) {
                    if (!again.code.equals("BAD_SIGNATURE")) {
                        throw again;
                    }
                }
            }
            registered = false;
            return commit(lease, epoch, baseRev, kind, last, null);
        }
    }
}
