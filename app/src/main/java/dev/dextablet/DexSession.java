package dev.dextablet;

import android.content.*;
import android.hardware.usb.*;
import android.view.Surface;
import dev.dextablet.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One selected USB phone, one owned display, and one scrcpy server. */
public final class DexSession implements Closeable {
    public interface Listener extends MediaReceiver.Listener {
        void status(String message);
        void ready();
        void stopped(String message);
    }
    public static final class Config {
        public int width=1920,height=1280,dpi=240;
        public int fps=60,bitRate=8000000;
        public String decoder;
        public boolean overlay,audio=true,hid=true;
    }
    private final Context context;
    private final UsbDevice target;
    private final Config config;
    private final Listener listener;
    private final Surface surface;
    private final ExecutorService setup=Executors.newSingleThreadExecutor();
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor controls=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256));
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final AtomicBoolean finishing=new AtomicBoolean();
    private final AtomicBoolean fileBusy=new AtomicBoolean();
    private volatile Closeable fileResource;
    private volatile AdbConnection adb;
    private volatile UsbTransport usb;
    private volatile AdbConnection.Stream server,video,audio,control;
    private volatile MediaReceiver media;
    private final CountDownLatch displayCreated=new CountDownLatch(1);
    private volatile int displayId=-1;
    private volatile int previousWindowMode=-1;
    private volatile boolean windowModeChanged;
    private String serverPath,journalKey,ownedOverlay,oldOverlay;
    private final StringBuilder diagnostics=new StringBuilder();
    private volatile boolean ready;
    private volatile boolean videoVisible=true;
    private volatile String endReason="연결이 종료되었습니다";
    public DexSession(Context context,UsbDevice target,Config config,Surface surface,Listener listener) {
        this.context=context.getApplicationContext(); this.target=target; this.config=config; this.surface=surface; this.listener=listener;
    }
    public boolean isReady() { return ready&&!stopping.get(); }
    public boolean matches(UsbDevice device) { return target.getDeviceName().equals(device.getDeviceName()); }
    public void start() {
        setup.execute(()->{
            try { startInternal(); }
            catch(Exception e) { if(!stopping.get()) { endReason=e.getMessage(); listener.failed(e); } stopping.set(true); finish(); }
        });
    }
    private void check() throws IOException { if(stopping.get()) throw new IOException("연결 취소"); }
    private String baseCommand() { return "CLASSPATH="+AdbWire.quote(serverPath)+" app_process / com.genymobile.scrcpy.Server 4.1"; }
    private SharedPreferences journal() { return context.getSharedPreferences("display-recovery",Context.MODE_PRIVATE); }
    private void startInternal() throws Exception {
        listener.status("USB 연결 중 · 갤럭시의 디버깅 허용 알림을 확인하세요"); check();
        usb=new UsbTransport((UsbManager)context.getSystemService(Context.USB_SERVICE),target);
        adb=new AdbConnection(usb.in,usb.out,usb,AdbIdentity.load(context.getFilesDir()),new AdbConnection.Listener() {
            public void authorized() { listener.status("갤럭시 연결 허용됨 · DeX 준비 중"); }
            public void failed(IOException error) { fail(error); }
        });
        check(); adb.connect(60000); check();
        String manufacturer=adb.shell("getprop ro.product.manufacturer");
        if(!manufacturer.equalsIgnoreCase("samsung")) throw new IOException("Samsung DeX를 지원하는 갤럭시를 선택하세요");
        String serial=target.getSerialNumber();
        if(serial!=null&&!serial.isEmpty()) {
            journalKey=hex(MessageDigest.getInstance("SHA-256").digest((target.getVendorId()+":"+serial).getBytes(StandardCharsets.UTF_8)));
            recoverOverlay();
        } else if(config.overlay) throw new IOException("기기 식별이 불가능하여 보조화면 복원을 보장할 수 없습니다. 가상화면 모드를 사용하세요");
        String token=UUID.randomUUID().toString().replace("-","");
        serverPath="/data/local/tmp/dextablet-"+token+".jar";
        listener.status("휴대폰에 화면 전송 모듈 준비 중");
        try(InputStream asset=context.getAssets().open("scrcpy-server-v4.1")) {
            new AdbSync(adb).push(asset,serverPath,0700,n->check());
        }
        check();
        if(config.overlay) createOverlay();
        String scid=String.format(Locale.ROOT,"%08x",new SecureRandom().nextInt()&0x7fffffff);
        String options=" scid="+scid+" log_level=info video=true audio="+config.audio+" audio_codec=raw control=true"
                +" video_codec=h264 video_bit_rate="+config.bitRate+" max_fps="+config.fps+" tunnel_forward=true"
                +" send_device_meta=false send_dummy_byte=false clipboard_autosync=false power_on=false"
                +" cleanup=true vd_destroy_content=false display_ime_policy=local";
        options+=config.overlay?" display_id="+displayId:" new_display="+config.width+"x"+config.height+"/"+config.dpi;
        listener.status("DeX 화면을 시작하고 있습니다");
        server=adb.open("shell:"+baseCommand()+options);
        readServerLog();
        String socket="localabstract:scrcpy_"+scid;
        IOException last=null;
        for(int i=0;i<40&&video==null;i++) {
            check(); try { video=adb.open(socket); } catch(IOException e) { last=e; Thread.sleep(150); }
        }
        if(video==null) throw new IOException("화면 전송 서버 시작 실패: "+(last==null?"응답 없음":last.getMessage()));
        check(); if(config.audio) audio=adb.open(socket); check(); control=adb.open(socket);
        readDeviceMessages();
        if(!config.overlay) {
            if(!displayCreated.await(12,TimeUnit.SECONDS)||displayId<1) throw new IOException("DeX 가상화면 ID를 확인할 수 없습니다");
            check();
            String mode=adb.shell("wm get-display-windowing-mode -d "+displayId);
            previousWindowMode=parseWindowMode(mode);
            if(previousWindowMode>=0&&previousWindowMode!=5) {
                String result=adb.shell("wm set-display-windowing-mode -d "+displayId+" 5");
                String actual=adb.shell("wm get-display-windowing-mode -d "+displayId);
                windowModeChanged=parseWindowMode(actual)==5;
                if(!windowModeChanged) listener.warning("창 이동 모드를 활성화하지 못했습니다. 보조화면 모드로 다시 연결할 수 있습니다");
            } else if(previousWindowMode<0) listener.warning("이 펌웨어의 창 관리 설정을 확인하지 못했습니다. 앱 창 동작을 확인하세요");
        }
        check();
        if(config.hid) control.write(ScrcpyWire.createKeyboard(HidKeyboard.DESCRIPTOR));
        media=new MediaReceiver(config.decoder,new MediaReceiver.Listener() {
            public void size(int w,int h) { listener.size(w,h); }
            public void firstFrame() { listener.firstFrame(); }
            public void warning(String text) { listener.warning(text); }
            public void failed(Exception e) { fail(e); }
        });
        ready=true; listener.ready(); media.startVideo(video,surface); if(audio!=null) media.startAudio(audio);
    }
    private static int parseWindowMode(String mode) {
        if(mode.contains("windowing mode=freeform")) return 5;
        if(mode.contains("windowing mode=fullscreen")) return 1;
        if(mode.contains("windowing mode=undefined")) return 0;
        return -1;
    }
    private void createOverlay() throws Exception {
        oldOverlay=adb.shell("settings get global overlay_display_devices");
        if(!DisplayGuard.empty(oldOverlay)) throw new IOException("휴대폰에 다른 보조화면이 이미 있습니다. 기존 화면을 보존하기 위해 시작을 중단했습니다");
        Set<Integer> before=DisplayGuard.ids(adb.shell(baseCommand()+" list_displays=true"));
        ownedOverlay=config.width+"x"+config.height+"/"+config.dpi;
        if(!journal().edit().putString(journalKey+".owned",ownedOverlay).putString(journalKey+".old",oldOverlay).commit()) throw new IOException("복원 기록 저장 실패");
        adb.shell("settings put global overlay_display_devices "+AdbWire.quote(ownedOverlay));
        if(!ownedOverlay.equals(adb.shell("settings get global overlay_display_devices"))) throw new IOException("보조화면 설정이 적용되지 않았습니다");
        for(int i=0;i<20;i++) {
            check(); String displays=adb.shell(baseCommand()+" list_displays=true");
            displayId=DisplayGuard.uniqueNewDisplay(before,displays,config.width,config.height);
            if(displayId>0) return; Thread.sleep(150);
        }
        throw new IOException("새 보조화면을 안전하게 구별하지 못했습니다");
    }
    private void recoverOverlay() throws IOException {
        String owned=journal().getString(journalKey+".owned",null); if(owned==null) return;
        String previous=journal().getString(journalKey+".old","null");
        String current=adb.shell("settings get global overlay_display_devices");
        if(DisplayGuard.mayRestore(current,owned)) {
            listener.status("이전 연결의 보조화면 설정 복원 중"); restoreValue(previous);
            String restored=adb.shell("settings get global overlay_display_devices");
            if(!(previous.equals(restored)||(DisplayGuard.empty(previous)&&DisplayGuard.empty(restored)))) throw new IOException("보조화면 복원 실패. 휴대폰의 개발자 옵션에서 보조 디스플레이를 없음으로 바꾸세요");
        } else if(!DisplayGuard.empty(current)) {
            listener.warning("보조화면 설정이 다른 값으로 변경되어 자동 복원을 건너뛰었습니다");
        }
        journal().edit().remove(journalKey+".owned").remove(journalKey+".old").commit();
    }
    private void restoreValue(String previous) throws IOException {
        adb.shell(DisplayGuard.empty(previous)?"settings delete global overlay_display_devices":"settings put global overlay_display_devices "+AdbWire.quote(previous));
    }
    private void readServerLog() {
        Thread thread=new Thread(()->{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(server,StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null&&!stopping.get()) {
                    synchronized(diagnostics) { if(diagnostics.length()>6000) diagnostics.delete(0,3000); diagnostics.append(line).append('\n'); }
                    int id=DisplayGuard.virtualId(line);
                    if(id>0) { displayId=id; displayCreated.countDown(); }
                    if(line.contains("UHID")&&(line.contains("ERROR")||line.contains("Could not")||line.contains("Failed")))
                        listener.warning("물리 키보드 연결 실패. 종료 후 기본 키보드 모드로 다시 연결하세요");
                }
                if(!stopping.get()) fail(new IOException("휴대폰의 화면 전송 서버가 종료되었습니다"));
            } catch(IOException e) { if(!stopping.get()) fail(e); }
        },"dex-server-log"); thread.setDaemon(true); thread.start();
    }
    private void readDeviceMessages() {
        Thread t=new Thread(()->{
            try {
                DataInputStream in=new DataInputStream(control);
                while(!stopping.get()) {
                    int type=in.readUnsignedByte();
                    if(type==0) { int length=in.readInt(); if(length<0||length>262144) throw new IOException("클립보드 응답 오류"); AdbWire.readFully(in,length); }
                    else if(type==1) in.readLong();
                    else if(type==2) { in.readUnsignedShort(); int length=in.readUnsignedShort(); AdbWire.readFully(in,length); }
                    else throw new IOException("입력 응답 오류");
                }
            } catch(IOException e) { if(!stopping.get()) fail(e); }
        },"dex-input-reply"); t.setDaemon(true); t.start();
    }
    public void send(byte[] bytes) {
        if(!isReady()||bytes==null) return;
        try { controls.execute(()->{ try { if(!stopping.get()) control.write(bytes); } catch(IOException e) { fail(e); } }); }
        catch(RejectedExecutionException e) { if(!stopping.get()) fail(new IOException("입력 전달이 지연되었습니다. 다시 연결하세요")); }
    }
    public void mute(boolean mute) { if(media!=null) media.mute(mute); }
    public void setVideoVisible(boolean visible){
        boolean changed=videoVisible!=visible;videoVisible=visible;
        if(media!=null)media.setVideoVisible(visible);
        // Re-encode a static desktop after frames were discarded while the document picker was open.
        if(visible&&changed)send(new byte[]{17});
    }
    public String diagnosticReport(){
        synchronized(diagnostics){return "DeX Tablet 0.2.0 / scrcpy 4.1\nMode: "+(config.overlay?"overlay":"virtual")
                +"\nRequested: "+config.width+"x"+config.height+"/"+config.dpi+"\nDisplay: "+displayId
                +"\nDecoder: "+config.decoder+"\nMaximum FPS: "+config.fps+"\nBitrate: "+config.bitRate
                +"\nResult: "+endReason+"\n\n"+diagnostics;}
    }
    public void transferUpload(InputStream input,String name,AdbSync.Progress progress,java.util.function.Consumer<String> complete) {
        if(!beginFile(input)){complete.accept(null);return;}
        Runnable task=()->{
            String destination=null,finishedPath=null;
            try(InputStream stream=input) {
                check(); String safe=(name==null?"file":name).replaceAll("[\\\\/\\p{Cntrl}]","_"); if(safe.isEmpty()) safe="file";
                if(safe.length()>100) safe=safe.substring(safe.length()-100);
                String dir="/sdcard/Download/DeXTablet";
                String result=adb.shell("mkdir -p "+AdbWire.quote(dir)+" && echo DEXTABLET_OK");
                if(!result.endsWith("DEXTABLET_OK")) throw new IOException("갤럭시 다운로드 폴더를 만들 수 없습니다");
                destination=dir+"/"+UUID.randomUUID()+"_"+safe;
                new AdbSync(adb).push(stream,destination,0644,n->{check();progress.bytes(n);});finishedPath=destination;
            } catch(Exception e) {
                finishedPath=null;
                if(!stopping.get())listener.warning("파일 보내기 실패: "+e.getMessage());
                if(destination!=null&&adb!=null&&!adb.isClosed())try{adb.shell("rm -f "+AdbWire.quote(destination));}catch(IOException ignored){}
            } finally {fileResource=null;fileBusy.set(false);}
            if(finishedPath!=null)send(ScrcpyWire.scanFile(finishedPath));complete.accept(finishedPath);
        };
        try{commands.execute(task);}catch(RejectedExecutionException e){closeFileResource();fileBusy.set(false);complete.accept(null);}
    }
    public void listFiles(String path,java.util.function.Consumer<List<AdbSync.Entry>> callback) {
        try{commands.execute(()->{try { check(); callback.accept(new AdbSync(adb).list(path)); }
        catch(Exception e) { if(!stopping.get())listener.warning("파일 목록 읽기 실패: "+e.getMessage()); callback.accept(null); }});}
        catch(RejectedExecutionException e){callback.accept(null);}
    }
    public void transferDownload(String path,OutputStream output,AdbSync.Progress progress,java.util.function.Consumer<Boolean> complete) {
        if(!beginFile(output)){complete.accept(false);return;}
        Runnable task=()->{boolean success=false;
            try(OutputStream out=output){check();new AdbSync(adb).pull(path,out,n->{check();progress.bytes(n);});out.flush();success=true;}
            catch(Exception e){success=false;if(!stopping.get())listener.warning("파일 받기 실패: "+e.getMessage());}
            finally{fileResource=null;fileBusy.set(false);}
            complete.accept(success);
        };
        try{commands.execute(task);}catch(RejectedExecutionException e){closeFileResource();fileBusy.set(false);complete.accept(false);}
    }
    private boolean beginFile(Closeable resource){
        if(!isReady()||!fileBusy.compareAndSet(false,true)){try{resource.close();}catch(IOException ignored){}listener.warning("현재 파일 전송이 끝난 뒤 다시 시도하세요");return false;}
        fileResource=resource;return true;
    }
    private void closeFileResource(){Closeable f=fileResource;if(f!=null)try{f.close();}catch(IOException ignored){}}
    private void fail(Exception error) {
        if(stopping.compareAndSet(false,true)) { endReason=error.getMessage(); listener.failed(error); scheduleFinish(); }
    }
    public void stop(String reason) {
        if(stopping.compareAndSet(false,true)) {
            endReason=reason;
            // During authorization/startup, interrupt the blocking USB wait before queuing cleanup.
            if(!ready&&adb!=null) adb.close();
            scheduleFinish();
        }
    }
    private void scheduleFinish() { try { setup.execute(this::finish); } catch(RejectedExecutionException ignored) { finish(); } }
    private void finish() {
        if(!finishing.compareAndSet(false,true)) return;
        stopping.set(true); ready=false;
        listener.status("연결 종료 및 화면 설정 정리 중");
        controls.shutdownNow();commands.shutdown();closeFileResource();
        if(media!=null) media.close();
        if(adb!=null&&!adb.isClosed()) {
            if(windowModeChanged&&displayId>0) try {
                if(parseWindowMode(adb.shell("wm get-display-windowing-mode -d "+displayId))==5)
                    adb.shell("wm set-display-windowing-mode -d "+displayId+" "+previousWindowMode);
            } catch(IOException ignored) { }
        }
        closeStream(control); closeStream(video); closeStream(audio); closeStream(server);
        if(adb!=null&&!adb.isClosed()) {
            try { if(journalKey!=null) recoverOverlay(); }
            catch(IOException e) { endReason+=" · 보조화면 복원이 남았습니다. 같은 갤럭시를 다시 연결하거나 개발자 옵션에서 보조 디스플레이를 없음으로 설정하세요"; }
            try { if(serverPath!=null) adb.shell("rm -f "+AdbWire.quote(serverPath)); } catch(IOException ignored) { }
        } else if(ownedOverlay!=null) endReason+=" · 같은 갤럭시를 다시 연결하면 보조화면 복원을 시도합니다";
        if(adb!=null) adb.close(); if(usb!=null) usb.close(); setup.shutdown();
        listener.stopped(endReason);
    }
    private static void closeStream(AdbConnection.Stream s) { if(s!=null) s.close(); }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    @Override public void close() { stop("연결이 종료되었습니다"); }
}
