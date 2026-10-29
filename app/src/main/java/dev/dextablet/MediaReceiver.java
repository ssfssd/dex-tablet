package dev.dextablet;

import android.media.*;
import android.view.Surface;
import dev.dextablet.core.*;
import java.io.*;
import java.nio.ByteBuffer;

public final class MediaReceiver implements Closeable {
    public interface Listener {
        void size(int width,int height);
        void firstFrame();
        void warning(String text);
        void failed(Exception error);
    }
    private volatile boolean closed;
    private volatile boolean renderVideo=true;
    private volatile float volume=1f;
    private volatile AudioTrack audio;
    private final Listener listener;
    private final String decoderName;
    public MediaReceiver(String decoderName,Listener listener) { this.decoderName=decoderName;this.listener=listener; }
    public void startVideo(InputStream stream,Surface surface) {
        Thread worker=new Thread(()->video(stream,surface),"dex-video"); worker.setDaemon(true); worker.start();
    }
    private void video(InputStream stream,Surface surface) {
        Decoder decoder=null;
        try {
            int codec=ScrcpyWire.codec(stream);
            if(codec!=ScrcpyWire.H264) throw new IOException("H.264 영상 시작에 실패했습니다");
            while(!closed) {
                ScrcpyWire.Frame frame=ScrcpyWire.frame(stream);
                if(frame.session) {
                    if(decoder!=null) decoder.close();
                    decoder=new Decoder(surface,frame.width,frame.height);
                    listener.size(frame.width,frame.height); continue;
                }
                if(decoder==null) throw new IOException("영상 크기 정보가 없습니다");
                decoder.queue(frame);
            }
        } catch(Exception e) { if(!closed) listener.failed(e); }
        finally { if(decoder!=null) decoder.close(); }
    }
    private final class Decoder implements Closeable {
        final MediaCodec codec;
        final Thread output;
        volatile boolean stopped;
        Decoder(Surface surface,int width,int height) throws IOException {
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8*1024*1024);
            codec=decoderName==null?MediaCodec.createDecoderByType("video/avc"):MediaCodec.createByCodecName(decoderName);
            try {
                if(android.os.Build.VERSION.SDK_INT>=30)try{
                    if(codec.getCodecInfo().getCapabilitiesForType("video/avc").isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
                }catch(RuntimeException ignored){}
                codec.configure(format,surface,null,0);
                codec.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener() {
                    boolean delivered;
                    @Override public void onFrameRendered(MediaCodec mc,long pts,long ns) {
                        if(!closed&&!stopped&&!delivered) { delivered=true; listener.firstFrame(); }
                    }
                },new android.os.Handler(android.os.Looper.getMainLooper()));
                codec.start();
            } catch(RuntimeException e) { codec.release(); throw e; }
            output=new Thread(()->{
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                try {
                    while(!closed&&!stopped) {
                        int index=codec.dequeueOutputBuffer(info,10000);
                        if(index>=0) codec.releaseOutputBuffer(index,renderVideo);
                    }
                } catch(Exception e) { if(!closed&&!stopped) listener.failed(e); }
            },"dex-video-output"); output.setDaemon(true); output.start();
        }
        void queue(ScrcpyWire.Frame frame) throws IOException {
            while(!closed&&!stopped) {
                int index=codec.dequeueInputBuffer(10000); if(index<0) continue;
                ByteBuffer buffer=codec.getInputBuffer(index);
                if(buffer==null||buffer.capacity()<frame.data.length) throw new IOException("영상 프레임이 디코더 용량을 초과했습니다");
                buffer.clear(); buffer.put(frame.data);
                codec.queueInputBuffer(index,0,frame.data.length,frame.pts,frame.config?MediaCodec.BUFFER_FLAG_CODEC_CONFIG:0); return;
            }
        }
        @Override public void close() {
            stopped=true;
            try { output.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            try { codec.stop(); } catch(Exception ignored) { }
            codec.release();
        }
    }
    public void startAudio(InputStream stream) {
        Thread worker=new Thread(()->{
            AudioTrack track=null;
            try {
                int codec=ScrcpyWire.codec(stream);
                if(codec==0||codec==1) { listener.warning("휴대폰에서 소리 캡처를 허용하지 않았습니다. 화면은 계속 사용할 수 있습니다"); return; }
                if(codec!=ScrcpyWire.RAW) throw new IOException("지원하지 않는 오디오 형식");
                int minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(Math.max(minimum,19200)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                audio=track; track.setVolume(volume); track.play();
                while(!closed) {
                    ScrcpyWire.Frame f=ScrcpyWire.frame(stream); if(f.session||f.config) continue;
                    if(f.data.length%4!=0) throw new IOException("오디오 패킷 오류");
                    int offset=0;
                    while(offset<f.data.length&&!closed) {
                        int n=track.write(f.data,offset,f.data.length-offset,AudioTrack.WRITE_BLOCKING);
                        if(n<=0) throw new IOException("태블릿 오디오 출력 실패"); offset+=n;
                    }
                }
            } catch(Exception e) { if(!closed) listener.warning("소리 수신 중단: "+e.getMessage()); }
            finally { audio=null; if(track!=null) { try{track.stop();}catch(Exception ignored){} track.release(); } }
        },"dex-audio"); worker.setDaemon(true); worker.start();
    }
    public void mute(boolean mute) { volume=mute?0f:1f; AudioTrack a=audio; if(a!=null) try{a.setVolume(volume);}catch(Exception ignored){} }
    public void setVideoVisible(boolean visible){renderVideo=visible;}
    @Override public void close() { closed=true; AudioTrack a=audio; if(a!=null) try{a.pause();}catch(Exception ignored){} }
}
