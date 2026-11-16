package kr.adn.swingcam;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** 분석 속도 시험: 이 폰에서 뼈대·헤드 모델이 방식별(CPU/GPU/NPU)로 1장에 몇 ms 걸리는지 잰다. */
public class Bench {
    public interface Log { void line(String s); }

    public static String run(Context ctx, Log log) {
        StringBuilder out = new StringBuilder();
        List<Bitmap> frames = loadFrames(ctx, out);
        say(out, log, "시험 프레임 " + frames.size() + "장 (540x960)");
        // ---- 뼈대(MediaPipe Pose heavy) ----
        for (Delegate d : new Delegate[]{Delegate.CPU, Delegate.GPU}) {
            String name = "뼈대 " + (d == Delegate.CPU ? "CPU" : "GPU");
            try {
                PoseLandmarker lm = PoseLandmarker.createFromOptions(ctx, PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_heavy.task").setDelegate(d).build())
                        .setRunningMode(RunningMode.IMAGE).setNumPoses(1).build());
                int found = 0; lm.detect(new BitmapImageBuilder(frames.get(0)).build());   // 준비 운동
                long t = System.nanoTime(); int n = 0;
                for (int r = 0; r < 2; r++) for (Bitmap b : frames) { MPImage im = new BitmapImageBuilder(b).build();
                    if (!lm.detect(im).landmarks().isEmpty()) found++; n++; }
                double ms = (System.nanoTime() - t) / 1e6 / n; lm.close();
                say(out, log, String.format("%s: %.0f ms/장 (초당 %.0f장) · 사람 찾음 %d/%d", name, ms, 1000 / ms, found, n));
            } catch (Throwable e) { say(out, log, name + ": 실패 - " + e); }
        }
        // ---- 헤드 모델(YOLO 416, ONNX Runtime) ----
        try {
            byte[] model; try (InputStream in = ctx.getAssets().open("clubhead416.onnx")) { model = in.readAllBytes(); }
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            float[] px = toInput(frames.get(0));
            String[] modes = {"CPU 4스레드", "XNNPACK", "NNAPI(NPU/GPU)"};
            for (int m = 0; m < modes.length; m++) {
                String name = "헤드 " + modes[m];
                try (OrtSession.SessionOptions so = new OrtSession.SessionOptions()) {
                    if (m == 0) so.setIntraOpNumThreads(4);
                    if (m == 1) { so.setIntraOpNumThreads(1); so.addXnnpack(Collections.singletonMap("intra_op_num_threads", "4")); }
                    if (m == 2) so.addNnapi();
                    try (OrtSession s = env.createSession(model, so)) {
                        String in = s.getInputNames().iterator().next();
                        long t = 0; int n = 0;
                        for (int i = 0; i < 23; i++) {
                            try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(px), new long[]{1, 3, 416, 416})) {
                                long t0 = System.nanoTime(); s.run(Collections.singletonMap(in, x)).close();
                                if (i >= 3) { t += System.nanoTime() - t0; n++; }   // 처음 3번은 준비 운동
                            }
                        }
                        double ms = t / 1e6 / n;
                        say(out, log, String.format("%s: %.0f ms/장 (초당 %.0f장)", name, ms, 1000 / ms));
                    }
                } catch (Throwable e) { say(out, log, name + ": 실패 - " + e); }
            }
        } catch (Throwable e) { say(out, log, "헤드 모델: 실패 - " + e); }
        say(out, log, "기기: " + android.os.Build.MODEL + " / " + android.os.Build.SOC_MODEL);
        return out.toString();
    }

    private static void say(StringBuilder out, Log log, String s) { out.append(s).append('\n'); if (log != null) log.line(s); }

    private static float[] toInput(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 416, 416, true); int[] p = new int[416 * 416]; b.getPixels(p, 0, 416, 0, 0, 416, 416);
        float[] f = new float[3 * 416 * 416]; int N = 416 * 416;
        for (int i = 0; i < N; i++) { f[i] = ((p[i] >> 16) & 255) / 255f; f[N + i] = ((p[i] >> 8) & 255) / 255f; f[2 * N + i] = (p[i] & 255) / 255f; }
        return f;
    }

    /** 가장 최근 SwingCam 영상에서 8장. 없으면 회색 화면(속도만 잼). */
    private static List<Bitmap> loadFrames(Context ctx, StringBuilder out) {
        List<Bitmap> fr = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME},
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{"%SwingCam%"}, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                Uri u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                out.append("영상: ").append(c.getString(1)).append('\n');
                MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(ctx, u);
                long dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
                for (int i = 0; i < 8; i++) { Bitmap b = r.getFrameAtTime(dur * (i + 1) / 10, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b != null) fr.add(Bitmap.createScaledBitmap(b, b.getWidth() >= b.getHeight() ? 960 : 540, b.getWidth() >= b.getHeight() ? 540 : 960, true).copy(Bitmap.Config.ARGB_8888, false)); }
                r.release();
            }
        } catch (Throwable e) { out.append("영상 읽기 실패: ").append(e).append('\n'); }
        if (fr.isEmpty()) { Bitmap b = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888); b.eraseColor(Color.GRAY); for (int i = 0; i < 8; i++) fr.add(b); out.append("저장된 영상이 없어 빈 화면으로 속도만 잽니다\n"); }
        return fr;
    }
}
