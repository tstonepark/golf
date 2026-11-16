package kr.adn.swingcam;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.content.ContentValues;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Range;
import android.view.Gravity;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.SwingFrameUtil;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 스윙 카메라 (cam.html 대체 앱).
 * - 후면 기본 카메라 1080p 60fps, 셔터 속도 직접 지정(잔상 줄이기)
 * - 아이패드 분석 페이지(index.html)와 같은 연결 방식(PeerJS 신호 + WebRTC 직접 전송)
 * - 기울기·생존 신호를 cam.html 과 같은 형식으로 보냄 → 아이패드 쪽은 바꿀 것 없음
 */
public class MainActivity extends Activity {
    private static final String PFX = "adn-golfcoach-";
    private static final String REL = "https://github.com/tstonepark/golf/releases/download/swingcam/";
    private static final int W = 1920, H = 1080, FPS = 60;
    private boolean hs;                       // 고속(240fps) 모드
    private RingRecorder rr; private Surface recSurface;
    private Button autoBtn; private boolean autoOn = true; private volatile long lastSaveAt = 0; private Thread mic; private volatile boolean micRun = false;
    private int fwd = 0;                      // 고속일 때 아이패드로는 4장 중 1장만(60fps)
    private Button speedBtn, saveBtn;
    private static final long[] SHUTTERS = {0, 500, 1000, 2000};   // 0 = 자동, 나머지 1/n 초

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView codeTv, statusTv, levelTv, infoTv;
    private Button shutterBtn, updBtn;
    private SurfaceViewRenderer preview;

    // 카메라
    private CameraManager cm;
    private HandlerThread camThread; private Handler camH;
    private CameraDevice cam; private CameraCaptureSession camSession;
    private int sensorOrientation = 90;
    private int shutterIdx;
    private volatile long autoExp = 0; private volatile int autoIso = 0;
    private volatile long curExp = 0; private volatile int curIso = 0;
    private Range<Integer> isoRange = new Range<>(50, 3200);

    // WebRTC
    private EglBase egl;
    private PeerConnectionFactory factory;
    private VideoSource vsrc; private VideoTrack vtrack;
    private SurfaceTextureHelper sth;
    private PeerJs pj; private String code;
    private String remote, dataId, mediaId;
    private PeerConnection dataPc, mediaPc; private DataChannel dc;
    private final List<IceCandidate> pendData = new ArrayList<>(), pendMedia = new ArrayList<>();
    private boolean dataRemoteSet = false, mediaRemoteSet = false;
    private long frames = 0, fpsT = 0, recCnt = 0, lastFwdNs = 0, lastMotNs = 0; private double camFps = 0; private volatile double recFps = 0;

    // 기울기
    private SensorManager sm;
    private final float[] grav = new float[3]; private int gravN = 0;
    private volatile Double pitch = null, roll = null;
    private volatile int devRot = 0;   // 폰을 시계방향으로 돌린 각도(0/90/180/270)
    private int videoRot() { return (sensorOrientation + devRot) % 360; }

