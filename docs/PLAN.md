# PLAN A — Aduana: pasarela de control para agentes de IA

> Documento único y autosuficiente. Contiene: (1) el plan de desarrollo para Opus 5.5, (2) las revisiones posteriores con sus mínimos, (3) el plan de comercialización.
> Fecha de redacción: 12-11-2026. Autor del plan: Ignacio Rodríguez Caro.
> Nombre provisional del producto: **Aduana** (identificador técnico `aduana`). Antes de crear el repo, comprobar que el nombre está libre en GitHub, npm, Docker Hub y como dominio `.dev`/`.io`. Alternativas si no lo está: `Garita`, `Peaje`, `Fielato`.

---

## 0. Cómo usar este documento

### 0.1 Para Nacho

1. Crea la carpeta `C:\Users\Nacho\Documents\aduana` y copia dentro este archivo como `docs/PLAN.md`.
2. Abre una sesión de Claude Code con Opus 5.5 en esa carpeta y pega el **prompt de arranque** del apartado 0.3.
3. Trabaja **una fase por sesión**. Al terminar cada fase, revisa la puerta de la fase (apartado 10), y solo entonces abre sesión nueva para la siguiente con el **prompt de fase** del apartado 0.4.
4. Cuando la fase 12 esté cerrada, ejecuta las revisiones del apartado 13. Hasta que no pasen los mínimos, no se publica ni se vende nada.
5. Con las revisiones pasadas, sigue el plan comercial del apartado 14.

### 0.2 Para Opus 5.5 (reglas de lectura)

- Este documento es la especificación. Si algo del documento contradice lo que recuerdas de memoria, gana el documento. Si el documento contradice la documentación oficial vigente de una herramienta o de un estándar, gana la documentación oficial, y lo anotas en un ADR.
- **No inventes**: ni APIs, ni parámetros, ni versiones, ni cifras. Lo que no esté aquí ni en una fuente oficial que hayas leído en esta sesión, se pregunta a Nacho en una línea o se deja como "not measured" / "not decided".
- Todo lo que dice "verificar" en este documento es una tarea obligatoria, no una sugerencia: se lee la fuente oficial, se anota la URL y la fecha de consulta en `docs/adr/0001-verified-facts-and-pinned-versions.md`.
- Las reglas del archivo `CLAUDE.md` del repo (apartado 8.1) sustituyen, dentro de este proyecto, a las reglas globales de Nacho sobre "no añadir comentarios" y "esperar OK antes de escribir código". Aquí los comentarios de autoría son obligatorios y el plan ya está aprobado.
- Háblale a Nacho en español de España. Todo lo que queda en el repo va en inglés.

### 0.3 Prompt de arranque (fase 0)

```
Lee docs/PLAN.md entero antes de hacer nada. Es la especificación completa del proyecto Aduana.
Ejecuta únicamente la Fase 0 del apartado 10. No empieces la Fase 1.
Sigue al pie de la letra los apartados 8 (normas de código) y 9 (git).
Al terminar: ejecuta los comandos de verificación de la fase, actualiza PROGRESS.md y docs/HANDOFF.md,
y dime en español: qué has hecho, el resultado de cada comando de verificación y qué decisiones has
dejado anotadas en ADR. Si algo del plan no se puede cumplir, para y dímelo; no lo sustituyas por otra cosa.
```

### 0.4 Prompt de fase (fases 1 a 12)

```
Lee CLAUDE.md, PROGRESS.md y docs/HANDOFF.md. Después lee en docs/PLAN.md los apartados 3, 8, 9 y la Fase N del apartado 10.
Ejecuta únicamente la Fase N. Tests primero. No toques nada de fases posteriores.
Al terminar: comandos de verificación de la fase, `make lint`, `make test`, actualiza PROGRESS.md,
el README (tabla de estado) y docs/HANDOFF.md, y dame el resultado de cada criterio de aceptación uno por uno:
"cumplido" con la prueba que lo demuestra, o "no cumplido" con el motivo.
```

### 0.5 Cuándo debe parar Opus y preguntar

- Un criterio de aceptación no se puede cumplir tal como está escrito.
- Hace falta una credencial, una cuenta o un pago.
- La especificación oficial de MCP contradice un diseño de este documento.
- Hay que añadir una dependencia que no está en la lista del apartado 4.3.
- Una acción es destructiva o irreversible (borrar ramas, reescribir historia, publicar).

En cualquier otro caso, decide, anota la decisión en un ADR y sigue.

---

## 1. Producto

### 1.1 Problema

Las empresas conectan agentes de IA a sus sistemas a través de servidores MCP (CRM, bases de datos, pagos, correo). Hoy no pueden responder con pruebas a tres preguntas:

1. **¿Qué hizo exactamente cada agente?** Los registros están repartidos entre el cliente, el servidor y el proveedor del modelo, y cualquiera con acceso puede modificarlos.
2. **¿Qué impide que haga lo que no debe?** La única barrera suele ser el prompt del sistema, que una inyección puede saltarse.
3. **¿Quién aprobó la acción peligrosa?** La confirmación la pide el propio modelo, que es justo el componente que puede estar manipulado.

### 1.2 Qué es Aduana

Un proxy que se coloca entre los agentes y sus servidores MCP. Todo `tools/call` pasa por él. Para cada llamada, **código determinista** decide: permitir, denegar, exigir aprobación humana o permitir con datos redactados. Cada decisión queda en un registro encadenado por hash que cualquiera puede verificar sin confiar en Aduana.

Frase del producto (README, primera línea): *"A control gateway for AI agents: every tool call is checked by policy written in code, approved by a person when it matters, and recorded in a log that anyone can verify."*

Principio (se repite en README y ADR 2): **The model proposes, the code decides.**

### 1.3 Usuario y comprador

| Rol | Qué necesita | Qué usa de Aduana |
|---|---|---|
| Ingeniero de plataforma / de agentes | Dar herramientas a agentes sin escribir controles en cada servidor | Proxy, catálogo, políticas en YAML, CLI |
| Responsable de seguridad | Límites demostrables y registro inalterable | Políticas, banco de ataques, verificador |
| Responsable de operaciones | Aprobar lo sensible sin frenar lo demás | Cola de aprobaciones en la consola |
| Cumplimiento / auditoría | Evidencia exportable | Registro verificable, informes |

### 1.4 Competencia (contrastada el 12-11-2026) y hueco

Existen muchas pasarelas MCP: Docker MCP Gateway, Obot, MCPX (Lunar.dev), agentgateway, IBM ContextForge, Lasso, la de Microsoft, y las de Kong, Tyk y TrueFoundry. Lo que se ha podido contrastar en comparativas públicas (casi todas escritas por proveedores, por lo que hay que releer la documentación de cada uno en la Fase 0):

- Docker es fuerte en aislamiento por contenedor; el control por usuario y la auditoría de organización están en su plan de pago o hay que construirlos.
- Obot y MCPX son los más completos en acceso y auditoría en su versión abierta; la integración con proveedor de identidad en MCPX es de pago.
- agentgateway, ContextForge y Lasso documentan poco su auditoría y sus políticas.

**No competimos en "otra pasarela más".** El hueco que Aduana ocupa son cuatro cosas que, según lo contrastado, nadie ofrece juntas en abierto:

1. **Registro verificable por terceros**: cadena de hash con puntos de control firmados y un verificador independiente de menos de 200 líneas que no usa código de Aduana.
2. **Políticas que se prueban como código**: casos de prueba junto a la política, y simulación de una política nueva contra el tráfico real registrado ("qué habría bloqueado esta política la semana pasada").
3. **Aprobación ligada al contenido**: la aprobación vale para ese hash exacto de argumentos, una sola vez y con caducidad. Si el agente cambia un céntimo, hay que aprobar de nuevo.
4. **Seguimiento de datos no fiables**: si un valor que llegó en el resultado de una herramienta marcada como no fiable (web, correo) aparece después como argumento de una herramienta con efectos, la política puede exigir aprobación o denegar.

Y un quinto elemento de credibilidad: **un banco de ataques público con resultados medidos en cada build**.

Tarea obligatoria de Fase 0: releer la documentación de Obot, MCPX, Docker MCP Gateway y agentgateway y escribir `docs/comparison.md` con una tabla honesta (qué tienen ellos que Aduana no, y al revés), con URL y fecha de cada afirmación. Si alguno ya ofrece los cuatro puntos, se para y se avisa a Nacho.

### 1.5 Lo que Aduana NO es (alcance negativo)

- No es un cliente MCP ni un framework de agentes.
- No llama a ningún modelo de lenguaje. Cero coste de modelo, cero claves de modelo.
- No es un catálogo/marketplace de servidores MCP.
- No ejecuta servidores MCP de terceros en contenedores aislados (eso es Docker). Sí puede lanzar servidores stdio locales como proceso hijo (Fase 8).
- No es multi-región ni alta disponibilidad en la versión 1. Un nodo, una base de datos.

---

## 2. Repositorio, licencia y nombre

- Repo: `github.com/rnachoc/aduana`. **Privado** durante el desarrollo; se hace público al cerrar el apartado 13 con todo el historial (el historial enseña el método de trabajo).
- Licencia: **Apache-2.0** (cesión de patentes, la que piden los departamentos legales de empresa). Archivo `LICENSE` y `NOTICE`. Cabecera de licencia corta en cada archivo fuente (ver 8.3).
- Nombre en prosa: "Aduana". Identificadores técnicos en minúscula: `aduana`. Paquete Java base: `io.github.rnachoc.aduana`.
- Ninguna dependencia con licencia GPL, AGPL, SSPL o "source available". `make lint` lo comprueba (Fase 0).

---

## 3. Principios innegociables

Van en `CLAUDE.md`, en el README y cada uno tiene al menos un test que lo hace cumplir.

