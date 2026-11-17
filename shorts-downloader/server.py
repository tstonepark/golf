#!/usr/bin/env python3
"""
shorts_web — 휴대폰에서 YouTube Shorts를 MP4로 받는 LAN 웹 서버

실행:
    pip install -U yt-dlp flask
    (ffmpeg 설치 필요)
    python server.py
→ 콘솔에 뜨는 주소(http://192.168.x.x:8000)를 같은 Wi-Fi의 휴대폰에서 열기

환경변수(.env 또는 셸):
    ACCESS_TOKEN=비밀번호   # 설정 시 화면에서 입력해야 사용 가능 (권장)
    PORT=8000
    FILE_TTL_SEC=600       # 받아둔 파일 자동 삭제 시간
"""
import logging
import os
import re
import secrets
import shutil
import socket
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

from flask import Flask, jsonify, request, send_file, Response

try:
    import yt_dlp
except ImportError:
    raise SystemExit("pip install -U yt-dlp")


# ---------- 설정 ----------
def load_dotenv(path: Path = Path(__file__).with_name(".env")) -> None:
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())


load_dotenv()
ACCESS_TOKEN = os.environ.get("ACCESS_TOKEN", "")
PORT = int(os.environ.get("PORT", "8000"))
FILE_TTL_SEC = int(os.environ.get("FILE_TTL_SEC", "600"))
WORK_DIR = Path(tempfile.mkdtemp(prefix="shorts_web_"))
ALLOWED_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"}
MAX_URL_LEN = 300

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("shorts_web")
app = Flask(__name__)

# file_id -> {"path": Path, "name": str, "ts": float}
FILES: dict[str, dict] = {}
LOCK = threading.Lock()


# ---------- 봉투 ----------
def ok(data: dict, code: int = 200):
    return jsonify({"ok": True, "data": data, "error": None}), code


def err(msg: str, code: int):
    return jsonify({"ok": False, "data": None, "error": msg}), code


# ---------- 검증 ----------
def valid_url(url: str) -> bool:
    if not url or len(url) > MAX_URL_LEN:
        return False
    try:
        p = urlparse(url.strip())
    except ValueError:
        return False
    return p.scheme in ("http", "https") and (p.hostname or "").lower() in ALLOWED_HOSTS


def authorized() -> bool:
    if not ACCESS_TOKEN:
        return True
    given = request.headers.get("X-Access-Token") or request.args.get("t", "")
    return secrets.compare_digest(given, ACCESS_TOKEN)


