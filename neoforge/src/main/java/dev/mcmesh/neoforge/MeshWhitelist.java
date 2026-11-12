package dev.mcmesh.neoforge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;

/** Per-world join whitelist stored in the world folder. Gateway membership is separate and managed in the web panel. */
public final class MeshWhitelist {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Set<String> names = new TreeSet<>();
    private static volatile boolean enabled;
    private static volatile Path file;

    private MeshWhitelist() { }

    public static synchronized void load(Path root) {
        file = root.resolve("mesh/whitelist.json");
        names.clear();
        enabled = false;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
            if (o.has("names")) {
                o.getAsJsonArray("names").forEach(e -> names.add(e.getAsString().toLowerCase(Locale.ROOT)));
            }
        } catch (Exception e) {
            LOG.error("unreadable {}, whitelist stays off", file, e);
            file = null;
        }
    }

    public static synchronized void unload() {
        file = null;
        names.clear();
        enabled = false;
    }

    public static boolean active() {
        return file != null;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static synchronized boolean allows(String name) {
        return !enabled || names.contains(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized List<String> list() {
        return List.copyOf(names);
    }

    public static synchronized void add(String name) {
        names.add(name.toLowerCase(Locale.ROOT));
        save();
    }

    public static synchronized void remove(String name) {
        names.remove(name.toLowerCase(Locale.ROOT));
        save();
    }

    public static synchronized void enabled(boolean v) {
        enabled = v;
        save();
    }

    private static void save() {
        if (file == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        JsonArray arr = new JsonArray();
        names.forEach(arr::add);
        o.add("names", arr);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("whitelist.json.tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("could not save {}", file, e);
        }
    }
}
