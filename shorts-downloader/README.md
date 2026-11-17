# Shorts Downloader

YouTube Shorts를 MP4로 저장하는 도구.

| 파일 | 용도 |
|---|---|
| `server.py` | 휴대폰용 웹 화면 + LAN 서버 (PC에서 실행 → 같은 Wi-Fi 휴대폰에서 접속) |
| `shorts_dl.py` | PC 명령줄용 |

## 설치
```
pip install -U yt-dlp flask
```
ffmpeg 필요 — Windows `winget install Gyan.FFmpeg` / macOS `brew install ffmpeg`

## 휴대폰에서 쓰기
1. `.env.example` → `.env` 로 복사, `ACCESS_TOKEN` 에 비밀번호 입력
2. `python server.py`
3. 콘솔에 뜬 `http://192.168.x.x:8000` 을 휴대폰에서 열기
4. 링크 붙여넣기 → **MP4로 받기** → **휴대폰에 저장**

## PC에서 쓰기
```
python shorts_dl.py "https://youtube.com/shorts/..."
```

## 참고
- LAN 전용. 공유기 포트포워딩으로 외부 공개 금지.
- 받은 파일은 서버에서 10분 후 자동 삭제.
- 다운로드 실패 시 `pip install -U yt-dlp`.
- 본인 영상/개인 보관 용도로 사용 (YouTube 약관).
