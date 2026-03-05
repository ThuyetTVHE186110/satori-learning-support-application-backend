# ── Build ─────────────────────────────────────────────────────────────────
FROM python:3.11-slim

# System dependencies
# NOTE: libgl1-mesa-glx removed in Debian Trixie → use libgl1
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        libglib2.0-0 \
        libsm6 \
        libxext6 \
        libxrender1 \
        libgomp1 \
        libgl1 \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir -r requirements.txt

COPY main.py .

# Model weights are downloaded at container STARTUP (not build time).
# PaddlePaddle C++ crashes when initializing inside a Docker RUN layer
# (no full process environment). The FastAPI lifespan handler does the warm-up
# instead, and the model cache is persisted via a named Docker volume so it
# is only downloaded once.
# v2.0: Both Japanese and Vietnamese models are loaded automatically.

EXPOSE 8090
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
