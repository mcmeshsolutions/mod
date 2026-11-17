package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.MeshBoot;
import java.net.InetAddress;
import java.net.UnknownHostException;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    // must be explicitly IPv4 127.0.0.1: on Windows 11 with Java 21, InetAddress.getLoopbackAddress()
    // returns IPv6 ::1, which refuses connections from TunnelAgent targeting 127.0.0.1
    private static final InetAddress LOOPBACK_IPV4;

    static {
        try {
            LOOPBACK_IPV4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // only the tunnel agent may reach a mesh-hosted integrated server
    @ModifyArg(method = "publishServer", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"))
    private InetAddress mcmesh$loopbackOnly(InetAddress any) {
        return MeshBoot.held() != null ? LOOPBACK_IPV4 : any;
    }
}
