FROM --platform=linux/amd64 ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive
ENV CERTORA=/opt/certora

WORKDIR /src

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        python3 \
        python3-pip \
        python3-venv \
        cmake \
        build-essential \
        git \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/root/.cargo/bin:${PATH}"

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain 1.82.0

COPY . .

RUN git init \
    && git config user.email "docker-build@local" \
    && git config user.name "docker-build" \
    && git add -A \
    && git commit -qm "docker build snapshot"

RUN ./gradlew --no-daemon --no-watch-fs assemble -Ptesting

FROM --platform=linux/amd64 ubuntu:24.04 AS runtime

ENV DEBIAN_FRONTEND=noninteractive
ENV CERTORA=/opt/certora
ENV CERTORA_PY_VENV=/opt/certora/python-venv
ENV SOLC_SELECT_VENV=/opt/solc-select-venv

WORKDIR /work

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless \
        python3 \
        python3-pip \
        python3-venv \
        z3 \
        cvc5 \
        ca-certificates \
        bash \
        curl \
        xz-utils \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /opt/certora /opt/certora
COPY scripts/certora_cli_requirements.txt /tmp/certora_cli_requirements.txt
COPY docker/entrypoint.sh /usr/local/bin/certora-entrypoint

RUN set -eux; \
    chmod +x /usr/local/bin/certora-entrypoint; \
    chmod +x /opt/certora/*.py /opt/certora/tac_optimizer /opt/certora/certora-select; \
    python3 -m venv "$CERTORA_PY_VENV"; \
    "$CERTORA_PY_VENV/bin/pip" install --upgrade pip; \
    "$CERTORA_PY_VENV/bin/pip" install -r /tmp/certora_cli_requirements.txt; \
    python3 -m venv "$SOLC_SELECT_VENV"; \
    "$SOLC_SELECT_VENV/bin/pip" install --upgrade pip; \
    "$SOLC_SELECT_VENV/bin/pip" install solc-select; \
    for v in \
      0.4.24 0.4.25 0.5.12 0.5.13 0.5.17 0.6.12 0.7.6 \
      0.8.0 0.8.1 0.8.4 0.8.10 0.8.12 0.8.13 0.8.16 0.8.17 0.8.19 \
      0.8.21 0.8.22 0.8.23 0.8.24 0.8.25 0.8.26 0.8.27 0.8.28 0.8.29 0.8.30; do \
      "$SOLC_SELECT_VENV/bin/solc-select" install "$v"; \
      solc_alias="solc${v#0.}"; \
      ln -sf "/root/.solc-select/artifacts/solc-$v/solc-$v" "/usr/local/bin/$solc_alias"; \
    done; \
    "$SOLC_SELECT_VENV/bin/solc-select" use 0.8.30; \
    ln -sf /usr/local/bin/solc8.30 /usr/local/bin/solc; \
    rm -f /tmp/certora_cli_requirements.txt

ENV PATH="$CERTORA:$CERTORA_PY_VENV/bin:/usr/local/bin:$PATH:$SOLC_SELECT_VENV/bin"

ENTRYPOINT ["/usr/local/bin/certora-entrypoint"]
CMD ["certoraRun.py", "-h"]
