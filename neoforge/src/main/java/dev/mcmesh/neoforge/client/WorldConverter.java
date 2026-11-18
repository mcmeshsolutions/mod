package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.HostConverter;
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

/** Copies a singleplayer save, creates a world on the gateway, uploads the copy as its first revision, and generates an instant shareable invite link. */
public final class WorldConverter {
    private WorldConverter() { }

    public record Result(String worldId, Path mirror, String inviteCode, String inviteUrl) { }

    public static Result convert(Path save, String displayName, Consumer<String> status) throws Exception {
        if (!HostConverter.isValidWorldSave(save)) {
            throw new IOException("The selected folder is not a valid Minecraft world save.");
        }
        status.accept("Signing in to gateway");
        Identity id = MeshAuth.login();
        GatewayClient gw = new GatewayClient(MeshConfig.API_URL.get(), id.token());
        status.accept("Creating the shared world");
        String worldId = gw.createWorld(displayName, SharedConstants.getCurrentVersion().getName(), "neoforge");
        Path mirror = FMLPaths.GAMEDIR.get().resolve("saves").resolve(HostFlow.saveName(worldId));
        status.accept("Copying the singleplayer save");
        copy(save, mirror);
        status.accept("Uploading chunks and player data");
        MeshService s = MeshService.join(mirror, worldId, id.token(), id.name(), 1, Runtime.getRuntime().availableProcessors());
        if (s == null) {
            throw new IOException("The gateway did not grant the new world to this machine");
        }
        status.accept("Sealing the first revision");
        s.shutdown();

        status.accept("Generating shareable invite link");
        String inviteCode = "";
        String inviteUrl = "";
        try {
            inviteCode = gw.createInvite(worldId, "CLIENT_ONLY");
            inviteUrl = HostConverter.formatInviteLink(MeshConfig.API_URL.get(), inviteCode);
        } catch (Exception ignored) {}

        return new Result(worldId, mirror, inviteCode, inviteUrl);
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
