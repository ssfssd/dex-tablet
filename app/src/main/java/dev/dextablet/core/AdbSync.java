package dev.dextablet.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only user-selected files are transferred. Paths are not shell interpolated. */
public final class AdbSync {
    private final AdbConnection adb;
    public interface Progress { void bytes(long n) throws IOException; }
    public AdbSync(AdbConnection adb) { this.adb=adb; }
    private static byte[] message(String command, byte[] data) {
        ByteBuffer b=ByteBuffer.allocate(8+data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(AdbWire.command(command)).putInt(data.length).put(data); return b.array();
    }
    private static ByteBuffer header(InputStream stream) throws IOException { return ByteBuffer.wrap(AdbWire.readFully(stream,8)).order(ByteOrder.LITTLE_ENDIAN); }
    private static IOException failure(InputStream stream, int length) throws IOException {
        if(length<0||length>4096) return new IOException("파일 전송 실패");
        return new IOException("파일 전송 실패: "+new String(AdbWire.readFully(stream,length),StandardCharsets.UTF_8));
    }
    public void push(InputStream input,String path,int mode,Progress progress) throws IOException {
        try(AdbConnection.Stream s=adb.open("sync:")) {
            s.readTimeoutMs=15000;
            s.write(message("SEND",(path+","+mode).getBytes(StandardCharsets.UTF_8)));
            byte[] b=new byte[32*1024]; int n; long total=0;
            while((n=input.read(b))>=0) {
                if(n==0) continue;
                s.write(message("DATA",Arrays.copyOf(b,n))); total+=n; progress.bytes(total);
            }
            ByteBuffer done=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            done.putInt(AdbWire.command("DONE")).putInt((int)(System.currentTimeMillis()/1000)); s.write(done.array());
            ByteBuffer h=header(s); int id=h.getInt(), length=h.getInt();
            if(id==AdbWire.command("FAIL")) throw failure(s,length);
            if(id!=AdbWire.OKAY || length!=0) throw new IOException("파일 저장 확인 실패");
        }
    }
    public void pull(String path,OutputStream output,Progress progress) throws IOException {
        try(AdbConnection.Stream s=adb.open("sync:")) {
            s.readTimeoutMs=15000; s.write(message("RECV",path.getBytes(StandardCharsets.UTF_8))); long total=0;
            while(true) {
                ByteBuffer h=header(s); int id=h.getInt(),length=h.getInt();
                if(id==AdbWire.command("DONE")) return;
                if(id==AdbWire.command("FAIL")) throw failure(s,length);
                if(id!=AdbWire.command("DATA") || length<0 || length>65536) throw new IOException("파일 패킷 오류");
                output.write(AdbWire.readFully(s,length)); total+=length; progress.bytes(total);
            }
        }
    }
    public static final class Entry {
        public final String name; public final boolean directory; public final long size;
        Entry(String name,int mode,long size) { this.name=name; this.directory=(mode&0170000)==0040000; this.size=size; }
    }
    public List<Entry> list(String path) throws IOException {
        try(AdbConnection.Stream s=adb.open("sync:")) {
            s.readTimeoutMs=10000; s.write(message("LIST",path.getBytes(StandardCharsets.UTF_8)));
            List<Entry> result=new ArrayList<>();
            while(true) {
                int id=ByteBuffer.wrap(AdbWire.readFully(s,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if(id==AdbWire.command("FAIL")) throw failure(s,ByteBuffer.wrap(AdbWire.readFully(s,4)).order(ByteOrder.LITTLE_ENDIAN).getInt());
                ByteBuffer h=ByteBuffer.wrap(AdbWire.readFully(s,16)).order(ByteOrder.LITTLE_ENDIAN);
                if(id==AdbWire.command("DONE")) break;
                if(id!=AdbWire.command("DENT")) throw new IOException("파일 목록 오류");
                int mode=h.getInt(); long size=Integer.toUnsignedLong(h.getInt()); h.getInt(); int len=h.getInt();
                if(len<1||len>4096||result.size()>5000) throw new IOException("파일 목록 제한 초과");
                String name=new String(AdbWire.readFully(s,len),StandardCharsets.UTF_8);
                if(!name.equals(".")&&!name.equals("..")&&!name.contains("/")&&!name.contains("\0")) result.add(new Entry(name,mode,size));
            }
            result.sort(Comparator.comparing((Entry e)->!e.directory).thenComparing(e->e.name)); return result;
        }
    }
}