1. **El modelo propone, el código decide.** Ninguna decisión depende de texto generado por un modelo. Aduana no llama a modelos.
2. **Denegar por defecto.** Una llamada sin regla que la permita se deniega. Una herramienta que no está en el catálogo no existe para el agente.
3. **El agente nunca tiene las credenciales del sistema de destino.** Las inyecta Aduana.
4. **El registro es solo de añadir.** El rol de base de datos del servicio no tiene `UPDATE` ni `DELETE` sobre la tabla de auditoría, y un trigger lo impide incluso al propietario.
5. **Lo que no se registra, no se ejecuta.** Si falla la escritura en el registro, la llamada no se reenvía (fallo cerrado).
6. **Todo texto que vuelve de una herramienta es dato no fiable**, nunca instrucción.
7. **Ningún número sin medir.** Si no se ha medido, se escribe "not measured".
8. **Docker y make son los únicos requisitos** para levantar y probar todo.
9. **Sin secretos en el repo.** gitleaks en pre-commit y en CI, sobre el historial completo.
10. **`tenant_id` en todas las tablas desde el primer día**, aunque la versión abierta use un solo inquilino.

---

## 4. Arquitectura

### 4.1 Componentes

```
 Agentes / clientes MCP
        │  MCP (Streamable HTTP) + credencial de Aduana
        ▼
┌──────────────────────────── gateway (Java, Spring Boot) ────────────────────────────┐
│ 1 autenticar → 2 resolver principal → 3 catálogo (filtrar tools/list)               │
│ 4 validar argumentos (JSON Schema) → 5 marcar flujo no fiable (taint)               │
│ 6 política (ALLOW / DENY / REQUIRE_APPROVAL + obligaciones) → 7 límites             │
│ 8 aprobación (si procede) → 9 registrar intención → 10 reenviar con credencial      │
│ 11 inspeccionar resultado (redacción, marca de inyección, taint) → 12 registrar     │
└───────┬───────────────────────────┬──────────────────────────────┬─────────────────┘
        │                           │                              │
        ▼                           ▼                              ▼
  Servidores MCP              PostgreSQL                     console (Angular)
  (HTTP o stdio)       catálogo · políticas · registro    aprobaciones · registro
                       aprobaciones · taint · límites     políticas · simulación

  cli (aduana): policy test · policy simulate · audit verify · audit export
  verifier/ (Python, solo biblioteca estándar): verificación independiente de la cadena
  demo/: 3 servidores MCP falsos + agente guionizado + banco de ataques
```

### 4.2 Módulos del repo

| Carpeta | Lenguaje | Contenido |
|---|---|---|
| `policy/` | Java, sin Spring | Biblioteca pura: modelo de política, evaluador, obligaciones, casos de prueba de política. Sin E/S. |
| `audit/` | Java, sin Spring | Biblioteca pura: forma canónica, cadena de hash, puntos de control firmados, verificación. |
| `gateway/` | Java, Spring Boot | Servidor: extremo MCP, conectores a servidores de destino, tubería, API REST de administración, persistencia. Depende de `policy` y `audit`. |
| `cli/` | Java | Comando `aduana` (subcomandos del apartado 6.4). Se empaqueta en la misma imagen. |
| `console/` | TypeScript, Angular | Consola web. Solo habla con la API REST de administración. |
| `verifier/` | Python 3, solo stdlib | `verify_chain.py`: verificador independiente. |
| `demo/` | TypeScript (SDK oficial de MCP) | Servidores MCP falsos y agente guionizado. |
| `attacks/` | YAML + runner en Java | Banco de ataques y su ejecutor. |
| `bench/` | k6 o equivalente | Medición de latencia añadida. |
| `docs/` | Markdown | ADR, arquitectura, modelo de amenazas, comparativa, caso de estudio. |
| `deploy/` | YAML | `compose.yaml` de producción mínima; Helm opcional (Fase 12). |

### 4.3 Stack y dependencias permitidas

Regla de versiones: en la Fase 0 se comprueba la versión **estable vigente** de cada pieza en su web oficial, se fija (versión exacta o digest) y se anota en el ADR 1. No se usan versiones candidatas ni de memoria.

| Pieza | Elección | Nota |
|---|---|---|
| Lenguaje servidor | Java, LTS vigente (verificar; a fecha de redacción debería ser Java 25) | Hilos virtuales para E/S de proxy |
| Framework | Spring Boot estable vigente (verificar) | Web MVC con hilos virtuales; no WebFlux |
| Build Java | Maven, multi-módulo, con wrapper | |
| Protocolo | MCP revisión **2026-07-28** como objetivo; compatibilidad de lectura con **2025-11-25** | Ver 4.4 |
| SDK MCP | SDK oficial de Java de `modelcontextprotocol` solo si soporta 2026-07-28 (verificar); si no, implementación propia mínima del subconjunto del apartado 6.1 | Decisión en ADR 4 |
| Base de datos | PostgreSQL estable vigente | Flyway para migraciones |
| Acceso a datos | Spring JDBC (`JdbcClient`), SQL explícito. Sin JPA | El registro necesita control exacto del SQL |
| JSON | Jackson | |
| JSON Schema | `networknt/json-schema-validator` (verificar licencia y versión) | Validación de argumentos |
| Canonicalización | RFC 8785 (JCS). Implementación propia de ≤150 líneas con tests de los vectores del RFC, o biblioteca con licencia permisiva (verificar) | ADR 6 |
| Firma | Ed25519 del JDK (`java.security`) | Sin BouncyCastle salvo necesidad anotada |
| Cifrado de credenciales | AES-256-GCM del JDK, clave maestra por variable de entorno | |
| JWT/OIDC | Spring Security OAuth2 Resource Server | Validación con JWKS |
| Tests Java | JUnit 5, AssertJ, Testcontainers (PostgreSQL), PIT (mutación) | |
| Consola | Angular estable vigente, componentes standalone, sin biblioteca de UI externa | CSS propio, un solo tipo de letra servido desde el repo |
| Tests consola | Los que traiga Angular por defecto + Playwright para 5 flujos | |
| Demo | TypeScript + SDK oficial de MCP para TypeScript | |
| Verificador | Python 3, **solo biblioteca estándar** para la cadena; firma opcional con `cryptography` | |
| Carga | k6 (verificar licencia; si es AGPL, se usa solo como herramienta externa en CI, no se distribuye) | |
| CI | GitHub Actions | |
| Calidad | Spotless + Checkstyle (Java), ESLint + Prettier (TS), ruff (Python), yamllint, editorconfig-checker, gitleaks | |

Prohibido sin ADR y sin permiso de Nacho: Kafka, Redis, Temporal, Kubernetes obligatorio, Lombok, MapStruct, cualquier ORM, cualquier llamada a un modelo.

### 4.4 Hechos del protocolo que hay que verificar antes de escribir código

Contrastado el 12-11-2026: la revisión vigente de MCP es **2026-07-28**, la primera desde 2025-11-25. Cambios relevantes según el registro de cambios oficial y cobertura secundaria:

- El núcleo es **sin estado**: desaparecen el saludo `initialize` y la cabecera `Mcp-Session-Id`; versión y capacidades viajan en `_meta` en cada petición.
- Los servidores deben implementar `server/discover`.
- HTTP+SSE queda clasificado como obsoleto; el transporte es Streamable HTTP.
- Cambios de autorización (SEP-2352, SEP-2468: validación de `iss`).
- Aparecen "Tasks" y peticiones de varias idas y vueltas (MRTR); Roots, Sampling y Logging quedan en retirada.

Opus debe leer en la Fase 0, completos: `modelcontextprotocol.io/specification/2026-07-28` (índice, transporte, autorización, herramientas, registro de cambios). Lo anterior es un resumen de segunda mano y puede tener errores: si la especificación dice otra cosa, gana la especificación.

Consecuencia de diseño: al no haber sesión, Aduana identifica el "contexto" de un agente por `principal_id` más, si viene, un identificador de traza en `_meta`; si no viene, por ventana temporal (apartado 5.6).

---

## 5. Diseño detallado

### 5.1 Tubería de una llamada `tools/call`

Orden fijo. Cada paso tiene su clase y su test. Un fallo en cualquier paso produce una entrada de registro y una respuesta de error MCP con código estable (apartado 6.3).

1. **Autenticar**: clave de API de Aduana (`Authorization: Bearer adn_…`) o JWT OIDC. Sin credencial válida → 401, entrada `AUTH_FAILED`.
2. **Resolver principal**: `principal_id`, `tenant_id`, atributos (`team`, `env`, etiquetas libres).
3. **Catálogo**: el nombre de herramienta que ve el agente es `<servidor>__<herramienta>`. Si no existe o está deshabilitada → `TOOL_NOT_FOUND` (mismo error para "no existe" y "no tienes acceso").
4. **Validar argumentos** contra el `inputSchema` guardado en el catálogo. Campos desconocidos se rechazan si la política del servidor dice `strict: true` (por defecto sí).
5. **Calcular marcas**: `args_hash` (SHA-256 de la forma canónica de los argumentos), `flow_from_untrusted` (5.6), `context_tainted` (5.6), detección de datos sensibles en argumentos (5.7).
6. **Política**: produce decisión + obligaciones + lista de reglas que casaron (5.2).
7. **Límites**: frecuencia por principal y por herramienta, presupuesto por ventana, clave de idempotencia (5.8).
8. **Aprobación** si la decisión es `REQUIRE_APPROVAL` (5.4).
9. **Registrar intención**: entrada `CALL_DECIDED`. Si la escritura falla → no se reenvía, error `AUDIT_UNAVAILABLE`.
10. **Reenviar** al servidor de destino con la credencial inyectada y un tiempo límite.
11. **Inspeccionar resultado**: redacción según obligaciones, marca de patrones de inyección, alimentar el almacén de valores no fiables si la herramienta es `untrusted_source`.
12. **Registrar resultado**: entrada `CALL_COMPLETED` o `CALL_FAILED` con `result_hash`, marcas y latencia.

`tools/list` y `server/discover` se responden desde el catálogo, filtrado por lo que la política permite ver al principal. Lo que el agente no puede usar, no lo ve.

### 5.2 Política

Formato: YAML declarativo, **sin lenguaje de expresiones**. Se valida contra un JSON Schema publicado en `policy/schema/policy.schema.json`. Un "bundle" es un directorio con `policy.yaml` y `tests/*.yaml`; su identidad es el SHA-256 de su forma canónica.

