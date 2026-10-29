package dev.dextablet.core;

/** Select a tablet-sized desktop without depending on a particular brand or aspect ratio. */
public final class DisplayProfile {
    public interface DecoderSupport { String decoder(int width,int height,int fps); }
    public final int width,height,dpi,fps,bitRate;
    public final String decoder;
    private DisplayProfile(int width,int height,int fps,String decoder) {
        this.width=width;this.height=height;this.fps=fps;this.decoder=decoder;
        dpi=Math.max(120,Math.min(320,(Math.min(width,height)*160/720+7)/8*8));
        bitRate=(int)Math.max(2000000,Math.min(12000000,(long)width*height*fps/16));
    }
    /** quality: 0 = automatic, 1 = sharp, 2 = lighter connection. */
    public static DisplayProfile choose(int viewWidth,int viewHeight,int quality,DecoderSupport support) {
        if(viewWidth<=0||viewHeight<=0)throw new IllegalArgumentException("태블릿 화면 크기를 아직 확인하지 못했습니다");
        int requested=quality==1?2560:quality==2?1280:1920;
        double scale=Math.min(1d,(double)requested/Math.max(viewWidth,viewHeight));
        int initialLong=Math.max(16,(int)(Math.max(viewWidth,viewHeight)*scale)/16*16);
        int[] candidates={initialLong,1920,1600,1280,960,640};
        int previous=Integer.MAX_VALUE;
        for(int edge:candidates) {
            if(edge>initialLong||edge>=previous)continue;previous=edge;
            double s=(double)edge/Math.max(viewWidth,viewHeight);
            int w=Math.max(16,(int)(viewWidth*s)/16*16),h=Math.max(16,(int)(viewHeight*s)/16*16);
            if(Math.min(w,h)<240)continue;
            int[] rates=quality==2?new int[]{30}:new int[]{60,30};
            for(int fps:rates) {
                String decoder=support.decoder(w,h,fps);
                if(decoder!=null)return new DisplayProfile(w,h,fps,decoder);
            }
        }
        throw new IllegalArgumentException("사용 가능한 H.264 디코더/화면 구성을 찾지 못했습니다. 다른 태블릿이 필요할 수 있습니다");
    }
}
