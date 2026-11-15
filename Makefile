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
CONSOLE_NODE := docker run --rm -v "$(CURDIR)":/src -v aduana-npm:/root/.npm -w /src/console \
	-e NG_CLI_ANALYTICS=false $(NODE_IMAGE)
SCENARIOS := scenarios/support-happy-path.yaml scenarios/injected-refund-direct.yaml
GATEWAY_SCENARIOS := scenarios/gateway/support-through-aduana.yaml scenarios/gateway/injected-refund-through-aduana.yaml
CLI := docker run --rm -v "$(CURDIR)":/src -w /src $(JDK_IMAGE) java -jar cli/target/aduana-cli.jar
GATEWAY_MCP := http://gateway:8080/mcp
SEED := docker run --rm --network $(NETWORK) --env-file .env -v "$(CURDIR)":/src -w /src \
	$(PYTHON_IMAGE) python scripts/demo_seed.py http://gateway:8080
DIRECT_URLS :=crm=http://crm:3001/mcp,payments=http://payments:3002/mcp,webfetch=http://webfetch:3003/mcp

.PHONY: help up down logs smoke lint lint-fast lint-image test test-scripts test-java \
	test-policy test-audit test-gateway test-demo test-console console-e2e demo attacks attacks-check bench test-cli test-verifier audit-export audit-verify policy-test bench-policy mutation demo-direct e2e hooks secrets

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
	$(LINT) python scripts/check_readme_numbers.py
	$(LINT) ruff check scripts verifier
	$(LINT) ruff format --check scripts verifier
	$(LINT) yamllint -s -c build-config/yamllint.yaml .
	$(RUN) $(SAFE_GIT) $(EDITORCONFIG_IMAGE)
	$(RUN) $(SAFE_GIT) $(GITLEAKS_IMAGE) git --redact --no-banner /src
	sh scripts/check_licenses.sh
	$(MAVEN) spotless:check checkstyle:check
	$(NODE) sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm run --silent lint'
	$(CONSOLE_NODE) sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm run --silent lint'

lint-fast: ## Secrets in the staged diff and authorship of staged files (the pre-commit hook)
	sh scripts/lint_fast.sh

secrets: ## Scan the whole git history for secrets
	$(RUN) $(SAFE_GIT) $(GITLEAKS_IMAGE) git --redact --no-banner /src

test: test-scripts test-java test-demo test-console test-verifier ## Run every test suite

test-scripts: ## Test the repository scripts
	$(PYTHON) -m unittest discover -s scripts -p 'test_*.py'

test-java: ## Test every Java module
	$(MAVEN) verify

test-policy: ## Test the policy module
	$(MAVEN) -pl policy verify

test-audit: ## Test the audit module
	$(MAVEN) -pl audit verify

test-verifier: ## Test the independent Python verifier (standard library only)
	$(RUN) $(PYTHON_IMAGE) python -m unittest discover -s verifier -p 'test_*.py'

audit-export: ## Export the running gateway's log, checkpoints and key to out/audit/
	mkdir -p out/audit
	$(MAVEN) -pl cli -am -DskipTests -Djacoco.skip=true package
	docker run --rm --network $(NETWORK) --env-file .env -v "$(CURDIR)":/src -w /src $(JDK_IMAGE) 		java -jar cli/target/aduana-cli.jar audit export --url http://gateway:8080 		-o out/audit/entries.jsonl --checkpoints-out out/audit/checkpoints.jsonl --key-out out/audit/key.json

audit-verify: audit-export ## Verify the running gateway's log with both verifiers and scan it for values in clear
	$(CLI) audit verify --export out/audit/entries.jsonl --checkpoints out/audit/checkpoints.jsonl --key out/audit/key.json
	$(RUN) $(PYTHON_IMAGE) python verifier/verify_chain.py out/audit/entries.jsonl --checkpoints out/audit/checkpoints.jsonl
	$(RUN) $(PYTHON_IMAGE) python scripts/check_log_secrets.py out/audit/entries.jsonl

