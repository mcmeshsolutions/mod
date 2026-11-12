package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.MeshTap;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
    @Shadow
    @org.spongepowered.asm.mixin.Final
    private Path folder;

    @Inject(method = "write", at = @At("HEAD"))
    private void mcmesh$tap(ChunkPos pos, CompoundTag tag, CallbackInfo ci) throws IOException {
        if (!MeshTap.active()) {
            return;
        }
        String kind = folder.getFileName().toString();
        if (!kind.equals("region") && !kind.equals("entities") && !kind.equals("poi")) {
            return;
        }
        String dim = MeshTap.dimOf(folder.getParent());
        if (dim == null) {
            return;
        }
        if (tag == null) {
            MeshTap.delete(dim, kind, pos.x, pos.z);
            return;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            NbtIo.write(tag, out);
        }
        MeshTap.put(dim, kind, pos.x, pos.z, bos.toByteArray());
    }
}
