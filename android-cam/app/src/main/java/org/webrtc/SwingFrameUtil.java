package org.webrtc;

import android.graphics.Matrix;

/**
 * 카메라 → SurfaceTexture 로 받은 프레임의 회전을 WebRTC 내장 Camera2 캡처와 같은 방식으로 정리.
 * (텍스처에 들어간 회전을 되돌리고, 회전 정보는 프레임에 따로 실어 보냄 → 받는 쪽이 세로로 돌려 보여 줌)
 * org.webrtc 패키지 안에 있어야 TextureBufferImpl 을 쓸 수 있음.
 */
public final class SwingFrameUtil {
    private SwingFrameUtil() {}
    public static VideoFrame.Buffer undoRotation(VideoFrame.Buffer b, int rotation) {
        if (!(b instanceof TextureBufferImpl)) { b.retain(); return b; }
        TextureBufferImpl t = (TextureBufferImpl) b;
        Matrix m = new Matrix();
        m.preTranslate(0.5f, 0.5f);
        m.preRotate(rotation);
        m.preTranslate(-0.5f, -0.5f);
        return t.applyTransformMatrix(m, t.getWidth(), t.getHeight());
    }
}
