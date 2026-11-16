import asyncio
import base64
import json

import numpy as np

from config import Settings
from services.openai_realtime_speech import (
    OpenAIRealtimeSpeechEngine,
    session_update,
    websocket_url,
)


class FakeRealtimeSocket:
    def __init__(self):
        self.sent = []
        self.received = [json.dumps({"type": "session.created"})]

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        del exc_type, exc, traceback

    async def recv(self):
        return self.received.pop(0)

    async def send(self, raw):
        event = json.loads(raw)
        self.sent.append(event)
        if event["type"] == "session.update":
            self.received.append(json.dumps({"type": "session.updated"}))


def test_session_configuration_uses_24khz_pcm_server_vad_and_selected_voice():
    settings = Settings(
        openai_api_key="test-key",
        openai_realtime_model="gpt-realtime-2.1",
        openai_realtime_voice="marin",
        openai_realtime_transcription_model="gpt-4o-transcribe",
    )

    event = session_update(settings, "Speak in Telugu.", "te")
    session = event["session"]
    assert session["model"] == "gpt-realtime-2.1"
    assert session["output_modalities"] == ["audio"]
    assert session["audio"]["input"]["format"] == {"type": "audio/pcm", "rate": 24000}
    assert session["audio"]["input"]["transcription"] == {
        "model": "gpt-4o-transcribe",
        "language": "te",
    }
    assert session["audio"]["input"]["turn_detection"]["type"] == "server_vad"
    assert session["audio"]["input"]["turn_detection"]["interrupt_response"] is True
    assert session["audio"]["output"]["format"] == {"type": "audio/pcm", "rate": 24000}
    assert session["audio"]["output"]["voice"] == "marin"


def test_websocket_url_sets_model_and_preserves_other_query_parameters():
    settings = Settings(
        openai_realtime_ws_url="wss://example.test/realtime?region=west&model=old",
        openai_realtime_model="gpt-realtime-2.1",
    )
    assert websocket_url(settings) == (
        "wss://example.test/realtime?region=west&model=gpt-realtime-2.1"
    )


async def test_open_session_authenticates_and_sends_greeting_after_session_update(monkeypatch):
    import services.openai_realtime_speech as speech

    settings = Settings(openai_api_key="test-key")
    socket = FakeRealtimeSocket()
    connection = {}

    async def fake_connect(url, **kwargs):
        connection["url"] = url
        connection["kwargs"] = kwargs
        return socket

    monkeypatch.setattr(speech.websockets, "connect", fake_connect)
    engine = OpenAIRealtimeSpeechEngine(settings)
    async with engine.open_session("Speak in Telugu.", "I am an AI assistant.", "te") as active_socket:
        assert active_socket is socket

    assert connection["url"] == "wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1"
    assert connection["kwargs"]["additional_headers"] == {"Authorization": "Bearer test-key"}
    assert [event["type"] for event in socket.sent] == [
        "session.update",
        "conversation.item.create",
        "response.create",
    ]
    assert socket.sent[-1]["response"]["output_modalities"] == ["audio"]


async def test_audio_conversion_resamples_24khz_output_to_16khz_pcm():
    settings = Settings(openai_api_key="test-key")
    engine = OpenAIRealtimeSpeechEngine(settings)
    pcm24k = np.arange(960, dtype="<i2").tobytes()

    converted = engine.audio_delta(
        {"delta": base64.b64encode(pcm24k).decode("ascii")}
    )
    tail = engine.finish_output_audio()

    assert (len(converted) + len(tail)) // 2 == 640


async def test_audio_conversion_resamples_16khz_input_before_base64_append():
    settings = Settings(openai_api_key="test-key")
    engine = OpenAIRealtimeSpeechEngine(settings)
    socket = FakeRealtimeSocket()
    lock = asyncio.Lock()
    frame = np.zeros(320, dtype="<i2").tobytes()

    for _ in range(3):
        await engine.send_audio(socket, frame, lock)

    audio_events = [event for event in socket.sent if event["type"] == "input_audio_buffer.append"]
    sent_samples = sum(len(base64.b64decode(event["audio"])) // 2 for event in audio_events)
    assert sent_samples > 0
    assert all(len(base64.b64decode(event["audio"])) % 2 == 0 for event in audio_events)
