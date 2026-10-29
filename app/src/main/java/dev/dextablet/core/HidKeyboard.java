package dev.dextablet.core;

import java.util.*;

/** Boot keyboard reports, with LANG1/LANG2 usages for Korean keyboards. */
public final class HidKeyboard {
    private final Set<Integer> down=new TreeSet<>();
    // HID 1.11 boot keyboard layout, also used by scrcpy (Apache-2.0).
    public static final byte[] DESCRIPTOR=hex("05010906a101050719e029e7150025017501950881027508950181010508190129057501950591027503950191010507190029e7150026e700750895068100c0");
    private static byte[] hex(String value) { byte[] r=new byte[value.length()/2]; for(int i=0;i<r.length;i++) r[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16); return r; }
    public byte[] event(int androidCode,boolean pressed) {
        int usage=usage(androidCode); if(usage==0) return null;
        if(pressed) down.add(usage); else down.remove(usage);
        byte[] report=new byte[8]; int count=0;
        for(int k:down) {
            if(k>=0xe0&&k<=0xe7) report[0]|=(byte)(1<<(k-0xe0));
            else { if(count<6) report[2+count]=(byte)k; count++; }
        }
        if(count>6) Arrays.fill(report,2,8,(byte)1);
        return report;
    }
    public byte[] release() { down.clear(); return new byte[8]; }
    public static int usage(int k) {
        if(k>=29&&k<=54) return k-29+4;
        if(k>=8&&k<=16) return k-8+30;
        if(k==7) return 39;
        if(k>=131&&k<=142) return k-131+58;
        if(k>=145&&k<=153) return k-145+89;
        switch(k) {
            case 66:return 40; case 111:return 41; case 67:return 42; case 61:return 43; case 62:return 44;
            case 69:return 45; case 70:return 46; case 71:return 47; case 72:return 48; case 73:return 49;
            case 74:return 51; case 75:return 52; case 68:return 53; case 55:return 54; case 56:return 55; case 76:return 56;
            case 115:return 57; case 120:return 70; case 116:return 71; case 121:return 72;
            case 124:return 73; case 122:return 74; case 92:return 75; case 112:return 76; case 123:return 77; case 93:return 78;
            case 22:return 79; case 21:return 80; case 20:return 81; case 19:return 82;
            case 143:return 83; case 154:return 84; case 155:return 85; case 156:return 86; case 157:return 87; case 160:return 88;
            case 144:return 98; case 158:return 99; case 82:return 101;
            case 204:return 144; case 213:return 139; case 214:return 138; case 215:return 136; case 216:return 137; case 217:return 135; case 218:return 144;
            case 113:return 224; case 59:return 225; case 57:return 226; case 117:return 227;
            case 114:return 228; case 60:return 229; case 58:return 230; case 118:return 231;
            default:return 0;
        }
    }
}
