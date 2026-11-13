package dev.mcmesh.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.mcmesh.core.CompatManifest;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/** Reads data/&lt;modid&gt;/mcmesh_compat.json from every loaded mod. Nothing acts on the manifests yet; they are parsed, validated and logged. */
public final class CompatRegistry {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile Map<String, CompatManifest.Parsed> loaded = Map.of();

    private CompatRegistry() { }

    public static Map<String, CompatManifest.Parsed> manifests() {
        return loaded;
    }

    /** A manifest that fails validation is ignored whole and its problems are logged; it never stops startup. */
    static void scan() {
        Map<String, CompatManifest.Parsed> found = new LinkedHashMap<>();
        int refused = 0;
        for (var mod : ModList.get().getMods()) {
            Path file;
            try {
                file = mod.getOwningFile().getFile().findResource("data", mod.getModId(), "mcmesh_compat.json");
            } catch (RuntimeException e) {
                continue;
            }
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject doc = JsonParser.parseReader(r).getAsJsonObject();
                CompatManifest.Result res = CompatManifest.parse(doc);
                if (res.ok()) {
                    found.put(mod.getModId(), res.manifest());
                } else {
                    refused++;
                    LOG.warn("ignoring the compat manifest of {}: {}", mod.getModId(), res.problems());
                }
            } catch (Exception e) {
                refused++;
                LOG.warn("ignoring the unreadable compat manifest of {}: {}", mod.getModId(), e.toString());
            }
        }
        loaded = Map.copyOf(found);
        LOG.info("compat manifests: {} loaded {}, {} refused", found.size(), found.keySet(), refused);
    }
}
