"""Client adapter for the Hugging Face speech-to-speech Realtime WebSocket."""

from __future__ import annotations

import base64
import json
import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import websockets

from config import Settings

PCM_SAMPLE_RATE = 16000


def session_update(instructions: str) -> dict[str, Any]:
    """Configure the HF pipeline's native 16 kHz PCM and server-side VAD."""
    return {
        "type": "session.update",
        "session": {
            "type": "realtime",
            "instructions": instructions,
            "audio": {
                "input": {
                    "turn_detection": {
                        "type": "server_vad",
                        "threshold": 0.45,
                        "interrupt_response": True,
                    }
                },
                "output": {},
            },
        },
    }


def append_audio(pcm16le: bytes) -> dict[str, str]:
    return {
        "type": "input_audio_buffer.append",
        "audio": base64.b64encode(pcm16le).decode("ascii"),
    }


def audio_delta(event: dict[str, Any]) -> bytes:
    payload = event.get("delta")
    if not isinstance(payload, str):
        raise ValueError("Realtime audio event has no base64 delta")
    return base64.b64decode(payload, validate=True)


def initial_greeting(greeting: str) -> tuple[dict[str, Any], dict[str, str]]:
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
        {"type": "response.create"},
    )


def summary_request(transcript: str) -> tuple[dict[str, Any], dict[str, Any]]:
    text = (
        "Write a concise factual call summary in English using only the transcript below. "
        "Include the customer's property needs, stated interest or objections, and any agreed next step. "
        "Do not infer missing details or include personal phone numbers.\n\n"
        f"Transcript:\n{transcript[:12000]}"
    )
    return (
        {
            "type": "conversation.item.create",
            "item": {
                "type": "message",
                "role": "user",
                "content": [{"type": "input_text", "text": text}],
            },
        },
        {"type": "response.create", "response": {"modalities": ["text"]}},
    )


async def connect(settings: Settings):
    """Open the private HF endpoint without sending any OpenAI credentials."""
    return await websockets.connect(
        settings.hf_speech_ws_url,
        open_timeout=settings.hf_connect_timeout_seconds,
        ping_interval=20,
        ping_timeout=20,
        max_size=4 * 1024 * 1024,
    )


async def generate_summary(settings: Settings, transcript: str) -> str:
    """Ask the local HF Qwen pipeline for a transcript-grounded summary."""
    async with await connect(settings) as socket:
        try:
            created = json.loads(await asyncio.wait_for(socket.recv(), timeout=10))
        except (TimeoutError, json.JSONDecodeError) as exc:
            raise RuntimeError("HF Realtime session did not initialize") from exc
        if created.get("type") == "error":
            raise RuntimeError("HF Realtime rejected the summary session")

        await socket.send(
            json.dumps(
                session_update(
                    "You summarize real-estate calls. Use only transcript facts, avoid inventing information, "
                    "and omit phone numbers or other unnecessary personal data. Return a concise summary in English."
                )
            )
        )
        await socket.send(
            json.dumps(
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "message",
                        "role": "user",
                        "content": [
                            {
                                "type": "input_text",
                                "text": f"Summarize this call transcript:\n{transcript[:12000]}",
                            }
                        ],
                    },
                }
            )
        )
        await socket.send(json.dumps({"type": "response.create", "response": {"modalities": ["text"]}}))

        parts: list[str] = []
        async with asyncio.timeout(30):
            async for raw in socket:
                event = json.loads(raw)
                if event.get("type") == "response.output_text.delta":
                    delta = event.get("delta")
                    if isinstance(delta, str):
                        parts.append(delta)
                elif event.get("type") == "error":
                    raise RuntimeError("HF Realtime summary generation failed")
                elif event.get("type") == "response.done":
                    break
        return " ".join("".join(parts).split())[:10000]


async def send_json(socket: Any, event: dict[str, Any], lock: Any) -> None:
    async with lock:
        await socket.send(json.dumps(event, separators=(",", ":")))


class HFRealtimeSpeechEngine:
    def __init__(self, settings: Settings):
        self._settings = settings

    @asynccontextmanager
    async def open_session(
        self, instructions: str, greeting: str, language: str
    ) -> AsyncIterator[Any]:
        del language
        async with await connect(self._settings) as socket:
            try:
                created = json.loads(await asyncio.wait_for(socket.recv(), timeout=10))
            except (TimeoutError, json.JSONDecodeError) as exc:
                raise RuntimeError("HF Realtime session did not initialize") from exc
            if created.get("type") != "session.created":
                raise RuntimeError("HF Realtime did not send session.created first")

            lock = asyncio.Lock()
            await send_json(socket, session_update(instructions), lock)
            greeting_item, greeting_response = initial_greeting(greeting)
            await send_json(socket, greeting_item, lock)
            await send_json(socket, greeting_response, lock)
            yield socket

    async def send_audio(self, socket: Any, pcm16le: bytes, lock: Any) -> None:
        await send_json(socket, append_audio(pcm16le), lock)

    def audio_delta(self, event: dict[str, Any]) -> bytes:
        return audio_delta(event)

    def finish_output_audio(self) -> bytes:
        return b""

    def discard_output_audio(self) -> None:
        return None

    async def generate_summary(self, transcript: str) -> str:
        return await generate_summary(self._settings, transcript)
