package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.LazyGate;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every read, scan and write of a region goes through getRegionFile; a region still waiting on the gateway is fetched first. */
@Mixin(RegionFileStorage.class)
public abstract class RegionGuardMixin {
    @Shadow
    @org.spongepowered.asm.mixin.Final
    private Path folder;

    @Inject(method = "getRegionFile", at = @At("HEAD"))
    private void mcmesh$faultIn(ChunkPos pos, CallbackInfoReturnable<RegionFile> cir) throws IOException {
        LazyGate.before(folder, pos.x, pos.z);
    }
}
