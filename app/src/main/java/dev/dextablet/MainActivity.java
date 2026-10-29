package dev.dextablet;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import dev.dextablet.core.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String USB_PERMISSION="dev.dextablet.USB_PERMISSION";
    private static final int UPLOAD=40,DOWNLOAD=41;
    private static final int BG=0xff101722,PANEL=0xff1b2738,INK=0xfff4f7fc,MUTED=0xffaebdd1,ACCENT=0xff60e5ca;
    private UsbManager usbManager;
    private TabletCapabilities tabletCapabilities;
    private String compatibilityIssue;
    private final ArrayList<UsbDevice> devices=new ArrayList<>();
    private Spinner deviceChoice,qualityChoice,modeChoice;
    private CheckBox audioChoice,hidChoice;
    private TextView status,screenLabel;
    private Button connect,refresh,stop;
    private View setupPanel;
    private LinearLayout toolsBar;
    private FrameLayout viewer;
    private TextureView texture;
    private Surface surface;
    private SurfaceTexture retainedTexture;
    private DexSession session;
    private UsbDevice selected;
    private DexSession.Config pendingConfig;
    private final HidKeyboard keyboard=new HidKeyboard();
    private final Set<Integer> sdkKeys=new HashSet<>();
    private int generation;
    private int remoteWidth=1920,remoteHeight=1280;
    private boolean busy,picker,forwardInput,usingHid=true,muted,dialogOpen;
    private boolean touchDown;
    private long touchPointer;
    private int lastX,lastY;
    private String downloadPath;
    private String lastReport="아직 연결 진단 기록이 없습니다";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver usbEvents=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(USB_PERMISSION.equals(intent.getAction())) {
                if(!busy||selected==null||device==null||!selected.getDeviceName().equals(device.getDeviceName())) return;
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&usbManager.hasPermission(device)) openSession();
                else idle("USB 접근이 허용되지 않았습니다. 다시 연결할 수 있습니다");
            } else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if(session!=null&&device!=null&&session.matches(device)) session.stop("USB 케이블 연결이 해제되었습니다");
                else if(selected!=null&&device!=null&&selected.getDeviceName().equals(device.getDeviceName())&&busy) idle("USB 기기가 분리되었습니다");
                if(!busy) scan();
            } else if(!busy) scan();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        usbManager=(UsbManager)getSystemService(USB_SERVICE);
        tabletCapabilities=new TabletCapabilities();
        if(usbManager==null||!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST))
            compatibilityIssue="이 태블릿은 Android USB 호스트/OTG 기능을 제공하지 않습니다";
        else if(!tabletCapabilities.available())compatibilityIssue="이 태블릿에서 H.264 영상 디코더를 찾지 못했습니다";
        buildUi();
        IntentFilter filter=new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(usbEvents,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(usbEvents,filter);
        scan();
    }
    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(10),dp(16),dp(10)); setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            root.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),
                    dp(16)+insets.getSystemWindowInsetRight(),dp(10)+insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView title=text("DeX Tablet",23,INK); title.setTypeface(null,1); root.addView(title);
        status=text("갤럭시를 USB 데이터 케이블로 연결하세요",14,MUTED); root.addView(status);
        toolsBar=new LinearLayout(this); toolsBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView toolsScroll=new HorizontalScrollView(this); toolsScroll.setHorizontalScrollBarEnabled(false); toolsScroll.addView(toolsBar); root.addView(toolsScroll);
        tool("뒤로",()->tapKey(KeyEvent.KEYCODE_BACK)); tool("홈",()->tapKey(KeyEvent.KEYCODE_HOME)); tool("최근 앱",()->tapKey(KeyEvent.KEYCODE_APP_SWITCH));
        tool("한/영",()->tapKey(KeyEvent.KEYCODE_LANGUAGE_SWITCH));
        tool("키보드 설정",()->send(new byte[]{15}));
        tool("붙여넣기",this::paste); tool("파일 보내기",this::pickUpload); tool("파일 받기",()->browse("/sdcard/Download"));
        tool("소리 켜기/끄기",()->{muted=!muted;if(session!=null)session.mute(muted);toast(muted?"태블릿 소리 꺼짐":"태블릿 소리 켜짐");});
        stop=button("연결 종료",()->{ if(session!=null)session.stop("사용자가 연결을 종료했습니다"); }); toolsBar.addView(stop);
        setTools(false);
        viewer=new FrameLayout(this); viewer.setBackgroundColor(0xff090e16);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,0,1); vp.topMargin=dp(10); root.addView(viewer,vp);
        texture=new TextureView(this); texture.setSurfaceTextureListener(this); texture.setFocusable(true); texture.setFocusableInTouchMode(true);
        viewer.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        texture.setOnTouchListener((v,event)->touch(event)); texture.setOnGenericMotionListener((v,event)->mouse(event));
        viewer.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{updateTransform();fitSetup();});
        screenLabel=text("휴대폰의 DeX 화면이 여기에 표시됩니다",17,MUTED); screenLabel.setGravity(Gravity.CENTER);
        viewer.addView(screenLabel,new FrameLayout.LayoutParams(-1,-1)); screenLabel.setClickable(false);
        ScrollView scroll=new ScrollView(this); LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26),dp(20),dp(26),dp(20)); card.setBackground(round(PANEL,18)); scroll.addView(card);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(540),-2,Gravity.CENTER); cp.setMargins(dp(12),dp(12),dp(12),dp(12)); viewer.addView(scroll,cp); setupPanel=scroll;
        card.addView(text("갤럭시를 태블릿의 작업 공간으로",22,INK));
        card.addView(text("DeX 지원 갤럭시 → USB → 안드로이드 태블릿\nAndroid 9 이상 · USB 호스트/OTG 필요",14,MUTED));
        card.addView(text("1  연결할 갤럭시",14,ACCENT)); deviceChoice=spinner(new String[]{"연결된 갤럭시 없음"}); card.addView(deviceChoice);
        refresh=button("USB 기기 다시 찾기",this::scan); card.addView(refresh);
        card.addView(text("2  화면 품질",14,ACCENT));
        qualityChoice=spinner(new String[]{"자동 · 화면 비율/디코더에 맞춤", "선명하게 · 최대 긴 변 2560", "가볍게 · 최대 긴 변 1280 / 30fps"}); card.addView(qualityChoice);
        card.addView(text("3  DeX 실행 방식",14,ACCENT));
        modeChoice=spinner(new String[]{"가상화면 · 권장", "보조화면 · 호환 모드"}); card.addView(modeChoice);
        audioChoice=new CheckBox(this); audioChoice.setText("갤럭시 소리를 태블릿에서 재생"); audioChoice.setTextColor(INK); audioChoice.setChecked(true); card.addView(audioChoice);
        hidChoice=new CheckBox(this); hidChoice.setText("물리 키보드 전달 · 한글/단축키 권장"); hidChoice.setTextColor(INK); hidChoice.setChecked(true); card.addView(hidChoice);
        connect=button("유선 DeX 시작",this::requestStart); connect.setTextColor(BG); connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT)); card.addView(connect);
        Button help=button("연결 방법과 지원 범위",this::help); card.addView(help);
        card.addView(button("연결 진단 복사",()->{String report=session==null?lastReport:session.diagnosticReport();((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("DeX Tablet diagnostic",report));toast("연결 진단 내용을 복사했습니다");}));
        card.addView(text("갤럭시: USB 디버깅 켜기 → 연결 허용\n태블릿: USB 제어 주체 / OTG 호스트\n카메라·마이크·랜카드의 USB 장치 전달은 지원하지 않습니다.",12,MUTED));
        // Fit setup card on narrow/multi-window displays, too.
        viewer.post(this::fitSetup);
    }
    private void fitSetup(){if(setupPanel==null)return;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)setupPanel.getLayoutParams();int w=Math.min(dp(540),Math.max(1,viewer.getWidth()-dp(24)));if(p.width!=w){p.width=w;setupPanel.setLayoutParams(p);}}
    private void scan() {
        if(busy) return;
        devices.clear();
        if(compatibilityIssue!=null){status.setText(compatibilityIssue);connect.setEnabled(false);return;}
        for(UsbDevice d:usbManager.getDeviceList().values()) if(d.getVendorId()==0x04e8&&UsbTransport.find(d)!=null) devices.add(d);
        devices.sort(Comparator.comparing(UsbDevice::getDeviceName));
        ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<devices.size();i++) { UsbDevice d=devices.get(i);labels.add((i+1)+". "+(d.getProductName()==null?"Samsung Galaxy":d.getProductName())); }
        if(labels.isEmpty()) labels.add("연결된 갤럭시 없음 · USB 디버깅/케이블 확인");
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels); deviceChoice.setAdapter(adapter);
        connect.setEnabled(!devices.isEmpty());
        status.setText(devices.isEmpty()?"USB 기기 대기 중 · 태블릿이 USB 제어 주체인지 확인하세요":"갤럭시를 선택하고 유선 DeX 시작을 누르세요");
    }
    private void requestStart() {
        if(busy||devices.isEmpty()||compatibilityIssue!=null)return;
        int index=deviceChoice.getSelectedItemPosition(); if(index<0||index>=devices.size())return;
        selected=devices.get(index); pendingConfig=new DexSession.Config();
        try {
            DisplayProfile profile=DisplayProfile.choose(viewer.getWidth(),viewer.getHeight(),qualityChoice.getSelectedItemPosition(),tabletCapabilities);
            pendingConfig.width=profile.width;pendingConfig.height=profile.height;pendingConfig.dpi=profile.dpi;
            pendingConfig.fps=profile.fps;pendingConfig.bitRate=profile.bitRate;pendingConfig.decoder=profile.decoder;
        }catch(IllegalArgumentException e){status.setText(e.getMessage());selected=null;pendingConfig=null;return;}
        pendingConfig.overlay=modeChoice.getSelectedItemPosition()==1; pendingConfig.audio=audioChoice.isChecked();pendingConfig.hid=hidChoice.isChecked();
        if(pendingConfig.overlay) new AlertDialog.Builder(this).setTitle("보조화면 호환 모드")
                .setMessage("휴대폰에 작은 보조화면이 나타납니다. 종료 버튼을 누르면 이전 설정으로 복원합니다. 케이블을 먼저 빼거나 앱이 강제 종료되면 다음 연결 때 복원을 시도합니다. 일부 One UI 펌웨어에서는 창 제목줄을 끌 때 SystemUI가 재시작될 수 있어 호환 시험이 필요합니다.")
                .setPositiveButton("연결",(d,w)->requestPermission()).setNegativeButton("취소",null).show();
        else requestPermission();
    }
    private void requestPermission() {
        busy=true; connect.setEnabled(false); refresh.setEnabled(false);
        status.setText("태블릿의 USB 접근 허용을 확인하세요");
        if(usbManager.hasPermission(selected)) { openSession();return; }
        Intent intent=new Intent(USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pi=PendingIntent.getBroadcast(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        try{usbManager.requestPermission(selected,pi);}catch(RuntimeException e){idle("USB 허용 요청 실패: "+e.getMessage());}
    }
    private void openSession() {
        if(!busy||session!=null)return;
        if(surface==null||!surface.isValid()) { idle("화면 준비가 완료되지 않았습니다. 다시 시작하세요");return; }
        setupPanel.setVisibility(View.GONE); screenLabel.setVisibility(View.VISIBLE); screenLabel.setText("DeX 화면 준비 중…");
        usingHid=pendingConfig.hid; muted=false; keyboard.release();sdkKeys.clear();stop.setEnabled(true);
        final int active=++generation;
        DexSession created=new DexSession(this,selected,pendingConfig,surface,new DexSession.Listener() {
            private void update(Runnable r){ui(()->{if(active==generation)r.run();});}
            public void status(String message){update(()->status.setText(message));}
            public void ready(){update(()->{if(session==null||!session.isReady())return;forwardInput=true;setTools(true);texture.requestFocus();status.setText("입력 연결됨 · 첫 화면 수신 대기 중");});}
            public void size(int width,int height){update(()->{remoteWidth=width;remoteHeight=height;updateTransform();});}
            public void firstFrame(){update(()->{screenLabel.setVisibility(View.GONE);status.setText("DeX 화면 수신 중 · "+remoteWidth+" × "+remoteHeight+" · USB");});}
            public void warning(String message){update(()->{status.setText(message);toast(message);});}
            public void failed(Exception error){update(()->status.setText("연결 오류: "+error.getMessage()));}
            public void stopped(String message){update(()->{if(session!=null)lastReport=session.diagnosticReport();session=null;idle(message);});}
        });
        session=created;created.start();
    }
    private void idle(String message) {
        generation++;busy=false;forwardInput=false;touchDown=false;selected=null;pendingConfig=null;keyboard.release();sdkKeys.clear();
        setTools(false);setupPanel.setVisibility(View.VISIBLE);screenLabel.setVisibility(View.GONE);
        refresh.setEnabled(true);connect.setEnabled(!devices.isEmpty());status.setText(message);
    }
    private void setTools(boolean enabled) { for(int i=0;i<toolsBar.getChildCount();i++)toolsBar.getChildAt(i).setEnabled(enabled); }
    private void send(byte[] data){if(session!=null)session.send(data);}
    private void tapKey(int key){send(ScrcpyWire.key(0,key,0,0));send(ScrcpyWire.key(1,key,0,0));texture.requestFocus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code=event.getKeyCode();
        if(forwardInput&&!dialogOpen&&!picker&&session!=null&&session.isReady()&&event.getDeviceId()!=-1) {
            if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||code==KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(event);
            // Local escape hatch. Release all remote keys before opening the exit dialog.
            if(code==KeyEvent.KEYCODE_ESCAPE&&event.isCtrlPressed()&&event.isAltPressed()) {
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0) exitDialog();return true;
            }
            if(event.getAction()==KeyEvent.ACTION_DOWN||event.getAction()==KeyEvent.ACTION_UP) {
                if(usingHid) {
                    byte[] report=keyboard.event(code,event.getAction()==KeyEvent.ACTION_DOWN);
                    if(report!=null) {if(event.getRepeatCount()==0)send(ScrcpyWire.keyboardReport(report));return true;}
                }
                if(event.getAction()==KeyEvent.ACTION_DOWN)sdkKeys.add(code);else sdkKeys.remove(code);
                send(ScrcpyWire.key(event.getAction(),code,event.getRepeatCount(),event.getMetaState()));return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void releaseInputs() {
        byte[] released=keyboard.release();if(usingHid)send(ScrcpyWire.keyboardReport(released));
        for(int key:sdkKeys)send(ScrcpyWire.key(KeyEvent.ACTION_UP,key,0,0));sdkKeys.clear();
        if(touchDown) {send(ScrcpyWire.touch(MotionEvent.ACTION_UP,touchPointer,lastX,lastY,remoteWidth,remoteHeight,0,0,0));touchDown=false;}
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)releaseInputs();}
    private Viewport viewport(){return new Viewport(viewer.getWidth(),viewer.getHeight(),remoteWidth,remoteHeight);}
    private void updateTransform() {
        if(texture==null||texture.getWidth()==0||texture.getHeight()==0)return;
        Viewport fit=viewport();Matrix transform=new Matrix();
        transform.setScale(fit.width/texture.getWidth(),fit.height/texture.getHeight());transform.postTranslate(fit.left,fit.top);texture.setTransform(transform);
    }
    private boolean touch(MotionEvent e) {
        if(!forwardInput||session==null||!session.isReady())return false;
        texture.requestFocus();boolean mouse=e.isFromSource(InputDevice.SOURCE_MOUSE);
        if(mouse)return pointer(e,true);
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_POINTER_DOWN)return true;
        if(action==MotionEvent.ACTION_POINTER_UP){if(e.getPointerId(e.getActionIndex())==touchPointer)releaseInputs();return true;}
        if(action==MotionEvent.ACTION_DOWN)touchPointer=e.getPointerId(0);
        else if(!touchDown)return true;
        int index=e.findPointerIndex((int)touchPointer);if(index<0){releaseInputs();return true;}
        int[] point=viewport().map(e.getX(index),e.getY(index),touchDown);if(point==null)return true;
        lastX=point[0];lastY=point[1];
        if(action==MotionEvent.ACTION_DOWN)touchDown=true;
        if(action==MotionEvent.ACTION_CANCEL)action=MotionEvent.ACTION_UP;
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)
            send(ScrcpyWire.touch(action,touchPointer,lastX,lastY,remoteWidth,remoteHeight,action==MotionEvent.ACTION_UP?0:1,0,0));
        if(action==MotionEvent.ACTION_UP)touchDown=false;return true;
    }
    private boolean mouse(MotionEvent e){return forwardInput&&session!=null&&session.isReady()&&pointer(e,false);}
    private boolean pointer(MotionEvent e,boolean touchEvent) {
        int[] point=viewport().map(e.getX(),e.getY(),touchDown);if(point==null)return true;
        lastX=point[0];lastY=point[1];int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_SCROLL) {
            send(ScrcpyWire.scroll(lastX,lastY,remoteWidth,remoteHeight,e.getAxisValue(MotionEvent.AXIS_HSCROLL),e.getAxisValue(MotionEvent.AXIS_VSCROLL),e.getButtonState()));return true;
        }
        if(action==MotionEvent.ACTION_DOWN)touchDown=true;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){action=MotionEvent.ACTION_UP;touchDown=false;}
        if(action==MotionEvent.ACTION_HOVER_MOVE||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_UP
                ||action==MotionEvent.ACTION_BUTTON_PRESS||action==MotionEvent.ACTION_BUTTON_RELEASE) {
            touchPointer=-1;
            send(ScrcpyWire.touch(action,-1,lastX,lastY,remoteWidth,remoteHeight,e.getButtonState()==0?0:1,e.getActionButton(),e.getButtonState()));return true;
        }
        return false;
    }
    private void paste() {
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData data=clipboard.getPrimaryClip();if(data==null||data.getItemCount()==0){toast("태블릿 클립보드가 비어 있습니다");return;}
        CharSequence value=data.getItemAt(0).coerceToText(this);
        try {send(ScrcpyWire.paste(value==null?"":value.toString()));texture.requestFocus();}catch(IllegalArgumentException e){toast(e.getMessage());}
    }
    private void pickUpload() {
        releaseInputs();picker=true;if(session!=null)session.setVideoVisible(false);
        try {startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),UPLOAD);}
        catch(ActivityNotFoundException e){picker=false;if(session!=null)session.setVideoVisible(true);toast("파일 선택 앱이 없습니다");}
    }
    private void browse(String path) {
        if(session==null||!session.isReady())return;
        final DexSession browsing=session;
        releaseInputs();dialogOpen=true;status.setText("갤럭시 파일 목록을 읽는 중");
        browsing.listFiles(path,entries->ui(()->{
            if(session!=browsing)return;
            if(entries==null||session==null||!session.isReady()){dialogOpen=false;return;}
            ArrayList<String> labels=new ArrayList<>();boolean parent=!path.equals("/sdcard");if(parent)labels.add("↰ 상위 폴더");
            for(AdbSync.Entry e:entries)labels.add((e.directory?"▸ ":"  ")+e.name);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(path).setItems(labels.toArray(new String[0]),(d,which)->{
                if(parent&&which==0){browse(path.substring(0,path.lastIndexOf('/')));return;}
                AdbSync.Entry item=entries.get(which-(parent?1:0));String child=path+"/"+item.name;
                if(item.directory){browse(child);return;}
                downloadPath=child;picker=true;dialogOpen=false;if(session!=null)session.setVideoVisible(false);
                try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,item.name),DOWNLOAD);}
                catch(ActivityNotFoundException e){picker=false;if(session!=null)session.setVideoVisible(true);toast("저장 위치 선택 앱이 없습니다");}
            }).setNegativeButton("닫기",(d,w)->{dialogOpen=false;texture.requestFocus();}).create();
            dialog.setOnCancelListener(d->{dialogOpen=false;texture.requestFocus();});dialog.show();
        }));
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);picker=false;dialogOpen=false;
        if(session!=null&&retainedTexture==null)session.setVideoVisible(true);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();DexSession current=session;
        if(current==null||!current.isReady()){if(request==DOWNLOAD)deleteCreatedDocument(uri);toast("연결이 종료되어 파일을 전송할 수 없습니다");return;}
        try {
            if(request==UPLOAD) {
                String name="file";
                try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
                InputStream in=getContentResolver().openInputStream(uri);if(in==null)throw new IOException("선택한 파일을 열 수 없습니다");
                current.transferUpload(in,name,progress(),path->ui(()->{if(session==current&&path!=null){status.setText("전송 완료: "+path);toast("갤럭시 Download/DeXTablet 폴더에 저장했습니다");}}));
            } else if(request==DOWNLOAD) {
                OutputStream out=getContentResolver().openOutputStream(uri,"w");if(out==null)throw new IOException("저장 위치를 열 수 없습니다");
                current.transferDownload(downloadPath,out,progress(),ok->{
                    if(!ok)try{DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}
                    ui(()->{if(session==current&&ok){status.setText("태블릿에 파일 저장 완료");toast("파일 받기 완료");}});
                });
            }
        } catch(Exception e){if(request==DOWNLOAD)deleteCreatedDocument(uri);toast("파일 전송을 시작하지 못했습니다: "+e.getMessage());}
        texture.requestFocus();
    }
    private void deleteCreatedDocument(Uri uri){try{DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}}
    private AdbSync.Progress progress(){final int active=generation;return new AdbSync.Progress(){long last;public void bytes(long n){long now=System.currentTimeMillis();if(now-last>300){last=now;ui(()->{if(active==generation)status.setText(String.format(Locale.KOREA,"파일 전송 중 · %.1f MB",n/1048576.0));});}}};}
    private void exitDialog(){releaseInputs();dialogOpen=true;new AlertDialog.Builder(this).setMessage("DeX 연결을 종료할까요?").setPositiveButton("종료",(d,w)->{dialogOpen=false;if(session!=null)session.stop("사용자가 연결을 종료했습니다");}).setNegativeButton("계속 사용",(d,w)->{dialogOpen=false;texture.requestFocus();}).setOnCancelListener(d->{dialogOpen=false;}).show();}
    @Override public void onBackPressed(){if(session!=null)exitDialog();else super.onBackPressed();}
    private void help(){new AlertDialog.Builder(this).setTitle("유선 연결 안내").setMessage(
            "1. 이 앱을 화면을 표시할 안드로이드 태블릿에 설치합니다. Android 9 이상, USB 호스트/OTG와 H.264 디코더가 필요합니다. 갤럭시에 앱을 설치할 필요는 없습니다.\n\n"
            +"2. DeX를 지원하는 갤럭시의 개발자 옵션에서 USB 디버깅을 켭니다. USB 데이터 케이블로 두 기기를 연결합니다. 태블릿 쪽 USB 환경설정에서 ‘이 기기’가 USB 제어 주체인지 확인합니다. 필요하면 태블릿 쪽 OTG 어댑터를 사용합니다.\n\n"
            +"3. 앱에서 갤럭시를 선택하고 시작합니다. 태블릿의 USB 접근과 갤럭시의 디버깅 연결을 각각 허용합니다.\n\n"
            +"4. 키보드 설정에서 갤럭시의 물리 키보드 언어를 한국어/영어로 설정합니다. 한/영 버튼, Shift+Space 또는 설정한 전환 키를 사용합니다. 물리 키보드가 동작하지 않으면 종료 후 체크를 해제하고 다시 연결하세요.\n\n"
            +"5. 종료 버튼을 누른 뒤 케이블을 분리합니다. Ctrl+Alt+Esc로 종료 메뉴를 열 수 있습니다.\n\n"
            +"태블릿에 연결한 USB 저장장치는 파일 선택기를 통해 이용합니다. USB 장치 자체의 공유, 마이크 입력, 웹캠, 랜카드 전달은 제공하지 않습니다.\n\n"
            +"실기기 검증 전 개발 빌드입니다. 잠금 화면·DRM 앱은 검은 화면이 나올 수 있습니다.\n\n"
            +"scrcpy 4.1 · Genymobile / Romain Vimont · Apache-2.0\nhttps://github.com/Genymobile/scrcpy").setPositiveButton("확인",null).show();}
    private void ui(Runnable runnable){main.post(()->{if(!isFinishing()&&!isDestroyed())runnable.run();});}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextColor(INK);b.setOnClickListener(v->action.run());return b;}
    private void tool(String label,Runnable action){toolsBar.addView(button(label,action));}
    private Spinner spinner(String[] labels){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));return s;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture value,int w,int h){
        if(retainedTexture!=null){texture.setSurfaceTexture(retainedTexture);retainedTexture=null;}
        else surface=new Surface(value);
        if(session!=null&&!picker)session.setVideoVisible(true);updateTransform();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture value,int w,int h){updateTransform();}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture value){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture value){
        if(picker&&session!=null){session.setVideoVisible(false);retainedTexture=value;return false;}
        if(session!=null)session.stop("태블릿 화면이 닫혀 연결을 종료합니다");if(surface!=null){surface.release();surface=null;}return true;
    }
    @Override protected void onResume(){super.onResume();if(session!=null&&!picker&&retainedTexture==null)session.setVideoVisible(true);}
    @Override protected void onPause(){releaseInputs();super.onPause();}
    @Override protected void onStop(){super.onStop();if(!picker&&session!=null)session.stop("앱이 화면에서 닫혀 연결을 종료했습니다");}
    @Override protected void onDestroy(){if(session!=null)session.close();if(surface!=null){surface.release();surface=null;}if(retainedTexture!=null){retainedTexture.release();retainedTexture=null;}unregisterReceiver(usbEvents);super.onDestroy();}
}
