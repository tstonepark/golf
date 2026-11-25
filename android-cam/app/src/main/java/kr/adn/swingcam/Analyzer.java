package kr.adn.swingcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Landmark;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 폰 안에서 스윙 영상을 분석: 영상 → (뼈대 좌표, 헤드 후보) 측정값만 뽑는다.
 * 판정·진단은 아이패드 화면 쪽 analysis.js 가 그대로 한다(계산 규칙은 한 곳에만 둠).
 * 측정하지 못한 프레임은 비워 둔다. 값을 지어내지 않는다.
 */
public class Analyzer {
    public interface Log { void line(String s); }
    public static class Result { public String json, summary; }

    /** 분석은 항상 이 한 스레드에서(모델 생성·사용 스레드를 같게, 동시에 두 개 돌지 않게) */
    public static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> new Thread(r, "analyzer"));
    private static final ExecutorService HEADX = Executors.newSingleThreadExecutor(r -> new Thread(r, "analyzer-head"));
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> new Thread(r, "analyzer-px"));

    private static final int IN = 416;
    private static final int[] KP = {0, 11, 12, 15, 16, 19, 20, 23, 24, 27, 28};   // 코, 어깨, 손목, 검지, 골반, 발목
    private static final int[] WK = {11, 12, 23, 24};                               // 3D: 어깨·골반
    private static final int NK = KP.length * 2, NW = WK.length * 3;

    private static PoseLandmarker lm; private static String poseDev = "";
    private static OrtEnvironment env; private static OrtSession head; private static String headIn, headDev = "";
    private static long tsLast = 0;

    private static void load(Context ctx) throws Exception {
        if (lm == null) {
            Throwable last = null;
            for (Delegate d : new Delegate[]{Delegate.GPU, Delegate.CPU}) {
                try {
                    lm = PoseLandmarker.createFromOptions(ctx, PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_heavy.task").setDelegate(d).build())
                            .setRunningMode(RunningMode.VIDEO).setNumPoses(1).build());
                    poseDev = d == Delegate.GPU ? "GPU" : "CPU"; break;
                } catch (Throwable e) { last = e; lm = null; }
            }
            if (lm == null) throw new Exception("뼈대 모델 준비 실패: " + last);
        }
        if (head == null) {
            /* 헤드·그립 모델 8차: 정면 촬영 장면 추가 학습 */
            byte[] model; try (InputStream in = ctx.getAssets().open("clubhead416.onnx")) { model = in.readAllBytes(); }
            env = OrtEnvironment.getEnvironment();
            Throwable last = null;
            for (int m = 0; m < 2 && head == null; m++) {
                try {
                    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
                    if (m == 0) { so.setIntraOpNumThreads(1); so.addXnnpack(Collections.singletonMap("intra_op_num_threads", "4")); }
                    else so.setIntraOpNumThreads(4);
                    head = env.createSession(model, so); headDev = m == 0 ? "XNNPACK" : "CPU";
                } catch (Throwable e) { last = e; head = null; }
            }
            if (head == null) throw new Exception("헤드 모델 준비 실패: " + last);
            headIn = head.getInputNames().iterator().next();
        }
    }

    // ---------- 영상 ----------
    private static class Clip { long[] pts; HashMap<Long, Integer> idx = new HashMap<>(); int n, track, rot, W, H; double fps; String mime; }
    private interface Want { boolean at(int i); }
    private interface Sink { void frame(int i, int[] px) throws Exception; }

    private static Clip open(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            Clip c = new Clip(); c.track = -1; MediaFormat fmt = null;
            for (int t = 0; t < ex.getTrackCount(); t++) { MediaFormat m = ex.getTrackFormat(t); String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { c.track = t; fmt = m; c.mime = mime; break; } }
            if (c.track < 0) throw new Exception("영상 트랙이 없습니다");
            int r = fmt.containsKey(MediaFormat.KEY_ROTATION) ? fmt.getInteger(MediaFormat.KEY_ROTATION) : 0;
            c.rot = ((r % 360) + 360) % 360;
            int w = fmt.getInteger(MediaFormat.KEY_WIDTH), h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
            boolean swap = c.rot == 90 || c.rot == 270; int dw = swap ? h : w, dh = swap ? w : h;
            if (dw > dh) { c.W = 960; c.H = 540; } else { c.W = 540; c.H = 960; }
            ex.selectTrack(c.track);
            ArrayList<Long> l = new ArrayList<>();
            while (true) { long t = ex.getSampleTime(); if (t < 0) break; l.add(t); if (!ex.advance()) break; }
            Collections.sort(l);
            c.n = l.size(); if (c.n < 30) throw new Exception("영상이 너무 짧습니다");
            c.pts = new long[c.n]; for (int i = 0; i < c.n; i++) { c.pts[i] = l.get(i); c.idx.put(c.pts[i], i); }
            c.fps = (c.n - 1) / ((c.pts[c.n - 1] - c.pts[0]) / 1e6);
            return c;
        } finally { ex.release(); }
    }

    /** a..b 구간(표시 순서 번호)에서 want 인 프레임만 분석 크기(W×H, 회전 반영) ARGB로 꺼내 sink에 넘김 */
    private static void decode(File f, Clip c, int a, int b, Want want, Sink sink) throws Exception {
        MediaExtractor ex = new MediaExtractor(); MediaCodec dec = null;
        try {
            ex.setDataSource(f.getAbsolutePath()); ex.selectTrack(c.track);
            ex.seekTo(c.pts[a], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            for (int attempt = 0; dec == null; attempt++) {
                MediaFormat fmt = ex.getTrackFormat(c.track);
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                if (attempt == 0) fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);   // 가능한 한 빨리
                MediaCodec d = MediaCodec.createDecoderByType(c.mime);
                try { d.configure(fmt, null, null, 0); d.start(); dec = d; }
                catch (Exception e) { try { d.release(); } catch (Exception ignore) {} if (attempt >= 1) throw e; }
            }
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo(); boolean inEos = false, done = false; long lastOut = System.currentTimeMillis();
            while (!done) {
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(2000);
                    if (ii >= 0) { ByteBuffer ib = dec.getInputBuffer(ii); int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                        else { dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0); ex.advance(); } }
                }
                int oi = dec.dequeueOutputBuffer(bi, 2000);
                if (oi >= 0) {
                    lastOut = System.currentTimeMillis();
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    Integer i = bi.size > 0 ? c.idx.get(bi.presentationTimeUs) : null;
                    int[] px = null;
                    if (i != null && i >= a && i <= b && want.at(i)) { Image im = dec.getOutputImage(oi); if (im != null) { try { px = toArgb(im, c); } finally { im.close(); } } }
                    dec.releaseOutputBuffer(oi, false);
                    if (px != null) sink.frame(i, px);
                    if (i != null && i >= b) done = true;
                } else if (System.currentTimeMillis() - lastOut > 6000) throw new Exception("영상 디코더가 응답하지 않습니다");
            }
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignore) {}
            ex.release();
        }
    }

    private static byte[] yb, ub, vb;
    /** YUV → ARGB, 분석 크기로 축소(2×2 평균)하면서 회전까지 한 번에. 4개 스레드로 나눠 처리 */
    private static int[] toArgb(Image im, Clip c) throws Exception {
        Rect cr = im.getCropRect(); final int ws = cr.width(), hs = cr.height(), cl = cr.left, ct = cr.top;
        Image.Plane[] p = im.getPlanes();
        ByteBuffer by = p[0].getBuffer(), bu = p[1].getBuffer(), bv = p[2].getBuffer();
        final int yl = by.remaining(), ul = bu.remaining(), vl = bv.remaining();
        if (yb == null || yb.length < yl) yb = new byte[yl]; if (ub == null || ub.length < ul) ub = new byte[ul]; if (vb == null || vb.length < vl) vb = new byte[vl];
        by.get(yb, 0, yl); bu.get(ub, 0, ul); bv.get(vb, 0, vl);
        final byte[] Y = yb, U = ub, V = vb;
        final int yRS = p[0].getRowStride(), yPS = p[0].getPixelStride(), uRS = p[1].getRowStride(), uPS = p[1].getPixelStride(), vRS = p[2].getRowStride(), vPS = p[2].getPixelStride();
        final int W = c.W, H = c.H, rot = c.rot; final boolean swap = rot == 90 || rot == 270;
        final int[] mx = new int[W], my = new int[H];
        for (int dx = 0; dx < W; dx++) mx[dx] = !swap ? (rot == 0 ? dx * ws / W : (W - 1 - dx) * ws / W) : (rot == 90 ? (W - 1 - dx) * hs / W : dx * hs / W);
        for (int dy = 0; dy < H; dy++) my[dy] = !swap ? (rot == 0 ? dy * hs / H : (H - 1 - dy) * hs / H) : (rot == 90 ? dy * ws / H : (H - 1 - dy) * ws / H);
        final boolean avg = (swap ? ws / H : ws / W) >= 2;
        final int[] out = new int[W * H];
        List<Callable<Void>> jobs = new ArrayList<>(); final int NB = 4;
        for (int k = 0; k < NB; k++) { final int r0 = H * k / NB, r1 = H * (k + 1) / NB;
            jobs.add(() -> {
                for (int dy = r0; dy < r1; dy++) { int o = dy * W;
                    for (int dx = 0; dx < W; dx++) {
                        int sx = swap ? my[dy] : mx[dx], sy = swap ? mx[dx] : my[dy];
                        int yi = (ct + sy) * yRS + (cl + sx) * yPS, yv;
                        if (avg) { int x1 = sx + 1 < ws ? yPS : 0, y1 = sy + 1 < hs ? yRS : 0;
                            yv = ((Y[yi] & 255) + (Y[yi + x1] & 255) + (Y[yi + y1] & 255) + (Y[yi + y1 + x1] & 255) + 2) >> 2; }
                        else yv = Y[yi] & 255;
                        int ui = ((ct + sy) >> 1) * uRS + ((cl + sx) >> 1) * uPS, vi = ((ct + sy) >> 1) * vRS + ((cl + sx) >> 1) * vPS;
                        if (ui >= ul) ui = ul - 1; if (vi >= vl) vi = vl - 1;
                        int cc = yv - 16, d = (U[ui] & 255) - 128, e = (V[vi] & 255) - 128; if (cc < 0) cc = 0;
                        int r = (298 * cc + 409 * e + 128) >> 8, g = (298 * cc - 100 * d - 208 * e + 128) >> 8, bl = (298 * cc + 516 * d + 128) >> 8;
                        if (r < 0) r = 0; else if (r > 255) r = 255; if (g < 0) g = 0; else if (g > 255) g = 255; if (bl < 0) bl = 0; else if (bl > 255) bl = 255;
                        out[o + dx] = 0xff000000 | (r << 16) | (g << 8) | bl;
                    } }
                return null; });
        }
        for (Future<Void> fu : POOL.invokeAll(jobs)) fu.get();
        return out;
    }

    // ---------- 모델 ----------
    private static double[] poseAt(Bitmap bmp, int[] px, int W, int H, long ts) {
        try {
            bmp.setPixels(px, 0, W, 0, 0, W, H);
            if (ts <= tsLast) ts = tsLast + 1; tsLast = ts;
            PoseLandmarkerResult r = lm.detectForVideo(new BitmapImageBuilder(bmp).build(), ts);
            if (r == null || r.landmarks().isEmpty()) return null;
            List<NormalizedLandmark> L = r.landmarks().get(0);
            boolean hasW = !r.worldLandmarks().isEmpty();
            double[] o = new double[NK + (hasW ? NW : 0)];
            for (int k = 0; k < KP.length; k++) { o[2 * k] = L.get(KP[k]).x() * W; o[2 * k + 1] = L.get(KP[k]).y() * H; }
            /* 머리 위치 = 양쪽 귀(7,8)의 중점. 코는 고개만 돌려도 크게 움직여 머리 이동이 부풀었다 */
            o[0] = (L.get(7).x() + L.get(8).x()) / 2f * W; o[1] = (L.get(7).y() + L.get(8).y()) / 2f * H;
            if (hasW) { List<Landmark> w = r.worldLandmarks().get(0);
                for (int j = 0; j < WK.length; j++) { o[NK + 3 * j] = w.get(WK[j]).x(); o[NK + 3 * j + 1] = w.get(WK[j]).y(); o[NK + 3 * j + 2] = w.get(WK[j]).z(); } }
            return o;
        } catch (Throwable e) { return null; }
    }
    private static double handsY(double[] p) { return (p[7] + p[9] + p[11] + p[13]) / 4; }

    /** 골퍼 주변 정사각형(cx,cy,s)을 416으로 줄여 헤드 모델에 넣고 후보 최대 3개 [신뢰도,x,y]… 를 돌려줌 */
    private static float[] headAt(int[] px, int W, int H, double cx, double cy, double s, float[][] gripOut, int gi) throws Exception {
        float[] f = new float[3 * IN * IN]; int N = IN * IN; double step = s / IN; final float G = 114 / 255f;
        for (int v = 0; v < IN; v++) {
            double y = cy + (v + 0.5) * step - 0.5; int y0 = (int) Math.floor(y); float fy = (float) (y - y0);
            for (int u = 0; u < IN; u++) {
                double x = cx + (u + 0.5) * step - 0.5; int x0 = (int) Math.floor(x); float fx = (float) (x - x0); int o = v * IN + u;
                if (x0 < -1 || y0 < -1 || x0 >= W || y0 >= H) { f[o] = G; f[N + o] = G; f[2 * N + o] = G; continue; }
                int xa = x0 < 0 ? 0 : x0, xb = x0 + 1 >= W ? W - 1 : x0 + 1, ya = y0 < 0 ? 0 : y0, yb2 = y0 + 1 >= H ? H - 1 : y0 + 1;
                int p00 = px[ya * W + xa], p01 = px[ya * W + xb], p10 = px[yb2 * W + xa], p11 = px[yb2 * W + xb];
                float w00 = (1 - fx) * (1 - fy), w01 = fx * (1 - fy), w10 = (1 - fx) * fy, w11 = fx * fy;
                f[o] = (((p00 >> 16) & 255) * w00 + ((p01 >> 16) & 255) * w01 + ((p10 >> 16) & 255) * w10 + ((p11 >> 16) & 255) * w11) / 255f;
                f[N + o] = (((p00 >> 8) & 255) * w00 + ((p01 >> 8) & 255) * w01 + ((p10 >> 8) & 255) * w10 + ((p11 >> 8) & 255) * w11) / 255f;
                f[2 * N + o] = ((p00 & 255) * w00 + (p01 & 255) * w01 + (p10 & 255) * w10 + (p11 & 255) * w11) / 255f;
            }
        }
        float[] o; int rows = 5;   // 4(상자) + 종류별 점수: 헤드, (7번 모델부터) 그립
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), new long[]{1, 3, IN, IN});
             OrtSession.Result r = head.run(Collections.singletonMap(headIn, t))) {
            OnnxTensor ot = (OnnxTensor) r.get(0); long[] shp = ot.getInfo().getShape(); if (shp.length >= 2 && shp[1] >= 5) rows = (int) shp[1];
            FloatBuffer fb = ot.getFloatBuffer(); o = new float[fb.remaining()]; fb.get(o);
        }
        int A = o.length / rows; double k = IN / s;
        ArrayList<float[]> c = new ArrayList<>();
        for (int i = 0; i < A; i++) { float cf = o[4 * A + i]; if (cf > 0.15f) c.add(new float[]{cf, (float) (o[i] / k + cx), (float) (o[A + i] / k + cy)}); }
        Collections.sort(c, (p, q) -> Float.compare(q[0], p[0]));
        ArrayList<float[]> keep = new ArrayList<>();
        for (float[] q : c) { boolean far = true; for (float[] z : keep) if (Math.hypot(z[1] - q[1], z[2] - q[2]) <= 12) { far = false; break; }
            if (far) keep.add(q); if (keep.size() >= 3) break; }
        float[] out = new float[keep.size() * 3];
        for (int i = 0; i < keep.size(); i++) System.arraycopy(keep.get(i), 0, out, 3 * i, 3);
        // 그립(손으로 쥔 곳): 가장 확실한 하나만. 뼈대의 손목 추정보다 정확해서 화면 쪽이 손 위치로 쓴다
        if (rows >= 6 && gripOut != null) { int bi = -1; float bc = 0.25f; for (int i = 0; i < A; i++) { float cf = o[5 * A + i]; if (cf > bc) { bc = cf; bi = i; } }
            gripOut[gi] = bi < 0 ? null : new float[]{bc, (float) (o[bi] / k + cx), (float) (o[A + bi] / k + cy)}; }
        return out;
    }

    /** analysis.js handsImpact 와 같은 규칙: 손이 0.3초 안에 가장 크게 떨어져 어드레스 높이 근처에 온 프레임 */
    private static int handsImpact(double[][] pose, int n, double fps) {
        ArrayList<Double> ys = new ArrayList<>(); for (int i = 0; i < n; i++) if (pose[i] != null) ys.add(handsY(pose[i]));
        if (ys.size() < 10) return -1; Collections.sort(ys);
        double lowY = ys.get((int) Math.floor(ys.size() * 0.85)), topY = ys.get((int) Math.floor(ys.size() * 0.02)); int win = (int) Math.round(0.3 * fps);
        double best = 0; int bi = -1;
        for (int i = 0; i < n; i++) { if (pose[i] == null) continue; double h = handsY(pose[i]); if (h < lowY - 0.3 * (lowY - topY)) continue;
            double mn = 1e9; for (int j = Math.max(0, i - win); j < i; j++) if (pose[j] != null) mn = Math.min(mn, handsY(pose[j]));
            double fall = h - mn; if (fall > best + 2) { best = fall; bi = i; } }
        return best > 0.4 * (lowY - topY) ? bi : -1;
    }
    private static double med(ArrayList<Double> v) { Collections.sort(v); return v.get(v.size() >> 1); }

    // ---------- 실시간 감시(스윙 동작 감지용): 작은 화면 1장의 뼈대 ----------
    public static volatile boolean busy = false;
    private static Bitmap liveBmp;
    /** 반환: KP 순서의 x,y(픽셀) … 사람을 못 찾으면 null. EX 스레드에서만 부를 것 */
    public static double[] livePose(Context ctx, int[] px, int W, int H) throws Exception {
        load(ctx);
        if (liveBmp == null || liveBmp.getWidth() != W || liveBmp.getHeight() != H) liveBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        return poseAt(liveBmp, px, W, H, tsLast + 60);
    }
    /** I420(센서 방향) → 회전을 반영한 ARGB. 작은 화면용(단순 추출) */
    public static int[] i420ToArgb(ByteBuffer y, int ys, ByteBuffer u, int us, ByteBuffer v, int vs, int w, int h, int rot) {
        boolean swap = rot == 90 || rot == 270; int W = swap ? h : w, H = swap ? w : h; int[] out = new int[W * H];
        for (int dy = 0; dy < H; dy++) for (int dx = 0; dx < W; dx++) {
            int sx, sy;
            if (rot == 90) { sx = dy; sy = h - 1 - dx; } else if (rot == 270) { sx = w - 1 - dy; sy = dx; } else if (rot == 180) { sx = w - 1 - dx; sy = h - 1 - dy; } else { sx = dx; sy = dy; }
            int cc = (y.get(sy * ys + sx) & 255) - 16, d = (u.get((sy >> 1) * us + (sx >> 1)) & 255) - 128, e = (v.get((sy >> 1) * vs + (sx >> 1)) & 255) - 128; if (cc < 0) cc = 0;
            int r = (298 * cc + 409 * e + 128) >> 8, g = (298 * cc - 100 * d - 208 * e + 128) >> 8, bl = (298 * cc + 516 * d + 128) >> 8;
            if (r < 0) r = 0; else if (r > 255) r = 255; if (g < 0) g = 0; else if (g > 255) g = 255; if (bl < 0) bl = 0; else if (bl > 255) bl = 255;
            out[dy * W + dx] = 0xff000000 | (r << 16) | (g << 8) | bl;
        }
        return out;
    }

    // ---------- 전체 흐름 ----------
    /** agoMs: 임팩트가 영상 끝에서 몇 ms 전인지(폰이 스윙 동작·공 소리로 이미 알고 있을 때, 모르면 -1).
     *  box: 실시간 감시가 본 골퍼 위치 [골반x/W, 골반y/H, (발목-코)/H] (없으면 null).
     *  둘 다 있으면 '스윙 위치 찾기(1차)'를 건너뛰어 시간을 절반 가까이 줄인다. 맞지 않으면 처음부터 2단계로 다시 한다. */
    public static Result run(Context ctx, File f, Log log, long agoMs, double[] box) throws Exception {
        busy = true; try { return run0(ctx, f, log, agoMs, box); } finally { busy = false; }
    }
    private static Result run0(Context ctx, File f, Log log, long agoMs, double[] box) throws Exception {
        long T0 = System.currentTimeMillis();
        if (log != null) log.line("스윙 분석 준비 중…");
        load(ctx);
        long T1 = System.currentTimeMillis();
        final Clip c = open(f); final int n = c.n, W = c.W, H = c.H; final double fps = c.fps;
        final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        final double[][] pose = new double[n][];
        int ciH = -1;
        if (agoMs >= 0 && box != null && box.length >= 3) { int h = n - 1 - (int) Math.round(agoMs / 1000.0 * fps); if (h > 0.6 * fps && h < n - 0.1 * fps) ciH = h; }
        final boolean hint = ciH >= 0; final int[] cnt = {0};
        if (!hint) {
            // 1차: 초당 약 20장만 뼈대 → 스윙(임팩트) 위치
            final int ST = Math.max(1, (int) Math.round(fps / 20));
            final long base1 = tsLast + 1000; final int n1 = (n + ST - 1) / ST;
            decode(f, c, 0, n - 1, i -> i % ST == 0, (i, px) -> {
                pose[i] = poseAt(bmp, px, W, H, base1 + Math.round(i * 1000.0 / fps));
                if (++cnt[0] % 10 == 0 && log != null) log.line("스윙 분석 1/2 · " + (cnt[0] * 100 / n1) + "%"); });
            ciH = handsImpact(pose, n, fps);
            if (ciH < 0) throw new Exception("스윙을 찾지 못함");
        }
        final int ci0 = ciH;
        long T2 = System.currentTimeMillis();
        // 골퍼 주변 정사각형
        double M = Math.max(W, H), cx0 = (W - M) / 2, cy0 = (H - M) / 2, cs0 = M; boolean hasCrop = false;
        { ArrayList<Double> gh = new ArrayList<>(), hx = new ArrayList<>(), hy = new ArrayList<>();
            if (hint) for (int k = 0; k < 4; k++) { gh.add(box[2] * H); hx.add(box[0] * W); hy.add(box[1] * H); }
            else for (int i = Math.max(0, ci0 - (int) Math.round(2.5 * fps)); i <= Math.min(n - 1, ci0 + (int) Math.round(0.5 * fps)); i++) { double[] p = pose[i]; if (p == null) continue;
                gh.add((p[19] + p[21]) / 2 - p[1]); hx.add((p[14] + p[16]) / 2); hy.add((p[15] + p[17]) / 2); }
            if (gh.size() >= 4) { double g = Math.max(60, med(gh)), mx = med(hx), my = med(hy) - 0.12 * g, S = Math.max(380, Math.min(M, 2.25 * g));
                cx0 = S <= W ? Math.max(0, Math.min(W - S, mx - S / 2)) : (W - S) / 2; cy0 = S <= H ? Math.max(0, Math.min(H - S, my - S / 2)) : (H - S) / 2; cs0 = S; hasCrop = true; } }
        final double cx = cx0, cy = cy0, cs = cs0;
        // 2차: 다운스윙 주변은 촘촘히, 나머지는 듬성듬성
        // (임팩트 위치를 미리 받은 경우 ±0.15초쯤 틀릴 수 있어 촘촘한 구간을 넓게, 대신 느린 구간의 뼈대는 초당 20장)
        final int a = Math.max(0, ci0 - (int) Math.round(2.8 * fps)), b = Math.min(n - 1, ci0 + (int) Math.round(1.3 * fps));
        /* 건너뛰는 간격은 240fps 기준이다. 광각 렌즈는 60fps로 찍히므로 그대로 쓰면 뼈대가 초당 5장뿐이라
           구간(테이크백·탑)이 엉뚱하게 잡혔다. 영상 fps에 맞춰 줄인다(60fps면 12→3, 6→2, 3→1, 2→1). */
        final double zPre = hint ? 0.6 : 0.45, zEv = hint ? 0.3 : 0.15; final double fq = fps / 240.0;
        final int SP = Math.max(1, (int) Math.round((hint ? 12 : 6) * fq)), S6 = Math.max(1, (int) Math.round(6 * fq)), S3 = Math.max(1, (int) Math.round(3 * fq)), S2 = Math.max(1, (int) Math.round(2 * fq));
        final int d0 = ci0 - (int) Math.round(zPre * fps), dm0 = ci0 - (int) Math.round(zEv * fps), d1 = ci0 + (int) Math.round(zEv * fps);
        final Want needH = i -> (i >= dm0 && i <= d1) || (i >= d0 && i < dm0 && i % S2 == 0) || i % S6 == 0;
        final Want needP = i -> pose[i] == null && ((i >= d0 && i <= d1 && i % S3 == 0) || i % SP == 0);
        int tot0 = 0; for (int i = a; i <= b; i++) if (needH.at(i) || needP.at(i)) tot0++;
        final int tot = Math.max(1, tot0); cnt[0] = 0;
        final long base2 = tsLast + 5000;
        final int[][] thumb = {null};
        final float[][] pts = new float[n][], grips = new float[n][]; final Semaphore sem = new Semaphore(6); final ArrayList<Future<?>> futs = new ArrayList<>();
        decode(f, c, a, b, i -> needH.at(i) || needP.at(i), (i, px) -> {
            if (needH.at(i)) { sem.acquire(); futs.add(HEADX.submit(() -> { try { pts[i] = headAt(px, W, H, cx, cy, cs, grips, i); } catch (Throwable e) { pts[i] = null; } finally { sem.release(); } })); }
            if (needP.at(i)) pose[i] = poseAt(bmp, px, W, H, base2 + Math.round(i * 1000.0 / fps));
            if (thumb[0] == null && i >= ci0 - (int) Math.round(0.5 * fps)) thumb[0] = px;   // 확인용: 폰이 실제로 분석한 화면 1장
            if (++cnt[0] % 12 == 0 && log != null) log.line("스윙 분석 2/2 · " + (cnt[0] * 100 / tot) + "%"); });
        for (Future<?> fu : futs) fu.get();
        int ci = ci0;
        if (hint) {   // 미리 받은 위치가 실제 뼈대 움직임과 맞는지 확인. 안 맞으면 처음부터 2단계로
            ci = handsImpact(pose, n, fps);
            if (ci < 0 || Math.abs(ci - ci0) > 0.35 * fps) { if (log != null) log.line("스윙 위치 재확인 중…"); return run0(ctx, f, log, -1, null); }
        }
        long T3 = System.currentTimeMillis();
        // 측정값만 JSON으로 (판정은 화면 쪽)
        StringBuilder sb = new StringBuilder(1 << 18); int nPose = 0, nHead = 0;
        sb.append("{\"v\":1,\"W\":").append(W).append(",\"H\":").append(H).append(",\"n\":").append(n).append(",\"fps\":").append(String.format(Locale.US, "%.4f", fps))
          .append(",\"t0\":").append(String.format(Locale.US, "%.4f", c.pts[0] / 1e6)).append(",\"ci\":").append(ci).append(",\"a\":").append(a).append(",\"b\":").append(b).append(",\"crop\":");
        if (hasCrop) sb.append('[').append(num(cx, 1)).append(',').append(num(cy, 1)).append(',').append(num(cs, 1)).append(']'); else sb.append("null");
        sb.append(",\"pose\":[");
        for (int i = a; i <= b; i++) { double[] p = pose[i]; if (p == null) continue; if (nPose++ > 0) sb.append(',');
            sb.append('[').append(i); for (int k = 0; k < p.length; k++) sb.append(',').append(num(p[k], k < NK ? 1 : 3)); sb.append(']'); }
        sb.append("],\"pts\":[");
        for (int i = a; i <= b; i++) { float[] p = pts[i]; if (p == null || p.length == 0) continue; if (nHead++ > 0) sb.append(',');
            sb.append('[').append(i); for (int k = 0; k < p.length; k++) sb.append(',').append(num(p[k], k % 3 == 0 ? 2 : 1)); sb.append(']'); }
        // 장면마다의 실제 시각(ms, 첫 장면 기준): 녹화에 빠진 틈이 있어 간격이 일정하지 않으므로 화면 쪽이 이것으로 장면↔시각을 맞춘다
        sb.append("],\"ts\":["); for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append(num((c.pts[i] - c.pts[0]) / 1000.0, 1)); }
        sb.append("],\"grip\":["); int nGrip = 0;
        for (int i = a; i <= b; i++) { float[] g = grips[i]; if (g == null) continue; if (nGrip++ > 0) sb.append(',');
            sb.append('[').append(i).append(',').append(num(g[0], 2)).append(',').append(num(g[1], 1)).append(',').append(num(g[2], 1)).append(']'); }
        double secs = (System.currentTimeMillis() - T0) / 1000.0;
        sb.append("],\"secs\":").append(num(secs, 1)).append(",\"ms\":[").append(T1 - T0).append(',').append(T2 - T1).append(',').append(T3 - T2)
          .append("],\"hint\":").append(hint ? 1 : 0).append(",\"eng\":\"").append(headDev).append('/').append(poseDev).append('"');
        if (thumb[0] != null) try {
            Bitmap full = Bitmap.createBitmap(thumb[0], W, H, Bitmap.Config.ARGB_8888), sm = Bitmap.createScaledBitmap(full, W / 2, H / 2, true);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); sm.compress(Bitmap.CompressFormat.JPEG, 60, bo);
            sb.append(",\"thumb\":\"").append(android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP)).append('"');
        } catch (Throwable ignore) {}
        sb.append('}');
        Result r = new Result(); r.json = sb.toString();
        r.summary = String.format(Locale.US, "폰 분석 %.1f초 (준비 %.1f · 1차 %.1f · 2차 %.1f)" + (hint ? " 빠른 경로" : "") + " 뼈대 %d · 헤드 %d장", secs, (T1 - T0) / 1000.0, (T2 - T1) / 1000.0, (T3 - T2) / 1000.0, nPose, nHead);
        return r;
    }
    private static String num(double v, int dec) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        String s = String.format(Locale.US, "%." + dec + "f", v);
        if (s.indexOf('.') >= 0) { int e = s.length(); while (s.charAt(e - 1) == '0') e--; if (s.charAt(e - 1) == '.') e--; s = s.substring(0, e); }
        return s.equals("-0") ? "0" : s;
    }
}
