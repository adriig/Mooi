# Production-owned mic-sessions image; build context is the mic-sessions source directory.
FROM python:3.13-slim-trixie AS build
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-editable

FROM python:3.13-slim-trixie AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl wget git openssh-client rsync jq make ripgrep unzip zip xz-utils procps less file gnupg libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker:29-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker:29-cli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins
COPY --from=node:24-trixie-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:24-trixie-slim /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=oven/bun:1.3-debian /usr/local/bin/bun /usr/local/bin/bun
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /uvx /usr/local/bin/
COPY --from=eclipse-temurin:25-jdk /opt/java/openjdk /opt/java/openjdk
ARG RTK_VERSION=1.0.1
RUN case "$(dpkg --print-architecture)" in \
        amd64) target=x86_64-unknown-linux-musl sha=a44e8ffc43981100b4ddf1710399a1649a6c874591aa6d0c0727fe6cbd983c41 ;; \
        arm64) target=aarch64-unknown-linux-gnu sha=ee24a43dac9162de55fea8460b5efcd56d401ec28b5117c921bffa8a57bc136e ;; \
        *) echo "rtk: unsupported architecture" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o /tmp/rtk.tar.gz "https://github.com/rtk-ai/rtk/releases/download/v${RTK_VERSION}/rtk-oss-${target}.tar.gz" \
    && echo "${sha}  /tmp/rtk.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/rtk.tar.gz -C /usr/local/bin rtk \
    && rm /tmp/rtk.tar.gz \
    && rtk --version
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && ln -s bun /usr/local/bin/bunx
ARG CLAUDE_CODE_VERSION=2.1.325
RUN npm install -g --no-audit --no-fund "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && claude --version && rm -rf /root/.npm
ARG AGY_VERSION=1.5.5
ARG AGY_BUILD=6740358537805824
ARG AGY_SHA512_AMD64=746a2bd73f83b65d9b44f2fc55d0f0f511ff7f89702846213eeabd76d06bc9c98576b424c2babc7b947f15e518447b946ba02dca260b778363c55c07a7f7a277
ARG AGY_SHA512_ARM64=eb3e025c125c1c60a1493a0c1d9cdf15e8372277d6d3b015e8d29e1bd095344eece5cbc2447f238148bd259d5f2daa0f75641adc0ac02b5fad57edc4ed842d59
RUN case "$(dpkg --print-architecture)" in \
        amd64) path=linux-x64/cli_linux_x64.tar.gz sha="${AGY_SHA512_AMD64}" ;; \
        arm64) path=linux-arm/cli_linux_arm64.tar.gz sha="${AGY_SHA512_ARM64}" ;; \
        *) echo "agy: unsupported architecture" >&2; exit 1 ;; \
    esac \
    && mkdir /tmp/agy \
    && curl -fsSL -o /tmp/agy.tar.gz "https://storage.googleapis.com/antigravity-public/antigravity-cli/${AGY_VERSION}-${AGY_BUILD}/${path}" \
    && echo "${sha}  /tmp/agy.tar.gz" | sha512sum -c - \
    && tar -xzf /tmp/agy.tar.gz -C /tmp/agy \
    && install -m 0755 /tmp/agy/antigravity /usr/local/bin/agy \
    && rm -rf /tmp/agy /tmp/agy.tar.gz && agy --version
ENV AGY_CLI_DISABLE_AUTO_UPDATE=true
COPY browser/package.json browser/package-lock.json /opt/mooi-browser/
RUN cd /opt/mooi-browser && npm ci --ignore-scripts --no-audit --no-fund \
    && PLAYWRIGHT_BROWSERS_PATH=/opt/mooi-browser/.browsers node node_modules/playwright/cli.js install --with-deps --no-shell chromium \
    && rm -rf /var/lib/apt/lists/* /root/.npm
COPY browser/mcp.mjs /opt/mooi-browser/mcp.mjs
RUN groupadd -g 1001 mooi && useradd -u 1001 -g mooi -m -d /home/mooi -s /bin/bash mooi
WORKDIR /app
COPY --from=build --chown=mooi:mooi /app/.venv /app/.venv
USER mooi
ENV JAVA_HOME=/opt/java/openjdk \
    AGENT_BROWSER_DIRECTORY=/opt/mooi-browser \
    PATH="/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    PYTHONUNBUFFERED=1 LANG=C.UTF-8 SESSIONS_PORT=8000 \
    WORKSPACE_ROOT=/var/lib/mooi/workspaces DOCKER_HOST=unix:///var/run/docker.sock
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
    CMD ["/app/.venv/bin/python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"]
CMD ["/app/.venv/bin/uvicorn", "mic_sessions.main:app", "--host", "0.0.0.0", "--port", "8000", "--timeout-graceful-shutdown", "5", "--no-server-header", "--no-date-header"]
