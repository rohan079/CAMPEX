"""Bidirectional Edesy L16 media adapter to a selectable speech-to-speech backend."""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from config import get_settings
from services import hf_realtime
from services.conversation_service import ActiveCall, ConversationService
from services.openai_realtime_speech import OpenAIRealtimeSpeechEngine

router = APIRouter()
logger = logging.getLogger(__name__)

SAMPLE_RATE = 16000
CHANNELS = 1
BYTES_PER_SAMPLE = 2
FRAME_DURATION_MS = 20
FRAME_BYTES = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * FRAME_DURATION_MS // 1000
MAX_QUEUED_FRAMES = 100


def _drain_audio(queue: asyncio.Queue[tuple[int, bytes]]) -> None:
    while True:
        try:
            queue.get_nowait()
        except asyncio.QueueEmpty:
            return


async def _receive_metadata(ws: WebSocket) -> dict[str, Any] | None:
    message = await ws.receive()
    raw = message.get("text")
    if not isinstance(raw, str):
        return None
    data = json.loads(raw)
    return data if isinstance(data, dict) else None


async def _summarize_call(
    engine: Any, conversation: ConversationService, active: ActiveCall
) -> None:
    transcript = "\n".join(active.transcript)
    if not transcript:
        return
    try:
        summary = await engine.generate_summary(transcript)
    except Exception:
        logger.exception("Realtime summary generation failed for call %s", active.call_id)
        return
    if summary and not await conversation.save_summary(active, summary):
        logger.error("Spring did not accept summary for call %s", active.call_id)


