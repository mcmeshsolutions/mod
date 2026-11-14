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

/** Reads data/&lt;modid&gt;/mcmesh_compat.json from every loaded mod. Tick limits (max_tick_micros) and ephemeral dimensions are applied; the other keys are parsed and kept. */
public final class CompatRegistry {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile Map<String, CompatManifest.Parsed> loaded = Map.of();
    private static volatile dev.mcmesh.core.CompatRules rules = dev.mcmesh.core.CompatRules.NONE;

    private CompatRegistry() { }

    public static Map<String, CompatManifest.Parsed> manifests() {
        return loaded;
    }

    public static dev.mcmesh.core.CompatRules rules() {
        return rules;
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
        // a pack author can supply or override a manifest for any mod: config/mcmesh-compat/<modid>.json
        Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mcmesh-compat");
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    String id = f.getFileName().toString().replaceAll("\\.json$", "");
                    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        CompatManifest.Result res = CompatManifest.parse(JsonParser.parseReader(r).getAsJsonObject());
                        if (res.ok()) {
                            found.put(id, res.manifest());
                        } else {
                            refused++;
                            LOG.warn("ignoring the pack compat manifest {}: {}", f.getFileName(), res.problems());
                        }
                    } catch (Exception e) {
                        refused++;
                        LOG.warn("ignoring the unreadable pack compat manifest {}: {}", f.getFileName(), e.toString());
                    }
                }
            } catch (java.io.IOException e) {
                LOG.warn("could not read {}: {}", dir, e.toString());
            }
        }
        loaded = Map.copyOf(found);
        rules =dev.mcmesh.core.CompatRules.of(loaded);
        dev.mcmesh.core.ChunkUploader.skipDimension = rules::ephemeral;
        LOG.info("compat manifests: {} loaded {}, {} refused", found.size(), found.keySet(), refused);
    }
}
