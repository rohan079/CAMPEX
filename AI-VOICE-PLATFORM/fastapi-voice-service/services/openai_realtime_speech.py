"""OpenAI Realtime speech-to-speech adapter for Edesy's 16 kHz PCM stream."""

from __future__ import annotations

import asyncio
import base64
import json
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import numpy as np
import soxr
import websockets

from config import Settings

EDESY_SAMPLE_RATE = 16000
OPENAI_SAMPLE_RATE = 24000


def websocket_url(settings: Settings) -> str:
    parts = urlsplit(settings.openai_realtime_ws_url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["model"] = settings.openai_realtime_model
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


def session_update(
    settings: Settings,
    instructions: str,
    language: str | None,
    output_modalities: Sequence[str] = ("audio",),
) -> dict[str, Any]:
    session: dict[str, Any] = {
        "type": "realtime",
        "model": settings.openai_realtime_model,
        "instructions": instructions,
        "output_modalities": list(output_modalities),
    }
    if "audio" in output_modalities:
        input_audio: dict[str, Any] = {
            "format": {"type": "audio/pcm", "rate": OPENAI_SAMPLE_RATE},
            "turn_detection": {
                "type": "server_vad",
                "threshold": 0.45,
                "interrupt_response": True,
                "create_response": True,
            },
            "transcription": {"model": settings.openai_realtime_transcription_model},
        }
        if language:
            input_audio["transcription"]["language"] = language
        session["audio"] = {
            "input": input_audio,
            "output": {
                "format": {"type": "audio/pcm", "rate": OPENAI_SAMPLE_RATE},
                "voice": settings.openai_realtime_voice,
            },
        }
    return {"type": "session.update", "session": session}


def _greeting_events(greeting: str) -> tuple[dict[str, Any], dict[str, Any]]:
    text = f"Start the call now. Say this as your first greeting, then wait for the customer: {greeting}"
    return (
        {
            "type": "conversation.item.create",
            "item": {
                "type": "message",
                "role": "user",
                "content": [{"type": "input_text", "text": text}],
            },
        },
        {"type": "response.create", "response": {"output_modalities": ["audio"]}},
    )


def _new_resampler(input_rate: int, output_rate: int) -> soxr.ResampleStream:
    return soxr.ResampleStream(input_rate, output_rate, 1, dtype="int16", quality="HQ")


class OpenAIRealtimeSpeechEngine:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._input_resampler = _new_resampler(EDESY_SAMPLE_RATE, OPENAI_SAMPLE_RATE)
        self._output_resampler = _new_resampler(OPENAI_SAMPLE_RATE, EDESY_SAMPLE_RATE)

    @asynccontextmanager
    async def open_session(
        self,
        instructions: str,
        greeting: str | None,
        language: str | None,
        output_modalities: Sequence[str] = ("audio",),
    ) -> AsyncIterator[Any]:
        if not self._settings.openai_api_key:
            raise RuntimeError("OPENAI_API_KEY is required for OpenAI Realtime speech")
        self._input_resampler = _new_resampler(EDESY_SAMPLE_RATE, OPENAI_SAMPLE_RATE)
        self._output_resampler = _new_resampler(OPENAI_SAMPLE_RATE, EDESY_SAMPLE_RATE)
        async with await websockets.connect(
            websocket_url(self._settings),
            additional_headers={"Authorization": f"Bearer {self._settings.openai_api_key}"},
            open_timeout=self._settings.openai_realtime_connect_timeout_seconds,
            ping_interval=20,
            ping_timeout=20,
            max_size=4 * 1024 * 1024,
        ) as socket:
            try:
                created = json.loads(await asyncio.wait_for(socket.recv(), timeout=15))
            except (TimeoutError, json.JSONDecodeError) as exc:
                raise RuntimeError("OpenAI Realtime session did not initialize") from exc
            if created.get("type") != "session.created":
                raise RuntimeError("OpenAI Realtime did not send session.created first")

            send_lock = asyncio.Lock()
            await self._send(
                socket,
                session_update(self._settings, instructions, language, output_modalities),
                send_lock,
            )
            try:
                updated = json.loads(await asyncio.wait_for(socket.recv(), timeout=15))
            except (TimeoutError, json.JSONDecodeError) as exc:
                raise RuntimeError("OpenAI Realtime session configuration was not confirmed") from exc
            if updated.get("type") == "error":
                error = updated.get("error") or {}
                error_type = error.get("type") if isinstance(error, dict) else "protocol_error"
                raise RuntimeError(f"OpenAI Realtime rejected session configuration: {error_type}")
            if updated.get("type") != "session.updated":
                raise RuntimeError("OpenAI Realtime did not confirm session configuration")

            if greeting:
                greeting_item, greeting_response = _greeting_events(greeting)
                await self._send(socket, greeting_item, send_lock)
                await self._send(socket, greeting_response, send_lock)
            yield socket

    async def send_audio(self, socket: Any, pcm16le: bytes, lock: Any) -> None:
        if len(pcm16le) % 2:
            raise ValueError("Edesy sent an incomplete PCM16 sample")
        if not pcm16le:
            return
        samples = np.frombuffer(pcm16le, dtype="<i2")
        converted = self._input_resampler.resample_chunk(samples, last=False)
        if converted.size:
            event = {
                "type": "input_audio_buffer.append",
                "audio": base64.b64encode(converted.astype("<i2", copy=False).tobytes()).decode("ascii"),
            }
            await self._send(socket, event, lock)

    def audio_delta(self, event: dict[str, Any]) -> bytes:
        payload = event.get("delta")
        if not isinstance(payload, str):
            raise ValueError("OpenAI Realtime audio event has no base64 delta")
        pcm24k = base64.b64decode(payload, validate=True)
        if len(pcm24k) % 2:
            raise ValueError("OpenAI Realtime returned an incomplete PCM16 sample")
        samples = np.frombuffer(pcm24k, dtype="<i2")
        converted = self._output_resampler.resample_chunk(samples, last=False)
        return converted.astype("<i2", copy=False).tobytes()

    def finish_output_audio(self) -> bytes:
        tail = self._output_resampler.resample_chunk(np.empty(0, dtype=np.int16), last=True)
        self._output_resampler = _new_resampler(OPENAI_SAMPLE_RATE, EDESY_SAMPLE_RATE)
        return tail.astype("<i2", copy=False).tobytes()

    def discard_output_audio(self) -> None:
        self._output_resampler = _new_resampler(OPENAI_SAMPLE_RATE, EDESY_SAMPLE_RATE)

    async def generate_summary(self, transcript: str) -> str:
        instructions = (
            "Summarize real-estate calls using only transcript facts. Be concise, write in English, "
            "and omit phone numbers or other unnecessary personal data."
        )
        async with self.open_session(
            instructions=instructions,
            greeting=None,
            language=None,
            output_modalities=("text",),
        ) as socket:
            lock = asyncio.Lock()
            await self._send(
                socket,
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "message",
                        "role": "user",
                        "content": [
                            {
                                "type": "input_text",
                                "text": (
                                    "Write a concise factual call summary. Include the customer's property needs, "
                                    "stated interest or objections, and any agreed next step. Do not infer details.\n\n"
                                    f"Transcript:\n{transcript[:12000]}"
                                ),
                            }
                        ],
                    },
                },
                lock,
            )
            await self._send(
                socket,
                {"type": "response.create", "response": {"output_modalities": ["text"]}},
                lock,
            )

            parts: list[str] = []
            async with asyncio.timeout(30):
                async for raw in socket:
                    event = json.loads(raw)
                    if event.get("type") == "response.output_text.delta":
                        delta = event.get("delta")
                        if isinstance(delta, str):
                            parts.append(delta)
                    elif event.get("type") == "error":
                        error = event.get("error") or {}
                        error_type = error.get("type") if isinstance(error, dict) else "protocol_error"
                        raise RuntimeError(f"OpenAI Realtime summary failed: {error_type}")
                    elif event.get("type") == "response.done":
                        response = event.get("response") or {}
                        if response.get("status") != "completed":
                            raise RuntimeError("OpenAI Realtime summary response did not complete")
                        break
            return " ".join("".join(parts).split())[:10000]

    @staticmethod
    async def _send(socket: Any, event: dict[str, Any], lock: Any) -> None:
        async with lock:
            await socket.send(json.dumps(event, separators=(",", ":")))