test-cli: ## Test the aduana command line
	$(MAVEN) -pl cli -am verify

policy-test: ## Validate the demo policy and run its test cases with the aduana CLI
	$(MAVEN) -pl cli -am -DskipTests package
	$(CLI) policy validate demo/policy
	$(CLI) policy test demo/policy

bench-policy: ## Time the evaluator on a 200-rule policy; writes policy/target/policy-benchmark.txt
	$(MAVEN) -pl policy -Dtest.excludedGroups= -Dgroups=benchmark -Djacoco.skip=true test
	cat policy/target/policy-benchmark.txt

mutation: ## Run PIT mutation tests on the policy and audit modules (threshold 80%)
	$(MAVEN) -pl policy,audit test-compile org.pitest:pitest-maven:mutationCoverage

test-gateway: ## Test the gateway and the modules it depends on
	$(MAVEN) -pl gateway -am verify

test-demo: ## Contract-test the demo MCP servers and the scripted agent
	$(NODE) sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm test'

demo-direct: ## Run the scenarios straight against the demo servers, without Aduana
	$(COMPOSE) up -d --wait --force-recreate crm payments webfetch
	$(COMPOSE) run --rm agent --direct $(DIRECT_URLS) $(SCENARIOS)

e2e: ## Run the scenarios through the gateway, in 2026-07-28 and in 2025-11-25 mode
	$(COMPOSE) up -d --wait --force-recreate crm payments webfetch
	KEY=$$($(SEED)) && sh scripts/e2e_round.sh "$$KEY" && \
	$(COMPOSE) up -d --wait --force-recreate payments && sh scripts/e2e_round.sh "$$KEY" --legacy

test-console: ## Unit-test the console (70 % line coverage) and build it
	$(CONSOLE_NODE) sh -c 'npm ci --no-audit --no-fund --loglevel=error && npm test && npm run --silent build'

demo: ## Seed the running stack and run the scripted scenarios through it, leaving data in the console
	$(COMPOSE) up -d --wait crm payments webfetch
	KEY=$$($(SEED)) && sh scripts/e2e_round.sh "$$KEY"

console-e2e: ## Run the Playwright console flows and axe against the running stack; saves docs/images
	KEY=$$($(SEED)) && docker run --rm --network $(NETWORK) --hostname console-e2e --env-file .env \
	-e AGENT_KEY="$$KEY" -e CONSOLE_URL=http://gateway:8080 -e STUB_HOST=console-e2e \
	-e SCREENSHOTS=/src/docs/images -v "$(CURDIR)":/src -w /src/console $(PLAYWRIGHT_IMAGE) \
	sh -c 'test -d node_modules || npm ci --no-audit --no-fund --loglevel=error; npx playwright test'

attacks: ## Run the attack bench against the running stack and rewrite attacks/REPORT.md
	$(COMPOSE) up -d --wait --force-recreate crm payments webfetch
	$(MAVEN) -pl cli -am -DskipTests package
	docker run --rm --network $(NETWORK) --hostname attacks-runner --env-file .env -v "$(CURDIR)":/src -w /src \
	$(JDK_IMAGE) java -jar cli/target/aduana-cli.jar attacks run --url http://gateway:8080 \
	--db-url jdbc:postgresql://postgres:5432/aduana --stub-host attacks-runner

attacks-check: attacks ## Fail when the committed attack report differs from a fresh run
	git diff --exit-code -- attacks/REPORT.md

bench: ## Measure the latency Aduana adds at 50, 200 and 500 requests per second; writes bench/REPORT.md
	$(COMPOSE) -f compose.yaml -f bench/compose.bench.yaml up -d --wait --force-recreate gateway crm
	docker run --rm --network $(NETWORK) --env-file .env -v "$(CURDIR)":/src -w /src \
	$(JDK_IMAGE) java bench/Bench.java
	$(COMPOSE) up -d --wait --force-recreate gateway

hooks: ## Use the repository git hooks
	git config core.hooksPath .githooks
