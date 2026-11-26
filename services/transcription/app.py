import asyncio
import json
import os
import subprocess
import tempfile
from pathlib import Path

import httpx
from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware

SUPABASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
SUPABASE_ANON_KEY = os.environ.get("SUPABASE_ANON_KEY", "")
MODEL = os.environ.get("WHISPER_MODEL", "/models/ggml-tiny-q5_1.bin")
MAX_UPLOAD = int(os.environ.get("MAX_UPLOAD_BYTES", str(20 * 1024 * 1024)))
MAX_SECONDS = int(os.environ.get("MAX_AUDIO_SECONDS", "300"))
ALLOWED_ORIGINS = [
    item.strip() for item in os.environ.get(
        "CORS_ORIGINS", "https://nikolasconnor.github.io"
    ).split(",") if item.strip()
]

app = FastAPI(title="Nexura Local Transcription", docs_url=None, redoc_url=None)
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=False,
    allow_methods=["POST", "GET", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)
transcription_lock = asyncio.Semaphore(1)


@app.get("/health")
async def health():
    return {"ok": True, "engine": "whisper.cpp", "model": Path(MODEL).name}


async def require_user(authorization: str | None):
    if not SUPABASE_URL or not SUPABASE_ANON_KEY:
        raise HTTPException(status_code=503, detail="Supabase auth is not configured")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Authentication required")
    async with httpx.AsyncClient(timeout=12) as client:
        response = await client.get(
            SUPABASE_URL + "/auth/v1/user",
            headers={"apikey": SUPABASE_ANON_KEY, "Authorization": authorization},
        )
    if response.status_code != 200:
        raise HTTPException(status_code=401, detail="Invalid or expired session")
    return response.json()


@app.post("/v1/transcribe")
async def transcribe(
    audio: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    await require_user(authorization)
    if not audio.content_type or not (
        audio.content_type.startswith("audio/") or audio.content_type == "application/octet-stream"
    ):
        raise HTTPException(status_code=415, detail="Upload an audio file")
    raw = await audio.read(MAX_UPLOAD + 1)
    if not raw:
        raise HTTPException(status_code=400, detail="Audio file is empty")
    if len(raw) > MAX_UPLOAD:
        raise HTTPException(status_code=413, detail="Audio file exceeds 20 MB")
    if not Path(MODEL).is_file():
        raise HTTPException(status_code=503, detail="Local speech model is not installed")

    async with transcription_lock:
        with tempfile.TemporaryDirectory(prefix="nexura-stt-") as temp:
            root = Path(temp)
            source = root / "source.audio"
            wav = root / "audio.wav"
            output = root / "result"
            source.write_bytes(raw)
            probe = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                 "-of", "default=noprint_wrappers=1:nokey=1", str(source)],
                capture_output=True, text=True, timeout=20,
            )
            if probe.returncode != 0:
                raise HTTPException(status_code=415, detail="Unsupported or damaged audio")
            try:
                duration = float(probe.stdout.strip())
            except ValueError:
                raise HTTPException(status_code=415, detail="Could not read audio duration")
            if duration <= 0 or duration > MAX_SECONDS:
                raise HTTPException(status_code=413, detail="Audio must be 1–300 seconds long")

            convert = subprocess.run(
                ["ffmpeg", "-nostdin", "-v", "error", "-i", str(source),
                 "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-y", str(wav)],
                capture_output=True, timeout=90,
            )
            if convert.returncode != 0:
                raise HTTPException(status_code=415, detail="Could not decode audio")

            try:
                run = subprocess.run(
                    ["whisper-cli", "-m", MODEL, "-f", str(wav), "-l", "auto",
                     "-nt", "-np", "-oj", "-of", str(output)],
                    capture_output=True, text=True, timeout=max(90, int(duration * 8)),
                )
            except subprocess.TimeoutExpired:
                raise HTTPException(status_code=504, detail="Transcription timed out")
            result_file = output.with_suffix(".json")
            if run.returncode != 0 or not result_file.is_file():
                raise HTTPException(status_code=500, detail="Local speech recognition failed")
            try:
                result = json.loads(result_file.read_text(encoding="utf-8"))
                segments = result.get("transcription") or result.get("segments") or []
                text = " ".join(
                    str(item.get("text", "")).strip() for item in segments
                    if isinstance(item, dict) and item.get("text")
                ).strip()
                language = result.get("result", {}).get("language") or result.get("language")
            except (ValueError, OSError, AttributeError):
                raise HTTPException(status_code=500, detail="Invalid recognition result")
            if not text:
                raise HTTPException(status_code=422, detail="No speech was detected")
            return {"text": text, "language": language, "duration_seconds": round(duration, 1), "engine": "whisper.cpp"}
