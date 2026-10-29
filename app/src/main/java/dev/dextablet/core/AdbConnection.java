package dev.dextablet.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class AdbConnection implements Closeable {
    public interface Authenticator { byte[] sign(byte[] token) throws Exception; byte[] publicKey() throws Exception; }
    public interface Listener { void authorized(); void failed(IOException error); }
    private final InputStream in;
    private final OutputStream out;
    private final Closeable transport;
    private final Authenticator auth;
    private final Listener listener;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile IOException failure;
    private volatile int maxPayload = 4096;
    private boolean sentSignature;
    public AdbConnection(InputStream in, OutputStream out, Closeable transport, Authenticator auth, Listener listener) {
        this.in=in; this.out=out; this.transport=transport; this.auth=auth; this.listener=listener;
    }
    public void connect(long timeoutMs) throws IOException {
        send(AdbWire.CNXN, 0x01000000, 256 * 1024, AdbWire.utf8z("host::"));
        Thread reader = new Thread(this::readLoop, "adb-reader"); reader.setDaemon(true); reader.start();
        try {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) { close(); throw new IOException("갤럭시에서 USB 디버깅 허용을 누른 뒤 다시 연결하세요"); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); close(); throw new IOException("연결 취소", e); }
        checkOpen();
    }
    private void readLoop() {
        try {
            while (!closed) {
                AdbWire.Packet p = AdbWire.read(in);
                if (p.command == AdbWire.AUTH) {
                    if (p.arg0 != 1) throw new IOException("지원하지 않는 ADB 인증");
                    if (!sentSignature) { sentSignature = true; send(AdbWire.AUTH, 2, 0, auth.sign(p.data)); }
                    else send(AdbWire.AUTH, 3, 0, auth.publicKey());
                } else if (p.command == AdbWire.CNXN) {
                    if (p.arg1 < 4096) throw new IOException("ADB 최대 패킷 오류");
                    maxPayload = Math.min(p.arg1, 256 * 1024); connected.countDown(); listener.authorized();
                } else {
                    Stream stream = streams.get(p.arg1);
                    if (stream == null) {
                        if (p.command == AdbWire.WRTE || p.command == AdbWire.OKAY) send(AdbWire.CLSE, p.arg1, p.arg0, null);
                        continue;
                    }
                    if (p.command == AdbWire.OKAY) stream.okay(p.arg0);
                    else if (p.command == AdbWire.WRTE) {
                        stream.received(p.data); send(AdbWire.OKAY, p.arg1, p.arg0, null);
                    } else if (p.command == AdbWire.CLSE) {
                        streams.remove(p.arg1); stream.ended(); send(AdbWire.CLSE, p.arg1, p.arg0, null);
                    } else throw new IOException("알 수 없는 ADB 명령");
                }
            }
        } catch (Exception e) {
            if (!closed) { failure = e instanceof IOException ? (IOException)e : new IOException("ADB 인증 실패",e); close(); listener.failed(failure); }
        }
    }
    private void checkOpen() throws IOException { if (failure != null) throw failure; if (closed) throw new IOException("USB 연결이 닫혔습니다"); }
    private synchronized void send(int command, int a, int b, byte[] data) throws IOException {
        checkOpen(); AdbWire.write(out, new AdbWire.Packet(command,a,b,data));
    }
    public Stream open(String service) throws IOException {
        checkOpen(); int id = nextId.getAndIncrement(); Stream stream = new Stream(id); streams.put(id,stream);
        try {
            send(AdbWire.OPEN,id,0,AdbWire.utf8z(service)); stream.awaitReady(8000); return stream;
        } catch(IOException e) { stream.close(); throw e; }
    }
    public String shell(String command) throws IOException {
        try (Stream stream = open("shell:" + command)) {
            stream.readTimeoutMs = 10000;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((n=stream.read(b)) >= 0) {
                if (buffer.size()+n > 1024*1024 || System.nanoTime()>deadline) throw new IOException("기기 명령 응답 제한 초과");
                buffer.write(b,0,n);
            }
            return new String(buffer.toByteArray(),StandardCharsets.UTF_8).trim();
        }
    }
    public boolean isClosed() { return closed; }
    @Override public void close() {
        if (closed) return;
        closed = true; connected.countDown();
        try { transport.close(); } catch (IOException ignored) { }
        for (Stream stream : streams.values()) stream.ended(); streams.clear();
    }
    public final class Stream extends InputStream {
        private final int id;
        private int remoteId;
        private boolean ready, credit, ended;
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int queued, offset;
        public volatile long readTimeoutMs;
        Stream(int id) { this.id=id; }
        synchronized void okay(int remote) { remoteId=remote; ready=true; credit=true; notifyAll(); }
        synchronized void received(byte[] data) throws IOException {
            if (queued + data.length > 8*1024*1024) throw new IOException("기기가 영상을 처리하지 못했습니다. 낮은 해상도로 다시 연결하세요");
            if (data.length>0) { chunks.add(data); queued += data.length; } notifyAll();
        }
        synchronized void ended() { ended=true; notifyAll(); }
        synchronized void awaitReady(long timeout) throws IOException {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
            while (!ready && !ended && !closed) await(end);
            // A short shell service may send its output and CLSE before the caller wakes.
            if (!ready || closed) throw new IOException("ADB 서비스가 열리지 않았습니다");
        }
        private void await(long deadline) throws IOException {
            long remaining = deadline-System.nanoTime(); if (remaining<=0) throw new IOException("USB 응답 시간 초과");
            try { TimeUnit.NANOSECONDS.timedWait(this,remaining); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("연결 취소", e); }
        }
        public synchronized void write(byte[] data) throws IOException { write(data,0,data.length); }
        public synchronized void write(byte[] data,int off,int length) throws IOException {
            while (length>0) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (!credit && !ended && !closed) await(deadline);
                if (ended || closed) throw new IOException("ADB 스트림 종료");
                int n=Math.min(length,maxPayload); credit=false;
                send(AdbWire.WRTE,id,remoteId,Arrays.copyOfRange(data,off,off+n)); off+=n; length-=n;
            }
        }
        @Override public int read() throws IOException { byte[] b=new byte[1]; return read(b,0,1)<0 ? -1 : b[0]&255; }
        @Override public synchronized int read(byte[] b,int off,int length) throws IOException {
            if (length==0) return 0;
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
            while (chunks.isEmpty() && !ended && !closed) {
                if (readTimeoutMs>0) await(deadline);
                else try { wait(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            if (chunks.isEmpty()) { if (failure!=null) throw failure; return -1; }
            byte[] chunk=chunks.peek(); int n=Math.min(length,chunk.length-offset); System.arraycopy(chunk,offset,b,off,n);
            offset+=n; queued-=n; if(offset==chunk.length) { chunks.remove(); offset=0; } return n;
        }
        @Override public void close() {
            int remote;
            synchronized(this) { if(ended) return; remote=remoteId; ended=true; notifyAll(); }
            streams.remove(id);
            try { if(!closed) send(AdbWire.CLSE,id,remote,null); } catch(IOException ignored) { }
        }
    }
}
