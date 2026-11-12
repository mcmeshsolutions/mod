package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.Identity;
import dev.mcmesh.neoforge.MeshAuth;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.neoforged.fml.loading.FMLPaths;

/** Copies a singleplayer save, creates a world on the gateway and uploads the copy as its first revision. */
public final class WorldConverter {
    private WorldConverter() { }

    public record Result(String worldId, Path mirror) { }

    public static Result convert(Path save, String displayName, Consumer<String> status) throws Exception {
        status.accept("Signing in");
        Identity id = MeshAuth.login();
        GatewayClient gw = new GatewayClient(MeshConfig.API_URL.get(), id.token());
        status.accept("Creating the shared world");
        String worldId = gw.createWorld(displayName, SharedConstants.getCurrentVersion().getName(), "neoforge");
        Path mirror = FMLPaths.GAMEDIR.get().resolve("saves").resolve(HostFlow.saveName(worldId));
        status.accept("Copying the save");
        copy(save, mirror);
        status.accept("Uploading chunks and player data");
        MeshService s = MeshService.join(mirror, worldId, id.token(), id.name(), 1, Runtime.getRuntime().availableProcessors());
        if (s == null) {
            throw new IOException("the gateway did not grant the new world to this machine");
        }
        status.accept("Sealing the first revision");
        s.shutdown();
        return new Result(worldId, mirror);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String name = p.getFileName().toString();
                if (name.equals("session.lock") || name.equals("mcmesh.rev")) {
                    continue;
                }
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        }
    }
}
