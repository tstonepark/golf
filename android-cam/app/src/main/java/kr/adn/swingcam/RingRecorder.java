package kr.adn.swingcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 카메라 원본을 계속 H.264로 압축해 메모리에 '최근 몇 초'만 보관(링 버퍼).
 * 스윙이 끝났다는 신호가 오면 그 구간만 mp4로 잘라 저장 → 전송 압축을 거치지 않은 폰 원본.
 */
public class RingRecorder {
    private static class Sample { final byte[] d; final long pts; final int flags; Sample(byte[] d, long pts, int flags) { this.d = d; this.pts = pts; this.flags = flags; } }

    private MediaCodec enc;
    private Surface input;
    private volatile MediaFormat outFmt;
    private Thread drain;
    private volatile boolean run;
    private final ArrayDeque<Sample> ring = new ArrayDeque<>();
    private static final long KEEP_US = 16_000_000L;   // 최근 16초 보관(수동 저장 때 걸어가는 시간까지 고려)
    public int fps;

    public Surface start(int w, int h, int fps) throws Exception {
        this.fps = fps;
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, fps >= 200 ? 60_000_000 : fps >= 100 ? 45_000_000 : 30_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
        f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f);   // 0.5초마다 키프레임 → 원하는 지점 근처에서 자를 수 있음
        enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = enc.createInputSurface();
        enc.start();
        run = true;
        drain = new Thread(this::drainLoop, "ring-drain");
        drain.start();
        return input;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (run) {
            int idx;
            try { idx = enc.dequeueOutputBuffer(bi, 10_000); } catch (Exception e) { break; }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFmt = enc.getOutputFormat(); continue; }
            if (idx < 0) continue;
            try {
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                    ByteBuffer ob = enc.getOutputBuffer(idx);
                    byte[] d = new byte[bi.size];
                    ob.position(bi.offset); ob.limit(bi.offset + bi.size); ob.get(d);
                    synchronized (ring) {
                        ring.addLast(new Sample(d, bi.presentationTimeUs, bi.flags));
                        while (!ring.isEmpty() && bi.presentationTimeUs - ring.peekFirst().pts > KEEP_US) ring.removeFirst();
                    }
                }
                enc.releaseOutputBuffer(idx, false);
            } catch (Exception ignore) {}
        }
    }

    /** 최근 preUs 만큼을 mp4로 저장. 시작은 그 직전 키프레임부터. 반환: 저장한 프레임 수(실패 시 -1) */
    public int save(long preUs, File out, int orientation) {
        List<Sample> s;
        synchronized (ring) { s = new ArrayList<>(ring); }
        if (s.isEmpty() || outFmt == null) return -1;
        long last = s.get(s.size() - 1).pts, from = last - preUs;
        int start = -1;   // from 직전(또는 같은) 키프레임, 없으면 첫 키프레임
        for (int i = 0; i < s.size(); i++) {
            if ((s.get(i).flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue;
            if (s.get(i).pts <= from) start = i; else { if (start < 0) start = i; break; }
        }
        if (start < 0) return -1;
        MediaMuxer m = null;
        try {
            m = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            m.setOrientationHint(orientation);
            int tr = m.addTrack(outFmt);
            m.start();
            long base = s.get(start).pts; int n = 0;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            for (int i = start; i < s.size(); i++) {
                Sample x = s.get(i);
                bi.set(0, x.d.length, x.pts - base, x.flags);
                m.writeSampleData(tr, ByteBuffer.wrap(x.d), bi);
                n++;
            }
            m.stop();
            return n;
        } catch (Exception e) { return -1; }
        finally { try { if (m != null) m.release(); } catch (Exception ignore) {} }
    }

    public void stop() {
        run = false;
        try { if (drain != null) drain.join(500); } catch (Exception ignore) {}
        try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignore) {}
        try { if (input != null) input.release(); } catch (Exception ignore) {}
        enc = null; input = null; outFmt = null;
        synchronized (ring) { ring.clear(); }
    }
}