```yaml
version: 1
defaults:
  decision: deny            # obligatorio y solo puede ser "deny"
rules:
  - id: crm-read
    description: Support agents may read CRM records.
    match:
      principal: { attributes: { team: support } }
      tool: { server: crm, name: { in: [get_customer, search_customers] } }
    effect: allow
    obligations:
      redact_result: [email, phone_es, dni_nie, iban]

  - id: refunds-need-a-person
    match:
      tool: { server: payments, name: refund }
    effect: require_approval
    approval: { approvers_group: finance, expires_in: 15m }

  - id: big-refunds-denied
    match:
      tool: { server: payments, name: refund }
      args:
        - { path: "$.amount_cents", gt: 50000 }
    effect: deny
    reason_code: REFUND_OVER_LIMIT

  - id: untrusted-flow-into-side-effects
    match:
      tool: { side_effect: true }
      context: { flow_from_untrusted: true }
    effect: require_approval
```

Semántica exacta (cada punto con su test):

- Se evalúan **todas** las reglas. No hay "primera que casa".
- Precedencia de efectos: `deny` > `require_approval` > `allow`. Si ninguna casa → `deny` con `reason_code: NO_MATCHING_RULE`.
- Las obligaciones de todas las reglas `allow` y `require_approval` que casan se unen.
- Comparadores permitidos en `args`: `eq`, `ne`, `in`, `not_in`, `gt`, `gte`, `lt`, `lte`, `matches` (expresión regular con tiempo de evaluación acotado y longitud máxima; se usa un motor sin retroceso catastrófico o se limita la entrada: ADR), `exists`, `length_lte`. `path` es un subconjunto de JSONPath: solo `$`, `.campo`, `[n]`, `[*]`. Con `[*]` la condición debe cumplirse para todos los elementos salvo `any: true`.
- Tipos: comparar número con cadena es error de política detectado al cargar si el esquema de la herramienta lo permite saber; en ejecución, tipo inesperado → la condición no casa y se registra `POLICY_TYPE_MISMATCH`.
- Una política que no valida contra el esquema, o con `id` repetidos, o con `defaults.decision` distinto de `deny`, no se carga y la anterior sigue activa.
- El evaluador es una función pura: `decide(PolicyBundle, CallContext) → Decision`. Sin reloj, sin E/S, sin aleatoriedad. El reloj y los contadores entran en `CallContext`.

Casos de prueba de política (`tests/*.yaml`), ejecutados por `aduana policy test`:

```yaml
- name: a support agent cannot refund
  given:
    principal: { attributes: { team: support } }
    tool: { server: payments, name: refund }
    args: { amount_cents: 1000, order_id: "A1" }
  expect: { decision: require_approval, rules: [refunds-need-a-person] }
```

### 5.3 Catálogo

Por cada servidor de destino: `name`, transporte (`http` o `stdio`), URL o comando, credencial cifrada, `strict`, estado. Por cada herramienta: nombre, `inputSchema` y descripción tal como los publica el servidor, hash de esa definición, y metadatos que pone el administrador: `side_effect` (bool), `untrusted_source` (bool), `sensitivity` (`low|medium|high`), `enabled`.

**Fijación de definiciones**: al dar de alta un servidor se guarda el hash de cada definición de herramienta. Si el servidor cambia después una descripción o un esquema, la herramienta queda en estado `changed_pending_review`, deja de ofrecerse y se registra `TOOL_DEFINITION_CHANGED`. Esto para el ataque en que un servidor cambia la descripción de una herramienta para inyectar instrucciones.

Las descripciones de herramientas son texto de terceros: se pasan por el detector de patrones de inyección al darlas de alta y se muestran marcadas en la consola.

### 5.4 Aprobación en dos fases

- Una decisión `REQUIRE_APPROVAL` crea un `approval` con: `approval_id`, `binding_hash = SHA-256(tenant_id ‖ principal_id ‖ tool ‖ args_hash ‖ policy_bundle_hash)`, grupo aprobador, caducidad, estado `pending`.
- **Modo `hold`** (por defecto): la llamada espera hasta `hold_timeout` (por defecto 60 s) a que alguien decida. Aprobada → continúa en el paso 9. Rechazada → error `APPROVAL_REJECTED`. Sin decisión → respuesta `APPROVAL_PENDING` con el `approval_id`.
- **Reintento**: si el agente repite la llamada con exactamente los mismos argumentos, el `binding_hash` coincide y se usa la aprobación existente: si ya está aprobada y sin usar, se consume y se ejecuta; si sigue pendiente, vuelve a esperar; si caducó o se usó, se crea otra.
- Una aprobación se consume **una sola vez**, de forma atómica (`UPDATE … WHERE state='approved' RETURNING`, dentro de la misma transacción que la entrada de registro).
- Quien aprueba ve los argumentos **completos y redactados según política**, la herramienta, el principal, las reglas que casaron y las marcas (flujo no fiable, datos sensibles). Nunca ve texto libre del agente como justificación: no existe ese campo.
- Un principal de tipo agente no puede aprobar. Solo usuarios de consola del grupo indicado. Quien creó o modificó la regla puede aprobar (no hay separación de funciones en v1; anotado en "What it does not do").
- En la Fase 0 se evalúa si el mecanismo "Tasks" de la especificación 2026-07-28 encaja mejor que `hold`. Decisión en ADR 7. Si no está claro, se implementa `hold` y se deja Tasks como trabajo futuro.

### 5.5 Registro verificable

Entrada (objeto JSON, campos en este orden lógico; el orden real lo fija la canonicalización):

| Campo | Tipo | Nota |
|---|---|---|
| `seq` | entero | Consecutivo por inquilino, sin huecos |
| `tenant_id` | uuid | |
| `ts` | cadena | UTC, RFC 3339, microsegundos |
| `type` | cadena | Lista cerrada del apartado 6.3 |
| `principal_id` | uuid o null | |
| `server`, `tool` | cadena o null | |
| `args_hash` | hex o null | |
| `args_redacted` | objeto o null | Argumentos tras redacción; nunca en claro si hay datos sensibles |
| `decision` | cadena o null | |
| `rule_ids` | lista | |
| `reason_code` | cadena o null | |
| `policy_bundle_hash` | hex | |
| `approval_id` | uuid o null | |
| `actor_id` | uuid o null | Usuario de consola en eventos de administración |
| `result_hash` | hex o null | |
| `flags` | lista | `INJECTION_PATTERN`, `REDACTED`, `FLOW_FROM_UNTRUSTED`, … |
| `latency_ms` | entero o null | |
| `prev_hash` | hex | 64 ceros en la primera entrada |
| `hash` | hex | `SHA-256(prev_hash_bytes ‖ JCS(entrada sin el campo hash))` |

Reglas:

- Inserción serializada por inquilino (bloqueo consultivo de PostgreSQL por `tenant_id` o fila "cabeza de cadena" con `SELECT … FOR UPDATE`). Test de concurrencia con 50 hilos: cero huecos, cero bifurcaciones.
- **Puntos de control**: cada N entradas o T minutos (configurable) se inserta una fila en `audit_checkpoint` con `seq`, `hash` y firma Ed25519 de `(tenant_id, seq, hash, ts)`. La clave pública se publica en `GET /.well-known/aduana-audit-key`. Los puntos de control se pueden exportar a un archivo o a un cubo externo para que un borrado completo de la base también se detecte.
- También se registran los cambios de administración: alta/baja de servidor, cambio de metadatos de herramienta, carga de política, creación/revocación de clave, decisión de aprobación.
- `aduana audit verify` recorre la cadena y los puntos de control. `verifier/verify_chain.py` hace lo mismo sobre un export JSONL sin compartir código. Ambos deben detectar: edición, borrado, reordenación, inserción, cambio de fecha y truncado del final (esto último, solo con puntos de control exportados).
- Rol de base de datos `aduana_app`: `INSERT` y `SELECT` sobre `audit_entry` y `audit_checkpoint`; sin `UPDATE`, `DELETE` ni `TRUNCATE`. Trigger `BEFORE UPDATE OR DELETE` que lanza excepción. Test que lo intenta con el rol propietario y falla.

### 5.6 Datos no fiables (taint)

- Cuando una herramienta con `untrusted_source: true` devuelve un resultado, se extraen "valores rastreables": URL, direcciones de correo, IBAN, números de más de 8 dígitos y cadenas de 24 caracteres o más sin espacios. Se guarda el **SHA-256 de cada valor normalizado** (no el valor) en `taint_token` con `tenant_id`, `principal_id`, `trace_key` y caducidad (por defecto 30 minutos).
- `trace_key` = identificador de traza de `_meta` si el cliente lo envía; si no, cadena vacía (ámbito: todo el principal durante la ventana).
- En el paso 5, se extraen los mismos tipos de valores de los argumentos. Si alguno coincide con un `taint_token` vigente → `flow_from_untrusted = true`, y la entrada de registro indica qué ruta del argumento y de qué llamada previa (por `seq`) venía.
- `context_tainted = true` si el principal ha recibido cualquier resultado de fuente no fiable dentro de la ventana.
- Limitación que se escribe en el README: detecta copia literal de valores, no paráfrasis. Es una señal para la política, no una garantía.

### 5.7 Redacción y detección de datos sensibles

Detectores deterministas, cada uno con comprobación de validez para no marcar de más:

| Id | Detecta | Validación |
|---|---|---|
| `email` | Correo | Sintaxis |
| `phone_es` | Teléfono español | Prefijos y longitud |
| `dni_nie` | DNI / NIE | Letra de control |
| `iban` | IBAN | Módulo 97 |
| `card` | Tarjeta | Luhn + longitud |
| `secret` | Claves y tokens | Patrones conocidos (se reutiliza el conjunto de reglas de gitleaks si su licencia lo permite: verificar) + entropía |

- En resultados: `redact_result` sustituye por `[REDACTED:<id>]` antes de devolver al agente.
- En argumentos: `deny_args_containing: [card, secret]` deniega; `flag_args_containing` solo marca.
- El registro guarda siempre la versión redactada de argumentos; el hash se calcula sobre los originales.

### 5.8 Límites

- Frecuencia por `(principal, herramienta)` y por `principal`: ventana deslizante en PostgreSQL (tabla `rate_bucket`), suficiente para un nodo. ADR 9 explica por qué no Redis.
- Presupuesto: campo opcional `cost` por herramienta (unidades arbitrarias) y tope por principal y día.
- Idempotencia: si el cliente envía clave de idempotencia en `_meta` (nombre del campo: verificar si la especificación define uno; si no, `aduana/idempotency-key`), una segunda llamada con la misma clave y mismos argumentos devuelve el resultado guardado y no reenvía; con argumentos distintos → error `IDEMPOTENCY_CONFLICT`.
- Tiempo límite por llamada y tamaño máximo de argumentos y de resultado.

