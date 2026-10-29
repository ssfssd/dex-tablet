package dev.dextablet;

import android.hardware.usb.*;
import java.io.*;

/** USB bulk transport; never opens storage/HID interfaces belonging to other peripherals. */
public final class UsbTransport implements Closeable {
    private final UsbDeviceConnection device;
    private final UsbInterface adbInterface;
    private final UsbEndpoint input,output;
    private volatile boolean closed;
    public static UsbInterface find(UsbDevice device) {
        for(int i=0;i<device.getInterfaceCount();i++) {
            UsbInterface it=device.getInterface(i);
            if(it.getInterfaceClass()==255&&it.getInterfaceSubclass()==66&&it.getInterfaceProtocol()==1) return it;
        }
        return null;
    }
    public UsbTransport(UsbManager manager,UsbDevice target) throws IOException {
        adbInterface=find(target);
        if(adbInterface==null) throw new IOException("ADB 인터페이스가 없습니다. 갤럭시의 USB 디버깅을 켜세요");
        UsbEndpoint epIn=null,epOut=null;
        for(int i=0;i<adbInterface.getEndpointCount();i++) {
            UsbEndpoint ep=adbInterface.getEndpoint(i);
            if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if(ep.getDirection()==UsbConstants.USB_DIR_IN) epIn=ep; else epOut=ep;
            }
        }
        if(epIn==null||epOut==null) throw new IOException("USB 데이터 엔드포인트가 없습니다");
        input=epIn; output=epOut; device=manager.openDevice(target);
        if(device==null) throw new IOException("태블릿에서 USB 접근을 허용하세요");
        if(!device.claimInterface(adbInterface,true)) { device.close(); throw new IOException("USB가 다른 앱에서 사용 중입니다"); }
    }
    public final InputStream in=new InputStream() {
        private final byte[] buffer=new byte[16384]; private int offset,length;
        @Override public int read() throws IOException { byte[] b=new byte[1]; int n=read(b,0,1); return n<0?-1:b[0]&255; }
        @Override public int read(byte[] b,int off,int count) throws IOException {
            if(count==0) return 0;
            while(offset==length) {
                if(closed) return -1;
                // A negative result can also be an idle timeout. Detach broadcasts close the connection.
                length=device.bulkTransfer(input,buffer,buffer.length,1000); offset=0;
                if(length<=0) { length=0; continue; }
            }
            int n=Math.min(count,length-offset); System.arraycopy(buffer,offset,b,off,n); offset+=n; return n;
        }
    };
    public final OutputStream out=new OutputStream() {
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
        @Override public void write(byte[] b,int off,int count) throws IOException {
            int total=count;
            while(count>0) {
                if(closed) throw new IOException("USB 연결 종료");
                int n=device.bulkTransfer(output,b,off,Math.min(count,16384),5000);
                if(n<=0) throw new IOException("USB 전송 실패. 케이블 연결을 확인하세요");
                off+=n; count-=n;
            }
            // Match adb's USB transport: terminate a packet-aligned logical write with a ZLP.
            if(total>0&&total%output.getMaxPacketSize()==0&&device.bulkTransfer(output,new byte[0],0,5000)<0)
                throw new IOException("USB 패킷 종료 전송 실패");
        }
    };
    @Override public void close() {
        if(closed) return; closed=true;
        device.releaseInterface(adbInterface); device.close();
    }
}
