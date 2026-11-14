#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# Lists the shipped dependencies of every module and refuses copyleft or unknown licences.
set -eu
export MSYS_NO_PATHCONV=1
root=$(git rev-parse --show-toplevel)
. "$root/scripts/images.env"
docker run --rm -v "$root":/src -v aduana-m2:/root/.m2 -w /src "$JDK_IMAGE" \
  ./mvnw -B -q compile dependency:list -DincludeScope=runtime -DoutputFile=target/dependency-list.txt -DappendOutput=false
lists=$(cd "$root" && ls -1 */target/dependency-list.txt)
locks=$(cd "$root" && git ls-files --cached --others --exclude-standard -- '*package-lock.json' | grep -v node_modules || true)
# shellcheck disable=SC2086
docker run --rm -v "$root":/src -v aduana-m2:/m2:ro -w /src "$PYTHON_IMAGE" \
  python scripts/check_licenses.py /m2/repository $lists ${locks:+--npm $locks}
