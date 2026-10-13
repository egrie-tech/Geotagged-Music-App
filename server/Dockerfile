FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SOUNDTRAIL_HOST=0.0.0.0 \
    PORT=8080 \
    SOUNDTRAIL_DB=/data/soundtrail.sqlite3

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY app.py /app/app.py
RUN groupadd --system soundtrail && \
    useradd --system --gid soundtrail --home-dir /app soundtrail && \
    mkdir -p /data && chown soundtrail:soundtrail /data

USER soundtrail
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2)"
CMD ["python", "/app/app.py"]
