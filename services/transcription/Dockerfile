FROM ubuntu:24.04 AS whisper-build
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake git ca-certificates && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git /src/whisper.cpp
RUN cmake -S /src/whisper.cpp -B /src/whisper.cpp/build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_SERVER=OFF
RUN cmake --build /src/whisper.cpp/build --config Release -j2 --target whisper-cli

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates libgomp1 && rm -rf /var/lib/apt/lists/*
COPY --from=whisper-build /src/whisper.cpp/build/bin/whisper-cli /usr/local/bin/whisper-cli
RUN mkdir -p /models && python -c "import urllib.request; urllib.request.urlretrieve('https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin', '/models/ggml-tiny-q5_1.bin')"
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
ENV WHISPER_MODEL=/models/ggml-tiny-q5_1.bin MAX_UPLOAD_BYTES=20971520 MAX_AUDIO_SECONDS=300
EXPOSE 8080
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1"]