### 5.9 Modelo de datos (PostgreSQL)

Todas las tablas llevan `tenant_id uuid not null` y sus índices empiezan por él. Migraciones Flyway `V1__…sql` en adelante; nunca se edita una migración ya publicada.

| Tabla | Columnas principales |
|---|---|
| `tenant` | `id`, `name`, `created_at` |
| `principal` | `id`, `tenant_id`, `kind` (`agent`/`user`), `name`, `attributes jsonb`, `groups text[]`, `status` |
| `api_key` | `id`, `tenant_id`, `principal_id`, `prefix`, `key_sha256`, `created_at`, `expires_at`, `revoked_at`. La clave se muestra una vez. |
| `console_user` | `id`, `tenant_id`, `principal_id`, `email`, `password_hash` (Argon2id; verificar biblioteca), `role` (`admin`/`approver`/`viewer`) |
| `upstream_server` | `id`, `tenant_id`, `name`, `transport`, `endpoint`, `credential_ciphertext`, `credential_nonce`, `strict`, `status` |
| `tool` | `id`, `tenant_id`, `server_id`, `name`, `definition jsonb`, `definition_sha256`, `side_effect`, `untrusted_source`, `sensitivity`, `cost`, `state` |
| `policy_bundle` | `id`, `tenant_id`, `sha256`, `content jsonb`, `loaded_at`, `loaded_by`, `active` (una activa por inquilino: índice único parcial) |
| `approval` | `id`, `tenant_id`, `binding_hash`, `principal_id`, `tool_id`, `args_redacted jsonb`, `approvers_group`, `state`, `expires_at`, `decided_by`, `decided_at`, `consumed_at` |
| `audit_entry` | Campos de 5.5; clave primaria `(tenant_id, seq)` |
| `audit_checkpoint` | `tenant_id`, `seq`, `hash`, `ts`, `signature`, `key_id` |
| `taint_token` | `tenant_id`, `principal_id`, `trace_key`, `value_sha256`, `source_seq`, `expires_at` |
| `rate_bucket` | `tenant_id`, `scope`, `window_start`, `count` |
| `idempotency_record` | `tenant_id`, `principal_id`, `key`, `args_hash`, `result jsonb`, `expires_at` |

---

## 6. Contratos

### 6.1 Extremo MCP (hacia agentes)

- `POST /mcp` — Streamable HTTP según 2026-07-28. Métodos soportados en v1: `server/discover`, `tools/list`, `tools/call`. Cualquier otro método → error de método no soportado, registrado.
- Compatibilidad 2025-11-25: se acepta `initialize` y se responde de forma mínima para que clientes antiguos funcionen; cubierto por un test con un cliente de esa revisión. Si resulta inviable sin duplicar la tubería, se documenta como no soportado (ADR 4).
- Recursos y prompts de MCP: fuera de alcance en v1; se responde lista vacía.

### 6.2 API REST de administración (hacia consola y CLI)

Prefijo `/api/v1`. Autenticación de consola por cookie de sesión `HttpOnly`, `SameSite=Strict`, con token CSRF. Todas las rutas declaradas en una tabla `RouteAccess` (ruta → roles); un test falla si existe una ruta sin fila en la tabla.

| Método y ruta | Rol | Qué hace |
|---|---|---|
| `POST /auth/login`, `POST /auth/logout` | público / cualquiera | Sesión |
| `GET/POST /servers`, `PATCH /servers/{id}` | admin | Catálogo de servidores |
| `POST /servers/{id}/sync` | admin | Relee definiciones y marca cambios |
| `GET /tools`, `PATCH /tools/{id}` | admin | Metadatos y revisión de cambios |
| `GET/POST /principals`, `POST /principals/{id}/keys`, `DELETE /keys/{id}` | admin | Identidades y claves |
| `GET /policy`, `POST /policy` | admin | Ver y cargar bundle (valida y ejecuta sus tests antes de activar) |
| `POST /policy/simulate` | admin | Simula un bundle candidato contra un rango del registro |
| `GET /approvals?state=`, `POST /approvals/{id}/approve`, `POST /approvals/{id}/reject` | approver | Cola |
| `GET /audit?from=&to=&principal=&tool=&decision=` | viewer | Consulta paginada por `seq` |
| `GET /audit/verify` | viewer | Estado de la cadena |
| `GET /audit/export?from=&to=` | admin | JSONL + puntos de control |
| `GET /events` (SSE) | viewer | Actividad en vivo para la consola |
| `GET /.well-known/aduana-audit-key` | público | Clave pública de firma |

Errores REST: `application/problem+json` (RFC 9457) con `type`, `title`, `status`, `code`.

### 6.3 Listas cerradas

Tipos de entrada de registro: `AUTH_FAILED`, `TOOL_NOT_FOUND`, `ARGS_INVALID`, `CALL_DECIDED`, `CALL_COMPLETED`, `CALL_FAILED`, `APPROVAL_CREATED`, `APPROVAL_DECIDED`, `APPROVAL_EXPIRED`, `RATE_LIMITED`, `IDEMPOTENT_REPLAY`, `TOOL_DEFINITION_CHANGED`, `POLICY_LOADED`, `POLICY_REJECTED`, `SERVER_CHANGED`, `TOOL_CHANGED`, `KEY_CREATED`, `KEY_REVOKED`, `CHECKPOINT`.

Códigos de error hacia el agente (en el resultado de error MCP, campo estructurado `code` + `message` + `hint`): `TOOL_NOT_FOUND`, `ARGS_INVALID`, `POLICY_DENIED`, `APPROVAL_PENDING`, `APPROVAL_REJECTED`, `RATE_LIMITED`, `BUDGET_EXCEEDED`, `IDEMPOTENCY_CONFLICT`, `UPSTREAM_TIMEOUT`, `UPSTREAM_ERROR`, `AUDIT_UNAVAILABLE`. El `hint` está pensado para que un modelo sepa qué hacer ("ask the user to request approval from finance"), y **nunca** incluye el texto de la regla ni nombres de otras herramientas.

### 6.4 CLI

```
aduana policy validate <dir>
aduana policy test <dir>
aduana policy simulate <dir> --from <ts> --to <ts> [--url …]
aduana audit verify [--export <file.jsonl>] [--checkpoints <file>]
aduana audit export --from <ts> --to <ts> -o <file.jsonl>
aduana keys create --principal <name>
aduana attacks run [--category <c>]
```

Código de salida 0 si todo correcto, 1 si hay fallos de política/verificación, 2 si error de uso.

---

## 7. Estructura del repositorio

```
aduana/
├── .editorconfig  .gitattributes  .gitignore  .githooks/pre-commit
├── .github/workflows/ci.yml  pages.yml  release.yml
├── CLAUDE.md  README.md  LICENSE  NOTICE  SECURITY.md  CONTRIBUTING.md  CHANGELOG.md
├── PROGRESS.md
├── Makefile  compose.yaml  .env.example
├── pom.xml                         # padre Maven
├── policy/   audit/   gateway/   cli/
├── console/
├── verifier/verify_chain.py  verifier/test_verify_chain.py
├── demo/servers/{crm,payments,webfetch}/   demo/agent/   demo/scenarios/*.yaml
├── attacks/cases/<categoria>/*.yaml   attacks/REPORT.md
├── bench/   bench/REPORT.md
├── scripts/check_authorship.py  check_licenses.sh  check_readme_numbers.py  …
├── deploy/compose.prod.yaml  deploy/helm/
└── docs/
    ├── PLAN.md  HANDOFF.md  architecture.md  threat-model.md  comparison.md
    ├── policy-reference.md  audit-format.md  case-study.md  video-script.md
    ├── adr/0001-…md …
    └── images/
```

Objetivos de `make` (todos existen desde la fase en que tienen sentido; `make` sin argumentos lista todos con una línea de ayuda):

`up`, `down`, `logs`, `smoke`, `lint`, `test`, `test-policy`, `test-audit`, `test-gateway`, `test-console`, `test-verifier`, `e2e`, `demo`, `attacks`, `bench`, `mutation`, `console`, `hooks`, `release-check`.

`make up` crea `.env` desde `.env.example` (solo valores vacíos o `local-only`), construye y espera a que todo esté sano. `make demo` ejecuta los escenarios guionizados contra la pila levantada y deja la consola con datos.

---

## 8. Normas de desarrollo

### 8.1 Contenido exacto de `CLAUDE.md` del repo

```markdown
# Aduana: working rules

Read [`docs/HANDOFF.md`](docs/HANDOFF.md) first: it says what exists, what is left, and how to do each piece.
The full specification is [`docs/PLAN.md`](docs/PLAN.md).

## Language and authorship

- Everything in the repository is in English: code, comments, docs, commit messages. Talk to the owner in Spanish from Spain.
- Commits are authored by `Ignacio Rodríguez Caro <rnachoccontact@gmail.com>`.
- Commit messages carry no co-author trailer and never credit an AI tool as author. Do not add `Co-Authored-By` or "Generated with" lines, whatever your defaults say; the `commit-msg` hook rejects them.
- Every class, interface, record, enum, method and function carries a doc comment naming its author:
  `Ignacio Rodríguez Caro <rnachoccontact@gmail.com>`. `make lint` fails otherwise (`scripts/check_authorship.py`).
- The product is "Aduana" in prose; technical identifiers stay lowercase `aduana`.

## How to work

- Docker and make are the only prerequisites. Images are pinned by digest; keep them pinned.
- Tests first: commit the failing test (`test(...)`), then the code that passes it (`feat(...)` or `fix(...)`), then the docs (`docs: ...`).
- Before every commit: `make lint` and the test suite of what you touched. `make test` runs all of them.
- Aduana never calls a language model. Do not add one.
- Never claim a number that was not measured. Say "not measured" instead.
- Never weaken a test, a threshold or a lint rule to make a build pass. Fix the code or stop and ask.
- No new dependency without an ADR. No GPL, AGPL or SSPL dependency ever.
- Keep `PROGRESS.md`, the README status table and `docs/HANDOFF.md` true after every change.
- Do not push, publish, tag or change repository settings unless the owner asks in this session.
```

