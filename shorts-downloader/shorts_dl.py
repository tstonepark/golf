#!/usr/bin/env python3
"""
shorts_dl.py — YouTube Shorts(및 일반 영상)를 MP4로 저장

사용법:
    python shorts_dl.py <URL> [URL ...] [-o 저장폴더]
    python shorts_dl.py -f urls.txt          # 한 줄에 URL 하나

필요:
    pip install -U yt-dlp
    ffmpeg (영상+음성 병합용)
      - Windows: winget install Gyan.FFmpeg
      - macOS:   brew install ffmpeg
      - Linux:   sudo apt install ffmpeg
"""
import argparse
import logging
import shutil
import sys
from pathlib import Path
from urllib.parse import urlparse

try:
    import yt_dlp
except ImportError:
    sys.exit("yt-dlp 가 없습니다:  pip install -U yt-dlp")

ALLOWED_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"}

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("shorts_dl")


def validate_url(url: str) -> bool:
    """YouTube 도메인의 http(s) URL만 허용."""
    try:
        p = urlparse(url.strip())
    except ValueError:
        return False
    return p.scheme in ("http", "https") and (p.hostname or "").lower() in ALLOWED_HOSTS


def build_opts(out_dir: Path) -> dict:
    return {
        # mp4 영상 + m4a 음성 우선, 없으면 mp4 단일 파일
        "format": "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b",
        "merge_output_format": "mp4",
        # 파일명: 제목 [영상ID].mp4  (OS 금지 문자 자동 치환)
        "outtmpl": str(out_dir / "%(title).80s [%(id)s].%(ext)s"),
        "restrictfilenames": False,
        "windowsfilenames": True,
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": False,
        "retries": 3,
    }


def download(urls: list[str], out_dir: Path) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    ok, fail = 0, 0
    with yt_dlp.YoutubeDL(build_opts(out_dir)) as ydl:
        for url in urls:
            if not validate_url(url):
                log.warning("건너뜀 (YouTube URL 아님): %s", url)
                fail += 1
                continue
            try:
                info = ydl.extract_info(url, download=True)
                log.info("완료: %s", ydl.prepare_filename(info).rsplit(".", 1)[0] + ".mp4")
                ok += 1
            except yt_dlp.utils.DownloadError as e:
                log.error("실패: %s — %s", url, str(e).splitlines()[0])
                fail += 1
    log.info("결과: 성공 %d / 실패 %d  → %s", ok, fail, out_dir.resolve())
    return 0 if fail == 0 else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="YouTube Shorts → MP4 저장")
    ap.add_argument("urls", nargs="*", help="Shorts/영상 URL")
    ap.add_argument("-f", "--file", help="URL 목록 텍스트 파일")
    ap.add_argument("-o", "--out", default="downloads", help="저장 폴더 (기본: downloads)")
    args = ap.parse_args()

    urls = list(args.urls)
    if args.file:
        urls += [l.strip() for l in Path(args.file).read_text(encoding="utf-8").splitlines()
                 if l.strip() and not l.startswith("#")]
    if not urls:
        url = input("URL 입력: ").strip()
        urls = [url] if url else []
    if not urls:
        ap.print_help()
        return 1

    if not shutil.which("ffmpeg"):
        log.warning("ffmpeg 없음 — 병합이 필요한 고화질은 실패할 수 있습니다.")

    return download(urls, Path(args.out))


if __name__ == "__main__":
    sys.exit(main())
