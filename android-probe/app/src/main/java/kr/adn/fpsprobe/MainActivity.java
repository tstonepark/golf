package kr.adn.fpsprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.content.ContentValues;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 스윙 카메라 FPS 측정 앱.
 * 이 폰이 '다른 앱'에 열어 주는 고속 촬영(120/240fps)과 셔터(노출 시간) 조절이 실제로 되는지 잰다.
 * 측정값은 카메라가 보고하는 프레임 시각(SENSOR_TIMESTAMP)과 실제 적용된 노출 시간(CaptureResult)으로 계산한다.
 */
public class MainActivity extends Activity {
    private CameraManager cm;
    private TextureView tv;
    private TextView out, status;
    private HandlerThread bgThread;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CameraDevice device;
    private CameraCaptureSession session;
    private String camId;
    private final StringBuilder log = new StringBuilder();
    private final List<Runnable> queue = new ArrayList<>();
    private boolean busy = false;
    private MediaRecorder recorder;
    private Button upd;
    private int latest = 0;
    private static final String REL = "https://github.com/tstonepark/golf/releases/download/fps-probe/";
    private File recFile;

    // 측정 중 값
    private final List<Long> ts = new ArrayList<>();
    private final List<Long> exp = new ArrayList<>();
    private final List<Integer> iso = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        bgThread = new HandlerThread("cam"); bgThread.start(); bg = new Handler(bgThread.getLooper());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 21, 18));
        root.setPadding(24, 48, 24, 24);

        TextView title = text("스윙 카메라 FPS 측정", 26, Color.rgb(255, 210, 63));
        root.addView(title);
        status = text("아래 '전체 측정'을 누르세요. 폰을 세워 두고 약 20초 걸립니다.", 18, Color.WHITE);
        root.addView(status);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button all = button("전체 측정"); all.setOnClickListener(v -> runAll());
        Button copy = button("결과 복사"); copy.setOnClickListener(v -> copyResult());
        Button lens = button("카메라 바꾸기"); lens.setOnClickListener(v -> nextCamera());
        row.addView(all, new LinearLayout.LayoutParams(0, 160, 1));
        row.addView(copy, new LinearLayout.LayoutParams(0, 160, 1));
        row.addView(lens, new LinearLayout.LayoutParams(0, 160, 1));
        root.addView(row);
        upd = button("업데이트 확인 중…"); upd.setEnabled(false);
        upd.setOnClickListener(v -> startUpdate());
        root.addView(upd, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));

        tv = new TextureView(this);
        root.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 520));

        ScrollView sv = new ScrollView(this);
        out = text("", 17, Color.rgb(234, 242, 236));
        out.setTextIsSelectable(true);
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        checkUpdate();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        else start();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) start();
        else status.setText("카메라 권한이 필요합니다. 설정에서 허용해 주세요.");
    }

    private void start() {
        try {
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraMetadata.LENS_FACING_BACK) { camId = id; break; }
            }
            if (camId == null) camId = cm.getCameraIdList()[0];
        } catch (Exception e) { say("카메라 목록 실패: " + e); return; }
        dumpInfo();
    }

    private void nextCamera() {
        if (busy) return;
        try {
            String[] ids = cm.getCameraIdList();
            int i = Arrays.asList(ids).indexOf(camId);
            camId = ids[(i + 1) % ids.length];
        } catch (Exception e) { return; }
        log.setLength(0); dumpInfo();
    }

    // ---------- 카메라 능력 ----------
    private void dumpInfo() {
        try {
            add("[기기] " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE);
            add("[카메라 목록] " + Arrays.toString(cm.getCameraIdList()));
            CameraCharacteristics c = cm.getCameraCharacteristics(camId);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            Integer lvl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            add("\n[선택한 카메라 " + camId + "] " + (f != null && f == CameraMetadata.LENS_FACING_BACK ? "후면" : "전면/기타") + " · 하드웨어 수준 " + levelName(lvl));
            float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (fl != null) add("초점거리 " + Arrays.toString(fl) + "mm");
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean hs = false, manual = false;
            if (caps != null) for (int x : caps) {
                if (x == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) hs = true;
                if (x == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manual = true;
            }
            add("고속 촬영(120/240fps) 앱에 공개: " + (hs ? "예" : "아니오"));
            add("수동 셔터·ISO 앱에 공개: " + (manual ? "예" : "아니오"));
            Range<Long> er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (er != null) add("셔터 범위: 1/" + Math.round(1e9 / er.getUpper()) + "초 ~ 1/" + Math.round(1e9 / er.getLower()) + "초");
            Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (ir != null) add("ISO 범위: " + ir);
            Range<Integer>[] ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ae != null) add("일반 촬영 fps 범위: " + Arrays.toString(ae));
            StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (m != null && hs) {
                for (Size s : m.getHighSpeedVideoSizes()) {
                    try { add("고속 " + s + ": " + Arrays.toString(m.getHighSpeedVideoFpsRangesFor(s))); } catch (Exception ignore) {}
                }
            }
            if (c.getPhysicalCameraIds() != null && !c.getPhysicalCameraIds().isEmpty())
                add("묶인 물리 카메라: " + c.getPhysicalCameraIds());
        } catch (Exception e) { add("정보 읽기 실패: " + e); }
    }

    private String levelName(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            default: return String.valueOf(l);
        }
    }

    // ---------- 측정 순서 ----------
    private void runAll() {
        if (busy) return;
        if (!tv.isAvailable()) { say("화면 준비 중입니다. 잠시 뒤 다시 누르세요."); return; }
        queue.clear();
        add("\n===== 측정 시작 (카메라 " + camId + ") · 항목마다 3초 녹화 → 영상 파일의 프레임을 직접 셈 =====");
        queue.add(() -> test("고속 120fps · 자동 노출", true, 120, 0));
        queue.add(() -> test("고속 120fps · 셔터 1/1000초", true, 120, 1_000_000L));
        queue.add(() -> test("고속 240fps · 자동 노출", true, 240, 0));
        queue.add(() -> test("고속 240fps · 셔터 1/1000초", true, 240, 1_000_000L));
        queue.add(() -> test("일반 최대 fps · 셔터 1/1000초", false, 0, 1_000_000L));
        busy = true; next();
    }

    private void next() {
        if (queue.isEmpty()) { busy = false; add("===== 측정 끝 · '결과 복사'를 눌러 보내 주세요 ====="); setStatus("끝났습니다. '결과 복사' → 채팅에 붙여넣기"); return; }
        Runnable r = queue.remove(0);
        ui.postDelayed(r, 400);
    }

    /** highSpeed: 고속 세션 여부, fps: 목표(고속일 때), exposureNs: 0이면 자동 노출 */
    private void test(String name, boolean highSpeed, int fps, long exposureNs) {
        setStatus("측정 중: " + name);
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size; Range<Integer> range;
            if (highSpeed) {
                boolean hs = false; int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (caps != null) for (int x : caps) if (x == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) hs = true;
                if (!hs || m == null) { add("· " + name + ": 이 카메라는 고속 촬영을 앱에 열어 주지 않음"); next(); return; }
                size = null; range = null;
                // 1080p 우선, 없으면 가장 큰 크기
                List<Size> sizes = new ArrayList<>(Arrays.asList(m.getHighSpeedVideoSizes()));
                sizes.sort((a, bb) -> bb.getWidth() * bb.getHeight() - a.getWidth() * a.getHeight());
                for (Size s : sizes) {
                    for (Range<Integer> r : m.getHighSpeedVideoFpsRangesFor(s)) {
                        if (r.getLower() == fps && r.getUpper() == fps) {
                            if (size == null || (s.getWidth() == 1920 && s.getHeight() == 1080)) { size = s; range = r; }
                        }
                    }
                    if (size != null && size.getWidth() == 1920) break;
                }
                if (size == null) { add("· " + name + ": " + fps + "fps 고정 모드 없음"); next(); return; }
            } else {
                size = new Size(1920, 1080); range = null;
                Range<Integer>[] ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ae != null) for (Range<Integer> r : ae) if (r.getLower().equals(r.getUpper()) && (range == null || r.getUpper() > range.getUpper())) range = r;
                if (range == null && ae != null) for (Range<Integer> r : ae) if (range == null || r.getUpper() > range.getUpper()) range = r;
            }
            open(name, highSpeed, size, range, exposureNs, c);
        } catch (Exception e) { add("· " + name + ": 오류 " + e.getMessage()); next(); }
    }

    private void open(String name, boolean highSpeed, Size size, Range<Integer> range, long exposureNs, CameraCharacteristics c) throws CameraAccessException {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { next(); return; }
        cm.openCamera(camId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) {
                device = d;
                try {
                    SurfaceTexture st = tv.getSurfaceTexture();
                    st.setDefaultBufferSize(size.getWidth(), size.getHeight());
                    Surface surf = new Surface(st);
                    Surface recSurf = null;
                    try {
                        recFile = new File(getCacheDir(), "probe.mp4"); if (recFile.exists()) recFile.delete();
                        int fpsRec = highSpeed ? range.getUpper() : (range != null ? range.getUpper() : 30);
                        recorder = new MediaRecorder();
                        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                        recorder.setOutputFile(recFile.getAbsolutePath());
                        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                        recorder.setVideoSize(size.getWidth(), size.getHeight());
                        recorder.setVideoFrameRate(fpsRec);
                        recorder.setCaptureRate(fpsRec);
                        recorder.setVideoEncodingBitRate(Math.min(80_000_000, 200_000 * fpsRec + 20_000_000));
                        recorder.prepare();
                        recSurf = recorder.getSurface();
                    } catch (Exception re) { add("· " + name + ": 녹화기 준비 실패 " + re.getMessage()); recorder = null; }
                    final Surface rs = recSurf;
                    final List<Surface> outs = rs != null ? Arrays.asList(surf, rs) : Collections.singletonList(surf);
                    CameraCaptureSession.StateCallback scb = new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) { session = s; startRepeating(name, highSpeed, outs, size, range, exposureNs, c); }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { add("· " + name + ": 세션 설정 실패(폰이 거부)"); closeCam(); next(); }
                    };
                    if (highSpeed) d.createConstrainedHighSpeedCaptureSession(outs, scb, bg);
                    else d.createCaptureSession(outs, scb, bg);
                } catch (Exception e) { add("· " + name + ": 세션 오류 " + e.getMessage()); closeCam(); next(); }
            }
            @Override public void onDisconnected(CameraDevice d) { d.close(); }
            @Override public void onError(CameraDevice d, int err) { add("· " + name + ": 카메라 열기 오류 " + err); d.close(); next(); }
        }, bg);
    }

    private void startRepeating(String name, boolean highSpeed, List<Surface> outs, Size size, Range<Integer> range, long exposureNs, CameraCharacteristics c) {
        try {
            CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface o : outs) rb.addTarget(o);
            if (range != null) rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            if (exposureNs > 0) {
                Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                int isoV = ir != null ? Math.min(ir.getUpper(), 3200) : 1600;
                rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                rb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
                rb.set(CaptureRequest.SENSOR_SENSITIVITY, isoV);
            }
            ts.clear(); exp.clear(); iso.clear();
            CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    Long t = res.get(CaptureResult.SENSOR_TIMESTAMP); if (t != null) ts.add(t);
                    Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME); if (e != null) exp.add(e);
                    Integer i = res.get(CaptureResult.SENSOR_SENSITIVITY); if (i != null) iso.add(i);
                }
            };
            if (highSpeed) {
                CameraConstrainedHighSpeedCaptureSession hs = (CameraConstrainedHighSpeedCaptureSession) session;
                hs.setRepeatingBurst(hs.createHighSpeedRequestList(rb.build()), cb, bg);
            } else session.setRepeatingRequest(rb.build(), cb, bg);
            // 0.7초 워밍업 후 3초 녹화
            bg.postDelayed(() -> { ts.clear(); exp.clear(); iso.clear(); try { if (recorder != null) recorder.start(); } catch (Exception re) { add("   녹화 시작 실패: " + re.getMessage()); recorder = null; } }, 700);
            bg.postDelayed(() -> report(name, size, range, exposureNs), 3700);
        } catch (Exception e) { add("· " + name + ": 시작 실패 " + e.getMessage()); closeCam(); next(); }
    }

    private void report(String name, Size size, Range<Integer> range, long exposureNs) {
        List<Long> t = new ArrayList<>(ts); List<Long> e = new ArrayList<>(exp); List<Integer> i = new ArrayList<>(iso);
        String fileTxt = "녹화 없음";
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception re) { fileTxt = "녹화 저장 실패"; }
            try { recorder.release(); } catch (Exception ignore) {}
            recorder = null;
            closeCam();
            if (recFile != null && recFile.exists() && recFile.length() > 0) {
                fileTxt = countFrames(recFile);
                String saved = saveToGallery(recFile, name);
                fileTxt += saved;
            }
        } else closeCam();
        Collections.sort(t);
        String fpsTxt = "측정 안 됨";
        if (t.size() > 10) {
            double fps = (t.size() - 1) * 1e9 / (t.get(t.size() - 1) - t.get(0));
            fpsTxt = String.format("%.1f fps (%d장)", fps, t.size());
        }
        String expTxt = "보고 없음";
        if (!e.isEmpty()) { Collections.sort(e); long med = e.get(e.size() / 2); expTxt = "1/" + Math.round(1e9 / med) + "초"; }
        String isoTxt = i.isEmpty() ? "?" : String.valueOf(i.get(i.size() / 2));
        add("· " + name + "\n   요청: " + size + (range != null ? " " + range : "") + (exposureNs > 0 ? " · 셔터 1/" + Math.round(1e9 / exposureNs) : "")
                + "\n   영상 파일: " + fileTxt
                + "\n   (참고) 카메라 보고: " + fpsTxt + " · 실제 셔터 " + expTxt + " · ISO " + isoTxt);
        next();
    }

    /** 녹화된 영상의 프레임 수와 시각으로 실제 fps 계산 */
    private String countFrames(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int vt = -1; MediaFormat fmt = null;
            for (int k = 0; k < ex.getTrackCount(); k++) { MediaFormat mf = ex.getTrackFormat(k); String mime = mf.getString(MediaFormat.KEY_MIME); if (mime != null && mime.startsWith("video/")) { vt = k; fmt = mf; break; } }
            if (vt < 0) return "영상 트랙 없음";
            ex.selectTrack(vt);
            long first = -1, last = -1; int n = 0;
            while (true) { long st = ex.getSampleTime(); if (st < 0) break; if (first < 0) first = st; last = st; n++; ex.advance(); }
            String meta = "";
            if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) meta = " · 파일 표기 " + fmt.getInteger(MediaFormat.KEY_FRAME_RATE) + "fps";
            if (n < 2 || last <= first) return n + "장";
            double fps = (n - 1) * 1e6 / (last - first);
            return String.format("%d장 / %.2f초 = %.1f fps", n, (last - first) / 1e6, fps) + meta + " · " + (f.length() / 1024) + "KB";
        } catch (Exception e) { return "프레임 세기 실패: " + e.getMessage(); }
        finally { ex.release(); }
    }

    /** 갤러리(동영상/SwingProbe)에 저장 → 클럽이 또렷한지 눈으로 확인 */
    private String saveToGallery(File f, String name) {
        try {
            ContentValues cv = new ContentValues();
            String fn = "probe_" + name.replaceAll("[^0-9a-zA-Z가-힣]+", "_") + "_" + System.currentTimeMillis() + ".mp4";
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, fn);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SwingProbe");
            Uri u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (u == null) return "";
            try (InputStream in = new FileInputStream(f); OutputStream o = getContentResolver().openOutputStream(u)) {
                byte[] buf = new byte[65536]; int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            }
            return " · 갤러리 저장됨";
        } catch (Exception e) { return " · 갤러리 저장 실패"; }
    }

    private void closeCam() {
        try { if (recorder != null) { recorder.release(); recorder = null; } } catch (Exception ignore) {}
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        try { if (device != null) device.close(); } catch (Exception ignore) {}
        session = null; device = null;
    }

    // ---------- 앱 안 업데이트: 저장소 릴리스의 version.txt 와 내 버전 비교 → 새 APK 받아 설치 화면 열기 ----------
    private int myVersion() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; }
    }
    private void checkUpdate() {
        new Thread(() -> {
            int v = 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(REL + "version.txt?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) { v = Integer.parseInt(r.readLine().trim()); }
            } catch (Exception e) { v = -1; }
            final int lv = v; latest = v;
            ui.post(() -> {
                int mine = myVersion();
                if (lv < 0) { upd.setText("업데이트 확인 실패 (인터넷 확인) · 현재 " + mine); upd.setEnabled(true); upd.setOnClickListener(x -> { upd.setText("확인 중…"); checkUpdate(); }); }
                else if (lv > mine) { upd.setText("새 버전 있음 (" + mine + " → " + lv + ") · 눌러서 설치"); upd.setEnabled(true); upd.setOnClickListener(x -> startUpdate()); }
                else { upd.setText("최신 버전입니다 (" + mine + ") · 다시 확인"); upd.setEnabled(true); upd.setOnClickListener(x -> { upd.setText("확인 중…"); checkUpdate(); }); }
            });
        }).start();
    }
    private void startUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            say("이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 누르세요.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(REL + "fps-probe.apk?t=" + System.currentTimeMillis()));
        rq.setTitle("스윙 카메라 FPS 측정 업데이트"); rq.setMimeType("application/vnd.android.package-archive");
        rq.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "fps-probe-" + latest + ".apk");
        long id = dm.enqueue(rq);
        upd.setText("내려받는 중…"); upd.setEnabled(false);
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                if (it.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try { unregisterReceiver(this); } catch (Exception ignore) {}
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) { upd.setText("내려받기 실패 · 다시 누르기"); upd.setEnabled(true); return; }
                Intent ins = new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(ins);
                upd.setText("설치 화면에서 '업데이트'를 누르세요"); upd.setEnabled(true);
            }
        };
        registerReceiver(rcv, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }

    // ---------- 화면 ----------
    private void add(String s) { log.append(s).append('\n'); ui.post(() -> out.setText(log.toString())); }
    private void say(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }
    private void setStatus(String s) { ui.post(() -> status.setText(s)); }
    private void copyResult() {
        ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        c.setPrimaryClip(ClipData.newPlainText("fps", log.toString()));
        say("복사했습니다. 채팅창에 붙여넣으세요.");
    }
    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setPadding(8, 8, 8, 8); return t;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextSize(17); b.setGravity(Gravity.CENTER); return b;
    }

    @Override protected void onPause() { super.onPause(); if (busy) { try { if (recorder != null) { recorder.release(); recorder = null; } } catch (Exception ignore) {} queue.clear(); busy = false; closeCam(); add("(앱이 가려져 측정을 멈췄습니다. 다시 '전체 측정'을 누르세요)"); } }
    @Override protected void onDestroy() { super.onDestroy(); closeCam(); bgThread.quitSafely(); }
}
