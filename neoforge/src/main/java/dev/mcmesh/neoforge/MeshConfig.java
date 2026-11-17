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
    public static final ModConfigSpec.BooleanValue HUD_ALWAYS_VISIBLE;
    public static final ModConfigSpec.IntValue AUTOSAVE_SECONDS;
    public static final ModConfigSpec.IntValue STREAM_SECONDS;
    public static final ModConfigSpec.BooleanValue DISTRIBUTE_WORLDGEN;
    public static final ModConfigSpec.BooleanValue ANCHOR_STANDBY;
    public static final ModConfigSpec.BooleanValue ANCHOR_WAKE_ON_JOIN;
    public static final ModConfigSpec.BooleanValue GUEST_WARM_SYNC;
    public static final ModConfigSpec.BooleanValue SHARD_STANDBY;
    public static final ModConfigSpec.BooleanValue SHARD_OFFERS;
    public static final ModConfigSpec.BooleanValue CONTRIBUTE_WORLDGEN;
    public static final ModConfigSpec.ConfigValue<String> WORKER_HEAP;
    public static final ModConfigSpec.IntValue ANCHOR_IDLE_MINUTES;
    public static final ModConfigSpec.IntValue CHAT_RETENTION_DAYS;
    public static final ModConfigSpec.IntValue REPORT_CHAT_LINES;
    public static final ModConfigSpec.BooleanValue LAZY_BOOTSTRAP;
    public static final ModConfigSpec.IntValue LAZY_MIN_CHUNKS;
    public static final ModConfigSpec.IntValue LAZY_RADIUS_REGIONS;
    public static final ModConfigSpec.IntValue LAZY_SMALL_DIM_CHUNKS;
    public static final ModConfigSpec.IntValue SYNC_THREADS;
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
        PACK_VERSION = b.comment("Version of this modpack, dot-separated integers such as 1.4.0").define("packVersion", "1");
        PACK_MIN_HEAP_MB = b.comment("Memory in MB this modpack needs for the game, shipped by the pack author; 0 means no check. Hosting or joining is refused with an explanation when the instance has less.").defineInRange("packMinHeapMb", 0, 0, 262144);
        PACK_AUTHOR_KEY = b.comment("Pack author Ed25519 public key (base64url, 32 bytes); empty disables auto-update").define("packAuthorKey", "GaIjyKsDGyhXjC5QNC7Hbcgx49W6mCYjaMtkJ49i9qA");
        PACK_UPDATE_URL = b.comment("https URL of the signed pack manifest; the only place updates come from").define("packUpdateUrl", "https://play.23012006.xyz/dl/pack-manifest.json");
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
        ANCHOR_WAKE_ON_JOIN = b.comment("anchor only: do not take the world just because it is free, wait until someone tries to play it, and hand it back after anchorIdleMinutes with nobody on. Keeps a modded world from running all night for nobody. Needs a gateway that answers GET /v1/worlds/<id>/demand").define("anchorWakeOnJoin", false);
        SHARD_STANDBY = b.comment("dedicated server only, experimental: instead of hosting the world, wait for the machine that hosts it to set regions aside and host just those (a shard). Needs its own level-name: the folder is wiped and refilled with only the shard's regions").define("shardStandby", false);
        SHARD_OFFERS = b.comment("host only, experimental: allow the op command /meshshard to give regions of this world to a machine that waits as a shard host").define("shardOffers", false);
        GUEST_WARM_SYNC = b.comment("client only: while playing as a guest, keep this machine's copy of the world synced to the newest revision (about every 30 s, incremental), so a takeover of hosting from this machine fetches only the last changes, and this machine can keep playing solo if the host leaves. Shown top-right while syncing. Costs bandwidth and disk while playing; the first run downloads the whole world").define("guestWarmSync", true);
        CONTRIBUTE_WORLDGEN = b.comment("client only, off until tested on real machines: while this machine is not hosting, generate worldgen tiles for a host that asked for them, in a hidden low priority server process started from this game (one tile per process, stopped when you start hosting). Needs the world owner to have turned distributed worldgen on").define("contributeWorldgen", false);
        WORKER_HEAP = b.comment("memory for the hidden worldgen worker, for example 3G").define("workerHeap", "3G");
        ANCHOR_IDLE_MINUTES = b.comment("anchor with wake on join: minutes with no players before the world is saved and handed back, so the machine goes idle. 0 keeps it up forever").defineInRange("anchorIdleMinutes", 10, 0, 1440);
        CHAT_RETENTION_DAYS = b.comment("while hosting, keep player chat in logs/mesh-chat (one file per day, on this machine only, never uploaded) for this many days so the owner can read it when a report comes in. 0 keeps nothing and deletes what was kept. Players are not told by the game, say so in your rules").defineInRange("chatRetentionDays", 0, 0, 90);
        REPORT_CHAT_LINES = b.comment("while hosting, remember the last N chat lines in memory only (never written to disk) so a player can run /meshreport chat <player> <reason> and attach them for the world owner. 0 turns the memory and the command off").defineInRange("reportChatLines", 20, 0, 100);
        LAZY_BOOTSTRAP = b.comment("on a full download, start the world once the regions near spawn and the players are on disk and fetch the rest in the background or when the game first needs it. Off by default").define("lazyBootstrap", false);
        LAZY_MIN_CHUNKS = b.comment("lazy bootstrap only applies to worlds with at least this many stored chunk entries (region, entities and poi count separately)").defineInRange("lazyMinChunks", 20000, 1, Integer.MAX_VALUE);
        LAZY_RADIUS_REGIONS = b.comment("regions (512 blocks) around spawn and each player that are downloaded before the world opens").defineInRange("lazyRadiusRegions", 2, 0, 16);
        LAZY_SMALL_DIM_CHUNKS = b.comment("a dimension with no spawn or player in it and at most this many entries is downloaded whole before the world opens").defineInRange("lazySmallDimChunks", 4096, 0, Integer.MAX_VALUE);
        SYNC_THREADS = b.comment("parallel connections for the HTTP fallback only - the normal blob-stream sync pipelines every fetch over "
                + "one already-open connection with no concurrency cap of its own, so this setting no longer affects it. Only used when no "
                + "blob-stream connection is available at all, or to retry a handful of fetches that failed over it; each one here is its "
                + "own raw TCP connection, so a flaky network or busy gateway may need this lower")
                .defineInRange("syncThreads", 4, 1, 16);
        SHOW_HOST_HEALTH = b.comment("show a small line in the corner of the screen about how well the host machine is doing").define("showHostHealth", true);
        HUD_ALWAYS_VISIBLE = b.comment("the host/guest status line, lazy bootstrap and worldgen progress, and the F9 debug map draw during normal play; "
                + "set to false to see them only while the pause menu (Esc) is open, since they can be distracting otherwise").define("hudAlwaysVisible", true);
        AUTOSAVE_SECONDS = b.comment("seconds between uploads of the world to the gateway while hosting; a crash loses at most this much play, a smaller number uploads more often").defineInRange("autosaveSeconds", 120, 20, 3600);
        DISTRIBUTE_WORLDGEN = b.comment("Host only: ask the gateway for tiles of unexplored terrain ahead of the players, generated by other members' machines and checked by a second one, and install them before the game opens that region. The world owner must also switch it on for the world, and it only helps once workers run (deploy/worldgen-worker.sh). Off by default").define("distributeWorldgen", false);
        STREAM_SECONDS = b.comment("0 turns streaming off. Otherwise every this many seconds the changes since the last upload are sent to the gateway without making a revision, so a crash or a power cut loses at most this much play; the gateway recovers them from its write-ahead log. Costs a little disk and network on the host").defineInRange("streamSeconds", 0, 0, 120);
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
