FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1 HF_HOME=/models/hf
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git python3.10 python3-pip python3-venv libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN python3 -m pip install --no-cache-dir --upgrade pip \
    && python3 -m pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cu124 \
    && python3 -m pip install --no-cache-dir -r requirements.txt

COPY app.py .
RUN useradd --create-home appuser && mkdir -p /models/hf && chown -R appuser /app /models
USER appuser

EXPOSE 8091
CMD ["python3", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8091"]
