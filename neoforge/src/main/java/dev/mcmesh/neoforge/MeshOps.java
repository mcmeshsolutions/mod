package dev.mcmesh.neoforge;

import com.google.gson.Gson;
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

/** Operator list and the "everyone is an operator" switch, kept in the world folder so it follows the world between hosts. */
public final class MeshOps {
    static final String FILE = "mesh/ops.json";
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Set<String> ops = new TreeSet<>();
    private static volatile boolean opAll;
    private static volatile Path file;

    private MeshOps() { }

    /** Loads the list for a world; a world without one starts with just the founding host as operator. */
    public static synchronized void load(Path root, String founder) {
        file = root.resolve(FILE);
        ops.clear();
        opAll = false;
        if (Files.isRegularFile(file)) {
            try {
                JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                opAll = o.has("opAll") && o.get("opAll").getAsBoolean();
                if (o.has("ops")) {
                    o.getAsJsonArray("ops").forEach(e -> ops.add(e.getAsString().toLowerCase(Locale.ROOT)));
                }
                return;
            } catch (Exception e) {
                LOG.error("unreadable {}, keeping it and starting with no operators", file, e);
                file = null;
                return;
            }
        }
        if (founder != null && !founder.isEmpty()) {
            ops.add(founder.toLowerCase(Locale.ROOT));
            save();
        }
    }

    public static synchronized void unload() {
        file = null;
        ops.clear();
        opAll = false;
    }

    public static boolean active() {
        return file != null;
    }

    public static synchronized boolean isOp(String name) {
        return opAll || ops.contains(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized boolean isListed(String name) {
        return ops.contains(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized boolean opAll() {
        return opAll;
    }

    public static synchronized List<String> list() {
        return List.copyOf(ops);
    }

    public static synchronized boolean add(String name) {
        boolean r = ops.add(name.toLowerCase(Locale.ROOT));
        save();
        return r;
    }

    public static synchronized boolean remove(String name) {
        boolean r = ops.remove(name.toLowerCase(Locale.ROOT));
        save();
        return r;
    }

    public static synchronized void opAll(boolean v) {
        opAll = v;
        save();
    }

    private static void save() {
        if (file == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("opAll", opAll);
        var arr = new com.google.gson.JsonArray();
        ops.forEach(arr::add);
        o.add("ops", arr);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("ops.json.tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("could not save {}", file, e);
        }
    }
}
