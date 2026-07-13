# Base images pinned by digest so a moved tag can't change the build under us.
# To refresh: docker buildx imagetools inspect <image>:<tag> and copy the Digest.
FROM python:3.12-slim@sha256:423ed6ab25b1921a477529254bfeeabf5855151dc2c3141699a1bfc852199fbf

COPY --from=ghcr.io/astral-sh/uv:0.9@sha256:538e0b39736e7feae937a65983e49d2ab75e1559d35041f9878b7b7e51de91e4 /uv /usr/local/bin/uv

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --extra server --no-install-project

COPY src ./src
RUN uv sync --frozen --no-dev --extra server

ENV PATH="/app/.venv/bin:$PATH"

# Drop root: the app never writes to the image and needs no elevated privileges.
RUN useradd --create-home --uid 10001 app
USER app

EXPOSE 8080
CMD ["uvicorn", "outagewatch.api.main:app", "--host", "0.0.0.0", "--port", "8080"]
