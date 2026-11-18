package dev.mcmesh.neoforge.client;

import com.google.gson.JsonObject;
import dev.mcmesh.neoforge.MeshConfig;
import dev.mcmesh.neoforge.MeshService;
import dev.mcmesh.neoforge.MeshTap;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Rich presence over Discord's local IPC. Silent no-op without Discord or without a configured application id. */
@EventBusSubscriber(modid = "mcmesh", value = Dist.CLIENT)
public final class DiscordPresence {
    private static final Object LOCK = new Object();
    private static Channel chan;
    private static long since;

    private DiscordPresence() { }

    private interface Channel extends AutoCloseable {
        void write(ByteBuffer b) throws IOException;

        void readFully(ByteBuffer b) throws IOException;
    }

    @SubscribeEvent
    public static void in(ClientPlayerNetworkEvent.LoggingIn e) {
        String app = MeshConfig.DISCORD_APP.get();
        boolean mesh = MeshService.current() != null || !MeshTap.joinWorld.isEmpty();
        if (app.isEmpty() || !mesh) {
            return;
        }
        String role = MeshService.current() != null ? "Hosting a shared world" : "Playing in a shared world";
        since = System.currentTimeMillis() / 1000;
        Thread.ofVirtual().name("mesh-discord").start(() -> set(app, role));
    }

    @SubscribeEvent
    public static void out(ClientPlayerNetworkEvent.LoggingOut e) {
        Thread.ofVirtual().name("mesh-discord").start(DiscordPresence::clear);
    }

    private static void set(String app, String role) {
        synchronized (LOCK) {
            try {
                if (chan == null) {
                    chan = connect(app);
                }
                JsonObject act = new JsonObject();
                String envPack = System.getenv("MCMESH_PACK_NAME");
                String packName = (envPack != null && !envPack.isBlank()) ? envPack
                        : ("default".equals(MeshConfig.PACK_ID.get()) ? "mcmesh" : MeshConfig.PACK_ID.get());
                act.addProperty("state", packName);
                JsonObject ts = new JsonObject();
                ts.addProperty("start", since);
                act.add("timestamps", ts);
                send(1, command(act));
            } catch (Exception ex) {
                drop();
            }
        }
    }

    private static void clear() {
        synchronized (LOCK) {
            if (chan == null) {
                return;
            }
            try {
                send(1, command(null));
            } catch (Exception ex) {
                drop();
            }
        }
    }

    private static JsonObject command(JsonObject activity) {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) {
            args.add("activity", activity);
        }
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "SET_ACTIVITY");
        o.add("args", args);
        o.addProperty("nonce", UUID.randomUUID().toString());
        return o;
    }

    private static void drop() {
        try {
            if (chan != null) {
                chan.close();
            }
        } catch (Exception ignored) {
        }
        chan = null;
    }

    private static void send(int op, JsonObject body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer f = ByteBuffer.allocate(8 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        f.putInt(op).putInt(b.length).put(b).flip();
        chan.write(f);
        ByteBuffer h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        chan.readFully(h);
        h.flip();
        h.getInt();
        ByteBuffer rest = ByteBuffer.allocate(Math.min(h.getInt(), 1 << 16));
        chan.readFully(rest);
    }

    private static Channel connect(String app) throws IOException {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (int i = 0; i < 10; i++) {
            try {
                Channel c = win ? pipe(i) : socket(i);
                if (c == null) {
                    continue;
                }
                chan = c;
                JsonObject hs = new JsonObject();
                hs.addProperty("v", 1);
                hs.addProperty("client_id", app);
                send(0, hs);
                return c;
            } catch (IOException ignored) {
                chan = null;
            }
        }
        throw new IOException("no discord");
    }

    private static Channel pipe(int i) throws IOException {
        RandomAccessFile f = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
        return new Channel() {
            public void write(ByteBuffer b) throws IOException {
                byte[] a = new byte[b.remaining()];
                b.get(a);
                f.write(a);
            }

            public void readFully(ByteBuffer b) throws IOException {
                byte[] a = new byte[b.remaining()];
                f.readFully(a);
                b.put(a);
            }

            public void close() throws IOException {
                f.close();
            }
        };
    }

    private static Channel socket(int i) throws IOException {
        String[] bases = {System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), "/tmp"};
        String[] subs = {"", "app/com.discordapp.Discord/", "snap.discord/"};
        for (String base : bases) {
            if (base == null) {
                continue;
            }
            for (String sub : subs) {
                Path p = Path.of(base, sub + "discord-ipc-" + i);
                if (!Files.exists(p)) {
                    continue;
                }
                SocketChannel sc = SocketChannel.open(StandardProtocolFamily.UNIX);
                try {
                    sc.connect(UnixDomainSocketAddress.of(p));
                } catch (IOException e) {
                    sc.close();
                    continue;
                }
                return new Channel() {
                    public void write(ByteBuffer b) throws IOException {
                        while (b.hasRemaining()) {
                            sc.write(b);
                        }
                    }

                    public void readFully(ByteBuffer b) throws IOException {
                        while (b.hasRemaining()) {
                            if (sc.read(b) < 0) {
                                throw new IOException("closed");
                            }
                        }
                    }

                    public void close() throws IOException {
                        sc.close();
                    }
                };
            }
        }
        return null;
    }
}
