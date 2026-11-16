import asyncio
import base64
import json

from fastapi.testclient import TestClient

from config import Settings
from main import app
from services import hf_realtime


class FakeActiveCall:
    call_id = "call-edesy-1"
    session_id = "outbound-sid-1"
    instructions = "Speak naturally in Telugu. Do not invent property facts."
    greeting = "Hello, I am an AI assistant."
    media_attached = False
    terminal = False

    def __init__(self):
        self.provider_ready = asyncio.Event()
        self.provider_ready.set()
        self.controls = asyncio.Queue()
        self.transcript = []


class FakeConversation:
    def __init__(self):
        self.active = FakeActiveCall()
        self.summaries = []
        self.failed = False

    def attach_edesy_media(self, metadata):
        assert metadata["call_sid"] == "media-leg-1"
        return self.active

    async def record_transcript(self, active, speaker, text):
        assert active is self.active
        active.transcript.append(f"{speaker}: {text}")
        return False

    async def save_summary(self, active, summary):
        self.summaries.append((active.call_id, summary))
        return True

    async def finish_edesy_media(self, active):
        active.media_attached = False

    async def fail_edesy_media(self, active):
        self.failed = True

    async def shutdown(self):
        return None


class FakeHfSocket:
    def __init__(self):
        self.sent = []
        self.events = asyncio.Queue()
        self.audio = bytes((i % 128 for i in range(640)))

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        del exc_type, exc, traceback
        return None

    async def recv(self):
        return json.dumps({"type": "session.created"})

    async def send(self, raw):
        event = json.loads(raw)
        self.sent.append(event)
        if event["type"] == "response.create":
            await self.events.put(
                json.dumps(
                    {
                        "type": "response.output_audio.delta",
                        "delta": base64.b64encode(self.audio).decode("ascii"),
                    }
                )
            )
            await self.events.put(json.dumps({"type": "response.output_audio.done"}))
            await self.events.put(
                json.dumps({"type": "response.output_audio_transcript.done", "transcript": "నమస్కారం."})
            )
        elif event["type"] == "input_audio_buffer.append":
            await self.events.put(
                json.dumps(
                    {
                        "type": "conversation.item.input_audio_transcription.completed",
                        "transcript": "ధర ఎంత?",
                    }
                )
            )
            await self.events.put(
                json.dumps(
                    {
                        "type": "response.output_audio.delta",
                        "delta": base64.b64encode(self.audio).decode("ascii"),
                    }
                )
            )
            await self.events.put(json.dumps({"type": "response.output_audio.done"}))

    def __aiter__(self):
        return self

    async def __anext__(self):
        event = await self.events.get()
        if event is None:
            raise StopAsyncIteration
        return event


def test_edesy_binary_pcm_bridges_to_hf_realtime(monkeypatch):
    import api.media_edesy as media

    settings = Settings(
        call_provider="edesy",
        edesy_stream_token="stream-secret",
        hf_speech_ws_url="ws://hf.internal/v1/realtime?model=local",
    )
    conversation = FakeConversation()
    fake_hf = FakeHfSocket()

    monkeypatch.setattr(media, "get_settings", lambda: settings)

    async def fake_connect(connection_settings):
        assert connection_settings.hf_speech_ws_url == "ws://hf.internal/v1/realtime?model=local"
        return fake_hf

    async def fake_summary(_settings, transcript):
        assert _settings.call_provider == "edesy"
        assert "customer: ధర ఎంత?" in transcript
        return "Customer asked for the price; the agent should provide verified pricing."

    monkeypatch.setattr(hf_realtime, "connect", fake_connect)
    monkeypatch.setattr(hf_realtime, "generate_summary", fake_summary)

    with TestClient(app) as client:
        app.state.conversation = conversation
        with client.websocket_connect("/media/edesy?token=stream-secret") as ws:
            ws.send_text(
                json.dumps(
                    {
                        "call_sid": "media-leg-1",
                        "direction": "outbound",
                        "to": "+917969000000",
                        "sampleRate": 16000,
                        "mixType": "mono",
                    }
                )
            )
            ws.send_bytes(bytes(640))
            first_outbound = ws.receive_bytes()
            second_outbound = ws.receive_bytes()
            ws.send_text(json.dumps({"type": "disconnect"}))

    assert len(first_outbound) == 640
    assert len(second_outbound) == 640
    input_event = next(event for event in fake_hf.sent if event["type"] == "input_audio_buffer.append")
    assert base64.b64decode(input_event["audio"]) == bytes(640)
    assert any(event["type"] == "session.update" for event in fake_hf.sent)
    assert conversation.active.transcript == ["assistant: నమస్కారం.", "customer: ధర ఎంత?"]
    assert conversation.summaries[0][0] == "call-edesy-1"
    assert not conversation.failed