    private int latest = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("swingcam", MODE_PRIVATE);
        shutterIdx = prefs.getInt("shutter", 2);
        hs = prefs.getBoolean("hs", false);
        autoOn = prefs.getBoolean("auto", true);
        code = prefs.getString("code", null);
        if (code == null) newCode();
        buildUi();
        checkUpdate();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        else startAll();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startAll();
        else status("카메라 권한이 필요합니다. 설정 → 애플리케이션 → 스윙 카메라 → 권한", true);
    }

    // ---------- 화면 ----------
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 21, 18));
        root.setPadding(24, 40, 24, 24);

        TextView t = tv("아이패드에 이 번호를 입력하세요", 18, Color.rgb(143, 163, 154)); t.setGravity(Gravity.CENTER);
        root.addView(t);
        codeTv = tv("------", 64, Color.rgb(255, 210, 63)); codeTv.setGravity(Gravity.CENTER); codeTv.setLetterSpacing(0.15f);
        root.addView(codeTv);
        statusTv = tv("준비 중…", 20, Color.WHITE); statusTv.setGravity(Gravity.CENTER);
        root.addView(statusTv);

        preview = new SurfaceViewRenderer(this);
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        levelTv = tv("", 22, Color.WHITE); levelTv.setGravity(Gravity.CENTER);
        root.addView(levelTv);
        infoTv = tv("", 16, Color.rgb(143, 163, 154)); infoTv.setGravity(Gravity.CENTER);
        root.addView(infoTv);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        shutterBtn = btn(""); shutterBtn.setOnClickListener(v -> { shutterIdx = (shutterIdx + 1) % SHUTTERS.length; prefs.edit().putInt("shutter", shutterIdx).apply(); applyCapture(); updShutterBtn(); });
        Button nb = btn("번호 바꾸기"); nb.setOnClickListener(v -> { newCode(); restartSignal(); });
        row.addView(shutterBtn, new LinearLayout.LayoutParams(0, 170, 1));
        row.addView(nb, new LinearLayout.LayoutParams(0, 170, 1));
        root.addView(row);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        speedBtn = btn(""); speedBtn.setOnClickListener(v -> { hs = !hs; prefs.edit().putBoolean("hs", hs).apply(); updSpeedBtn(); restartSession(); });
        saveBtn = btn("지금 원본 저장 (최근 12초)"); saveBtn.setOnClickListener(v -> saveClip("수동", 12_000_000L));
        row2.addView(speedBtn, new LinearLayout.LayoutParams(0, 170, 1));
        row2.addView(saveBtn, new LinearLayout.LayoutParams(0, 170, 1));
        root.addView(row2);
        autoBtn = btn(""); autoBtn.setOnClickListener(v -> { autoOn = !autoOn; prefs.edit().putBoolean("auto", autoOn).apply(); updAutoBtn(); });
        root.addView(autoBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
        final Button benchBtn = btn("분석 속도 시험 (약 1분)");
        benchBtn.setOnClickListener(v -> { benchBtn.setEnabled(false); benchBtn.setText("속도 시험 중… 잠시만요");
            new Thread(() -> { String r; try { r = Bench.run(getApplicationContext(), line -> ui.post(() -> benchBtn.setText(line))); } catch (Throwable e) { r = "시험 실패: " + e; }
                final String res = r; ui.post(() -> { benchBtn.setEnabled(true); benchBtn.setText("분석 속도 시험 (약 1분)");
                    try { ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("bench", res)); } catch (Exception ignore) {}
                    new android.app.AlertDialog.Builder(MainActivity.this).setTitle("분석 속도 (복사됨 · 채팅에 붙여넣기)").setMessage(res).setPositiveButton("확인", null).show(); }); }).start(); });
        root.addView(benchBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
        updBtn = btn("업데이트 확인 중…"); updBtn.setEnabled(false);
        root.addView(updBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140));
        setContentView(root);
        codeTv.setText(code);
        updShutterBtn(); updSpeedBtn(); updAutoBtn();
    }
    private void updAutoBtn() { autoBtn.setText(autoOn ? "자동 저장(스윙 동작·공 소리): 켬" : "자동 저장(스윙 동작·공 소리): 끔"); }

    private void updSpeedBtn() { speedBtn.setText(hs ? "원본: 240fps 고속" : "원본: 60fps"); }
    private void updShutterBtn() {
        long s = SHUTTERS[shutterIdx];
        shutterBtn.setText(s == 0 ? "셔터: 자동" : "셔터: 1/" + s + "초");
    }

    private void status(String s, boolean bad) { ui.post(() -> { statusTv.setText(s); statusTv.setTextColor(bad ? Color.rgb(255, 138, 101) : Color.rgb(88, 214, 141)); }); }
    private TextView tv(String s, int sp, int c) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(c); t.setPadding(6, 6, 6, 6); return t; }
    private Button btn(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(18); return b; }
    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private void newCode() {
        int n = 100000 + new SecureRandom().nextInt(900000);
        code = String.valueOf(n);
        prefs.edit().putString("code", code).apply();
        if (codeTv != null) codeTv.setText(code);
    }

    // ---------- 시작 ----------
    private void startAll() {
        startMic();
        egl = EglBase.create();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
        vsrc = factory.createVideoSource(false);
        vtrack = factory.createVideoTrack("swing0", vsrc);
        preview.init(egl.getEglBaseContext(), null);
        preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        vtrack.addSink(preview);

        sth = SurfaceTextureHelper.create("SwingCam", egl.getEglBaseContext());
        sth.setTextureSize(W, H);
        vsrc.getCapturerObserver().onCapturerStarted(true);
        sth.startListening(frame -> {
            frames++;
            { long now0 = System.currentTimeMillis();
              if (now0 - fpsT > 1000) { camFps = frames * 1000.0 / Math.max(1, now0 - fpsT); frames = 0;
                  if (rr != null) { long c = rr.encCount; recFps = (c - recCnt) * 1000.0 / Math.max(1, now0 - fpsT); recCnt = c; }
                  fpsT = now0; } }
            // 아이패드로는 최대 60fps만 (시간 기준: 미리보기 프레임이 드물게 와도 더 줄이지 않음)
            long tn = frame.getTimestampNs();
            if (tn - lastMotNs > 66_000_000L) { lastMotNs = tn; measureMotion(frame); }
            if (autoOn && !watchBusy && !Analyzer.busy && tn - lastWatchNs > 60_000_000L) { lastWatchNs = tn; watch(frame); }
            if (sending) return;
            if (hs && tn - lastFwdNs < 15_000_000L) return; lastFwdNs = tn;
            VideoFrame.Buffer nb = SwingFrameUtil.undoRotation(frame.getBuffer(), -sensorOrientation);
            VideoFrame f = new VideoFrame(nb, videoRot(), frame.getTimestampNs());
            vsrc.getCapturerObserver().onFrameCaptured(f);
            f.release();
        });

        camThread = new HandlerThread("cam"); camThread.start(); camH = new Handler(camThread.getLooper());
        openCamera();
        startTilt();
        restartSignal();
        ui.postDelayed(tick, 100);
    }

    // ---------- 카메라 ----------
    private String backCameraId() throws Exception {
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return cm.getCameraIdList()[0];
    }

    private void openCamera() {
        cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = backCameraId();
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION); if (so != null) sensorOrientation = so;
            Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE); if (ir != null) isoRange = ir;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { cam = d; createSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); cam = null; status("카메라가 다른 앱에 넘어갔습니다. 앱을 다시 여세요", true); }
                @Override public void onError(CameraDevice d, int e) { d.close(); cam = null; status("카메라 오류 " + e, true); }
            }, camH);
        } catch (Exception e) { status("카메라 열기 실패: " + e.getMessage(), true); }
    }

    private Surface camSurface;
    private void createSession() {
        try {
            SurfaceTexture st = sth.getSurfaceTexture();
            st.setDefaultBufferSize(W, H);
            if (camSurface == null) camSurface = new Surface(st);
            List<Surface> outs = new ArrayList<>(); outs.add(camSurface);
            try { rr = new RingRecorder(); recSurface = rr.start(W, H, hs ? 240 : FPS); outs.add(recSurface); }
            catch (Exception re) { rr = null; recSurface = null; toast("원본 녹화기 준비 실패: " + re.getMessage()); }
            CameraCaptureSession.StateCallback cb = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { camSession = s; applyCapture(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    status(hs ? "240fps 고속 설정 실패 · 60fps로 돌아갑니다" : "카메라 설정 실패", true);
                    if (hs) ui.post(() -> { hs = false; prefs.edit().putBoolean("hs", false).apply(); updSpeedBtn(); restartSession(); });
                }
            };
            if (hs) cam.createConstrainedHighSpeedCaptureSession(outs, cb, camH);
            else cam.createCaptureSession(outs, cb, camH);
        } catch (Exception e) { status("카메라 설정 오류: " + e.getMessage(), true); }
    }

    private void restartSession() {
        if (cam == null || camH == null) return;
        camH.post(() -> {
            try { if (camSession != null) { camSession.stopRepeating(); camSession.close(); } } catch (Exception ignore) {}
            camSession = null;
            if (rr != null) { rr.stop(); rr = null; }
            createSession();
        });
    }

    /** 최근 6초 폰 원본을 갤러리(동영상/SwingCam)에 저장하고 아이패드에 알림 */
    private void saveClip(String why, long preUs) {
        if (rr == null) { toast("원본 녹화기가 없습니다"); return; }
        long now = System.currentTimeMillis();
        if (!"수동".equals(why) && now - lastSaveAt < 6000) return;   // 소리·아이패드 신호가 겹치면 한 번만
        lastSaveAt = now;
        final RingRecorder r = rr; final int f = r.fps;
        saveBtn.setEnabled(false); saveBtn.setText("저장 중…");
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "clip.mp4"); if (tmp.exists()) tmp.delete();
            final long endAt = System.currentTimeMillis();
            int n = r.save(preUs, tmp, videoRot());
            String name = null;
            if (n > 0) {
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(new java.util.Date());
                name = "swing_" + ts + "_" + f + "fps.mp4";
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SwingCam");
                    Uri u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                    try (InputStream in = new FileInputStream(tmp); OutputStream o = getContentResolver().openOutputStream(u)) {
                        byte[] buf = new byte[1 << 16]; int k; while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                    }
                } catch (Exception e) { name = null; }
            }
            final String nm = name; final int nn = n;
            ui.post(() -> {
                saveBtn.setEnabled(true); saveBtn.setText("지금 원본 저장 (최근 12초)");
                if (nm != null) { toast("원본 저장: 동영상/SwingCam/" + nm + " (" + nn + "장)");
                    try { sendData(new JSONObject().put("t", "saved").put("name", nm).put("fps", f).put("frames", nn).put("why", why)); } catch (Exception ignore) {}
                    sendClip(tmp, nm, f, !"수동".equals(why), endAt); }
                else toast("원본 저장 실패");
            });
        }).start();
    }


    // ---------- 화면 움직임 측정 (공 소리 오인 방지) ----------
    // 화면 가운데 세로 60% 영역의 밝기 변화량을 0.066초마다 기록. 최근 2초 기록을 보관.
    private byte[] motPrev = null; private final float[] motHist = new float[40]; private final long[] motTime = new long[40]; private int motIdx = 0;
    private void measureMotion(VideoFrame frame) {
        VideoFrame.I420Buffer b = null;
        try {
            VideoFrame.Buffer sc = frame.getBuffer().cropAndScale(0, 0, frame.getBuffer().getWidth(), frame.getBuffer().getHeight(), 96, 54);
            b = sc.toI420(); sc.release();
            int w = b.getWidth(), h = b.getHeight(), st = b.getStrideY(); java.nio.ByteBuffer y = b.getDataY();
            byte[] cur = new byte[w * h];
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) cur[r * w + c] = y.get(r * st + c);
            float m = 0;
            if (motPrev != null && motPrev.length == cur.length) {
                // 센서 기준 가로 영상: 가운데 영역(가장자리 20% 제외)만 — 옆 타석 사람은 보통 화면 가장자리
                int x0 = w / 5, x1 = w - w / 5, y0 = h / 5, y1 = h - h / 5; long sum = 0; int n = 0;
                for (int r = y0; r < y1; r++) for (int c = x0; c < x1; c++) { int d = (cur[r * w + c] & 255) - (motPrev[r * w + c] & 255); if (d > 25 || d < -25) sum++; n++; }
                m = n > 0 ? (float) sum / n : 0;
            }
            motPrev = cur;
            synchronized (motHist) { motHist[motIdx] = m; motTime[motIdx] = System.currentTimeMillis(); motIdx = (motIdx + 1) % motHist.length; }
        } catch (Exception ignore) {
        } finally { if (b != null) b.release(); }
    }
    // ---------- 스윙 동작 감지 (폰 자체 판단 · 아이패드 화면 인식에 기대지 않음) ----------
    // 초당 약 15번 작은 화면에서 뼈대를 찾아, 손이 어깨 위로 올라갔다가 0.7초 안에 골반 쪽으로 내려오면 스윙으로 보고 저장.
    private long lastWatchNs = 0; private volatile boolean watchBusy = false; private volatile String watchTxt = "골퍼 찾는 중";
    private long wTopAt = 0, wFired = 0;
    private void watch(VideoFrame frame) {
        VideoFrame.Buffer nb = null, sc = null; VideoFrame.I420Buffer b = null;
        try {
            nb = SwingFrameUtil.undoRotation(frame.getBuffer(), -sensorOrientation);
            int bw = nb.getWidth(), bh = nb.getHeight(); final int sw = bw >= bh ? 480 : 270, sh = bw >= bh ? 270 : 480;
            sc = nb.cropAndScale(0, 0, bw, bh, sw, sh); b = sc.toI420();
            final int rot = videoRot();
            final int[] px = Analyzer.i420ToArgb(b.getDataY(), b.getStrideY(), b.getDataU(), b.getStrideU(), b.getDataV(), b.getStrideV(), sw, sh, rot);
            final int W2 = rot % 180 == 0 ? sw : sh, H2 = rot % 180 == 0 ? sh : sw; final long t = System.currentTimeMillis();
            watchBusy = true; wW = W2; wH = H2;
            Analyzer.EX.execute(() -> { try { onWatch(Analyzer.livePose(getApplicationContext(), px, W2, H2), t); } catch (Throwable e) { watchTxt = "골퍼 인식 오류"; } finally { watchBusy = false; } });
        } catch (Throwable e) { watchBusy = false;
        } finally { if (b != null) b.release(); if (sc != null) sc.release(); if (nb != null) nb.release(); }
    }
    private volatile long impactAt = 0; private final double[][] boxHist = new double[45][]; private int boxIdx = 0; private volatile int wW = 270, wH = 480;
    /** 최근 6초 동안 실시간 감시가 본 골퍼 위치의 중앙값 [골반x, 골반y, 키(발목-코)] (화면 비율), 5장 미만이면 null */
    private double[] liveBox() {
        long now = System.currentTimeMillis(); java.util.ArrayList<double[]> v = new java.util.ArrayList<>();
        synchronized (boxHist) { for (double[] e : boxHist) if (e != null && now - e[0] < 6000) v.add(e); }
        if (v.size() < 5) return null; double[] o = new double[3];
        for (int k = 0; k < 3; k++) { final int kk = k + 1; double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i)[kk]; java.util.Arrays.sort(a); o[k] = a[a.length >> 1]; }
        return o[2] > 0.05 ? o : null;
    }
    private void onWatch(double[] p, long t) {
        if (p == null) { watchTxt = "골퍼 안 보임"; return; }
        double shX = (p[2] + p[4]) / 2, shY = (p[3] + p[5]) / 2, hipX = (p[14] + p[16]) / 2, hipY = (p[15] + p[17]) / 2, hy = (p[7] + p[9] + p[11] + p[13]) / 4;
        double tl = Math.hypot(shX - hipX, shY - hipY); if (tl < 6) { watchTxt = "골퍼가 너무 작음"; return; }
        double h = (hipY - hy) / tl;   // 손 높이: 0=골반, 1=어깨
        watchTxt = "골퍼 인식됨";
        synchronized (boxHist) { boxHist[boxIdx] = new double[]{t, hipX / wW, hipY / wH, ((p[19] + p[21]) / 2 - p[1]) / wH}; boxIdx = (boxIdx + 1) % boxHist.length; }
        if (h > 1.0) wTopAt = t;
        else if (h < 0.6 && wTopAt > 0 && t - wTopAt < 700 && t - wFired > 6000) {
            wFired = t; wTopAt = 0; impactAt = t;
            ui.post(() -> toast("스윙 동작 감지 · 저장합니다"));
            ui.postDelayed(() -> saveClip("스윙", 7_000_000L), 1300);
        }
    }

    /** 최근 ms 동안 화면 가운데에서 가장 크게 바뀐 비율(0~1). 내 스윙이면 다운스윙 때 크게 튄다. */
    private float recentMotion(long ms) {
        long now = System.currentTimeMillis(); float mx = 0;
        synchronized (motHist) { for (int i = 0; i < motHist.length; i++) if (now - motTime[i] <= ms && motHist[i] > mx) mx = motHist[i]; }
        return mx;
    }

    /** 마이크로 공 맞는 소리(짧고 큰 충격음)를 감지 → 1.5초 뒤 최근 7초 저장. 아이패드 없이도 동작. */
    private void startMic() {
        if (mic != null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        micRun = true;
        mic = new Thread(() -> {
            final int SR = 16000, N = 160;   // 10ms 단위
            int min = android.media.AudioRecord.getMinBufferSize(SR, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            android.media.AudioRecord ar;
            try { ar = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.UNPROCESSED, SR, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR)); }
            catch (Exception e) { return; }
            if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                try { ar.release(); ar = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, SR, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR)); } catch (Exception e) { return; }
            }
            short[] b = new short[N]; double bg = 300; long lastHit = 0;
            try { ar.startRecording(); } catch (Exception e) { ar.release(); return; }
            while (micRun) {
                int k = ar.read(b, 0, N); if (k <= 0) continue;
                double sum = 0; int peak = 0;
                for (int i = 0; i < k; i++) { int a = Math.abs(b[i]); sum += (double) a * a; if (a > peak) peak = a; }
                double rms = Math.sqrt(sum / k);
                long now = System.currentTimeMillis();
                // 충격음: 최대값이 크고(전체의 약 35% 이상) 주변 소음보다 훨씬 큼
                if (autoOn && peak > 11000 && rms > bg * 6 && now - lastHit > 5000) {
                    lastHit = now;
                    if (now - impactAt > 1500) impactAt = now;   // 스윙 동작으로 이미 잡았으면 그 시각을 유지
                    float mv = recentMotion(1500);
                    if (mv < 0.02f) {   // 소리는 났지만 이 카메라 앞에서는 스윙이 없었음 → 옆 타석
                        ui.post(() -> toast("소리 감지 · 화면에 스윙이 없어 저장 안 함"));
                        continue;
                    }
                    ui.post(() -> toast("공 맞는 소리 감지 · 저장합니다"));
                    ui.postDelayed(() -> saveClip("소리", 7_000_000L), 1500);
                }
                bg = bg * 0.995 + Math.min(rms, bg * 3) * 0.005;   // 주변 소음(충격음은 거의 반영 안 함)
            }
            try { ar.stop(); } catch (Exception ignore) {}
            ar.release();
        });
        mic.start();
    }

    /** 저장한 원본을 아이패드로 전송 (데이터 채널, base64 조각). 아이패드가 직접 고속 분석한다. */
    private volatile int clipSeq = 0;
    private volatile boolean sending = false;
    private void sendClip(File src, String name, int fps, boolean trim, long srcEndAt) {
        if (dc == null || dc.state() != DataChannel.State.OPEN) return;
        final int id = ++clipSeq;
        new Thread(() -> {
            final File f = new File(getCacheDir(), "send_" + id + ".mp4");
            java.util.concurrent.Future<?> an = null;
            try {
                // 자동 저장이면 스윙 구간(최근 5초)만 따로 묶어 보냄 → 전송량 약 30% 감소. 수동 저장은 전체.
                int made = 0;
                long endAt = System.currentTimeMillis();
                if (trim && rr != null) { try { made = rr.save(5_000_000L, f, videoRot()); } catch (Exception e) { made = 0; } }
                if (made <= 0) endAt = srcEndAt;
                final long ago = (impactAt > 0 && endAt - impactAt > 0 && endAt - impactAt < 4500) ? endAt - impactAt : -1; final double[] box = liveBox();
                if (made <= 0) try (InputStream in = new FileInputStream(src); OutputStream o = new java.io.FileOutputStream(f)) {
                    byte[] b = new byte[1 << 16]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); }
                sending = true;   // 전송 중에는 실시간 화면 전송을 쉬어 와이파이를 원본 전송에 몰아줌
                long size = f.length(); final int CH = 12 * 1024; /* base64 후 약 16KB: 데이터 채널 메시지 한도(64KB)보다 충분히 작게 */ int n = (int) ((size + CH - 1) / CH);
                // 폰에서 바로 분석(전송과 동시에). 측정값만 아이패드로 보내고, 실패하면 아이패드가 대신 분석한다.
                sendData(new JSONObject().put("t", "clipstart").put("id", id).put("name", name).put("size", size).put("n", n).put("fps", fps).put("det", 1));
                an = Analyzer.EX.submit(() -> analyzeAndSend(f, id, ago, box));
                byte[] b = new byte[CH];
                try (InputStream in = new FileInputStream(f)) {
                    for (int i = 0; i < n; i++) {
                        int got = 0, k; while (got < CH && (k = in.read(b, got, CH - got)) > 0) got += k;
                        String d = android.util.Base64.encodeToString(b, 0, got, android.util.Base64.NO_WRAP);
                        while (dc != null && dc.state() == DataChannel.State.OPEN && dc.bufferedAmount() > 2L * 1024 * 1024) Thread.sleep(2);
                        if (dc == null || dc.state() != DataChannel.State.OPEN || id != clipSeq) return;
                        sendData(new JSONObject().put("t", "clip").put("id", id).put("i", i).put("d", d));
                    }
                }
                sendData(new JSONObject().put("t", "clipend").put("id", id));
            } catch (Exception e) { ui.post(() -> toast("아이패드 전송 실패: " + e.getMessage())); }
            finally { sending = false;
                if (an != null) try { an.get(180, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignore) {}   // 분석이 파일을 다 읽은 뒤 지움
                f.delete(); }
        }).start();
    }

    /** 폰 안 분석 → 측정값(JSON)을 12KB씩 나눠 아이패드로 */
    private void analyzeAndSend(File f, int id, long ago, double[] box) {
        try {
            Analyzer.Result r = Analyzer.run(getApplicationContext(), f, line -> status(line, false), ago, box);
            String s = r.json; final int CH = 12000; int n = (s.length() + CH - 1) / CH;
            for (int i = 0; i < n; i++) sendData(new JSONObject().put("t", "det").put("id", id).put("i", i).put("n", n).put("d", s.substring(i * CH, Math.min(s.length(), (i + 1) * CH))));
            status(r.summary, false);
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
            try { sendData(new JSONObject().put("t", "det").put("id", id).put("err", m)); } catch (Exception ignore) {}
            status("폰 분석 실패: " + m + " · 아이패드가 대신 분석합니다", true);
        }
    }

    /** 셔터 적용. 수동일 때 ISO는 '자동일 때의 밝기'를 유지하도록 계산(셔터가 짧아진 만큼 ISO를 올림, 최대치 제한) */
    private void applyCapture() {
        if (camSession == null || cam == null) return;
        camH.post(() -> {
            try {
                CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                rb.addTarget(camSurface);
                if (recSurface != null) rb.addTarget(recSurface);
                int tf = hs ? 240 : FPS;
                rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(tf, tf));
                rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                long s = SHUTTERS[shutterIdx];
                if (s > 0) {
                    long exp = 1_000_000_000L / s;
                    int iso = 1600;
                    if (autoExp > 0 && autoIso > 0) iso = (int) Math.round(autoIso * (double) autoExp / exp);
                    iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));
                    rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    rb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
                    rb.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                }
                CameraCaptureSession.CaptureCallback ccb = new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest r, TotalCaptureResult res) {
                        Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME); Integer i = res.get(CaptureResult.SENSOR_SENSITIVITY);
                        if (e != null) curExp = e; if (i != null) curIso = i;
                        if (SHUTTERS[shutterIdx] == 0 && e != null && i != null) { autoExp = e; autoIso = i; }
                    }
                };
                if (hs && camSession instanceof CameraConstrainedHighSpeedCaptureSession) {
                    CameraConstrainedHighSpeedCaptureSession h = (CameraConstrainedHighSpeedCaptureSession) camSession;
                    h.setRepeatingBurst(h.createHighSpeedRequestList(rb.build()), ccb, camH);
                } else camSession.setRepeatingRequest(rb.build(), ccb, camH);
            } catch (Exception e) { status("셔터 적용 실패: " + e.getMessage(), true); }
        });
    }

    // ---------- 기울기 (cam.html 과 같은 계산) ----------
    private void startTilt() {
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (a == null) return;
        sm.registerListener(new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                float k = gravN < 10 ? 0.5f : 0.1f;
                for (int i = 0; i < 3; i++) grav[i] += k * (e.values[i] - grav[i]);
                gravN++;
                double gx = grav[0], gy = grav[1], sz = grav[2];
                // 폰 방향(세로/가로) 판정: OrientationEventListener 와 같은 기준 (0=세로, 90=시계방향으로 눕힘)
                double ax = Math.abs(gx), ay = Math.abs(gy);
                if (ax > ay * 1.4) devRot = gx < 0 ? 90 : 270; else if (ay > ax * 1.4) devRot = gy < 0 ? 180 : 0;
                double ux = devRot == 90 ? -1 : devRot == 270 ? 1 : 0, uy = devRot == 0 ? 1 : devRot == 180 ? -1 : 0;
                double sy = gx * ux + gy * uy, sx = gx * uy - gy * ux;   // 화면 위쪽/오른쪽 기준 중력
                if (sy < 0) { sx = -sx; sy = -sy; sz = -sz; }
                double r = Math.toDegrees(Math.atan2(-sx, sy)), up = Math.toDegrees(Math.atan2(sz, Math.hypot(sx, sy)));
                pitch = -up; roll = r;   // 후면 카메라 기준 (cam.html 과 동일)
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        }, a, SensorManager.SENSOR_DELAY_GAME);
    }

    // ---------- 주기 작업: 기울기·생존 신호 전송, 화면 표시 ----------
    private long hbT = 0, infoT = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (pitch != null) {
                boolean bad = Math.abs(pitch) > 10 || Math.abs(roll) > 3;
                levelTv.setText((devRot % 180 != 0 ? "가로 · " : "세로 · ") + (pitch > 0 ? "위로 " : "아래로 ") + Math.abs(Math.round(pitch)) + "° · 좌우 " + Math.round(Math.abs(roll)) + "° " + (bad ? "기울어짐" : "수평"));
                levelTv.setTextColor(bad ? Color.rgb(255, 138, 101) : Color.rgb(88, 214, 141));
            }
            infoTv.setText(String.format("%dx%d · 원본 녹화 %.0ffps · 화면 %.0ffps · 셔터 %s · ISO %d", W, H, recFps, camFps,
                    curExp > 0 ? "1/" + Math.round(1e9 / curExp) : "?", curIso) + (autoOn ? " · " + watchTxt : ""));
            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                try {
                    if (pitch != null) sendData(new JSONObject().put("t", "tilt").put("pitch", pitch).put("roll", roll));
                    else if (now - hbT > 1000) { hbT = now; sendData(new JSONObject().put("t", "hb")); }
                    if (now - infoT > 3000) { infoT = now; sendInfo(); }
                } catch (Exception ignore) {}
            }
            ui.postDelayed(this, 100);
        }
    };

    private void sendInfo() {
        try {
            long s = SHUTTERS[shutterIdx];
            sendData(new JSONObject().put("t", "info").put("label", "스윙 카메라 앱 · 셔터 " + (s == 0 ? "자동" : "1/" + s) + " · 원본 " + (hs ? 240 : FPS) + "fps")
                    .put("w", H).put("h", W).put("fps", camFps > 1 ? Math.min(60, camFps) : FPS).put("zoom", 1).put("zx", 0.5).put("zy", 0.5).put("hw", false));
        } catch (Exception ignore) {}
    }

    /** PeerJS 'json' 직렬화와 같은 형식: JSON 문자열을 UTF-8 바이트로 */
    private void sendData(JSONObject o) {
        if (dc == null || dc.state() != DataChannel.State.OPEN) return;
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        dc.send(new DataChannel.Buffer(ByteBuffer.wrap(b), true));
    }

    // ---------- 신호(PeerJS) ----------
    private void restartSignal() {
        closeRemote();
        if (pj != null) pj.close();
        status("중개 서버 접속 중…", false);
        pj = new PeerJs(PFX + code, new PeerJs.Listener() {
            @Override public void onOpen() { status("아이패드 연결 기다리는 중 · 위 번호를 아이패드에 입력하세요", false); }
            @Override public void onMessage(String type, String src, JSONObject p) { onSignal(type, src, p); }
            @Override public void onClosed(String why) {
                if (why.startsWith("ID-TAKEN")) { status("번호가 이미 쓰이는 중 · 잠시 뒤 다시 시도", true); }
                else status("중개 서버 연결 끊김 · 다시 접속 중", true);
                ui.postDelayed(() -> { if (pj != null && !pj.isOpen()) pj.connect(); }, 3000);
            }
        });
        pj.connect();
    }

    private PeerConnection.RTCConfiguration rtcConfig() {
        List<PeerConnection.IceServer> s = new ArrayList<>();
        s.add(PeerConnection.IceServer.builder(Arrays.asList("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer());
        s.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());
        s.add(PeerConnection.IceServer.builder(Arrays.asList("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"))
                .setUsername("peerjs").setPassword("peerjsp").createIceServer());
        PeerConnection.RTCConfiguration c = new PeerConnection.RTCConfiguration(s);
        c.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        c.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        return c;
    }

    private void onSignal(String type, String src, JSONObject p) {
        try {
            String cid = p.optString("connectionId");
            switch (type) {
                case "OFFER":
                    if ("data".equals(p.optString("type"))) acceptData(src, cid, p.getJSONObject("sdp").getString("sdp"));
                    break;
                case "ANSWER":
                    if (cid.equals(mediaId) && mediaPc != null)
                        mediaPc.setRemoteDescription(new Sdp("미디어 응답") { @Override public void onSetSuccess() { mediaRemoteSet = true; flush(mediaPc, pendMedia); } },
                                new SessionDescription(SessionDescription.Type.ANSWER, p.getJSONObject("sdp").getString("sdp")));
                    break;
                case "CANDIDATE": {
                    JSONObject c = p.getJSONObject("candidate");
                    IceCandidate ic = new IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"));
                    if (cid.equals(dataId) && dataPc != null) { if (dataRemoteSet) dataPc.addIceCandidate(ic); else pendData.add(ic); }
                    else if (cid.equals(mediaId) && mediaPc != null) { if (mediaRemoteSet) mediaPc.addIceCandidate(ic); else pendMedia.add(ic); }
                    break;
                }
                case "LEAVE": case "EXPIRE":
                    if (src != null && src.equals(remote)) { closeRemote(); status("아이패드 연결 끊김 · 다시 기다리는 중", true); }
                    break;
            }
        } catch (Exception e) { status("신호 처리 오류: " + e.getMessage(), true); }
    }

    private void flush(PeerConnection pc, List<IceCandidate> q) { for (IceCandidate c : q) pc.addIceCandidate(c); q.clear(); }

    /** 아이패드가 데이터 연결을 요청함 → 응답하고, 열리면 영상 전화를 검 */
    private void acceptData(String src, String cid, String sdp) {
        closeRemote();
        remote = src; dataId = cid; dataRemoteSet = false; pendData.clear();
        status("아이패드가 접속함 · 경로 찾는 중", false);
        dataPc = factory.createPeerConnection(rtcConfig(), new PcObs("data", cid) {
            @Override public void onDataChannel(DataChannel ch) {
                dc = ch;
                ch.registerObserver(new DataChannel.Observer() {
                    @Override public void onBufferedAmountChange(long l) {}
                    @Override public void onStateChange() { if (ch.state() == DataChannel.State.OPEN) ui.post(() -> onDataOpen()); }
                    @Override public void onMessage(DataChannel.Buffer buf) {
                        try { byte[] b = new byte[buf.data.remaining()]; buf.data.get(b); JSONObject m = new JSONObject(new String(b, StandardCharsets.UTF_8));
                            if ("lens".equals(m.optString("t"))) toast("렌즈 바꾸기는 아직 지원하지 않습니다");
                            else if ("swing".equals(m.optString("t"))) ui.postDelayed(() -> saveClip("스윙", 7_000_000L), 1200);   // 피니시 뒤 조금 더 담고 저장
                        } catch (Exception ignore) {}
                    }
                });
            }
        });
        if (dataPc == null) { status("연결 생성 실패", true); return; }
        dataPc.setRemoteDescription(new Sdp("데이터 요청") {
            @Override public void onSetSuccess() {
                dataRemoteSet = true; flush(dataPc, pendData);
                dataPc.createAnswer(new Sdp("데이터 응답 만들기") {
                    @Override public void onCreateSuccess(SessionDescription d) {
                        dataPc.setLocalDescription(new Sdp("데이터 응답 적용"), d);
                        try {
                            pj.send("ANSWER", remote, new JSONObject()
                                    .put("sdp", new JSONObject().put("type", "answer").put("sdp", d.description))
                                    .put("type", "data").put("connectionId", cid).put("browser", "chrome"));
                        } catch (Exception ignore) {}
                    }
                }, new MediaConstraints());
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    private void onDataOpen() {
        status("아이패드 연결됨 · 영상 보내는 중", false);
        sendInfo();
        if (mediaPc == null) startCall();
    }

    private void startCall() {
        mediaId = "mc_" + Long.toString(new SecureRandom().nextLong() & Long.MAX_VALUE, 36);
        mediaRemoteSet = false; pendMedia.clear();
        final String cid = mediaId;
        mediaPc = factory.createPeerConnection(rtcConfig(), new PcObs("media", cid));
        if (mediaPc == null) return;
        RtpSender sender = mediaPc.addTrack(vtrack, Collections.singletonList("swing"));
        mediaPc.createOffer(new Sdp("영상 요청 만들기") {
            @Override public void onCreateSuccess(SessionDescription d) {
                mediaPc.setLocalDescription(new Sdp("영상 요청 적용") {
                    @Override public void onSetSuccess() {
                        try {   // 화질: 최대 40Mbps, 프레임 수 우선
                            RtpParameters rp = sender.getParameters();
                            for (RtpParameters.Encoding e : rp.encodings) { e.maxBitrateBps = 40_000_000; e.maxFramerate = FPS; }
                            rp.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;
                            sender.setParameters(rp);
                        } catch (Exception ignore) {}
                    }
                }, d);
                try {
                    pj.send("OFFER", remote, new JSONObject()
                            .put("sdp", new JSONObject().put("type", "offer").put("sdp", d.description))
                            .put("type", "media").put("connectionId", cid));
                } catch (Exception ignore) {}
            }
        }, new MediaConstraints());
    }

    private void closeRemote() {
        try { if (dc != null) { dc.unregisterObserver(); dc.close(); } } catch (Exception ignore) {}
        try { if (mediaPc != null) mediaPc.close(); } catch (Exception ignore) {}
        try { if (dataPc != null) dataPc.close(); } catch (Exception ignore) {}
        dc = null; mediaPc = null; dataPc = null; mediaId = null; dataId = null; remote = null;
    }

    /** 연결 상태 감시 + 경로 후보를 상대에게 전달 */
    private class PcObs implements PeerConnection.Observer {
        final String kind, cid;
        PcObs(String kind, String cid) { this.kind = kind; this.cid = cid; }
        @Override public void onIceCandidate(IceCandidate c) {
            try {
                pj.send("CANDIDATE", remote, new JSONObject()
                        .put("candidate", new JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
                        .put("type", kind).put("connectionId", cid));
            } catch (Exception ignore) {}
        }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
            if ("media".equals(kind) && s == PeerConnection.IceConnectionState.CONNECTED) status("아이패드로 영상 보내는 중", false);
            if (s == PeerConnection.IceConnectionState.FAILED) {
                status("경로 연결 실패 · 같은 와이파이인지 확인", true);
                ui.post(() -> { if (cid.equals(dataId) || cid.equals(mediaId)) closeRemote(); });
            }
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
        @Override public void onIceConnectionReceivingChange(boolean b) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
        @Override public void onAddStream(MediaStream m) {}
        @Override public void onRemoveStream(MediaStream m) {}
        @Override public void onDataChannel(DataChannel d) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver r, MediaStream[] m) {}
    }

    private class Sdp implements SdpObserver {
        final String what;
        Sdp(String what) { this.what = what; }
        @Override public void onCreateSuccess(SessionDescription d) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String e) { status(what + " 실패: " + e, true); }
        @Override public void onSetFailure(String e) { status(what + " 실패: " + e, true); }
    }

    // ---------- 앱 안 업데이트 ----------
    private int myVersion() { try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; } }
    private void checkUpdate() {
        new Thread(() -> {
            int v;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(REL + "version.txt?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) { v = Integer.parseInt(r.readLine().trim()); }
            } catch (Exception e) { v = -1; }
            final int lv = v; latest = v;
            ui.post(() -> {
                int mine = myVersion();
                updBtn.setEnabled(true);
                if (lv > mine) { updBtn.setText("새 버전 있음 (" + mine + " → " + lv + ") · 눌러서 설치"); updBtn.setOnClickListener(x -> startUpdate()); }
                else { updBtn.setText((lv < 0 ? "업데이트 확인 실패" : "최신 버전") + " (" + mine + ") · 다시 확인"); updBtn.setOnClickListener(x -> { updBtn.setText("확인 중…"); checkUpdate(); }); }
            });
        }).start();
    }
    private void startUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 누르세요.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(REL + "swingcam.apk?t=" + System.currentTimeMillis()));
        rq.setTitle("스윙 카메라 업데이트"); rq.setMimeType("application/vnd.android.package-archive");
        rq.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "swingcam-" + latest + ".apk");
        long id = dm.enqueue(rq);
        updBtn.setText("내려받는 중…"); updBtn.setEnabled(false);
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                if (it.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try { unregisterReceiver(this); } catch (Exception ignore) {}
                Uri apk = dm.getUriForDownloadedFile(id);
                updBtn.setEnabled(true);
                if (apk == null) { updBtn.setText("내려받기 실패 · 다시 누르기"); return; }
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                updBtn.setText("설치 화면에서 '업데이트'를 누르세요");
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }

    @Override protected void onDestroy() {
        micRun = false;
        super.onDestroy();
        ui.removeCallbacks(tick);
        closeRemote();
        if (pj != null) pj.close();
        try { if (camSession != null) camSession.close(); } catch (Exception ignore) {}
        try { if (cam != null) cam.close(); } catch (Exception ignore) {}
        if (rr != null) rr.stop();
        try { if (sth != null) { sth.stopListening(); sth.dispose(); } } catch (Exception ignore) {}
        try { preview.release(); } catch (Exception ignore) {}
        if (camThread != null) camThread.quitSafely();
    }
}
