# Votivo Poll Service

Poll management microservice for Votivo, built for Lab 4 with Java 25, Spring Boot 4.1.2, Gradle 9.5.1 (Kotlin DSL), and PostgreSQL.

## Features

- Draft creation and editing; lifecycle: `DRAFT → PUBLISHED → CLOSED → ARCHIVED`.
- Public and link-based access, anonymity settings, single/multiple-choice questions.
- Keycloak JWT authentication, owner permissions, and admin moderation.
- Flyway migrations, optimistic locking, pagination, and validation (50 questions, 20 options per question).

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
docker compose --profile app up --build -d
```

Container JWKS defaults to `host.docker.internal`; override `CONTAINER_KEYCLOAK_JWK_SET_URI` when needed.

## Tests

```powershell
./gradlew.bat build
```

API tests use H2; JWT tests verify signed tokens. PostgreSQL tests require a **separate test database** and clear its tables:

```powershell
docker compose exec postgres createdb -U poll polls_test
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5433/polls_test'
$env:TEST_DB_USERNAME = 'poll'
$env:TEST_DB_PASSWORD = $env:DB_PASSWORD
./gradlew.bat postgresTest
```

GitHub Actions runs the build and PostgreSQL 18 tests.

## Current scope

Vote Service and RabbitMQ integration is pending. Closing or blocking a poll currently changes only local state; events are not delivered to other services.
Deletion is limited to never-published drafts until Vote Service provides a coordinated no-votes check.
Anonymity is stored as metadata; Vote/Result services must enforce it. Full-system Keycloak integration and performance/scaling requirements have not been verified.
