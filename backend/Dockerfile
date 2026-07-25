FROM ghcr.io/astral-sh/uv:debian-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV LANG=en_US.UTF-8
ENV PATH="/app/.venv/bin:$PATH"
# Pin the interpreter uv uses — without this, uv picks whatever the
# newest available CPython is (e.g. 3.14), which is too new for some
# dependencies' compiled wheels (pydantic-core/PyO3) and fails to build.
ENV UV_PYTHON=3.11

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential \
	bash \
        git \
        curl \
        ca-certificates \
        locales && \
    locale-gen en_US.UTF-8 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .
RUN uv python install 3.11
RUN uv lock
RUN uv sync --locked
RUN chmod +x start.sh
CMD ["bash", "start.sh"]
