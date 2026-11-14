# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# One image for the three demo MCP servers and the scripted agent. Build context: repository root.
FROM node:24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54 AS build
WORKDIR /demo
COPY demo/package.json demo/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY demo/ ./
RUN npx tsc -p tsconfig.json && npm prune --omit=dev

FROM node:24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54
WORKDIR /demo
COPY --from=build /demo/package.json ./
COPY --from=build /demo/node_modules node_modules
COPY --from=build /demo/dist dist
COPY demo/scenarios scenarios
ENV BIND=0.0.0.0
USER node
