"""Feature: push-to-talk dictation, one whole recording per request.

The browser records the complete dictation and only then uploads it, the way GitHub Copilot's voice
input does: `POST /api/stt/transcriptions` with an `audio/wav` body (PCM16, mono, 16 kHz). The
recording goes to the engine's offline transcription endpoint (`POST /v1/audio/transcriptions`, the
model's full-attention transcript mode rather than its realtime stream) and the answer is
`{"text": "..."}` (may be empty). Errors answer `{"message": "..."}`: 400 invalid audio, 403 origin,
413 too long, 503 busy, 502 transcription failure. `GET /api/stt/limits` tells the SPA the maximum
recording length, so it stops recording on time.

One transcription at a time per machine: the CPU engine serves them in order, and a request that
waits longer than `QUEUE_WAIT_SECONDS` gets `busy`. A recording without any frame loud enough to be
speech is answered at once with empty text, without waking the engine. Audio and text are never
stored nor logged: only counts and timings.
"""

from __future__ import annotations

import asyncio
import io
import json
import logging
import math
import sys
import time
import wave
from array import array

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from starlette.requests import ClientDisconnect

from mic_speech.shared.engine import get_engine
from mic_speech.shared.env import Settings, get_settings
from mic_speech.shared.metrics import Dictation, get_metrics

LOG = logging.getLogger("speech.dictation")

router = APIRouter()

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2
# RIFF header and optional chunks a WAV writer may add around the samples.
WAV_OVERHEAD_BYTES = 4096
# The SPA stops on the limit; its last audio frame may cross it slightly.
LENGTH_MARGIN_SECONDS = 1
QUEUE_WAIT_SECONDS = 30
ENGINE_CONNECT_TIMEOUT_SECONDS = 5
CONTEXT_BOOST = 3.0
# A 100 ms window this loud (RMS, PCM16) holds speech: above room noise, below any speech onset.
SPEECH_RMS = 500
WINDOW_BYTES = BYTES_PER_SECOND // 10

BUSY_MESSAGE = "Dictation is busy. Try again."
INVALID_MESSAGE = "The recording is not valid audio. Try again."
TOO_LONG_MESSAGE = "The recording is too long."
FORBIDDEN_MESSAGE = "Dictation is not available from this origin."
FAILURE_MESSAGE = "The audio could not be transcribed."

_lock = asyncio.Lock()
_background: set[asyncio.Task] = set()


class _Rejected(Exception):
    """The request cannot be transcribed: answered with `status` and a user-facing `message`."""

    def __init__(self, status: int, message: str, reason: str) -> None:
        super().__init__(reason)
        self.status = status
        self.message = message


def _rms(frame: bytes) -> float:
    samples = array("h", frame)
    if sys.byteorder == "big":
        samples.byteswap()
    return math.sqrt(math.sumprod(samples, samples) / len(samples))


def _has_speech(pcm: bytes) -> bool:
    windows = (pcm[offset:offset + WINDOW_BYTES] for offset in range(0, len(pcm), WINDOW_BYTES))
    return any(_rms(window) >= SPEECH_RMS for window in windows)


async def _read_body(request: Request, max_bytes: int) -> bytes:
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > max_bytes:
            raise _Rejected(413, TOO_LONG_MESSAGE, "Maximum dictation length exceeded")
    return bytes(body)


def _samples(audio: bytes, max_seconds: int) -> bytes:
    """The PCM16 samples of a valid recording; rejects any other WAV layout or length."""
    try:
        with wave.open(io.BytesIO(audio), "rb") as recording:
            layout = (recording.getnchannels(), recording.getsampwidth(), recording.getframerate(),
                      recording.getcomptype())
            frames = recording.getnframes()
            pcm = recording.readframes(frames)
    except (wave.Error, EOFError) as error:
        raise _Rejected(400, INVALID_MESSAGE, f"Unreadable WAV: {error}") from None
    if layout != (1, 2, SAMPLE_RATE, "NONE") or not frames or len(pcm) != frames * 2:
        raise _Rejected(400, INVALID_MESSAGE, "Unexpected WAV layout")
    if frames > (max_seconds + LENGTH_MARGIN_SECONDS) * SAMPLE_RATE:
        raise _Rejected(413, TOO_LONG_MESSAGE, "Maximum dictation length exceeded")
    return pcm


