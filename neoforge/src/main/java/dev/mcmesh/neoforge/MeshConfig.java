package dev.mcmesh.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MeshConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> API_URL;
    public static final ModConfigSpec.ConfigValue<String> RELAY_HOST;
    public static final ModConfigSpec.IntValue RELAY_PORT;
    public static final ModConfigSpec.ConfigValue<String> WORLD;
    public static final ModConfigSpec.ConfigValue<String> TOKEN;
    public static final ModConfigSpec.IntValue LOCAL_PORT;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_APP;
    public static final ModConfigSpec.IntValue VOICE_PORT;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CHANNELS;
    public static final ModConfigSpec.ConfigValue<String> PUBLIC_HOST;
    public static final ModConfigSpec.BooleanValue SHIP_LOGS;
    public static final ModConfigSpec.BooleanValue SHOW_HOST_HEALTH;
    public static final ModConfigSpec.IntValue AUTOSAVE_SECONDS;
    public static final ModConfigSpec.BooleanValue ANCHOR_STANDBY;
    public static final ModConfigSpec.ConfigValue<String> AUTH_MODE;
    public static final ModConfigSpec.ConfigValue<String> PLAYER_NAME;
    public static final ModConfigSpec.ConfigValue<String> PACK_ID;
    public static final ModConfigSpec.ConfigValue<String> PACK_VERSION;
    public static final ModConfigSpec.IntValue PACK_MIN_HEAP_MB;
    public static final ModConfigSpec.ConfigValue<String> PACK_AUTHOR_KEY;
    public static final ModConfigSpec.ConfigValue<String> PACK_UPDATE_URL;
    public static final ModConfigSpec.BooleanValue PACK_AUTO_UPDATE;
    public static final ModConfigSpec.ConfigValue<String> MODPACK_URL;
    public static final ModConfigSpec.ConfigValue<String> MODPACK_INSTANCE;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> GATEWAYS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        PACK_ID = b.comment("Modpack this install belongs to; a gateway only shows and accepts worlds of the same pack").define("packId", "default");
        PACK_VERSION = b.comment("Version of this modpack, dot-separated integers such as 1.4.0").define("packVersion", "0");
        PACK_MIN_HEAP_MB = b.comment("Memory in MB this modpack needs for the game, shipped by the pack author; 0 means no check. Hosting or joining is refused with an explanation when the instance has less.").defineInRange("packMinHeapMb", 0, 0, 262144);
        PACK_AUTHOR_KEY = b.comment("Pack author Ed25519 public key (base64url, 32 bytes); empty disables auto-update").define("packAuthorKey", "");
        PACK_UPDATE_URL = b.comment("https URL of the signed pack manifest; the only place updates come from").define("packUpdateUrl", "");
        PACK_AUTO_UPDATE = b.comment("set to false to never download pack updates").define("packAutoUpdate", true);
        API_URL = b.define("apiUrl", "https://play.23012006.xyz");
        RELAY_HOST = b.define("relayHost", "play.23012006.xyz");
        RELAY_PORT = b.defineInRange("relayPort", 25571, 1, 65535);
        WORLD = b.define("world", "");
        TOKEN = b.define("token", "");
        LOCAL_PORT = b.defineInRange("localServerPort", 25565, 1, 65535);
        VOICE_PORT = b.defineInRange("localVoicePort", 24454, 1, 65535);
        CHANNELS = b.comment("Extra relay channels as local ports, same order as the gateway MCMESH_CHANNELS, e.g. \"24456\"")
                .defineListAllowEmpty("channels", java.util.List.of(), () -> "0", o -> o instanceof String s && s.matches("\\d{1,5}"));
        AUTH_MODE = b.comment("auto (Mojang then key), mojang, or key").define("authMode", "auto");
        PLAYER_NAME = b.comment("name to claim on a dedicated server, where there is no launcher session").define("playerName", "");
        SHIP_LOGS = b.comment("send latest.log to the gateway operator once logged in, for debugging, off by default because logs can contain private details").define("shipLogs", false);
        ANCHOR_STANDBY = b.comment("dedicated server only: when another machine is hosting the world, wait for it to become free instead of refusing to start (anchor node, restart it with systemd)").define("anchorStandby", false);
        SHOW_HOST_HEALTH = b.comment("show a small line in the corner of the screen about how well the host machine is doing").define("showHostHealth", true);
        AUTOSAVE_SECONDS = b.comment("seconds between uploads of the world to the gateway while hosting; a crash loses at most this much play, a smaller number uploads more often").defineInRange("autosaveSeconds", 120, 20, 3600);
        PUBLIC_HOST = b.comment("address players type to reach the gateway; tickets are attached only when connecting to it").define("publicHost", "play.23012006.xyz");
        DISCORD_APP = b.comment("Discord application id for rich presence; empty disables it").define("discordAppId", "");
        MODPACK_URL = b.comment("direct link to a Prism instance export (.zip); empty hides the modpack updater").define("modpackUrl", "");
        MODPACK_INSTANCE = b.comment("folder name of the Prism instance the modpack is installed into; must differ from the running instance").define("modpackInstance", "Mesh Modpack");
        GATEWAYS = b.comment("Extra join-only gateways as \"id=https://api.url\"; hosting always uses apiUrl above")
                .defineListAllowEmpty("gateways", java.util.List.of(), () -> "", o -> o instanceof String);
        SPEC = b.build();
    }

    /** Default gateway first. */
    public static java.util.List<dev.mcmesh.core.GatewayList.Gateway> gateways() {
        return dev.mcmesh.core.GatewayList.parse(API_URL.get(), GATEWAYS.get(),
                e -> com.mojang.logging.LogUtils.getLogger().warn("ignoring gateway entry \"{}\"", e));
    }

    public static java.nio.file.Path trustedGatewaysFile() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mcmesh-trusted-gateways.txt");
    }

    /** Configured pack version, or the one the last applied auto-update installed when that is newer. */
    public static String packVersion() {
        String cfg = PACK_VERSION.get();
        String applied = dev.mcmesh.core.PackSwap.appliedVersion(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
        return applied != null && dev.mcmesh.core.PackCheck.valid(applied) && dev.mcmesh.core.PackCheck.valid(cfg)
                && dev.mcmesh.core.PackCheck.compare(applied, cfg) > 0 ? applied : cfg;
    }

    private MeshConfig() { }
}
