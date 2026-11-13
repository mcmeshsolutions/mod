package dev.mcmesh.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** The default gateway plus the join-only extra gateways from the config. */
public final class GatewayList {
    public static final String DEFAULT = "default";

    public record Gateway(String id, String apiUrl) {
        public boolean isDefault() {
            return id.equals(DEFAULT);
        }

        public String host() {
            String h = URI.create(apiUrl).getHost();
            return h == null ? apiUrl : h;
        }
    }

    private GatewayList() { }

    private static String trim(String url) {
        return url.strip().replaceAll("/+$", "");
    }

    /** entries are "id=apiUrl"; anything malformed, duplicated or not https (loopback excepted) goes to skipped. */
    public static List<Gateway> parse(String defaultUrl, List<? extends String> entries, Consumer<String> skipped) {
        List<Gateway> out = new ArrayList<>();
        out.add(new Gateway(DEFAULT, trim(defaultUrl)));
        Set<String> ids = new HashSet<>(Set.of(DEFAULT));
        Set<String> urls = new HashSet<>(Set.of(trim(defaultUrl)));
        for (String e : entries) {
            int eq = e.indexOf('=');
            String id = eq < 0 ? "" : e.substring(0, eq).strip();
            String url = eq < 0 ? "" : trim(e.substring(eq + 1));
            if (!id.matches("[A-Za-z0-9._-]{1,32}") || !safeUrl(url) || !ids.add(id) || !urls.add(url)) {
                skipped.accept(e);
                continue;
            }
            out.add(new Gateway(id, url));
        }
        return out;
    }

    private static boolean safeUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null) {
                return false;
            }
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            return "https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && loopback);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean trusted(Path file, String apiUrl) throws IOException {
        return Files.isRegularFile(file) && Files.readAllLines(file).stream().anyMatch(l -> trim(l).equals(trim(apiUrl)));
    }

    public static void trust(Path file, String apiUrl) throws IOException {
        if (trusted(file, apiUrl)) {
            return;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, trim(apiUrl) + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
