package dev.dextablet;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import dev.dextablet.core.DisplayProfile;
import java.util.*;

/** Read the receiving tablet's advertised decoding capabilities; no model whitelist. */
public final class TabletCapabilities implements DisplayProfile.DecoderSupport {
    private static final class Decoder {
        final String name;
        final MediaCodecInfo.VideoCapabilities video;
        Decoder(String name,MediaCodecInfo.VideoCapabilities video){this.name=name;this.video=video;}
    }
    private final List<Decoder> decoders=new ArrayList<>();
    public TabletCapabilities(){
        try {
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if(info.isEncoder())continue;
                for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("video/avc")) {
                    try{decoders.add(new Decoder(info.getName(),info.getCapabilitiesForType(type).getVideoCapabilities()));}
                    catch(RuntimeException ignored){}
                }
            }
        }catch(RuntimeException ignored){}
    }
    public boolean available(){return !decoders.isEmpty();}
    @Override public String decoder(int width,int height,int fps){
        for(Decoder d:decoders)try{
            if(d.video!=null&&d.video.areSizeAndRateSupported(width,height,fps))return d.name;
        }catch(RuntimeException ignored){}
        return null;
    }
}
