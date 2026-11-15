# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
# Builds the gateway and the console from source and runs them as a non-root user on a JRE-only image.
FROM node:24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54 AS console
WORKDIR /console
COPY console/package.json console/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund --loglevel=error
COPY console/ ./
RUN NG_CLI_ANALYTICS=false npx ng build

FROM eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY build-config build-config
COPY policy policy
COPY audit audit
COPY gateway gateway
COPY cli cli
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests -pl gateway -am package   && java -Djarmode=tools -jar gateway/target/aduana-gateway.jar extract --destination /out/app

FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin aduana
COPY --from=build /out/app /opt/aduana
COPY --from=console /console/dist/browser /app/console
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=5   CMD ["java", "-cp", "/opt/aduana/aduana-gateway.jar", "io.github.rnachoc.aduana.gateway.HealthProbe"]
ENTRYPOINT ["java", "-jar", "/opt/aduana/aduana-gateway.jar"]