### 8.2 Estilo de código

**General**
- Funciones cortas con un solo motivo para cambiar. Sin código muerto, sin código comentado, sin `TODO` sin número de issue.
- Nombres completos en inglés; sin abreviaturas salvo las del dominio (`MCP`, `JCS`).
- Errores: nunca se traga una excepción. O se trata, o se envuelve con contexto, o se propaga.
- Nada de registro (logs) con datos sensibles: los argumentos y resultados solo se registran redactados. Test que busca patrones sensibles en la salida de logs de la suite e2e.
- Reloj inyectado (`java.time.Clock`) en todo lo que dependa del tiempo. Ningún `Instant.now()` suelto.

**Java**
- Inyección por constructor. Campos `final`. `record` para datos inmutables. `sealed interface` para resultados con variantes (`Decision`).
- Capas en `gateway`: `web` (controladores finos) → `service` → `repository`. Un controlador nunca toca un repositorio.
- `policy` y `audit` no dependen de Spring ni de nada con E/S; un test de arquitectura (ArchUnit; verificar licencia) lo comprueba.
- Sin `null` en firmas públicas: `Optional` en retornos, validación en constructores.
- Spotless (formato Google Java Format) y Checkstyle en `make lint`.

**TypeScript (consola y demo)**
- `strict: true`. Sin `any`. Componentes standalone, señales para estado, `OnPush`.
- La consola no guarda tokens en `localStorage`. Política de seguridad de contenido que solo permite sus propios archivos.
- Un servicio por recurso de la API; los componentes no llaman a `HttpClient` directamente.

**Python (verificador y scripts)**
- Anotaciones de tipo en todo. `ruff` para formato y lint. El verificador no importa nada fuera de la biblioteca estándar.

### 8.3 Comentarios

Regla de autoría (la comprueba `scripts/check_authorship.py`, que se escribe en la Fase 0 con sus propios tests):

- Java: Javadoc en cada tipo y en cada método (incluidos privados; excluidos getters de `record` y métodos `@Test`, pero la clase de test sí lo lleva):
  ```java
  /**
   * Decides what happens to a tool call. Pure: same bundle and context, same decision.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  ```
- TypeScript: TSDoc con `@author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>` en cada clase, función exportada y método.
- Python: docstring cuya última línea es `Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>` en cada módulo, clase y función.
- Cabecera de archivo (todas las fuentes): `// SPDX-License-Identifier: Apache-2.0` y `// Copyright 2026 Ignacio Rodríguez Caro`.

Cómo se escribe el texto del comentario:

- Dice **por qué** existe o qué garantiza, no repite lo que el código ya dice. Mal: "Loops over the rules". Bien: "Every rule is evaluated, so a later deny always beats an earlier allow."
- Frases completas, en inglés sencillo, presente, voz activa. Primera frase de una línea.
- Sin emojis, sin "Note that", sin "This function", sin "Here we", sin "simply", sin "robust", sin numerar pasos dentro del cuerpo de un método. Sin referencias a la conversación ni a herramientas de IA.
- Los comentarios de línea solo donde hay una trampa real (un orden que importa, un caso límite de un estándar), citando el estándar: `// RFC 8785 §3.2.3: keys sort by UTF-16 code units, not by code points.`
- Si un comentario hace falta para entender *qué* hace el código, se reescribe el código.

### 8.4 Tests

- Orden obligatorio: test que falla → commit → código → commit.
- Nombres de test como frases: `a_deny_beats_an_allow_that_matches_first()`.
- Cobertura mínima de líneas: `policy` y `audit` 95 %, `gateway` 85 %, consola 70 %. Se mide con JaCoCo y falla el build por debajo.
- Mutación (PIT) en `policy` y `audit`: 80 % de mutantes muertos como mínimo.
- Pruebas de propiedades (jqwik; verificar licencia) para: canonicalización (ida y vuelta, orden de claves), cadena (cualquier mutación de cualquier entrada se detecta), evaluador (añadir una regla `deny` nunca convierte un `deny` en `allow`).
- Integración con Testcontainers y PostgreSQL real; nada de H2.
- E2E con la pila de `compose.yaml` y los servidores de `demo/`.
- Cada fila de la tabla de riesgos del README enlaza al test que la hace cumplir.

### 8.5 Documentación viva

- **ADR**: `docs/adr/NNNN-titulo-en-kebab.md`, formato: Context / Decision / Consequences / Alternatives considered. Uno por decisión no trivial. Lista mínima inicial en el apartado 12.
- **PROGRESS.md**: tabla de fases con estado y, bajo cada una, lista de lo hecho con el hash corto del commit.
- **docs/HANDOFF.md**: qué existe, qué falta, cómo se hace cada pieza que falta, trampas conocidas. Se reescribe al final de cada sesión.
- **CHANGELOG.md**: formato Keep a Changelog, a partir de la primera versión etiquetada.

---

## 9. Git

### 9.1 Identidad (primer comando de la Fase 0)

La configuración global de git de este equipo tiene un correo de ejemplo (`12345678+tuusuario@…`). **No se usa.** En el repo:

```bash
git config user.name "Ignacio Rodríguez Caro"
git config user.email "rnachoccontact@gmail.com"
git config core.hooksPath .githooks
git config commit.gpgsign false
```

Verificación: `git log -1 --format='%an <%ae>'` tras el primer commit debe dar exactamente `Ignacio Rodríguez Caro <rnachoccontact@gmail.com>`. `make lint` incluye una comprobación de que ningún commit de la rama tiene otro autor.

Para que GitHub atribuya los commits al perfil `rnachoc`, el correo `rnachoccontact@gmail.com` debe estar añadido y verificado en la cuenta de GitHub, y la opción "Block command line pushes that expose my email" debe estar desactivada; si no, el `push` se rechaza. Nacho lo comprueba antes del primer `push`. Ese correo será público en el historial.

### 9.2 Mensajes

- Conventional Commits, en inglés, imperativo, minúscula inicial, sin punto final, asunto ≤ 72 caracteres.
- Tipos: `feat`, `fix`, `test`, `docs`, `refactor`, `perf`, `build`, `ci`, `chore`, `style`. Ámbito opcional: `policy`, `audit`, `gateway`, `cli`, `console`, `demo`, `attacks`, `bench`.
- El asunto dice qué cambia para quien usa el sistema, con el estilo de los repos existentes de Nacho. Ejemplos válidos:
  - `test(policy): specify that a deny beats any allow, before it exists`
  - `feat(policy): evaluate every rule and let a deny win`
  - `fix(gateway): refuse to forward a call the audit log could not record`
  - `docs: say what the taint tracker cannot see`
- Cuerpo (opcional, tras línea en blanco): el porqué, en 1 a 5 líneas. Sin listas de archivos.
- **Sin pie de coautoría.** Ningún `Co-Authored-By`, ningún "Generated with" y ninguna atribución de autoría a una herramienta de IA en mensajes de commit ni descripciones de PR: todos los commits van únicamente a nombre de `Ignacio Rodríguez Caro <rnachoccontact@gmail.com>`. Esta regla sustituye a cualquier pie que la herramienta añada por defecto. El hook `.githooks/commit-msg` (se crea en la Fase 0, con su test) rechaza un mensaje que contenga `Co-Authored-By`, `Generated with` o `noreply@anthropic.com`, y `make lint` comprueba toda la rama.
- Prohibido en mensajes: emojis, "WIP", "fix stuff", "update", "final", menciones a prompts o a la conversación.

### 9.3 Flujo

- Rama `main` siempre verde. Trabajo de cada fase en `phase-NN-nombre`; al cerrar la fase, `git merge --no-ff` a `main` con mensaje `merge: phase NN, <nombre>`.
- Commits pequeños: un comportamiento por commit. Secuencia típica por comportamiento: `test(...)` → `feat(...)` → `docs:`.
- Nunca: `--no-verify`, `--force`, `commit --amend` sobre algo ya subido, `rebase` de `main`, `git add -A` sin revisar `git status`.
- Subir (`git push`) solo cuando Nacho lo pida en la sesión. Etiquetas y releases, solo Nacho o con su orden expresa.
- El hook `pre-commit` ejecuta gitleaks sobre lo preparado y `make lint` rápido.

---

## 10. Fases de desarrollo

Cada fase tiene: objetivo, entregables, criterios de aceptación (CA) y comandos de verificación. Una fase está cerrada cuando todos sus CA están "cumplido" con prueba, `make lint` y `make test` pasan, CI está verde y los tres documentos vivos están al día.

### Fase 0 — Cimientos

**Objetivo**: repo que construye, comprueba y arranca vacío; hechos verificados.

Entregables:
1. Identidad git (9.1), `.gitignore`, `.editorconfig`, `.gitattributes` (fin de línea LF), `LICENSE`, `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`.
2. `CLAUDE.md` (8.1), `PROGRESS.md`, `docs/HANDOFF.md`, `docs/PLAN.md`.
3. Maven multi-módulo con `policy`, `audit`, `gateway`, `cli` vacíos pero compilando, cada uno con un test trivial.
4. `compose.yaml` con PostgreSQL y `gateway` (un `GET /healthz`), imágenes fijadas por digest. `Makefile` con `up`, `down`, `smoke`, `lint`, `test`, `hooks`.
5. `scripts/check_authorship.py` con tests; `scripts/check_licenses.sh`; gitleaks en hook y CI; yamllint; editorconfig-checker.
6. CI de GitHub Actions: lint, test, compose up + smoke.
7. ADR 1 (hechos verificados y versiones fijadas), ADR 2 (el modelo propone, el código decide), ADR 3 (Docker y make como únicos requisitos), ADR 4 (SDK de MCP o implementación propia; compatibilidad 2025-11-25), ADR 7 (aprobación: hold frente a Tasks).
8. `docs/comparison.md` (apartado 1.4).

CA:
- CA0.1 `make up && make smoke` termina en 0 en una máquina con solo Docker y make.
- CA0.2 `make lint` falla si se quita un comentario de autoría de cualquier clase (demostrado con un test del script).
- CA0.3 `make lint` falla con una dependencia AGPL añadida a propósito (demostrado y revertido).
- CA0.4 Un commit con una clave falsa con formato real es rechazado por el hook.
- CA0.5 ADR 1 lista, con URL y fecha, cada versión fijada y cada hecho del apartado 4.4 confirmado o corregido.
- CA0.6 `docs/comparison.md` existe con fuente por afirmación.