def safe_name(title: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', "_", title).strip(" .") or "video"
    return name[:80] + ".mp4"


# ---------- 정리 ----------
def janitor():
    while True:
        time.sleep(60)
        now = time.time()
        with LOCK:
            expired = [k for k, v in FILES.items() if now - v["ts"] > FILE_TTL_SEC]
            for k in expired:
                shutil.rmtree(FILES[k]["path"].parent, ignore_errors=True)
                del FILES[k]
        if expired:
            log.info("만료 파일 %d개 삭제", len(expired))


# ---------- API ----------
@app.post("/api/fetch")
def api_fetch():
    if not authorized():
        return err("비밀번호가 맞지 않습니다", 401)
    body = request.get_json(silent=True) or {}
    url = str(body.get("url", "")).strip()
    if not valid_url(url):
        return err("YouTube 주소만 가능합니다", 400)

    fid = secrets.token_urlsafe(16)
    job_dir = WORK_DIR / fid
    job_dir.mkdir()
    opts = {
        "format": "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b",
        "merge_output_format": "mp4",
        "outtmpl": str(job_dir / "video.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "retries": 3,
    }
    t0 = time.time()
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
    except yt_dlp.utils.DownloadError as e:
        shutil.rmtree(job_dir, ignore_errors=True)
        log.warning("실패 url=%s err=%s", url, str(e).splitlines()[0])
        return err("다운로드 실패 (비공개/삭제 영상이거나 yt-dlp 업데이트 필요)", 502)

    files = list(job_dir.glob("video.*"))
    if not files:
        shutil.rmtree(job_dir, ignore_errors=True)
        return err("파일 생성 실패 (ffmpeg 확인)", 500)

    path = files[0]
    name = safe_name(info.get("title") or info.get("id") or "video")
    with LOCK:
        FILES[fid] = {"path": path, "name": name, "ts": time.time()}
    size = path.stat().st_size
    log.info("완료 id=%s title=%r size=%d sec=%.1f", info.get("id"), info.get("title"), size, time.time() - t0)
    return ok({
        "file_id": fid,
        "filename": name,
        "size_bytes": size,
        "duration_sec": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
    })


@app.get("/api/file/<fid>")
def api_file(fid: str):
    if not authorized():
        return err("비밀번호가 맞지 않습니다", 401)
    if not re.fullmatch(r"[A-Za-z0-9_-]{10,40}", fid):
        return err("잘못된 요청", 400)
    with LOCK:
        meta = FILES.get(fid)
    if not meta or not meta["path"].exists():
        return err("파일이 만료되었습니다. 다시 받아주세요", 404)
    return send_file(meta["path"], mimetype="video/mp4",
                     as_attachment=True, download_name=meta["name"])


@app.get("/api/config")
def api_config():
    return ok({"token_required": bool(ACCESS_TOKEN)})


# ---------- 화면 ----------
PAGE = """<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Shorts 저장</title>
<style>
  :root{color-scheme:light dark}
  *{box-sizing:border-box}
  body{margin:0;font-family:system-ui,-apple-system,"Apple SD Gothic Neo",sans-serif;
       font-size:20px;background:#111;color:#f2f2f2;padding:24px 18px}
  h1{font-size:30px;margin:0 0 20px}
  label{display:block;font-size:18px;margin:16px 0 8px;color:#bbb}
  input{width:100%;font-size:20px;padding:16px;border-radius:14px;border:2px solid #444;
        background:#1d1d1d;color:#fff}
  button{width:100%;font-size:22px;font-weight:700;padding:18px;margin-top:14px;
         border:0;border-radius:14px;background:#e53935;color:#fff}
  button.sec{background:#333}
  button:disabled{opacity:.5}
  #status{margin-top:22px;font-size:20px;line-height:1.5;min-height:1.5em}
  #status.err{color:#ff8a80}
  #result{margin-top:16px;display:none}
  #result img{width:100%;border-radius:14px}
  a.dl{display:block;text-align:center;text-decoration:none;font-size:22px;font-weight:700;
       padding:18px;margin-top:14px;border-radius:14px;background:#43a047;color:#fff}
</style></head><body>
<h1>📥 Shorts → MP4</h1>

<div id="tokenBox" style="display:none">
  <label for="token">비밀번호</label>
  <input id="token" type="password" autocomplete="current-password">
</div>

<label for="url">YouTube 주소</label>
<input id="url" type="url" inputmode="url" placeholder="https://youtube.com/shorts/...">
<button class="sec" id="paste" type="button">📋 붙여넣기</button>
<button id="go" type="button">MP4로 받기</button>

<div id="status"></div>
<div id="result">
  <img id="thumb" alt="">
  <div id="meta" style="margin-top:10px"></div>
  <a class="dl" id="dl" href="#">⬇️ 휴대폰에 저장</a>
</div>

<script>
const $ = id => document.getElementById(id);
let tokenRequired = false;
try { $("token").value = localStorage.getItem("tok") || ""; } catch(e) {}

fetch("/api/config").then(r => r.json()).then(j => {
  tokenRequired = j.ok && j.data.token_required;
  if (tokenRequired) $("tokenBox").style.display = "block";
});

$("paste").onclick = async () => {
  try { $("url").value = (await navigator.clipboard.readText()).trim(); }
  catch(e) { setStatus("길게 눌러 직접 붙여넣어 주세요", true); }
};

function setStatus(msg, isErr=false){ $("status").textContent = msg; $("status").className = isErr ? "err" : ""; }
function mb(b){ return (b/1048576).toFixed(1) + " MB"; }

$("go").onclick = async () => {
  const url = $("url").value.trim();
  const tok = $("token").value;
  if (!url) return setStatus("주소를 입력하세요", true);
  try { localStorage.setItem("tok", tok); } catch(e) {}
  $("go").disabled = true; $("result").style.display = "none";
  setStatus("⏳ 서버에서 받는 중… (10~30초)");
  try {
    const r = await fetch("/api/fetch", {
      method: "POST",
      headers: {"Content-Type": "application/json", "X-Access-Token": tok},
      body: JSON.stringify({url})
    });
    const j = await r.json();
    if (!j.ok) throw new Error(j.error);
    const d = j.data;
    $("thumb").src = d.thumbnail || "";
    $("thumb").style.display = d.thumbnail ? "block" : "none";
    $("meta").textContent = d.filename + " · " + mb(d.size_bytes);
    $("dl").href = "/api/file/" + encodeURIComponent(d.file_id) + (tok ? "?t=" + encodeURIComponent(tok) : "");
    $("result").style.display = "block";
    setStatus("✅ 준비 완료 — 아래 버튼을 누르세요");
  } catch(e) {
    setStatus("❌ " + (e.message || "실패"), true);
  } finally {
    $("go").disabled = false;
  }
};
</script>
</body></html>"""


@app.get("/")
def index():
    return Response(PAGE, mimetype="text/html")


@app.after_request
def headers(resp):
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["Referrer-Policy"] = "no-referrer"
    return resp


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    if not shutil.which("ffmpeg"):
        log.warning("ffmpeg 없음 — 고화질 병합이 실패할 수 있습니다")
    if not ACCESS_TOKEN:
        log.warning("ACCESS_TOKEN 미설정 — 같은 Wi-Fi의 누구나 사용 가능")
    threading.Thread(target=janitor, daemon=True).start()
    log.info("휴대폰에서 접속: http://%s:%d", lan_ip(), PORT)
    app.run(host="0.0.0.0", port=PORT, threaded=True)
