package dev.mcmesh.neoforge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Names everything that decides what a seed generates, so a worker is only ever compared with peers that should agree
 * with it. It is deliberately short: the pack identity stands for "the same server side mods and configs", the pack author
 * signs that, and the second peer's digest is the real check. A profile that is too coarse costs a rejected tile; one
 * that is too fine (the mod list would differ between a client and a dedicated server) means no worker ever matches.
 */
final class WorldgenProfile {
    /** Bumped together with GEN_VERSION on the gateway when the worker procedure changes what it produces. */
    static final int PROCEDURE = 1;
    private static volatile String cached;
    private static volatile String described = "";

    private WorldgenProfile() { }

    static String of(MinecraftServer server) {
        String c = cached;
        if (c == null) {
            synchronized (WorldgenProfile.class) {
                if (cached == null) {
                    cached = compute(server);
                }
                c = cached;
            }
        }
        return c;
    }

    /** The parts, for the log line that says why two machines do not match. */
    static String describe() {
        return described;
    }

    private static String compute(MinecraftServer server) {
        Map<String, String> p = new TreeMap<>();
        p.put("pack", MeshConfig.PACK_ID.get() + "@" + MeshConfig.packVersion());
        p.put("game", SharedConstants.getCurrentVersion().getName());
        p.put("neoforge", ModList.get().getModContainerById("neoforge").map(m -> m.getModInfo().getVersion().toString()).orElse("?"));
        var opts = server.getWorldData().worldGenOptions();
        p.put("seed", Long.toString(opts.seed()));
        p.put("structures", Boolean.toString(opts.generateStructures()));
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        p.put("generator", stem == null ? "?" : stem.generator().getClass().getName() + "/" + stem.generator().getBiomeSource().getClass().getName());
        // mod load order decides TerraBlender's region indices (board/RESEARCH.md 1d), so peers on different values never agree
        p.put("maxThreads", maxThreads());
        p.put("procedure", Integer.toString(PROCEDURE));
        described = p.toString();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(described.getBytes(StandardCharsets.UTF_8));
            return "wg" + PROCEDURE + ":" + HexFormat.of().formatHex(d, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String maxThreads() {
        try {
            Path f = FMLPaths.CONFIGDIR.get().resolve("fml.toml");
            for (String line : Files.readAllLines(f)) {
                String t = line.trim();
                if (t.startsWith("maxThreads")) {
                    return t.substring(t.indexOf('=') + 1).trim();
                }
            }
        } catch (Exception ignored) {
            // no fml.toml is the loader's default
        }
        return "default";
    }
}
