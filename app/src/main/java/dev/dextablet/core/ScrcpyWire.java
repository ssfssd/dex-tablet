package dev.dextablet.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Wire format pinned to upstream scrcpy v4.1. */
public final class ScrcpyWire {
    public static final int H264=0x68323634, RAW=0x00726177;
    public static final long CONFIG=1L<<62, KEY=1L<<61;
    private ScrcpyWire() { }
    public static final class Frame {
        public final boolean session,config,key;
        public final int width,height;
        public final long pts;
        public final byte[] data;
        private Frame(boolean session,int width,int height,long bits,byte[] data) {
            this.session=session; this.width=width; this.height=height;
            this.config=(bits&CONFIG)!=0; this.key=(bits&KEY)!=0; this.pts=bits&((1L<<61)-1); this.data=data;
        }
    }
    public static int codec(InputStream input) throws IOException { return new DataInputStream(input).readInt(); }
    public static Frame frame(InputStream input) throws IOException {
        ByteBuffer b=ByteBuffer.wrap(AdbWire.readFully(input,12));
        int flags=b.getInt();
        if((flags&0x80000000)!=0) {
            int w=b.getInt(),h=b.getInt();
            if(w<1||h<1||w>8192||h>8192) throw new IOException("지원하지 않는 영상 크기");
            return new Frame(true,w,h,0,null);
        }
        b.rewind(); long bits=b.getLong(); int size=b.getInt();
        if(size<0||size>8*1024*1024) throw new IOException("영상 패킷 크기 오류");
        return new Frame(false,0,0,bits,AdbWire.readFully(input,size));
    }
    public static byte[] key(int action,int code,int repeat,int meta) {
        return ByteBuffer.allocate(14).put((byte)0).put((byte)action).putInt(code).putInt(repeat).putInt(meta).array();
    }
    public static byte[] touch(int action,long pointer,int x,int y,int w,int h,float pressure,int actionButton,int buttons) {
        return ByteBuffer.allocate(32).put((byte)2).put((byte)action).putLong(pointer).putInt(x).putInt(y)
                .putShort((short)w).putShort((short)h).putShort((short)Math.round(Math.max(0,Math.min(1,pressure))*65535))
                .putInt(actionButton).putInt(buttons).array();
    }
    public static byte[] scroll(int x,int y,int w,int h,float horizontal,float vertical,int buttons) {
        return ByteBuffer.allocate(21).put((byte)3).putInt(x).putInt(y).putShort((short)w).putShort((short)h)
                .putShort(scrollUnit(horizontal)).putShort(scrollUnit(vertical)).putInt(buttons).array();
    }
    private static short scrollUnit(float value) { return (short)Math.max(-32768,Math.min(32767,Math.round(value*2048))); }
    public static byte[] createKeyboard(byte[] descriptor) {
        byte[] name="DeX Tablet Keyboard".getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(10+name.length+descriptor.length).put((byte)12).putShort((short)1)
                .putShort((short)0).putShort((short)0).put((byte)name.length).put(name).putShort((short)descriptor.length).put(descriptor).array();
    }
    public static byte[] keyboardReport(byte[] report) {
        return ByteBuffer.allocate(5+report.length).put((byte)13).putShort((short)1).putShort((short)report.length).put(report).array();
    }
    public static byte[] paste(String text) {
        byte[] b=text.getBytes(StandardCharsets.UTF_8);
        if(b.length>256*1024-14) throw new IllegalArgumentException("붙여넣을 내용이 너무 깁니다");
        return ByteBuffer.allocate(14+b.length).put((byte)9).putLong(0).put((byte)1).putInt(b.length).put(b).array();
    }
    public static byte[] scanFile(String path) {
        byte[] b=path.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(5+b.length).put((byte)22).putInt(b.length).put(b).array();
    }
}
