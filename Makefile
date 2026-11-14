# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# Docker and make are the only prerequisites: every tool below runs in a pinned container.

SHELL := /bin/sh
.DEFAULT_GOAL := help
# Git Bash on Windows rewrites paths such as /src unless this is set.
export MSYS_NO_PATHCONV := 1

include scripts/images.env

COMPOSE := docker compose
NETWORK := aduana_default
LINT_IMAGE := aduana-lint:local
RUN := docker run --rm -v "$(CURDIR)":/src -w /src
SAFE_GIT := -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=*
MAVEN := docker run --rm -v "$(CURDIR)":/src -v aduana-m2:/root/.m2 \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	-e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
	--add-host=host.docker.internal:host-gateway \
	-w /src $(JDK_IMAGE) ./mvnw -B -q
PYTHON := $(RUN) $(PYTHON_IMAGE) python
LINT := $(RUN) $(SAFE_GIT) $(LINT_IMAGE)
NODE := docker run --rm -v "$(CURDIR)":/src -v aduana-npm:/root/.npm -w /src/demo $(NODE_IMAGE)
SCENARIOS := scenarios/support-happy-path.yaml scenarios/injected-refund-direct.yaml
DIRECT_URLS := crm=http://crm:3001/mcp,payments=http://payments:3002/mcp,webfetch=http://webfetch:3003/mcp

.PHONY: help up down logs smoke lint lint-fast lint-image test test-scripts test-java \
	test-policy test-audit test-gateway test-demo demo-direct e2e hooks secrets

help: ## List every target
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "} {printf "  %-14s %s\n", $$1, $$2}'

.env:
	cp .env.example .env

up: .env ## Build and start the stack, then wait until every service is healthy
	$(COMPOSE) up -d --build --wait --wait-timeout 180

down: ## Stop the stack and remove its containers
	$(COMPOSE) down

logs: ## Follow the logs of the stack
	$(COMPOSE) logs -f

smoke: ## Probe the running stack from inside its network
	docker run --rm --network $(NETWORK) -v "$(CURDIR)":/src -w /src $(PYTHON_IMAGE) python scripts/smoke.py http://gateway:8080

lint-image:
	docker build -q -t $(LINT_IMAGE) build-config/lint > /dev/null

lint: lint-image ## Run every static check: authorship, commits, formats, secrets, licences
	$(LINT) python scripts/check_authorship.py
	$(LINT) python scripts/check_commits.py --range HEAD
	$(LINT) python scripts/check_pinned_images.py
	$(LINT) ruff check scripts
	$(LINT) ruff format --check scripts
	$(LINT) yamllint -s -c build-config/yamllint.yaml .
	$(RUN) $(SAFE_GIT) $(EDITORCONFIG_IMAGE)
	$(RUN) $(SAFE_GIT) $(GITLEAKS_IMAGE) git --redact --no-banner /src
	sh scripts/check_licenses.sh
	$(MAVEN) spotless:check checkstyle:check
	$(NODE) sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm run --silent lint'

lint-fast: ## Secrets in the staged diff and authorship of staged files (the pre-commit hook)
	sh scripts/lint_fast.sh

secrets: ## Scan the whole git history for secrets
	$(RUN) $(SAFE_GIT) $(GITLEAKS_IMAGE) git --redact --no-banner /src

test: test-scripts test-java test-demo ## Run every test suite

test-scripts: ## Test the repository scripts
	$(PYTHON) -m unittest discover -s scripts -p 'test_*.py'

test-java: ## Test every Java module
	$(MAVEN) verify

test-policy: ## Test the policy module
	$(MAVEN) -pl policy verify

test-audit: ## Test the audit module
	$(MAVEN) -pl audit verify

test-gateway: ## Test the gateway and the modules it depends on
	$(MAVEN) -pl gateway -am verify

test-demo: ## Contract-test the demo MCP servers and the scripted agent
	$(NODE) sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm test'

demo-direct: ## Run the scenarios straight against the demo servers, without Aduana
	$(COMPOSE) up -d --wait --force-recreate crm payments webfetch
	$(COMPOSE) run --rm agent --direct $(DIRECT_URLS) $(SCENARIOS)

e2e: ## Run the scenarios through the gateway, in 2026-07-28 and in 2025-11-25 mode
	$(COMPOSE) up -d --wait --force-recreate crm payments webfetch
	$(COMPOSE) run --rm agent --gateway http://gateway:8080/mcp $(SCENARIOS)
	$(COMPOSE) up -d --wait --force-recreate payments
	$(COMPOSE) run --rm agent --legacy --gateway http://gateway:8080/mcp $(SCENARIOS)

hooks: ## Use the repository git hooks
	git config core.hooksPath .githooks
