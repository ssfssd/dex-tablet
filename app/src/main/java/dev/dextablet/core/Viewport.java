package dev.dextablet.core;

/** Aspect-ratio fit and inverse mapping; black bars must never generate remote clicks. */
public final class Viewport {
    public final float left,top,width,height;
    public final int remoteWidth,remoteHeight;
    public Viewport(int viewW,int viewH,int remoteW,int remoteH) {
        remoteWidth=remoteW; remoteHeight=remoteH;
        float scale=Math.min((float)viewW/remoteW,(float)viewH/remoteH);
        width=remoteW*scale; height=remoteH*scale; left=(viewW-width)/2; top=(viewH-height)/2;
    }
    public int[] map(float x,float y,boolean clamp) {
        if(width<=0||height<=0) return null;
        if(!clamp&&(x<left||y<top||x>=left+width||y>=top+height)) return null;
        return new int[]{Math.max(0,Math.min(remoteWidth-1,(int)((x-left)*remoteWidth/width))),
                Math.max(0,Math.min(remoteHeight-1,(int)((y-top)*remoteHeight/height)))};
    }
}
