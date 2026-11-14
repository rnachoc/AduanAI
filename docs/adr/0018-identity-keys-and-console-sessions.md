# ADR 0018: API keys, console sessions, Argon2id and the master key

- Status: accepted
- Date: 2026-11-14

## Context

Phase 3 adds identities. Agents call `/mcp` with a credential; people use the console through
`/api/v1`. Plan §5.9 asks for Argon2id password hashes ("verify library") and §4.3 for AES-256-GCM
credential encryption under a master key from the environment.

## Decision

- **API keys**: `adn_` + 8 visible characters + 32 secret characters (192 random bits), shown once.
  Only the SHA-256 is stored; a plain hash is enough because the key has full entropy. Keys expire
  and can be revoked. The lookup index on the hash cannot start with `tenant_id`, because the tenant
  is unknown until the key is found; this is the one documented exception to that rule (with the
  e-mail index used at sign-in).
- **Agents only on `/mcp`**: a principal of kind `user` is refused there even with a valid key, so a
  console identity can never call tools and an agent identity can never approve (CA6.3 later).
- **OIDC**: when `aduana.oidc.issuer-uri` is set, a bearer that is not an Aduana key is validated as a
  JWT against the issuer's JWKS (Spring Security OAuth2 Resource Server) and its `sub` names the agent.
- **Console**: `POST /api/v1/auth/login` puts the user in the HTTP session (`ADUANA_SESSION`,
  `HttpOnly`, `SameSite=Strict`); writes need the CSRF token from the `XSRF-TOKEN` cookie echoed in
  `X-XSRF-TOKEN`. The session id changes at sign-in. Roles nest: admin ⊃ approver ⊃ viewer.
- **Argon2id** via Spring Security's `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`, which
  "requires BouncyCastle" (https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html,
  read 2026-11-14). New dependency `org.bouncycastle:bcprov-jdk18on` 1.87, Bouncy Castle Licence (MIT
  style), checked on https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/ on 2026-11-14.
  An unknown e-mail still costs one Argon2 verification, so timing does not reveal accounts.
- **Master key**: `ADUANA_MASTER_KEY` must be base64 of 32 bytes. The literal `local-only` selects a
  fixed key and logs a warning, so `.env.example` can stay free of real secrets (principle 9). The
  GCM associated data is `tenant_id/server_id`, so a ciphertext moved to another row does not open.
- **Flyway** comes from the Spring Boot 4.1.2 dependency management (12.4.0) rather than the newest
  release named in ADR 1 (13.12.2): Boot's auto-configuration is built and tested against its managed
  version. The same applies to the PostgreSQL driver (42.7.14) and Testcontainers (2.0.5).

## Consequences

The plan's dependency list gains BouncyCastle (recorded here, as the owner asked not to be stopped).
Rotating the master key needs a re-encryption job, not written yet.

## Alternatives considered

PBKDF2 from the JDK (no new dependency) was rejected because the plan names Argon2id. Storing keys
with Argon2: pointless for full-entropy keys and slow on every call.
