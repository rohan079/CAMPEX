from config import Settings
from services import openai_realtime as rt


def test_session_config_has_model_voice_client_delegation_and_greeting():
    settings = Settings(openai_live_model="gpt-live-1", openai_voice="marin")
    config = rt.build_session_config(settings, instructions="be nice", greeting="say hi")
    assert config["model"] == "gpt-live-1"
    assert config["instructions"] == "be nice"
    assert config["audio"]["output"]["voice"] == "marin"
    assert config["delegation"] == {"type": "client"}
    assert config["input"][0]["role"] == "developer"
    assert config["input"][0]["content"][0]["text"] == "say hi"


def test_transport_events_map_to_the_lead_service_vocabulary():
    assert rt.transport_status({"type": "transport.ringing"}) == "ringing"
    assert rt.transport_status({"type": "transport.answered"}) == "answered"
    assert rt.transport_status({"type": "transport.failed"}) == "failed"
    assert rt.transport_status({"type": "session.closed"}) == "closed"
    assert rt.transport_status({"type": "session.started"}) is None
    assert rt.transport_status({}) is None


def test_failure_detail_reads_strings_and_nested_errors():
    assert rt.failure_detail({"error": {"message": "busy"}}) == "busy"
    assert rt.failure_detail({"reason": "no answer"}) == "no answer"
    assert rt.failure_detail({}) is None


def test_user_transcript_only_from_transcript_events():
    assert rt.user_transcript({"type": "session.input_transcript.done", "transcript": "hello"}) == "hello"
    assert rt.user_transcript({"type": "conversation.item.input_audio_transcription.completed",
                               "item": {"text": "hi"}}) == "hi"
    assert rt.user_transcript({"type": "transport.ringing", "transcript": "ignored"}) is None
    assert rt.user_transcript({"type": "session.input_transcript.done", "transcript": "  "}) is None


def test_greeting_event_matches_the_documented_shape():
    event = rt.greeting_event("hello")
    assert event["type"] == "session.commentary.append"
    assert event["delegation_id"] is None
    assert event["content"] == "hello"
