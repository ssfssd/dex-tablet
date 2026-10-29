package dev.dextablet.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** ADB wire protocol. No Android dependency: exercised with a synthetic peer. */
public final class AdbWire {
    public static final int CNXN = command("CNXN"), AUTH = command("AUTH"), OPEN = command("OPEN"),
            OKAY = command("OKAY"), WRTE = command("WRTE"), CLSE = command("CLSE");
    public static final int MAX_PAYLOAD = 1024 * 1024;
    private AdbWire() { }
    public static int command(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    public static byte[] utf8z(String s) { return (s + "\0").getBytes(StandardCharsets.UTF_8); }
    public static byte[] readFully(InputStream in, int count) throws IOException {
        if (count < 0 || count > 16 * 1024 * 1024) throw new IOException("잘못된 패킷 크기");
        byte[] result = new byte[count];
        int off = 0;
        while (off < count) {
            int n = in.read(result, off, count - off);
            if (n < 0) throw new EOFException("연결이 닫혔습니다");
            if (n == 0) continue;
            off += n;
        }
        return result;
    }
    public static int checksum(byte[] b) { int sum = 0; for (byte v : b) sum += v & 255; return sum; }
    public static final class Packet {
        public final int command, arg0, arg1;
        public final byte[] data;
        public Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1;
            this.data = data == null ? new byte[0] : data;
        }
    }
    public static Packet read(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(readFully(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), a = b.getInt(), c = b.getInt(), length = b.getInt(), checksum = b.getInt(), magic = b.getInt();
        if (magic != (command ^ -1) || length < 0 || length > MAX_PAYLOAD) throw new IOException("ADB 헤더 오류");
        byte[] data = readFully(in, length);
        // We negotiate the legacy checksummed protocol, including on modern adbd.
        if (checksum(data) != checksum) throw new IOException("ADB 체크섬 오류");
        return new Packet(command, a, c, data);
    }
    public static void write(OutputStream out, Packet p) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(p.command).putInt(p.arg0).putInt(p.arg1).putInt(p.data.length).putInt(checksum(p.data)).putInt(p.command ^ -1);
        out.write(h.array());
        if (p.data.length > 0) out.write(p.data);
        out.flush();
    }
    public static String quote(String s) { return "'" + s.replace("'", "'\"'\"'") + "'"; }
}