@router.websocket("/media/edesy")
async def media_edesy(ws: WebSocket) -> None:
    """Edesy protocol: JSON metadata, then bidirectional 16 kHz mono PCM16LE binary frames."""
    settings = get_settings()
    speech_engine = (
        OpenAIRealtimeSpeechEngine(settings)
        if settings.speech_engine == "openai_realtime"
        else hf_realtime.HFRealtimeSpeechEngine(settings)
    )
    if settings.call_provider.lower() != "edesy":
        await ws.close(code=1008, reason="Edesy call provider is disabled")
        return
    token = ws.query_params.get("token", "")
    if not settings.edesy_stream_token or not hmac.compare_digest(token, settings.edesy_stream_token):
        await ws.close(code=1008, reason="Unauthorized media stream")
        return

    await ws.accept()
    active: ActiveCall | None = None
    normal_disconnect = False
    try:
        metadata = await _receive_metadata(ws)
        if metadata is None:
            await ws.close(code=1003, reason="Expected Edesy JSON metadata frame")
            return
        if metadata.get("sampleRate") != SAMPLE_RATE or metadata.get("mixType") != "mono":
            await ws.close(code=1003, reason="Expected 16 kHz mono PCM media")
            return

        conversation: ConversationService = ws.app.state.conversation
        active = conversation.attach_edesy_media(metadata)
        if active is None:
            logger.warning("Rejected unmatched Edesy media connection")
            await ws.close(code=1008, reason="No pending outbound call matches this media leg")
            return
        try:
            await asyncio.wait_for(active.provider_ready.wait(), timeout=15)
        except TimeoutError:
            raise RuntimeError("Edesy outbound call setup did not finish") from None
        if active.session_id.startswith("pending:"):
            raise RuntimeError("Edesy call has no confirmed provider SID")
        if active.terminal:
            await ws.close(code=1000, reason="Call ended before media setup")
            return

        async with speech_engine.open_session(
            active.instructions, active.greeting, active.language
        ) as speech_socket:
            speech_send_lock = asyncio.Lock()
            edesy_send_lock = asyncio.Lock()

            output_queue: asyncio.Queue[tuple[int, bytes]] = asyncio.Queue(maxsize=MAX_QUEUED_FRAMES)
            generation = [0]
            partial_audio = bytearray()

            async def send_edesy_control(command: str) -> None:
                async with edesy_send_lock:
                    await ws.send_text(json.dumps({"type": command}, separators=(",", ":")))

            async def stop_playback() -> None:
                generation[0] += 1
                partial_audio.clear()
                _drain_audio(output_queue)
                speech_engine.discard_output_audio()
                await send_edesy_control("killAudio")

            async def read_edesy() -> None:
                nonlocal normal_disconnect
                while True:
                    message = await ws.receive()
                    if message.get("type") == "websocket.disconnect":
                        normal_disconnect = True
                        return
                    raw_audio = message.get("bytes")
                    if isinstance(raw_audio, bytes):
                        if len(raw_audio) % BYTES_PER_SAMPLE:
                            raise ValueError("Edesy sent an incomplete PCM16 sample")
                        if not raw_audio:
                            continue
                        await speech_engine.send_audio(speech_socket, raw_audio, speech_send_lock)
                        continue

                    raw_text = message.get("text")
                    if not isinstance(raw_text, str):
                        continue
                    try:
                        control = json.loads(raw_text)
                    except json.JSONDecodeError:
                        logger.warning("Ignored malformed Edesy control frame for call %s", active.call_id)
                        continue
                    command = control.get("type") if isinstance(control, dict) else None
                    if command == "disconnect":
                        normal_disconnect = True
                        return
                    if command == "killAudio":
                        await stop_playback()

            async def read_speech_engine() -> None:
                async for raw_event in speech_socket:
                    event = json.loads(raw_event)
                    event_type = event.get("type")
                    if event_type == "input_audio_buffer.speech_started":
                        await stop_playback()
                    elif event_type == "response.output_audio.delta":
                        partial_audio.extend(speech_engine.audio_delta(event))
                        while len(partial_audio) >= FRAME_BYTES:
                            frame = bytes(partial_audio[:FRAME_BYTES])
                            del partial_audio[:FRAME_BYTES]
                            try:
                                output_queue.put_nowait((generation[0], frame))
                            except asyncio.QueueFull:
                                await stop_playback()
                                logger.warning("Cleared full Edesy playback queue for call %s", active.call_id)
                    elif event_type == "response.output_audio.done":
                        partial_audio.extend(speech_engine.finish_output_audio())
                        if partial_audio:
                            try:
                                output_queue.put_nowait((generation[0], bytes(partial_audio)))
                            except asyncio.QueueFull:
                                await stop_playback()
                            partial_audio.clear()
                    elif event_type == "response.done" and (event.get("response") or {}).get("status") == "cancelled":
                        await stop_playback()
                    elif event_type == "conversation.item.input_audio_transcription.completed":
                        transcript = event.get("transcript")
                        if isinstance(transcript, str):
                            await conversation.record_transcript(active, "customer", transcript)
                    elif event_type == "response.output_audio_transcript.done":
                        transcript = event.get("transcript")
                        if isinstance(transcript, str):
                            await conversation.record_transcript(active, "assistant", transcript)
                    elif event_type == "error":
                        error = event.get("error") or {}
                        error_type = error.get("type") if isinstance(error, dict) else "protocol_error"
                        logger.error(
                            "Realtime speech error for call %s engine=%s type=%s",
                            active.call_id,
                            settings.speech_engine,
                            error_type,
                        )
                        raise RuntimeError(f"Realtime speech error: {error_type or 'unknown'}")

            async def send_audio() -> None:
                while True:
                    frame_generation, frame = await output_queue.get()
                    if frame_generation != generation[0]:
                        continue
                    async with edesy_send_lock:
                        await ws.send_bytes(frame)
                    await asyncio.sleep(FRAME_DURATION_MS / 1000)

            async def send_controls() -> None:
                nonlocal normal_disconnect
                while True:
                    control = await active.controls.get()
                    async with edesy_send_lock:
                        await ws.send_text(json.dumps(control, separators=(",", ":")))
                    if control.get("type") == "disconnect":
                        normal_disconnect = True
                        return

            tasks = {
                asyncio.create_task(read_edesy(), name=f"edesy-in-{active.call_id}"),
                asyncio.create_task(read_speech_engine(), name=f"speech-events-{active.call_id}"),
                asyncio.create_task(send_audio(), name=f"edesy-out-{active.call_id}"),
                asyncio.create_task(send_controls(), name=f"edesy-control-{active.call_id}"),
            }
            try:
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
                if not normal_disconnect:
                    raise RuntimeError("A media bridge task ended unexpectedly")
            finally:
                for task in tasks:
                    if not task.done():
                        task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)

        if normal_disconnect:
            await _summarize_call(speech_engine, conversation, active)
    except WebSocketDisconnect:
        normal_disconnect = True
        if active is not None:
            await _summarize_call(speech_engine, ws.app.state.conversation, active)
    except Exception:
        logger.exception(
            "Edesy speech media bridge failed engine=%s%s",
            settings.speech_engine,
            f" for call {active.call_id}" if active else "",
        )
        if active is not None:
            await ws.app.state.conversation.fail_edesy_media(active)
        with contextlib.suppress(Exception):
            await ws.close(code=1011, reason="Speech media bridge failed")
    finally:
        if active is not None:
            await ws.app.state.conversation.finish_edesy_media(active)
        logger.info("Edesy media connection closed%s", f" for call {active.call_id}" if active else "")
