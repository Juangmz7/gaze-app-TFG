# Local development / testing image.
# Python version matches the recommendation-service CI workflow.
FROM python:3.14-slim

WORKDIR /app

# System dependencies (build-essential, libpq-dev for psycopg)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# CPU-only PyTorch (much smaller image). The container runs with MODEL_DEVICE=cpu
# and requirements.txt does not pin a CUDA build.
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

RUN pip install --no-cache-dir -r requirements.txt \
    opentelemetry-distro \
    opentelemetry-exporter-otlp \
 && opentelemetry-bootstrap -a install

# Pre-download the HuggingFace embedding model (cached while dependencies don't change)
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('Qwen/Qwen3-Embedding-0.6B', device='cpu')"

COPY alembic.ini .
COPY alembic/ ./alembic/
COPY src/ ./src/

ENV PYTHONPATH=/app/src

# Run migrations, then start FastStream wrapped in opentelemetry-instrument.
# Migrations at startup are fine with a single replica; move them to an init
# step before scaling out.
CMD ["sh", "-c", "alembic upgrade head && exec opentelemetry-instrument faststream run rabbitmq.config.connection:app"]