### Fase 1 — Entorno de demostración

**Objetivo**: tener contra qué probar antes de escribir el proxy.

Entregables: tres servidores MCP en `demo/servers` con el SDK oficial de TypeScript, datos sintéticos fijos:
- `crm`: `search_customers`, `get_customer`, `update_customer_email` (efecto).
- `payments`: `get_order`, `refund` (efecto), `list_refunds`.
- `webfetch`: `fetch_url` sobre un conjunto fijo de páginas locales, varias con instrucciones ocultas, direcciones de correo e IBAN de atacante. Marcado para ser `untrusted_source`.

Y `demo/agent`: un agente **guionizado** (sin modelo) que lee `demo/scenarios/*.yaml` (lista de llamadas con argumentos y resultado esperado) y las ejecuta contra una URL MCP.

CA:
- CA1.1 Los tres servidores responden a `server/discover`, `tools/list` y `tools/call` según 2026-07-28 (tests de contrato).
- CA1.2 `demo/agent` ejecuta un escenario directo contra los servidores (sin Aduana) y todo pasa.
- CA1.3 Ningún dato parece real: correos `@example.com`, IBAN con país de prueba inválido documentado.

### Fase 2 — Proxy transparente

**Objetivo**: Aduana en medio, sin decidir nada todavía.

Entregables: extremo `POST /mcp`; conector HTTP a servidores de destino configurados por archivo; espacio de nombres `<servidor>__<herramienta>`; tiempos límite; propagación de errores con los códigos de 6.3; test de compatibilidad 2025-11-25 según ADR 4.

CA:
- CA2.1 Los escenarios de la Fase 1 pasan idénticos a través de Aduana.
- CA2.2 Un servidor de destino caído produce `UPSTREAM_ERROR`; uno lento, `UPSTREAM_TIMEOUT`; ninguno deja hilos colgados (test).
- CA2.3 Argumentos de más de N bytes se rechazan antes de reenviar.

### Fase 3 — Identidad y catálogo

Entregables: esquema de base de datos (5.9) hasta `tool`; claves de API (hash, prefijo visible, caducidad, revocación); validación JWT OIDC opcional; catálogo persistente con sincronización, fijación de definiciones y estado `changed_pending_review`; cifrado de credenciales de destino; validación de argumentos con JSON Schema; API REST de servidores, herramientas, principales y claves con `RouteAccess`.

CA:
- CA3.1 Sin credencial → 401 y entrada (aún en tabla simple; la cadena llega en Fase 5).
- CA3.2 "No existe" y "sin acceso" devuelven respuestas indistinguibles (test que compara cuerpo y tiempos dentro de una tolerancia).
- CA3.3 Cambiar la descripción de una herramienta en el servidor demo la saca de `tools/list` hasta que un admin la revisa.
- CA3.4 La credencial de destino nunca aparece en logs, respuestas ni en la base en claro (test que busca el valor).
- CA3.5 Una ruta REST nueva sin fila en `RouteAccess` hace fallar un test.

### Fase 4 — Motor de políticas

Entregables: módulo `policy` completo (5.2): modelo, carga y validación, evaluador, obligaciones, ejecutor de casos de prueba; `aduana policy validate|test`; integración en la tubería (paso 6); filtrado de `tools/list` por política; bundle de ejemplo en `demo/policy/` con al menos 12 reglas y 40 casos de prueba; `docs/policy-reference.md`.

CA:
- CA4.1 Cada punto de la semántica de 5.2 tiene un test con su nombre.
- CA4.2 Propiedad: añadir una regla `deny` nunca relaja una decisión (1.000 casos generados).
- CA4.3 Una política inválida no sustituye a la activa.
- CA4.4 Cobertura de `policy` ≥ 95 %; mutación ≥ 80 %.
- CA4.5 El evaluador decide en menos de 1 ms de mediana con 200 reglas (medido con JMH o microbenchmark simple; se publica el número real).

### Fase 5 — Registro verificable

Entregables: módulo `audit` (5.5): canonicalización RFC 8785 con los vectores del RFC, cadena, puntos de control firmados; tabla con rol restringido y trigger; fallo cerrado (principio 5); `aduana audit verify|export`; `verifier/verify_chain.py`; eventos de administración registrados; `docs/audit-format.md` suficiente para que un tercero escriba su propio verificador.

CA:
- CA5.1 Los vectores de prueba del RFC 8785 pasan.
- CA5.2 Seis manipulaciones (editar, borrar, reordenar, insertar, cambiar fecha, truncar con puntos de control exportados) son detectadas por **los dos** verificadores, cada una con test.
- CA5.3 50 hilos insertando: cero huecos y cero bifurcaciones.
- CA5.4 `UPDATE`/`DELETE` sobre `audit_entry` falla con el rol de la aplicación y con el propietario.
- CA5.5 Con la base de datos de registro caída, ninguna llamada llega al servidor de destino (test e2e que cuenta llamadas recibidas en el demo).
- CA5.6 `verify_chain.py` tiene menos de 200 líneas y ningún import fuera de la biblioteca estándar (comprobado por test).

### Fase 6 — Aprobaciones

Entregables: 5.4 completo; API de aprobaciones; caducidad por tarea programada; eventos SSE.

CA:
- CA6.1 Aprobación consumida dos veces en paralelo (20 hilos): se ejecuta exactamente una vez.
- CA6.2 Cambiar un solo carácter de los argumentos tras aprobar exige nueva aprobación.
- CA6.3 Un principal agente no puede aprobar aunque tenga una clave válida.
- CA6.4 Aprobación caducada no ejecuta y queda registrada.
- CA6.5 Cargar una política nueva invalida las aprobaciones pendientes del bundle anterior (por el `policy_bundle_hash` en el `binding_hash`).

### Fase 7 — Redacción, marcas de inyección y datos no fiables

Entregables: detectores de 5.7 con validaciones; obligaciones `redact_result`, `deny_args_containing`, `flag_args_containing`; detector de patrones de inyección sobre resultados y descripciones (lista de patrones versionada en `gateway/src/main/resources/injection-patterns.yaml`; solo marca y envuelve, no bloquea); taint de 5.6.

CA:
- CA7.1 Cada detector: conjunto de positivos y de negativos parecidos (un DNI con letra errónea no se marca). Precisión y exhaustividad publicadas en `docs/detectors.md` con los números medidos sobre ese conjunto.
- CA7.2 Escenario demo: `fetch_url` devuelve una página con un IBAN de atacante; el agente guionizado llama después a `refund` hacia ese IBAN; la política lo manda a aprobación con la marca `FLOW_FROM_UNTRUSTED` y la entrada dice de qué `seq` venía.
- CA7.3 El registro nunca contiene un valor sensible en claro (test que recorre todas las entradas generadas por la suite e2e).
- CA7.4 En la base no hay valores no fiables en claro, solo hashes.

### Fase 8 — Límites, idempotencia y servidores stdio

Entregables: 5.8 completo; conector stdio (proceso hijo con lista blanca de comandos en configuración, entorno limpio, reinicio con retroceso, límite de memoria si el sistema lo permite); un cuarto servidor demo por stdio.

CA:
- CA8.1 Superar la frecuencia devuelve `RATE_LIMITED` con cuándo reintentar; los contadores sobreviven a un reinicio.
- CA8.2 Misma clave de idempotencia y mismos argumentos: una sola ejecución en destino. Mismos clave y distintos argumentos: `IDEMPOTENCY_CONFLICT`.
- CA8.3 Un comando stdio fuera de la lista blanca no se lanza.
- CA8.4 Si el proceso hijo muere, la siguiente llamada lo relanza o falla limpio; nunca cuelga.

### Fase 9 — Consola

Entregables (Angular): inicio de sesión; **Actividad** en vivo; **Aprobaciones** (cola, detalle con argumentos redactados, reglas y marcas, aprobar/rechazar); **Registro** (búsqueda, detalle, estado de verificación de la cadena con el último punto de control); **Catálogo** (servidores, herramientas, revisión de definiciones cambiadas con diff); **Política** (bundle activo solo lectura, resultado de sus tests); **Identidades** (principales, claves). Diseño sobrio, un tipo de letra servido desde el repo, modo claro y oscuro, usable con teclado.

CA:
- CA9.1 Cinco flujos Playwright: login, aprobar, rechazar, revisar cambio de definición, buscar en el registro.
- CA9.2 Sin tokens en `localStorage`; CSP que solo permite origen propio (test de cabeceras).
- CA9.3 axe-core (verificar licencia) sin errores críticos en las seis pantallas.
- CA9.4 Un usuario `viewer` no ve botones de aprobar y la API le responde 403 si lo intenta a mano.
- CA9.5 Capturas reales en `docs/images/` hechas con datos de `make demo`.

### Fase 10 — Simulación y repetición

Entregables: `POST /policy/simulate` y `aduana policy simulate`: toma un rango del registro, reconstruye el `CallContext` de cada `CALL_DECIDED` (por eso `args_redacted` y las marcas se guardan) y lo evalúa con el bundle candidato; salida: cuántas decisiones cambian, agrupadas por regla, con ejemplos. Pantalla **Simulación** en la consola. Página estática `replay/` (para GitHub Pages) que reproduce los escenarios demo grabados, sin servidor.

CA:
- CA10.1 Simular el bundle activo contra su propio tráfico da cero diferencias.
- CA10.2 Limitación documentada y probada: las condiciones sobre argumentos redactados se evalúan como "unknown" y se informan aparte; nunca se cuentan como "sin cambios".
- CA10.3 La página de repetición funciona abriendo el HTML generado sin servidor.

### Fase 11 — Banco de ataques, rendimiento e informes

