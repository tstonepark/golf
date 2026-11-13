package kr.adn.swingcam;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * PeerJS 중개 서버(0.peerjs.com) 접속. 아이패드 페이지(PeerJS 1.5)와 같은 신호 규약을 그대로 씀.
 * 서버는 메시지를 상대에게 전달만 함(영상은 와이파이 안에서 직접).
 */
public class PeerJs {
    public interface Listener {
        void onOpen();
        void onMessage(String type, String src, JSONObject payload);
        void onClosed(String why);
    }

    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Listener l;
    private WebSocket ws;
    private boolean open = false, stopped = false;
    public final String id;

    public PeerJs(String id, Listener l) { this.id = id; this.l = l; }

    public void connect() {
        stopped = false;
        String token = Long.toString(new SecureRandom().nextLong() & Long.MAX_VALUE, 36);
        String url = "wss://0.peerjs.com/peerjs?key=peerjs&id=" + id + "&token=" + token + "&version=1.5.4";
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket w, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    String type = m.optString("type");
                    if ("OPEN".equals(type)) { open = true; h.post(l::onOpen); return; }
                    if ("ID-TAKEN".equals(type) || "ERROR".equals(type) || "INVALID-KEY".equals(type)) {
                        h.post(() -> l.onClosed(type + " " + m.optJSONObject("payload")));
                        return;
                    }
                    String src = m.optString("src", null);
                    JSONObject p = m.optJSONObject("payload");
                    h.post(() -> l.onMessage(type, src, p == null ? new JSONObject() : p));
                } catch (Exception ignore) {}
            }
            @Override public void onClosed(WebSocket w, int code, String reason) { open = false; if (!stopped) h.post(() -> l.onClosed("닫힘 " + code)); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) { open = false; if (!stopped) h.post(() -> l.onClosed("실패 " + t.getMessage())); }
        });
        h.removeCallbacks(beat); h.postDelayed(beat, 5000);
    }

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            if (open) send("HEARTBEAT", null, null);
            h.postDelayed(this, 5000);
        }
    };

    public boolean isOpen() { return open; }

    public void send(String type, String dst, JSONObject payload) {
        try {
            JSONObject m = new JSONObject().put("type", type);
            if (dst != null) m.put("dst", dst);
            if (payload != null) m.put("payload", payload);
            if (ws != null) ws.send(m.toString());
        } catch (Exception ignore) {}
    }

    public void close() {
        stopped = true; open = false; h.removeCallbacks(beat);
        try { if (ws != null) { send("LEAVE", null, null); ws.close(1000, "bye"); } } catch (Exception ignore) {}
        ws = null;
    }
}
