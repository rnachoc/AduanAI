#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# One end-to-end round through the gateway: the support scenarios, finance approves the held
# refund, then the agent retries it. Usage: e2e_round.sh <agent key> [--legacy]
set -eu
export MSYS_NO_PATHCONV=1
root=$(git rev-parse --show-toplevel)
. "$root/scripts/images.env"
key=$1
shift
agent() {
  docker compose run --rm -e ADUANA_API_KEY="$key" agent "$@"
}
agent "$@" --gateway http://gateway:8080/mcp \
  scenarios/gateway/support-through-aduana.yaml scenarios/gateway/injected-refund-through-aduana.yaml \
  scenarios/gateway/injected-small-refund.yaml
docker run --rm --network aduana_default --env-file "$root/.env" -v "$root":/src -w /src/scripts \
  "$PYTHON_IMAGE" python demo_approve.py http://gateway:8080 finance@example.com finance payments__refund
agent "$@" --gateway http://gateway:8080/mcp scenarios/gateway/refund-after-approval.yaml
