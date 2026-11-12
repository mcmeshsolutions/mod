package dev.mcmesh.neoforge;

/** Mixins call this instead of core types, because the mixin transformer cannot resolve jar-in-jar classes. */
public final class MeshTap {
    private static volatile java.nio.file.Path root;

    private MeshTap() { }

    static void setRoot(java.nio.file.Path r) {
        root = r;
    }

    public static String dimOf(java.nio.file.Path dimDir) {
        java.nio.file.Path r = root;
        return r == null || dimDir == null ? null : dev.mcmesh.core.WorldPaths.dimOf(r, dimDir);
    }

    public static boolean active() {
        return MeshService.current() != null;
    }

    public static void put(String dim, String kind, int cx, int cz, byte[] data) {
        MeshService s = MeshService.current();
        if (s != null) {
            s.uploader().put(dim, kind, cx, cz, data);
        }
    }

    public static void delete(String dim, String kind, int cx, int cz) {
        MeshService s = MeshService.current();
        if (s != null) {
            s.uploader().delete(dim, kind, cx, cz);
        }
    }

    public static volatile String joinHost = "";
    public static volatile String joinWorld = "";
    /** World a plain server-list join to the gateway uses when the config names none. */
    public static volatile String defaultWorld = "";

    /** Appends a gateway ticket to the handshake host name when logging in to the mesh gateway. */
    public static String handshakeHost(String host, String intent) {
        String pub = joinHost.isEmpty() ? MeshConfig.PUBLIC_HOST.get() : joinHost;
        String world = joinHost.isEmpty() ? (MeshConfig.WORLD.get().isEmpty() ? defaultWorld : MeshConfig.WORLD.get()) : joinWorld;
        // vanilla sends the reverse-resolved hostname, so an explicit join cannot be matched by name
        boolean explicit = !joinHost.isEmpty();
        if (pub.isEmpty() || (!explicit && !pub.equalsIgnoreCase(host)) || intent.equals("STATUS")
                || !net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return host;
        }
        try {
            String token = MeshAuth.cached().token();
            String ticket = new dev.mcmesh.core.GatewayClient(MeshConfig.API_URL.get(), token).ticket(world);
            return host + "\0mesh=" + ticket;
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().error("mesh ticket failed, connecting without one", e);
            return host;
        }
    }
}
