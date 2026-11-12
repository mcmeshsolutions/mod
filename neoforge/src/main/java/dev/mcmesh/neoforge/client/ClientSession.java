package dev.mcmesh.neoforge.client;

import com.mojang.authlib.exceptions.AuthenticationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/** Client-only: asks Mojang to vouch for this player against the gateway's challenge. */
public final class ClientSession {
    private ClientSession() { }

    public static String name() {
        return Minecraft.getInstance().getUser().getName();
    }

    /** Throws when the session is not a valid Mojang one (unofficial launcher, offline, Mojang down). */
    public static void joinServer(String serverId) throws AuthenticationException {
        User u = Minecraft.getInstance().getUser();
        Minecraft.getInstance().getMinecraftSessionService().joinServer(u.getProfileId(), u.getAccessToken(), serverId);
    }
}
