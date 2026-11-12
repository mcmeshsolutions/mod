package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.MeshBoot;
import java.nio.file.Path;
import net.minecraft.server.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Main.class)
public abstract class ServerMainMixin {
    // runs after mod configs load and before vanilla reads level.dat, so a downloaded world is the one that gets loaded
    @ModifyArg(method = "main", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelStorageSource;createDefault(Ljava/nio/file/Path;)Lnet/minecraft/world/level/storage/LevelStorageSource;"))
    private static Path mcmesh$beforeWorldLoad(Path universe) {
        MeshBoot.beforeWorldLoad(universe);
        return universe;
    }
}
