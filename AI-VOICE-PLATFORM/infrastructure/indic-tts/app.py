"""Private OpenAI-compatible TTS adapter for the Telugu Indic Parler checkpoint.

The selected Parler checkpoint generates complete utterances (not incremental model
audio). This adapter returns PCM after generation and is intended for the HF S2S
pipeline's compatible TTS backend; it does not claim true streaming synthesis.
"""

from __future__ import annotations

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from math import gcd
from typing import Literal

import numpy as np
import torch
from fastapi import FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from scipy.signal import resample_poly
from parler_tts import ParlerTTSForConditionalGeneration
from transformers import AutoTokenizer

MODEL_ID = os.getenv("INDIC_TTS_MODEL", "ai4bharat/indic-parler-tts")
TARGET_SAMPLE_RATE = int(os.getenv("INDIC_TTS_TARGET_SAMPLE_RATE", "24000"))
DEFAULT_SPEAKER = os.getenv("INDIC_TTS_SPEAKER", "Lalitha")
DEVICE = os.getenv("INDIC_TTS_DEVICE", "cuda")
HF_TOKEN = os.getenv("HF_TOKEN") or None
SPEAKERS = {"Lalitha", "Prakash", "Kiran"}
logger = logging.getLogger(__name__)


class SpeechRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    model: str = MODEL_ID
    input: str = Field(min_length=1, max_length=1800)
    voice: str = DEFAULT_SPEAKER
    response_format: Literal["pcm"] = "pcm"


def _load_models() -> tuple[ParlerTTSForConditionalGeneration, AutoTokenizer, AutoTokenizer, str]:
    if DEVICE == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("INDIC_TTS_DEVICE=cuda but CUDA is unavailable")
    if DEVICE not in {"cuda", "cpu"}:
        raise RuntimeError("INDIC_TTS_DEVICE must be cuda or cpu")

    dtype = torch.float16 if DEVICE == "cuda" else torch.float32
    model = ParlerTTSForConditionalGeneration.from_pretrained(
        MODEL_ID, token=HF_TOKEN, torch_dtype=dtype
    ).to(DEVICE)
    text_tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, token=HF_TOKEN)
    text_encoder_id = model.config.text_encoder._name_or_path
    description_tokenizer = AutoTokenizer.from_pretrained(text_encoder_id, token=HF_TOKEN)
    return model, text_tokenizer, description_tokenizer, DEVICE


def _pcm16le(audio: np.ndarray, source_rate: int) -> bytes:
    waveform = np.asarray(audio, dtype=np.float32).squeeze()
    if waveform.ndim != 1 or not waveform.size:
        raise RuntimeError("Indic Parler returned an empty or non-mono waveform")
    if source_rate != TARGET_SAMPLE_RATE:
        factor = gcd(source_rate, TARGET_SAMPLE_RATE)
        waveform = resample_poly(
            waveform,
            up=TARGET_SAMPLE_RATE // factor,
            down=source_rate // factor,
        )
    pcm = np.clip(np.rint(waveform * 32767.0), -32768, 32767).astype("<i2")
    return pcm.tobytes()


def _synthesize(text: str, speaker: str) -> bytes:
    model = app.state.model
    device = app.state.model_device
    text_tokenizer = app.state.text_tokenizer
    description_tokenizer = app.state.description_tokenizer

    descriptions = {
        "Lalitha": "Lalitha speaks natural Telugu in a clear, warm female voice at a moderate pace and pitch. Use an Andhra Telugu pronunciation, close recording, and minimal background noise.",
        "Prakash": "Prakash speaks natural Telugu in a clear male voice at a moderate pace and pitch. Use an Andhra Telugu pronunciation, close recording, and minimal background noise.",
        "Kiran": "Kiran speaks natural Telugu in a clear voice at a moderate pace and pitch. Use an Andhra Telugu pronunciation, close recording, and minimal background noise.",
    }
    prompt = text_tokenizer(text, return_tensors="pt")
    description = description_tokenizer(descriptions[speaker], return_tensors="pt")
    prompt = {key: value.to(device) for key, value in prompt.items()}
    description = {key: value.to(device) for key, value in description.items()}
    with torch.inference_mode():
        generated = model.generate(
            input_ids=description["input_ids"],
            attention_mask=description.get("attention_mask"),
            prompt_input_ids=prompt["input_ids"],
            prompt_attention_mask=prompt.get("attention_mask"),
        )
    waveform = generated.detach().float().cpu().numpy()
    return _pcm16le(waveform, int(model.config.sampling_rate))


@asynccontextmanager
async def lifespan(application: FastAPI):
    model, text_tokenizer, description_tokenizer, device = _load_models()
    application.state.model = model
    application.state.text_tokenizer = text_tokenizer
    application.state.description_tokenizer = description_tokenizer
    application.state.model_device = device
    application.state.inference_lock = asyncio.Lock()
    yield


app = FastAPI(title="Private Indic Parler Telugu TTS", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok" if hasattr(app.state, "model") else "loading",
        "model": MODEL_ID,
        "device": DEVICE,
        "sample_rate": TARGET_SAMPLE_RATE,
    }


@app.post("/v1/audio/speech")
async def speech(body: SpeechRequest) -> Response:
    if body.response_format != "pcm":
        raise HTTPException(status_code=422, detail="Only raw PCM16 output is supported")
    if body.voice not in SPEAKERS:
        raise HTTPException(status_code=422, detail="Unsupported Indic Parler Telugu speaker")
    try:
        async with app.state.inference_lock:
            audio = await run_in_threadpool(_synthesize, body.input, body.voice)
    except Exception as exc:
        logger.exception("Indic Parler synthesis failed")
        raise HTTPException(status_code=502, detail="Indic Parler synthesis failed") from exc
    return Response(content=audio, media_type="audio/pcm")
