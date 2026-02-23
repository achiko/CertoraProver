#!/usr/bin/env sh
set -eu

if [ -z "${CERTORA:-}" ]; then
  export CERTORA=/opt/certora
fi

CERTORA_PY_VENV=${CERTORA_PY_VENV:-/opt/certora/python-venv}
SOLC_SELECT_VENV=${SOLC_SELECT_VENV:-/opt/solc-select-venv}
export PATH="$CERTORA:$CERTORA_PY_VENV/bin:/usr/local/bin:$PATH:$SOLC_SELECT_VENV/bin"

if [ "$#" -eq 0 ]; then
  cat <<'USAGE'
Certora Docker container (EVM)

Usage:
  docker compose run --rm certora <command>

Examples:
  docker compose run --rm certora certoraRun.py -h
  docker compose run --rm certora certoraEVMProver.py -h
USAGE
  exit 0
fi

exec "$@"
