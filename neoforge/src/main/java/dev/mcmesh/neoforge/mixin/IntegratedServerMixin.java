package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.MeshBoot;
import java.net.InetAddress;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    // only the tunnel agent may reach a mesh-hosted integrated server
    @ModifyArg(method = "publishServer", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"))
    private InetAddress mcmesh$loopbackOnly(InetAddress any) {
        return MeshBoot.held() != null ? InetAddress.getLoopbackAddress() : any;
    }
}
