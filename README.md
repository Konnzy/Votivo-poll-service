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
docker compose up -d postgres

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

GitHub Actions runs the build and PostgreSQL 18 tests.

## Events (RabbitMQ)

Poll Service publishes to the durable topic exchange `poll.events.exchange`. Vote Service and Result Service consume the events; each declares and binds its own queue. JSON, snake_case, the AMQP `message_id` is the event id (delivery is at least once, so a consumer drops an id it has already handled).

| Routing key | Published when | Body |
| --- | --- | --- |
| `poll.started` | a draft is published | `poll_id`, `title`, `anonymous`, `allow_vote_change`, `questions[{id, text, multiple, options[{id, text}]}]` |
| `poll.ended` | a published poll is closed by its owner (`reason: "closed"`) or blocked by a moderator (`reason: "blocked"`) | `poll_id`, `reason` |

The content of a published poll cannot change, so `poll.started` is the whole snapshot voting needs. Blocking a draft or a poll that is already closed publishes nothing: no votes are being taken.

Events are written to the `outbox_messages` table in the transaction of the change itself and published by `OutboxRelay` (every `OUTBOX_RELAY_INTERVAL`, default `1s`) with publisher confirms. Publishing is mandatory: an event that no queue is bound for is returned by the broker and stays in the outbox until a consumer declares its queue, so nothing is lost while the other services are still starting. The relay stops at the first failure to keep the order.

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
