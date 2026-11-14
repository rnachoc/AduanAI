# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# Python lint tools at fixed versions. yamllint is GPL-3.0: it runs here as a tool and is never shipped (ADR 13).
FROM python:3.13-slim@sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*   && pip install --no-cache-dir ruff==0.17.5 yamllint==1.38.0   && git config --system --add safe.directory '*'
WORKDIR /src
