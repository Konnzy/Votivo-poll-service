# Votivo Poll Service

Poll management microservice for Votivo, built with Java 25, Spring Boot 4.1.2, Gradle 9.5.1 (Kotlin DSL), and PostgreSQL.

## Features

- Draft creation and editing; lifecycle: `DRAFT → PUBLISHED → CLOSED → ARCHIVED`.
- Public and link-based access, anonymity settings, single/multiple-choice questions.
- Keycloak JWT authentication, owner permissions, and admin moderation.
- Flyway migrations, optimistic locking, pagination, and validation (50 questions, 20 options per question).
- Database-aware readiness, independent liveness, and graceful shutdown.
- Lifecycle events (`poll.started`, `poll.ended`) published to RabbitMQ through a transactional outbox.

## Architecture

Layered architecture: **Controller → Service → Repository → PostgreSQL**.
Packages under `src/main/java/ua/votivo/poll` separate controllers, DTOs, services, models, repositories, mapping, security, and error handling.

API documentation: [OpenAPI specification](docs/api.json).

## Run locally

Requires JDK 25, Docker Compose, and an available Keycloak instance. Gradle is included via Wrapper.

```powershell
Copy-Item .env.example .env
# Set DB_PASSWORD and adjust Keycloak URLs in .env.
docker compose up -d postgres rabbitmq

# Spring Boot does not load .env automatically.
Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $entry = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($entry[0], $entry[1], 'Process')
}
./gradlew.bat bootRun --args="--spring.profiles.active=local"
```

API: `http://localhost:8082/api/v1/polls`. PostgreSQL: `localhost:5433/polls`.
On Linux/macOS, export the environment variables and use `./gradlew`.

Keycloak defaults: realm `votivo`, audience `poll-service`, admin realm role `ADMIN`.
Configure an audience mapper for access tokens; the issuer must match `iss`, and JWKS must be reachable.
Poll ownership comes from JWT `sub`. The local profile still validates JWTs.

To run the app in Docker instead:

```powershell
docker compose --profile app up --build -d --wait
```

Container JWKS defaults to `host.docker.internal`; override `CONTAINER_KEYCLOAK_JWK_SET_URI` when needed.

The standalone Compose starts its own RabbitMQ and persists its data in `rabbit-data`. The app connects to `rabbitmq:5672` on the Compose network with the same user, password and virtual host as the broker. Host-side `bootRun` uses `localhost:5674` by default; management is at `http://localhost:15674`. Copy the RabbitMQ settings from `.env.example` into an existing `.env` before using `bootRun`. Host ports are configurable with `RABBITMQ_PORT` and `RABBITMQ_MANAGEMENT_PORT`; the container always uses port `5672`.

This standalone broker is separate from the integration repository's broker. A shared deployment must point all three services at the same broker and virtual host.

## Health and shutdown

Two operational endpoints are available without a JWT and return only a status:

| Endpoint | Checks | Failure response |
| --- | --- | --- |
| `GET /actuator/health/liveness` | Application liveness; independent of PostgreSQL | HTTP 503 when unhealthy |
| `GET /actuator/health/readiness` | Application readiness and a database connection | HTTP 503 when not ready or the database is unavailable |

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health/liveness
Invoke-RestMethod http://localhost:8082/actuator/health/readiness
```

Health components, connection errors, and database details are never returned. Only these two probe routes allow anonymous access; the aggregate `/actuator/health` requires authentication. Other Actuator endpoints are not exposed.

The Docker image runs a readiness probe using a small standalone Java class, so the runtime needs no curl or wget. It honors `SERVER_PORT` (default `8082`), times out on slow connections, and rejects redirects. Docker marks the container unhealthy after three failed probes following the startup period. The Compose file enables an init process and allows 30 seconds for stopping; Spring allows up to 20 seconds per shutdown phase for in-flight requests.

These probes cover Poll Service and its own database. They do not verify the complete Keycloak login flow or the readiness of Vote/Result Service.

## Tests

```powershell
./gradlew.bat build
```

To force a fresh test run instead of reusing up-to-date results:

```powershell
./gradlew.bat test --rerun-tasks
```

API tests use H2; JWT tests verify signed tokens. Tests cover content immutability after publication, ownership and moderation, hidden share tokens, public probe access, readiness when refusing traffic or losing the database, and the standalone container probe. PostgreSQL tests require a **separate test database** and clear its tables:

```powershell
docker compose exec postgres createdb -U poll polls_test
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5433/polls_test'
$env:TEST_DB_USERNAME = 'poll'
$env:TEST_DB_PASSWORD = $env:DB_PASSWORD
./gradlew.bat postgresTest
```

PostgreSQL tests verify migrations, ordered child persistence, cascade deletion, optimistic locking, and rollback of changes to an entire poll.

The delivery tests need both a **disposable RabbitMQ** and the separate test database. They purge the two poll-event queues; use an isolated broker, not the shared development environment:

```powershell
# Set TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD for the test database.
# Set RABBITMQ_HOST / RABBITMQ_PORT / RABBITMQ_USER / RABBITMQ_PASSWORD for the test broker.
./gradlew.bat messagingTest
```

`postgresTest` includes concurrent-publisher regression coverage. `messagingTest` verifies both durable subscriptions receive events before consumers start, restores a missing subscription, and checks dead-letter compatibility. GitHub Actions runs the build, PostgreSQL 18 tests, and delivery tests against dedicated PostgreSQL/RabbitMQ services.

## Events (RabbitMQ)

Poll Service publishes to the durable topic exchange `poll.events.exchange`. Vote Service and Result Service consume the events. Before publishing, Poll Service declares both durable subscriptions: `vote-service.poll-events` and `result-service.poll-events`. These declarations match the consumers, including their `votivo.dlx` dead-letter exchange, `<queue>.dlq` queues and dead-letter routing keys. JSON, snake_case, the AMQP `message_id` is the event id (delivery is at least once, so a consumer drops an id it has already handled).

| Routing key | Published when | Body |
| --- | --- | --- |
| `poll.started` | a draft is published | `poll_id`, `title`, `anonymous`, `allow_vote_change`, `questions[{id, text, multiple, options[{id, text}]}]` |
| `poll.ended` | a published poll is closed by its owner (`reason: "closed"`) or blocked by a moderator (`reason: "blocked"`) | `poll_id`, `reason` |

The content of a published poll cannot change, so `poll.started` is the whole snapshot voting needs. Blocking a draft or a poll that is already closed publishes nothing: no votes are being taken.

Events are written to the `outbox_messages` table in the transaction of the change itself and published by `OutboxRelay` (every `OUTBOX_RELAY_INTERVAL`, default `1s`) with publisher confirms and mandatory publishing. Both subscriptions are declared before every non-empty batch: an offline or not-yet-started consumer has its own durable copy waiting. Failure to declare either subscription keeps the event in the outbox.

Migration V3 adds a singleton `outbox_relay_lock` row. One relay transaction holds it while sending a batch and committing confirmed deletions or retry metadata. Other instances skip the relay lock and try again later; they never skip earlier outbox events. API instances remain independent, but publishing is intentionally serialized. The batch stops at its first failure. Stop old relay versions before rolling out this migration, because older binaries do not acquire the singleton lock. Delivery remains at least once: consumers must deduplicate `message_id`.

`allowVoteChange` (optional in the create/update request, `false` by default) tells Vote Service whether a participant may change or revoke a vote; an anonymous poll never allows it.

| Variable | Default | Description |
| --- | --- | --- |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | broker address |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | broker credentials |
| `RABBITMQ_VHOST` | `/` | virtual host |
| `OUTBOX_RELAY_ENABLED` | `true` | switch the relay off (the tests do) |
| `OUTBOX_RELAY_INTERVAL` | `1s` | how often pending events are published |

## Current scope

Inter-service integration uses REST and RabbitMQ. Closing or blocking a poll is delivered to Vote and Result Service as `poll.ended`.
Deletion is limited to never-published drafts until Vote Service provides a coordinated no-votes check.
Anonymity is stored as metadata; Vote/Result services must enforce it. Full-system Keycloak integration and performance/scaling requirements have not been verified.
