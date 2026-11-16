FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git libportaudio2 libsndfile1 python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --no-cache-dir --break-system-packages --upgrade pip uv
RUN uv pip install --system --no-cache \
    "speech-to-speech[faster-whisper] @ git+https://github.com/huggingface/speech-to-speech.git@d1dc31deab44510188ffcd7edfc87203ed9077fb"

EXPOSE 8765
CMD ["speech-to-speech", "serve", "--host", "0.0.0.0", "--port", "8765", "--stt", "faster-whisper", "--faster_whisper_stt_model_name", "large-v3-turbo", "--faster_whisper_stt_gen_language", "auto", "--llm_backend", "transformers", "--model_name", "Qwen/Qwen3.5-4B", "--tts", "openai", "--openai_tts_base_url", "http://indic-tts:8091/v1", "--openai_tts_model", "ai4bharat/indic-parler-tts", "--openai_tts_voice", "Lalitha", "--openai_tts_sample_rate", "24000", "--openai_tts_response_format", "pcm", "--enable_lang_prompt", "--num_pipelines", "1"]