async def _transcribe(settings: Settings, audio: bytes) -> str:
    fields = {
        "language": settings.speech_language,
        "response_format": "json",
        "automatic_punctuation": "true",
    }
    if settings.speech_contexts:
        fields["speech_contexts"] = json.dumps([{"phrases": settings.speech_contexts, "boost": CONTEXT_BOOST}])
    # Offline transcription runs faster than real time; the recording length bounds the wait generously.
    timeout = httpx.Timeout(settings.speech_max_seconds, connect=ENGINE_CONNECT_TIMEOUT_SECONDS)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            get_engine().transcriptions_url, data=fields, files={"file": ("dictation.wav", audio, "audio/wav")},
        )
    if response.status_code == 429:
        raise _Rejected(503, BUSY_MESSAGE, "Engine queue is full")
    response.raise_for_status()
    text = response.json().get("text")
    if not isinstance(text, str):
        raise TypeError("Engine answered without text")
    return text.strip()


def _record(outcome: str, audio_seconds: float, transcribe_ms: float | None) -> None:
    # Whole recordings have no partials; `stop_to_final_ms` is the engine's transcription time.
    entry = Dictation(
        outcome=outcome, audio_seconds=audio_seconds, first_partial_ms=None, partial_count=0,
        stop_to_final_ms=transcribe_ms,
    )
    LOG.info("Dictation outcome=%s audio_s=%.1f transcribe_ms=%s", outcome, audio_seconds,
             "-" if transcribe_ms is None else f"{transcribe_ms:.0f}")

    async def write() -> None:
        try:
            await get_metrics().record(entry)
        except Exception:  # noqa: BLE001 - metrics never fail a dictation
            LOG.warning("Could not record dictation metrics", exc_info=True)

    task = asyncio.create_task(write())
    _background.add(task)
    task.add_done_callback(_background.discard)


def _error(status: int, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"message": message})


@router.get("/api/stt/limits")
async def limits() -> dict:
    return {"maxSeconds": get_settings().speech_max_seconds}


@router.post("/api/stt/transcriptions")
async def transcriptions(request: Request) -> JSONResponse:
    settings = get_settings()
    if request.headers.get("origin") != settings.cors_origin:
        return _error(403, FORBIDDEN_MESSAGE)

    audio_seconds = 0.0
    try:
        max_bytes = (settings.speech_max_seconds + LENGTH_MARGIN_SECONDS) * BYTES_PER_SECOND + WAV_OVERHEAD_BYTES
        audio = await _read_body(request, max_bytes)
        pcm = _samples(audio, settings.speech_max_seconds)
        audio_seconds = len(pcm) / BYTES_PER_SECOND
        if not _has_speech(pcm):  # silence only: nothing to transcribe
            _record("empty", audio_seconds, None)
            return JSONResponse({"text": ""})
        try:
            await asyncio.wait_for(_lock.acquire(), QUEUE_WAIT_SECONDS)
        except TimeoutError:
            raise _Rejected(503, BUSY_MESSAGE, "Another dictation is being transcribed") from None
        try:
            started = time.perf_counter()
            text = await _transcribe(settings, audio)
            transcribe_ms = (time.perf_counter() - started) * 1000
        finally:
            _lock.release()
    except ClientDisconnect:
        _record("cancelled", audio_seconds, None)
        return _error(400, INVALID_MESSAGE)
    except _Rejected as rejection:
        LOG.info("Dictation rejected: %s", rejection)
        _record("error", audio_seconds, None)
        return _error(rejection.status, rejection.message)
    except Exception:  # noqa: BLE001 - any engine or transport failure ends this dictation only
        LOG.warning("Dictation failed", exc_info=True)
        _record("error", audio_seconds, None)
        return _error(502, FAILURE_MESSAGE)

    _record("final" if text else "empty", audio_seconds, transcribe_ms)
    return JSONResponse({"text": text})