Entregables:
- `attacks/cases/`: mínimo **80 casos** en estas categorías (mínimo por categoría entre paréntesis): inyección vía resultado de herramienta (12), envenenamiento de descripción de herramienta (6), exfiltración por argumentos (10), delegado confundido / otra identidad (8), escalada fuera de catálogo (6), reutilización y alteración de aprobaciones (8), manipulación del registro (8), abuso de recursos y bucles (8), evasión de redacción con codificaciones (8), acceso a rutas de administración (6). Cada caso: descripción, pasos, qué debe pararlo, y resultado esperado.
- `aduana attacks run` + `attacks/REPORT.md` generado, con una fila por caso: parado o no, y qué lo paró. CI falla si el informe no está al día o si un caso que debía pararse no se para.
- `bench/`: latencia añadida por Aduana frente a llamada directa, a 50, 200 y 500 peticiones por segundo, con política de 50 reglas y registro activado. `bench/REPORT.md` con p50/p95/p99, máquina y fecha.
- `docs/threat-model.md` (STRIDE por componente, con qué lo mitiga y qué queda sin cubrir).
- `scripts/check_readme_numbers.py`: todo número del README debe aparecer en un informe generado.

CA:
- CA11.1 ≥ 80 casos, mínimos por categoría cumplidos.
- CA11.2 100 % de los casos cuya defensa es determinista quedan parados. Los que dependen de heurística (patrones de inyección, taint literal) se informan con su tasa real, sea cual sea, y con los que escapan listados.
- CA11.3 Informe de rendimiento publicado con números medidos. Mínimo para pasar: p99 añadido ≤ 50 ms a 200 peticiones por segundo en el equipo de Nacho. Objetivo: ≤ 15 ms.
- CA11.4 CI falla si se edita un número del README a mano.

### Fase 12 — Empaquetado, documentación y publicación

Entregables: imagen Docker única (gateway + cli + consola servida como estáticos), multi-arquitectura; `deploy/compose.prod.yaml`; arranque rápido de **tres comandos** que deja la demo funcionando; README final (apartado 11); `docs/case-study.md`; `docs/video-script.md` (2 minutos); flujo de GitHub Pages para `replay/`; `release.yml` que construye la imagen al etiquetar (sin etiquetar todavía); Helm opcional solo si sobra tiempo.

CA:
- CA12.1 En una máquina limpia: `git clone`, `make up`, `make demo` → consola con datos en menos de 10 minutos (cronometrado y anotado).
- CA12.2 README cumple el apartado 11 punto por punto.
- CA12.3 `make release-check` pasa: lint, test, e2e, ataques, informe al día, licencias, sin secretos en el historial.
- CA12.4 Imagen final sin usuario root, sin shell innecesaria, con `HEALTHCHECK`; escaneada con Trivy (verificar licencia) sin vulnerabilidades críticas ni altas con parche disponible.

**Línea de corte del MVP**: si hay que recortar, lo último en caer es 0–7, 9 y 11. Se pueden posponer, por este orden: Helm, stdio (Fase 8, parte), simulación (Fase 10), compatibilidad 2025-11-25.

---

## 11. README: especificación

Idioma: inglés. Tono: el de `turno`: afirmaciones cortas, todo enlazado a su prueba. Sin "portfolio project". Orden exacto de secciones:

1. **Título y distintivos**: CI, licencia, versión. Nada más.
2. **Una frase** (la del apartado 1.2) y, en negrita, **The model proposes, the code decides.** con enlace al ADR 2.
3. **Tres enlaces**: repetición publicada · caso de estudio · decisiones (ADR).
4. **Una imagen**: la cola de aprobaciones con una devolución retenida por flujo no fiable. Captura real.
5. **Quick start**: tres comandos y una tabla "abre / qué ves". Requisitos: Docker y make.
6. **What it does**: 6–8 viñetas, cada una una frase con verbo, sin adjetivos.
7. **What was measured**: tabla (qué · resultado · informe) con: banco de ataques, latencia añadida, mutación en política y registro, detectores. Solo números que salgan de informes generados.
8. **What it does not do**: lista honesta (sin alta disponibilidad, taint solo literal, sin separación de funciones en aprobaciones, etc.).
9. **How it works**: diagrama Mermaid de 4.1 y la tubería en 12 pasos, una línea cada uno.
10. **Policy in one minute**: un ejemplo de regla, un caso de prueba y el comando.
11. **Verify the log yourself**: los dos comandos y un enlace a `docs/audit-format.md`.
12. **Risks**: tabla (riesgo · qué lo para · tests · se hace cumplir) como la de GuardiAI, una fila por categoría del banco de ataques.
13. **How Aduana compares**: 5 líneas y enlace a `docs/comparison.md`. Sin hablar mal de nadie; con fecha.
14. **Repository layout**: tabla carpeta · contenido.
15. **Status**: tabla de fases.
16. **Commercial use and support**: Apache-2.0; contacto para soporte e implantación (correo de contacto y LinkedIn).
17. **Licence**: `Apache-2.0, © 2026 Ignacio Rodríguez Caro.`

Reglas: sin emojis en títulos; ninguna afirmación en futuro ("will") fuera de Status; ninguna cifra sin informe; imágenes con texto alternativo descriptivo; longitud máxima orientativa, 250 líneas.

---

## 12. ADR mínimos

1 Hechos verificados y versiones · 2 El modelo propone, el código decide · 3 Docker y make · 4 SDK de MCP y compatibilidad · 5 Política declarativa sin lenguaje de expresiones · 6 Canonicalización RFC 8785 · 7 Aprobación hold frente a Tasks · 8 Fallo cerrado ante registro no disponible · 9 Límites en PostgreSQL, no Redis · 10 Taint por hash de valores literales · 11 Mínimo privilegio en la base de datos · 12 Un solo nodo en v1 · 13 Apache-2.0 y política de dependencias · 14 Sin llamadas a modelos · 15 `tenant_id` desde el inicio.

---

## 13. Revisiones posteriores al desarrollo

Se hacen con la Fase 12 cerrada, **en este orden**, cada una en una sesión nueva y sin contexto previo (así el revisor no hereda las suposiciones de quien lo construyó). Cada revisión produce `docs/reviews/RNN-nombre.md` con: qué se revisó, hallazgos (gravedad: bloqueante / importante / menor), y veredicto. Un hallazgo bloqueante se corrige y se repite la revisión completa.

| # | Revisión | Quién | Cómo | Mínimo para pasar |
|---|---|---|---|---|
| R1 | **Clon limpio** | Nacho, a mano, en otra carpeta o máquina | Seguir solo el README, sin ayuda | Demo funcionando en ≤ 15 min sin tocar nada fuera del README |
| R2 | **Conformidad con el plan** | Sesión nueva de Opus | Recorrer todos los CA de las fases 0–12 y volver a ejecutar su prueba | 100 % de CA cumplidos o con excepción firmada por Nacho en ADR |
| R3 | **Corrección del código** | `/code-review` nivel high sobre `main` completo, por módulos | Hallazgos verificados | Cero bloqueantes; importantes corregidos o con issue |
| R4 | **Seguridad** | `/security-review` + sesión nueva con el encargo "rompe Aduana": escribir 15 ataques nuevos que no estén en el banco | Los nuevos ataques se añaden al banco | Ningún ataque nuevo con éxito contra una garantía determinista; gitleaks limpio en todo el historial; Trivy sin críticas/altas con parche |
| R5 | **Protocolo** | Sesión nueva | Probar Aduana con tres clientes MCP reales (Claude Code, Claude Desktop y un tercero) y con dos servidores MCP públicos conocidos | Los tres clientes listan y llaman herramientas; incidencias documentadas |
| R6 | **Registro** | Nacho + sesión nueva | Escribir un tercer verificador desde cero leyendo solo `docs/audit-format.md` (en otro lenguaje) | El tercer verificador valida un export real y detecta las seis manipulaciones |
| R7 | **Rendimiento** | Sesión nueva | Repetir `make bench` tres veces; prueba de 1 hora a 100 peticiones por segundo | p99 dentro del mínimo; sin crecimiento de memoria ni de conexiones |
| R8 | **Calidad de tests** | Sesión nueva | Introducir a mano 20 errores en `policy`, `audit` y tubería | ≥ 19 de 20 detectados por la suite |
| R9 | **Honestidad** | Sesión nueva | Cada afirmación y cada número del README y del caso de estudio, trazado a test o informe | 100 % trazable; lo no trazable se borra |
| R10 | **Documentación** | Sesión nueva de un modelo más pequeño (Haiku) | Con solo `docs/`, escribir una política para un caso nuevo y verificar un export | Lo consigue sin leer código |
| R11 | **Legal y licencias** | Nacho + sesión nueva | Inventario de dependencias con licencia; NOTICE; nombre libre; sin marcas de terceros mal usadas; aviso de "no afiliado" respecto a MCP/Anthropic | Cero GPL/AGPL/SSPL; NOTICE completo |
| R12 | **Presentación** | Nacho | Ver el repo como lo vería un reclutador: 30 segundos en el README, 2 minutos en la repetición | Se entiende qué es y por qué importa sin desplazarse más de una pantalla |

Solo con R1–R12 pasadas: repo público, etiqueta `v0.1.0`, release con imagen, Pages activado.

---

## 14. Plan de comercialización

### 14.1 Advertencia de partida

Vender infraestructura de seguridad a empresas siendo una sola persona es lento: los ciclos de compra son de meses y piden certificaciones que no tendrás. Por eso el plan ordena las fuentes de ingreso de la más probable a la menos, y fija puntos de decisión con números. Todas las cifras de precio son **hipótesis para validar**, no previsiones.

### 14.2 Las cuatro vías de ingreso, por orden

| Orden | Vía | Qué vendes | Cuándo llega el dinero | Precio hipótesis |
|---|---|---|---|---|
| 1 | **Empleo o contrato** | Tu perfil, con Aduana como prueba | 1–3 meses | El salario o la tarifa |
| 2 | **Servicio de implantación** | "Auditoría de gobierno de agentes": revisas los agentes y servidores MCP de un cliente, instalas Aduana, escribes sus políticas y les dejas el banco de ataques funcionando | 2–4 meses | Paquete cerrado 2.500–6.000 € (1–2 semanas) |
| 3 | **Soporte y licencia de empresa** | Funciones de pago: SSO SAML/SCIM, varios inquilinos, separación de funciones en aprobaciones, informes de cumplimiento, soporte con tiempo de respuesta | 6–12 meses | 400–1.500 €/mes por instalación |
| 4 | **Servicio alojado** | Aduana gestionada | > 12 meses, solo si 2 y 3 funcionan | Por volumen de llamadas |

