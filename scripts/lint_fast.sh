#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# Fast checks for every commit: secrets in the staged diff, then authorship of staged sources.
set -eu
export MSYS_NO_PATHCONV=1
root=$(git rev-parse --show-toplevel)
. "$root/scripts/images.env"
docker run --rm -v "$root":/src -w /src \
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
  "$GITLEAKS_IMAGE" git --pre-commit --staged --redact --no-banner /src
staged=$(git diff --cached --name-only --diff-filter=ACMR -- '*.java' '*.ts' '*.py' | grep -vE '(^|/)(node_modules|target|dist)/|\.d\.ts$' || true)
[ -z "$staged" ] && exit 0
python=$(command -v python3 || command -v python || true)
if [ -n "$python" ]; then
  # shellcheck disable=SC2086
  exec "$python" scripts/check_authorship.py $staged
fi
# shellcheck disable=SC2086
exec docker run --rm -v "$root":/src -w /src "$PYTHON_IMAGE" python scripts/check_authorship.py $staged
