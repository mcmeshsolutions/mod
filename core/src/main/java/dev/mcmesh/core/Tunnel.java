package dev.mcmesh.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Frame codec; see gatewayd/src/tunnel.rs for the format. */
public final class Tunnel {
    public static final int MAX_PAYLOAD = 64 * 1024;
    public static final byte HELLO = 1, HELLO_OK = 2, ERR = 3, OPEN = 4, DATA = 5, CLOSE = 6, UDP = 7, UDP_CLOSE = 8, PING = 9, PONG = 10;
    public static final byte CUDP = 11;

    public record Frame(byte type, int id, byte[] data) {}

    private Tunnel() {}

    public static Frame read(DataInputStream in) throws IOException {
        byte type = in.readByte();
        int id = in.readInt();
        int len = in.readInt();
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("frame too large: " + len);
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return new Frame(type, id, data);
    }

    public static void write(OutputStream out, byte type, int id, byte[] data, int off, int len) throws IOException {
        if (len > MAX_PAYLOAD) {
            throw new IOException("frame too large: " + len);
        }
        ByteBuffer b = ByteBuffer.allocate(9 + len);
        b.put(type).putInt(id).putInt(len).put(data, off, len);
        out.write(b.array());
        out.flush();
    }
}
