package dev.dextablet.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Cipher;

/** No phone, credentials or personal files: all peer/file data is synthetic. */
public final class CoreTests {
    interface Checked { void run() throws Exception; }
    static int passed;
    static void test(String name,Checked body) throws Exception {
        body.run();passed++;System.out.println("PASS "+name);
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void bytes(byte[] a,byte[] b){if(!Arrays.equals(a,b))throw new AssertionError("byte mismatch");}
    static void rejects(Checked f) throws Exception {try{f.run();}catch(IOException|IllegalArgumentException expected){return;}throw new AssertionError("expected rejection");}
    static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static byte[] packet(AdbWire.Packet p)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();AdbWire.write(b,p);return b.toByteArray();}
    static ByteArrayInputStream input(byte[] b){return new ByteArrayInputStream(b);}
    static byte[] concat(byte[]...parts)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p);return b.toByteArray();}
    static byte[] media(long flags,byte[] data){return ByteBuffer.allocate(12+data.length).putLong(flags).putInt(data.length).put(data).array();}
    static byte[] session(int w,int h){return ByteBuffer.allocate(12).putInt(0x80000000).putInt(w).putInt(h).array();}
    static byte[] sync(String id,byte[] data){return ByteBuffer.allocate(8+data.length).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.command(id)).putInt(data.length).put(data).array();}
    static byte[] dent(String name,int mode,int size){byte[] b=utf(name);return ByteBuffer.allocate(20+b.length).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.command("DENT")).putInt(mode).putInt(size).putInt(0).putInt(b.length).put(b).array();}

    public static void main(String[] args)throws Exception{
        test("ADB round trip and fragmented reads",()->{
            byte[] data=new byte[32123];new Random(1).nextBytes(data);
            byte[] encoded=packet(new AdbWire.Packet(AdbWire.WRTE,17,23,data));
            InputStream split=new FilterInputStream(input(encoded)){public int read(byte[] b,int off,int n)throws IOException{return super.read(b,off,Math.min(n,7));}};
            AdbWire.Packet got=AdbWire.read(split);equal(got.arg0,17);equal(got.arg1,23);bytes(got.data,data);
        });
        test("ADB rejects bad magic and checksum",()->{
            byte[] p=packet(new AdbWire.Packet(AdbWire.WRTE,1,2,new byte[]{1,2,3}));p[20]^=1;rejects(()->AdbWire.read(input(p)));p[20]^=1;p[25]^=1;rejects(()->AdbWire.read(input(p)));
        });
        test("ADB rejects oversized and truncated input",()->{
            byte[] p=packet(new AdbWire.Packet(AdbWire.WRTE,1,2,new byte[]{7}));ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(12,AdbWire.MAX_PAYLOAD+1);rejects(()->AdbWire.read(input(p)));
            rejects(()->AdbWire.read(input(new byte[2])));rejects(()->AdbWire.readFully(input(new byte[0]),-1));
        });
        test("Shell filename quotation remains literal",()->equal(AdbWire.quote("a'b;$(id)"),"'a'\"'\"'b;$(id)'"));
        test("RSA authentication signs the raw ADB challenge",()->{
            KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair k=g.generateKeyPair();
            AdbIdentity id=new AdbIdentity(k);byte[] challenge=new byte[20];new Random(2).nextBytes(challenge);
            Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.DECRYPT_MODE,k.getPublic());byte[] digest=c.doFinal(id.sign(challenge));equal(digest.length,35);bytes(Arrays.copyOfRange(digest,15,35),challenge);
            rejects(()->id.sign(new byte[19]));
            String key=new String(id.publicKey(),StandardCharsets.US_ASCII);check(key.endsWith("\0"));byte[] wire=Base64.getDecoder().decode(key.split(" ")[0]);equal(wire.length,524);
            ByteBuffer b=ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);equal(b.getInt(),64);long inverse=Integer.toUnsignedLong(b.getInt());
            byte[] n=new byte[256];b.get(n);BigInteger modulus=little(n);equal(modulus,((RSAPublicKey)k.getPublic()).getModulus());
            equal(modulus.multiply(BigInteger.valueOf(inverse)).and(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)),BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE));
            byte[] rr=new byte[256];b.get(rr);equal(little(rr),BigInteger.ONE.shiftLeft(4096).mod(modulus));equal(b.getInt(),65537);
        });
        test("ADB identity persists in the app-owned directory",()->{
            File dir=new File(args[0],"synthetic-identity");check(dir.mkdir());AdbIdentity a=AdbIdentity.load(dir),b=AdbIdentity.load(dir);bytes(a.publicKey(),b.publicKey());
        });
        test("Short completed shell stream retains output",()->{
            AdbConnection adb=dummy();AdbConnection.Stream s=adb.new Stream(1);s.okay(2);s.received(utf("done"));s.ended();s.awaitReady(50);bytes(AdbWire.readFully(s,4),utf("done"));equal(s.read(),-1);adb.close();
        });
        test("Rejected service, timeout and bounded stream queue",()->{
            AdbConnection adb=dummy();AdbConnection.Stream rejected=adb.new Stream(1);rejected.ended();rejects(()->rejected.awaitReady(5));
            AdbConnection.Stream s=adb.new Stream(2);s.okay(3);s.readTimeoutMs=5;rejects(s::read);s.received(new byte[8*1024*1024]);rejects(()->s.received(new byte[]{1}));s.close();adb.close();
        });
        test("Stream close wakes a blocked stream reader",()->{
            AdbConnection adb=dummy();AdbConnection.Stream s=adb.new Stream(1);s.okay(2);AtomicInteger value=new AtomicInteger(7);
            Thread t=new Thread(()->{try{value.set(s.read());}catch(IOException e){value.set(-2);}});t.setDaemon(true);t.start();s.close();t.join(1000);check(!t.isAlive());equal(value.get(),-1);adb.close();
        });
        test("scrcpy 4.1 sessions, configuration, timestamps and resize",()->{
            InputStream s=input(concat(session(1920,1280),media(ScrcpyWire.CONFIG,new byte[]{0,0,1,103}),media(ScrcpyWire.KEY|123456L,new byte[]{9}),session(1280,1920)));
            ScrcpyWire.Frame f=ScrcpyWire.frame(s);check(f.session);equal(f.width,1920);equal(f.height,1280);
            f=ScrcpyWire.frame(s);check(f.config&&!f.key);equal(f.pts,0L);equal(f.data.length,4);
            f=ScrcpyWire.frame(s);check(f.key&&!f.config);equal(f.pts,123456L);
            f=ScrcpyWire.frame(s);check(f.session);equal(f.width,1280);equal(f.height,1920);
        });
        test("Media rejects oversized dimensions, lengths and truncation",()->{
            rejects(()->ScrcpyWire.frame(input(session(0,1080))));rejects(()->ScrcpyWire.frame(input(session(9000,1080))));
            rejects(()->ScrcpyWire.frame(input(ByteBuffer.allocate(12).putLong(0).putInt(8*1024*1024+1).array())));
            rejects(()->ScrcpyWire.frame(input(ByteBuffer.allocate(12).putLong(0).putInt(1).array())));
        });
        test("Pointer fields match the upstream big-endian protocol",()->{
            ByteBuffer b=ByteBuffer.wrap(ScrcpyWire.touch(0,-1,100,200,1920,1280,1,1,3));equal(b.remaining(),32);equal(b.get(),(byte)2);equal(b.get(),(byte)0);equal(b.getLong(),-1L);equal(b.getInt(),100);equal(b.getInt(),200);equal(b.getShort()&65535,1920);equal(b.getShort()&65535,1280);equal(b.getShort()&65535,65535);equal(b.getInt(),1);equal(b.getInt(),3);
        });
        test("Scroll fixed point saturates and retains direction",()->{
            ByteBuffer b=ByteBuffer.wrap(ScrcpyWire.scroll(1,2,1920,1280,30,-1.5f,0));b.position(13);equal(b.getShort(),(short)32767);equal(b.getShort(),(short)-3072);
        });
        test("Explicit clipboard paste preserves Korean UTF-8",()->{
            String text="한글 English 😀";ByteBuffer b=ByteBuffer.wrap(ScrcpyWire.paste(text));equal(b.get(),(byte)9);equal(b.getLong(),0L);equal(b.get(),(byte)1);equal(b.getInt(),utf(text).length);byte[] value=new byte[b.remaining()];b.get(value);bytes(value,utf(text));
            rejects(()->ScrcpyWire.paste(new String(new char[262144])));
        });
        test("UHID creation and input have valid lengths",()->{
            ByteBuffer b=ByteBuffer.wrap(ScrcpyWire.createKeyboard(HidKeyboard.DESCRIPTOR));equal(b.get(),(byte)12);equal(b.getShort(),(short)1);b.position(7);int name=b.get()&255;b.position(b.position()+name);equal(b.getShort()&65535,HidKeyboard.DESCRIPTOR.length);equal(b.remaining(),HidKeyboard.DESCRIPTOR.length);
            b=ByteBuffer.wrap(ScrcpyWire.keyboardReport(new byte[8]));equal(b.remaining(),13);equal(b.get(),(byte)13);equal(b.getShort(),(short)1);equal(b.getShort(),(short)8);
        });
        test("Keyboard chords, repeat, Korean switch and release",()->{
            HidKeyboard k=new HidKeyboard();equal(k.event(113,true)[0],(byte)1);byte[] r=k.event(29,true);equal(r[0],(byte)1);equal(r[2],(byte)4);bytes(r,k.event(29,true));r=k.event(29,false);equal(r[2],(byte)0);equal(r[0],(byte)1);bytes(k.release(),new byte[8]);equal(k.event(204,true)[2],(byte)144);equal(k.event(999,true),null);
        });
        test("Keyboard rollover preserves modifiers",()->{
            HidKeyboard k=new HidKeyboard();for(int key=29;key<36;key++)k.event(key,true);byte[] r=k.event(113,true);equal(r[0],(byte)1);for(int i=2;i<8;i++)equal(r[i],(byte)1);r=k.event(35,false);equal(r[2],(byte)4);equal(r[7],(byte)9);
        });
        test("Letterbox clicks are rejected, drags clamp to the screen",()->{
            Viewport v=new Viewport(1000,1000,1920,1080);check(v.map(500,100,false)==null);int[] p=v.map(500,500,false);equal(p[0],960);equal(p[1],540);p=v.map(-30,1200,true);equal(p[0],0);equal(p[1],1079);
            check(new Viewport(0,0,1920,1080).map(0,0,false)==null);
        });
        test("Viewport round trips for changing aspect ratios",()->{
            Random r=new Random(3);for(int i=0;i<100;i++){int w=100+r.nextInt(3000),h=100+r.nextInt(2000);Viewport v=new Viewport(500+r.nextInt(1500),500+r.nextInt(1500),w,h);int x=r.nextInt(w),y=r.nextInt(h);int[] p=v.map(v.left+(x+.5f)*v.width/w,v.top+(y+.5f)*v.height/h,false);equal(p[0],x);equal(p[1],y);}
        });
        test("Only the uniquely created display can be selected",()->{
            String list="--display-id=0 (1440x3120)\n--display-id=4 (1920x1280)";Set<Integer> before=DisplayGuard.ids("--display-id=0 (1440x3120)");equal(DisplayGuard.uniqueNewDisplay(before,list,1920,1280),4);equal(DisplayGuard.uniqueNewDisplay(before,list+"\n--display-id=5 (1920x1280)",1920,1280),-1);equal(DisplayGuard.uniqueNewDisplay(DisplayGuard.ids(list),list,1920,1280),-1);equal(DisplayGuard.virtualId("[server] INFO: New display: 1920x1280/240 (id=6)"),6);
        });
        test("Recovery never overwrites a changed overlay setting",()->{
            check(DisplayGuard.mayRestore("1920x1280/240","1920x1280/240"));check(!DisplayGuard.mayRestore("other","1920x1280/240"));check(!DisplayGuard.mayRestore("null",null));check(DisplayGuard.empty("null"));check(DisplayGuard.empty(""));
        });
        test("Generic tablet profiles preserve 16:10, 4:3 and portrait aspect ratios",()->{
            for(int[] size:new int[][]{{2560,1600},{2048,1536},{1200,1920},{1920,1280}}){
                DisplayProfile p=DisplayProfile.choose(size[0],size[1],0,(w,h,fps)->"fixture-decoder");
                check(Math.max(p.width,p.height)<=1920);equal(p.width%16,0);equal(p.height%16,0);
                check(Math.abs((double)p.width/p.height-(double)size[0]/size[1])<0.04);
                check(p.dpi>=120&&p.dpi<=320);check(p.bitRate>=2000000&&p.bitRate<=12000000);
            }
        });
        test("Generic profile reduces frame rate before resolution",()->{
            DisplayProfile p=DisplayProfile.choose(2560,1600,0,(w,h,fps)->fps<=30?"slow-decoder":null);
            equal(p.width,1920);equal(p.fps,30);equal(p.decoder,"slow-decoder");
        });
        test("Generic profile falls back to a decoder-supported resolution",()->{
            DisplayProfile p=DisplayProfile.choose(2560,1600,1,(w,h,fps)->w<=1280&&h<=800&&fps<=30?"limited-decoder":null);
            equal(p.width,1280);equal(p.height,800);equal(p.fps,30);
        });
        test("Light profile limits resolution and frame rate without upscaling",()->{
            DisplayProfile large=DisplayProfile.choose(3840,2400,2,(w,h,fps)->"fixture");equal(large.width,1280);equal(large.fps,30);
            DisplayProfile small=DisplayProfile.choose(1024,600,0,(w,h,fps)->"fixture");check(small.width<=1024&&small.height<=600);
        });
        test("Unsupported tablet profiles fail before changing the phone",()->{
            rejects(()->DisplayProfile.choose(0,800,0,(w,h,fps)->"fixture"));
            rejects(()->DisplayProfile.choose(1920,1200,0,(w,h,fps)->null));
            rejects(()->DisplayProfile.choose(100,100,0,(w,h,fps)->"fixture"));
        });
        try(Peer peer=new Peer()){
            peer.adb.connect(3000);
            test("Synthetic USB peer authenticates then runs shell",()->{equal(peer.authSteps.get(),2);equal(peer.adb.shell("fixture"),"synthetic result");});
            test("ADB multiplexing and ACK flow control split large writes",()->{
                byte[] payload=new byte[9001];new Random(4).nextBytes(payload);
                try(AdbConnection.Stream a=peer.adb.open("echo:" );AdbConnection.Stream b=peer.adb.open("echo:")){
                    a.readTimeoutMs=2000;b.readTimeoutMs=2000;a.write(payload);b.write(utf("parallel"));bytes(AdbWire.readFully(a,payload.length),payload);bytes(AdbWire.readFully(b,8),utf("parallel"));check(peer.maxWrite.get()<=4096);
                }
            });
            test("ADB rejects a service closed before OPEN acknowledgement",()->rejects(()->peer.adb.open("missing:")));
            test("SYNC upload preserves a large binary file",()->{
                byte[] payload=new byte[100003];new Random(5).nextBytes(payload);AtomicLong progress=new AtomicLong();new AdbSync(peer.adb).push(input(payload),"/synthetic/a'한글.bin",0644,progress::set);bytes(peer.uploaded,payload);equal(peer.uploadName,"/synthetic/a'한글.bin,420");equal(progress.get(),(long)payload.length);
            });
            test("SYNC download, directory filtering and unsigned file size",()->{
                ByteArrayOutputStream out=new ByteArrayOutputStream();new AdbSync(peer.adb).pull("/valid",out,n->{});bytes(out.toByteArray(),Peer.FILE);
                List<AdbSync.Entry> items=new AdbSync(peer.adb).list("/synthetic");equal(items.size(),2);equal(items.get(0).name,"folder");check(items.get(0).directory);equal(items.get(1).size,4294967295L);equal(items.get(1).name,"한글.bin");
            });
            test("SYNC reports remote failure and rejects invalid data frames",()->{
                rejects(()->new AdbSync(peer.adb).pull("/error",new ByteArrayOutputStream(),n->{}));rejects(()->new AdbSync(peer.adb).pull("/oversized",new ByteArrayOutputStream(),n->{}));
            });
            test("Canceled transfer closes its stream and leaves the connection usable",()->{
                rejects(()->new AdbSync(peer.adb).push(input(new byte[100000]),"/synthetic/canceled",0644,n->{throw new IOException("synthetic cancel");}));equal(peer.adb.shell("fixture"),"synthetic result");
            });
            test("USB transport closure wakes readers on open services",()->{
                AdbConnection.Stream s=peer.adb.open("silent:");AtomicInteger result=new AtomicInteger(7);
                Thread reader=new Thread(()->{try{result.set(s.read());}catch(IOException e){result.set(-2);}});reader.setDaemon(true);reader.start();
                peer.adb.close();reader.join(1000);check(!reader.isAlive());equal(result.get(),-1);
            });
            peer.verify();
        }
        System.out.println("RESULT: "+passed+" test groups passed (synthetic peer; no Android device).");
    }
    static BigInteger little(byte[] b){byte[] reverse=b.clone();for(int i=0;i<b.length;i++)reverse[i]=b[b.length-1-i];return new BigInteger(1,reverse);}
    static AdbConnection dummy(){return new AdbConnection(input(new byte[0]),new ByteArrayOutputStream(),()->{},new AdbConnection.Authenticator(){public byte[]sign(byte[] t){return new byte[256];}public byte[]publicKey(){return utf("fixture\0");}},new AdbConnection.Listener(){public void authorized(){}public void failed(IOException e){}});}

    /** Real AdbConnection/AdbSync code talks to this fragmented, ACK-driven phone simulator. */
    static final class Peer implements AutoCloseable {
        static final byte[] FILE=new byte[7007];static{new Random(8).nextBytes(FILE);}
        final PipedInputStream fromClient=new PipedInputStream(1024*1024),toClient=new PipedInputStream(1024*1024);
        final PipedOutputStream clientOut,serverOut;
        final AtomicInteger authSteps=new AtomicInteger(),maxWrite=new AtomicInteger();
        final AtomicReference<Throwable> problem=new AtomicReference<>();
        final AdbConnection adb;final Thread thread;final Map<Integer,Channel> channels=new HashMap<>();
        volatile boolean closed;volatile byte[] uploaded;volatile String uploadName;
        Peer()throws IOException{
            clientOut=new PipedOutputStream(fromClient);serverOut=new PipedOutputStream(toClient);
            adb=new AdbConnection(toClient,clientOut,()->{closed=true;fromClient.close();toClient.close();clientOut.close();serverOut.close();},new AdbConnection.Authenticator(){public byte[]sign(byte[] token){equal(token.length,20);return new byte[256];}public byte[]publicKey(){return utf("fixture-public-key\0");}},new AdbConnection.Listener(){public void authorized(){}public void failed(IOException e){if(!closed)problem.compareAndSet(null,e);}});
            thread=new Thread(this::run,"synthetic-phone");thread.setDaemon(true);thread.start();
        }
        void send(int command,int a,int b,byte[] data)throws IOException{
            byte[] p=packet(new AdbWire.Packet(command,a,b,data));for(int i=0;i<p.length;i+=13)serverOut.write(p,i,Math.min(13,p.length-i));serverOut.flush();
        }
        void run(){try{
            while(!closed){AdbWire.Packet p=AdbWire.read(fromClient);
                if(p.command==AdbWire.CNXN)send(AdbWire.AUTH,1,0,new byte[20]);
                else if(p.command==AdbWire.AUTH){int step=authSteps.incrementAndGet();if(step==1){equal(p.arg0,2);equal(p.data.length,256);send(AdbWire.AUTH,1,0,new byte[20]);}else{equal(p.arg0,3);send(AdbWire.CNXN,0x01000000,4096,AdbWire.utf8z("device::"));}}
                else if(p.command==AdbWire.OPEN){String service=new String(p.data,0,p.data.length-1,StandardCharsets.UTF_8);if(service.equals("missing:")){send(AdbWire.CLSE,0,p.arg0,null);continue;}Channel c=new Channel(p.arg0,service);channels.put(p.arg0,c);send(AdbWire.OKAY,c.remote,c.local,null);if(service.startsWith("shell:")){c.enqueue(utf("synthetic result\n"));c.endAfter=true;c.emit();}}
                else if(p.command==AdbWire.WRTE){Channel c=channels.get(p.arg0);check(c!=null);maxWrite.accumulateAndGet(p.data.length,Math::max);send(AdbWire.OKAY,c.remote,c.local,null);c.receive(p.data);}
                else if(p.command==AdbWire.OKAY){Channel c=channels.get(p.arg0);if(c!=null){c.inFlight=false;c.emit();}}
                else if(p.command==AdbWire.CLSE){Channel c=channels.remove(p.arg0);if(c!=null&&!c.ended)send(AdbWire.CLSE,c.remote,c.local,null);}
                else throw new AssertionError("unknown peer command");
            }
        }catch(Throwable e){if(!closed)problem.compareAndSet(null,e);}}
        void verify(){if(problem.get()!=null)throw new AssertionError("synthetic peer",problem.get());}
        @Override public void close()throws Exception{adb.close();thread.join(1000);verify();}
        final class Channel {
            final int local,remote;final String service;boolean inFlight,endAfter,ended;
            final Queue<byte[]> pending=new ArrayDeque<>();byte[] buffer=new byte[0];final ByteArrayOutputStream file=new ByteArrayOutputStream();
            Channel(int id,String service){local=id;remote=id+100;this.service=service;}
            void enqueue(byte[] b){for(int i=0;i<b.length;i+=1000)pending.add(Arrays.copyOfRange(b,i,Math.min(i+1000,b.length)));}
            void emit()throws IOException{if(inFlight||ended)return;if(!pending.isEmpty()){inFlight=true;send(AdbWire.WRTE,remote,local,pending.remove());}else if(endAfter){ended=true;send(AdbWire.CLSE,remote,local,null);}}
            void receive(byte[] data)throws IOException{
                if(service.equals("echo:")){enqueue(data);emit();return;}
                check(service.equals("sync:"));buffer=concat(buffer,data);
                while(buffer.length>=8){ByteBuffer b=ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);int id=b.getInt(),len=b.getInt();boolean done=id==AdbWire.command("DONE");int need=done?8:8+len;if(buffer.length<need)return;byte[] body=Arrays.copyOfRange(buffer,8,need);buffer=Arrays.copyOfRange(buffer,need,buffer.length);
                    if(id==AdbWire.command("SEND"))uploadName=new String(body,StandardCharsets.UTF_8);
                    else if(id==AdbWire.command("DATA"))file.write(body);
                    else if(done){uploaded=file.toByteArray();enqueue(sync("OKAY",new byte[0]));}
                    else if(id==AdbWire.command("RECV")){
                        String path=new String(body,StandardCharsets.UTF_8);
                        if(path.equals("/error"))enqueue(sync("FAIL",utf("synthetic denial")));
                        else if(path.equals("/oversized"))enqueue(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.command("DATA")).putInt(65537).array());
                        else enqueue(concat(sync("DATA",FILE),sync("DONE",new byte[0])));
                    }else if(id==AdbWire.command("LIST"))enqueue(concat(dent("..",0040755,0),dent("bad/name",0100644,3),dent("한글.bin",0100644,-1),dent("folder",0040755,0),ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWire.command("DONE")).array()));
                    else throw new AssertionError("unknown sync command");
                    emit();
                }
            }
        }
    }
}
