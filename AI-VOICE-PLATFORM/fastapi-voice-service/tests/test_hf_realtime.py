import base64

from services.hf_realtime import append_audio, audio_delta, initial_greeting, session_update, summary_request


def test_hf_session_uses_native_16khz_server_vad_and_interruptions():
    update = session_update("Speak in Telugu and do not invent facts.")
    session = update["session"]
    assert session["type"] == "realtime"
    assert session["instructions"] == "Speak in Telugu and do not invent facts."
    assert session["audio"]["input"]["turn_detection"] == {
        "type": "server_vad",
        "threshold": 0.45,
        "interrupt_response": True,
    }
    # The official server's native audio rate is 16 kHz; omit format to select it.
    assert "format" not in session["audio"]["input"]


def test_audio_append_is_base64_pcm_and_round_trips():
    pcm = bytes(range(256)) * 2 + b"\x00\x00" * 64
    event = append_audio(pcm)
    assert event["type"] == "input_audio_buffer.append"
    assert base64.b64decode(event["audio"]) == pcm
    assert audio_delta({"delta": base64.b64encode(pcm).decode("ascii")}) == pcm


def test_greeting_and_summary_use_text_protocol_events():
    greeting, start = initial_greeting("Hello, I am an AI assistant from Subha Gruha Homes.")
    assert greeting["type"] == "conversation.item.create"
    assert greeting["item"]["content"][0]["type"] == "input_text"
    assert start == {"type": "response.create"}

    summary, respond = summary_request("customer: wants plots near Vijayawada")
    assert summary["item"]["content"][0]["type"] == "input_text"
    assert respond["response"]["modalities"] == ["text"]