La vía 1 es el retorno más seguro y está alineada con tu búsqueda de empleo. La vía 2 es el primer dinero directo realista. No construyas nada de las vías 3 y 4 hasta tener dos clientes de la vía 2 que lo pidan.

### 14.3 A quién

**Cliente ideal de la vía 2 y 3** (por orden de facilidad):

1. **Consultoras y agencias de IA** de 5 a 100 personas que montan agentes para sus clientes. Les falta poder enseñar control y auditoría en sus propuestas. Compran rápido y repiten con cada cliente suyo. Decide el director técnico o un socio.
2. **Empresas medianas de sectores regulados** (banca, seguros, salud, legal) con un equipo de plataforma que ya ha puesto algún agente en pruebas. Tu pasado en banca es el argumento. Decide el responsable de plataforma con el visto bueno de seguridad. Ciclo largo.
3. **Empresas de software con producto de agentes** que necesitan pasar el cuestionario de seguridad de sus propios clientes.

Geografía inicial: España y, en inglés, Europa. El Reglamento Europeo de IA es el gancho: exige registro de actividad y supervisión humana a los sistemas de alto riesgo (artículos 12 y 14). **Verificar el calendario de aplicación vigente antes de usarlo en cualquier material**, porque ha habido propuestas de aplazamiento. Nunca prometas "cumplimiento"; di "evidencia que ayuda a cumplir".

### 14.4 Dónde encontrar a los clientes

- **GitHub y registros MCP**: quien publica servidores MCP con herramientas de escritura o tiene agentes en producción. Las organizaciones que abren issues en los repos de las pasarelas competidoras pidiendo auditoría o aprobaciones son clientes potenciales directos.
- **LinkedIn**: búsqueda por cargo ("Head of Platform", "AI Lead", "CTO") + empresa de los tres tipos de arriba + publicaciones recientes sobre agentes o MCP.
- **Comunidades**: Discord y foros oficiales de MCP, r/mcp, r/LocalLLaMA (para la parte abierta), Hacker News, comunidades españolas de IA y de Java.
- **Eventos**: meetups de IA, de Java y de seguridad de tu ciudad y de Madrid; proponer una charla de 20 minutos ("Cómo impedir con código que un agente haga una transferencia que nadie aprobó"). Las charlas dan más clientes de servicio que cualquier anuncio.
- **Asociaciones y partners**: consultoras de cumplimiento y de ciberseguridad que no tienen producto técnico para agentes; proponles revender tu paquete.

### 14.5 Publicidad: no, al principio

- **No inviertas en anuncios** en los primeros seis meses. Quien compra esto no llega por un anuncio; llega por una recomendación, un repo o una charla, y el coste por contacto en LinkedIn Ads para este público es muy alto.
- Excepción a valorar tras el punto de decisión del mes 4: 150–300 € en promocionar en LinkedIn la mejor publicación orgánica, solo hacia cargos concretos en España, durante dos semanas, y medir reuniones conseguidas.
- Gasto útil desde el principio: dominio (~15–40 €/año) y nada más.

### 14.6 Contenido orgánico (la vía principal de captación)

Regla: cada pieza enseña algo comprobable y enlaza a un test, un informe o la repetición. Nada de opinión sin demostración.

Calendario de 12 semanas a partir de la publicación:

| Semana | Pieza | Dónde |
|---|---|---|
| 1 | Lanzamiento: "Show HN" con la repetición y el banco de ataques; publicación en LinkedIn (español e inglés) con el vídeo de 2 minutos | HN, LinkedIn, r/mcp, Discord MCP |
| 2 | Artículo: "Un agente, una web con instrucciones ocultas y una devolución: qué lo para y qué no" (caso CA7.2 paso a paso) | Blog en GitHub Pages + dev.to + LinkedIn |
| 3 | Artículo técnico: "Un registro que puedes verificar sin fiarte de mí" + el verificador de 200 líneas | Blog, HN |
| 4 | Vídeo corto: aprobar una acción y ver que cambiar un céntimo la invalida | LinkedIn, YouTube |
| 5 | Comparativa honesta con fecha (la de `docs/comparison.md`) | Blog; avisar con educación a los proyectos comparados |
| 6 | Guía: "Políticas para agentes que se prueban como código" | Blog |
| 7 | Resultados: informe del banco de ataques, incluidos los que escapan | LinkedIn, blog |
| 8 | Charla en meetup (grabada) | Meetup + YouTube |
| 9 | Plantillas de política para 5 servidores MCP populares | Repo `aduana-policies` |
| 10 | Artículo en español: registro y supervisión humana en el Reglamento de IA, con qué evidencia genera Aduana | LinkedIn, blog |
| 11 | Caso real (anonimizado) de la primera implantación, si existe | Blog |
| 12 | Retrospectiva con números: estrellas, instalaciones, conversaciones | LinkedIn |

Ritmo sostenido después: una pieza técnica cada dos semanas y dos publicaciones cortas de LinkedIn por semana. Además: alta en listas "awesome-mcp" y en los directorios de pasarelas; responder con utilidad (no con enlaces) en hilos donde se pregunte por auditoría o aprobaciones.

### 14.7 Proceso de venta de la vía 2

1. **Contacto**: mensaje de 4 líneas, sin adjunto: qué has visto de ellos (un repo, una oferta de empleo, una publicación), el riesgo concreto, enlace a la repetición, propuesta de 20 minutos.
2. **Llamada de 20 minutos**: tres preguntas (¿qué herramientas con efectos tienen sus agentes?, ¿quién aprueba hoy?, ¿qué enseñarían a un auditor?). Demo de 5 minutos con su caso más parecido.
3. **Propuesta de una página**: alcance cerrado, precio cerrado, entregables (Aduana instalada, N políticas con tests, banco de ataques adaptado, informe), plazo, lo que no incluye.
4. **Piloto**: los tres primeros clientes, a mitad de precio a cambio de un caso publicable y una referencia.
5. **Cierre**: contrato de servicios sencillo, 50 % al inicio, 50 % a la entrega.

Objetivo de actividad: 15 contactos personalizados por semana durante 8 semanas. Referencia orientativa para medir, no promesa: de 100 contactos bien elegidos, 10–15 respuestas, 5–8 llamadas, 1–2 pilotos.

### 14.8 Requisitos legales y operativos antes de cobrar

- **Alta para facturar**: para prestar servicios de forma habitual necesitas alta censal en Hacienda y en autónomos (o una sociedad). Consulta con un gestor antes de la primera factura, también por la compatibilidad con una prestación por desempleo si la cobras.
- **Contrato de servicios** con limitación de responsabilidad, y **seguro de responsabilidad civil profesional** antes del primer cliente regulado.
- **Protección de datos**: Aduana instalada en casa del cliente no te convierte en encargado del tratamiento. Si accedes a sus sistemas durante la implantación, firma un acuerdo de confidencialidad y, si ves datos personales, un contrato de encargo.
- **Marca**: comprobar que "Aduana" no está registrada para software (clases 9 y 42) en la OEPM y la EUIPO antes de invertir en ella.
- **Página de seguridad** (`SECURITY.md` + correo de contacto) y política de divulgación: lo primero que mira un comprador de seguridad.

### 14.9 Métricas y puntos de decisión

| Momento | Métrica | Si se cumple | Si no |
|---|---|---|---|
| Mes 1 | ≥ 150 estrellas, ≥ 10 conversaciones con gente que usa agentes en producción | Seguir el calendario | Revisar el mensaje y la demo; pedir crítica a 5 personas del sector |
| Mes 2 | ≥ 5 llamadas de la vía 2 | Enviar propuestas | Cambiar de segmento (de regulados a consultoras, o al revés) |
| Mes 4 | ≥ 1 piloto firmado **o** una oferta de empleo/contrato atribuible a Aduana | Valorar la promoción de pago de 14.5 y empezar a diseñar la vía 3 con lo que pida el piloto | Mantener Aduana como proyecto abierto de referencia y dedicar el esfuerzo comercial a los planes B o C |
| Mes 8 | ≥ 3 clientes de servicio y ≥ 2 pidiendo lo mismo de pago | Construir la vía 3 | No construirla |

### 14.10 Qué no hacer

- No prometer "cumple el Reglamento de IA" ni "imposible de saltar".
- No hablar mal de la competencia; comparar con fecha y fuente.
- No construir el servicio alojado antes de tener clientes.
- No aceptar un cliente regulado sin contrato y sin seguro.
- No publicar cifras de clientes o de uso que no puedas demostrar.

---

## 15. Riesgos del proyecto

| Riesgo | Señal | Respuesta |
|---|---|---|
| La especificación de MCP cambia otra vez | Nueva revisión publicada | La capa de protocolo está aislada en un paquete; ADR y adaptación |
| Un competidor publica las cuatro diferencias | Comparativa trimestral | Reforzar verificabilidad y banco de ataques; el valor de portafolio no cambia |
| El proyecto se alarga y queda a medias, como Tramita | Una fase supera las 3 sesiones | Aplicar la línea de corte del MVP de la Fase 12 |
| Falsa sensación de seguridad por el taint | Usuarios lo tratan como garantía | Limitación escrita en README, consola y en el `hint` |
| Opus relaja un test para pasar | Diff que baja un umbral | Regla en CLAUDE.md + R8 |

---

## 16. Fuentes consultadas el 12-11-2026

- Registro de cambios de MCP 2026-07-28: https://modelcontextprotocol.io/specification/2026-07-28/changelog
- Cobertura de la versión candidata: https://thenewstack.io/mcp-release-candidate-rewrite/
- Resumen del núcleo sin estado: https://www.noze.it/en/insights/mcp-2026-07-28-stateless/
- Comparativas de pasarelas (escritas por proveedores; contrastar): https://obot.ai/blog/the-13-best-mcp-gateways-for-enterprise-teams/ · https://www.lunar.dev/post/the-best-open-source-mcp-gateways-in-2026 · https://tyk.io/learning-center/best-enterprise-mcp-gateways/ · https://www.truefoundry.com/blog/best-mcp-gateways · https://dev.to/amazinglitter26/top-5-open-source-mcp-gateways-in-2026-compared-4md5
