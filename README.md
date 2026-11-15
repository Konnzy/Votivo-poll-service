# Votivo Poll Service

Мікросервіс керування опитуваннями для Votivo. Базова реалізація лабораторної №4:
Java 25, Spring Boot 4.1.2, Gradle 9.5.1 (Kotlin DSL), PostgreSQL та Layered architecture.

## Реалізовано

- Створення чернетки з питаннями й варіантами; отримання та повна заміна вмісту чернетки.
- Життєвий цикл `DRAFT → PUBLISHED → CLOSED → ARCHIVED` з перевіркою власника.
- Публічний каталог активних опитувань, власні опитування, доступ за непередбачуваним посиланням.
- Налаштування анонімності та вибору однієї/кількох відповідей.
- JWT Keycloak: підпис, issuer, audience, строк дії та наявність subject. Роль адміністратора: realm role `ADMIN`.
- Перегляд усіх опитувань і блокування адміністратором; приховування заблокованих опитувань від інших користувачів.
- PostgreSQL, Flyway, optimistic locking, пагінація та помилки у форматі Problem Details.
- API/безпекові тести, окремі PostgreSQL-тести та GitHub Actions.

## Межі поточної версії

Poll Service не приймає голоси та не підраховує результати. Vote Service і RabbitMQ ще не інтегровані.
`close` змінює стан у Poll DB; припинення приймання голосів у Vote Service потребує узгодженого контракту.
Події публікації/завершення поки не надсилаються. Повідомлення про успішну доставку не імітуються.

Видаляти можна лише чернетки, які ніколи не публікувалися. FR-6 для вже опублікованих опитувань
без голосів буде завершено після реалізації узгодженої перевірки у Vote Service.
Налаштування `anonymous` є метаданими; захист зв'язку «користувач–відповідь» реалізовують також Vote/Result Service.
Автоматичний розклад, приватні запрошення, зміна голосу, Redis та AWS не входять до цього етапу.

## Структура й архітектура

```text
src/main/java/ua/votivo/poll/
  controller/    HTTP API
  dto/           запити й відповіді
  service/       бізнес-правила, права та транзакції
  model/         JPA-сутності та enum
  repository/    доступ до даних через Spring Data JPA
  mapper/        перетворення DTO та моделей
  security/      поточний користувач з JWT
  config/        конфігурація безпеки
  exception/     бізнес-помилки й HTTP Problem Details
src/main/resources/db/migration/  версійовані SQL-міграції
src/test/                        API, JWT і PostgreSQL-тести
docs/                            архітектура та OpenAPI
```

Залежності: **Controller → Service → Repository → PostgreSQL**. Контролер не читає БД напряму.
Транзакції визначено в сервісному шарі, JPA-сутності не повертаються через API.
Деталі та рішення щодо суперечностей попередніх робіт: [architecture.md](docs/architecture.md).
Специфікація API: [api.json](docs/api.json) (OpenAPI 3.1).

## Локальний запуск у PowerShell

Потрібні JDK 25, Docker з Docker Compose та доступний Keycloak. Встановлювати Gradle окремо не потрібно.

```powershell
Copy-Item .env.example .env
# Відредагуйте DB_PASSWORD у .env; файл не комітиться.
docker compose up -d postgres

# Docker Compose читає .env автоматично; Spring Boot не завантажує його самостійно.
Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $entry = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($entry[0], $entry[1], 'Process')
}
./gradlew.bat bootRun --args="--spring.profiles.active=local"
```

API: `http://localhost:8082/api/v1/polls`. PostgreSQL: `localhost:5433`, база `polls`.
Профіль `local` не вимикає перевірку JWT. Публічний перегляд не вимагає токена.

На Linux/macOS використовуйте `./gradlew`; експортуйте змінні з `.env` у своєму shell.

| Змінна | Призначення / типове значення |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/polls` |
| `DB_USERNAME` | `poll` |
| `DB_PASSWORD` | Обов'язкова; задається локально |
| `SERVER_PORT` | `8082` |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8080/realms/votivo` |
| `KEYCLOAK_JWK_SET_URI` | `http://localhost:8080/realms/votivo/protocol/openid-connect/certs` |
| `KEYCLOAK_AUDIENCE` | `poll-service` |

У Keycloak налаштуйте realm `votivo`, audience mapper, що додає `poll-service` до `aud` access token,
та realm role `ADMIN` для адміністратора. Власник береться виключно з `sub` перевіреного JWT.
Значення issuer має точно збігатися з `iss`; JWKS URL має бути доступним із середовища сервісу.
Для розгорнутого середовища налаштуйте HTTPS на API Gateway та захищений мережевий доступ до сервісу.

## Приклад створення та життєвого циклу

```powershell
$headers = @{ Authorization = "Bearer $env:ACCESS_TOKEN" }
$body = @{
    title = 'Мова наступного проєкту'
    description = 'Оберіть один варіант'
    accessType = 'PUBLIC'
    anonymous = $true
    questions = @(@{
        text = 'Яку мову обрати?'
        type = 'SINGLE'
        options = @(@{ text = 'Java' }, @{ text = 'Go' })
    })
} | ConvertTo-Json -Depth 8
$poll = Invoke-RestMethod http://localhost:8082/api/v1/polls -Method Post `
    -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod "http://localhost:8082/api/v1/polls/$($poll.id)/publish" -Method Post -Headers $headers
Invoke-RestMethod "http://localhost:8082/api/v1/polls/$($poll.id)/close" -Method Post -Headers $headers
Invoke-RestMethod "http://localhost:8082/api/v1/polls/$($poll.id)/archive" -Method Post -Headers $headers
```

`PUT /api/v1/polls/{id}` приймає такий самий формат і повністю замінює вміст чернетки.
Для `LINK` власник отримує `shareToken`: перегляд через `/api/v1/polls/by-link/{shareToken}`.
Секретний токен не потрапляє до списків або відповідей іншим користувачам. Посилання на чернетку недоступне.

Максимум 50 питань, 20 варіантів на питання. Чернетка може бути неповною;
публікація вимагає хоча б одного питання і двох варіантів на кожне питання.
Після публікації зміни вмісту заборонені. Архівовані опитування доступні за прямим дозволеним доступом,
але не входять до активного каталогу. Списки: `page=0`, `size=20`, максимум `size=100`.

## Збірка та перевірки

```powershell
./gradlew.bat build
```

Звичайні тести використовують H2 у режимі PostgreSQL, справжні шари застосунку й SQL-міграцію.
HTTP-тести підставляють автентифікацію; окремі JWT-тести перевіряють підписані RSA-токени через локальний JWKS.
Це не замінює перевірку інтеграції з командним Keycloak.

PostgreSQL-тести потребують **окремої тестової БД**, оскільки очищають її таблиці:

```powershell
# Для локальної Compose-БД; POSTGRES_USER має право створення бази.
docker compose exec postgres createdb -U poll polls_test
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5433/polls_test'
$env:TEST_DB_USERNAME = 'poll'
$env:TEST_DB_PASSWORD = $env:DB_PASSWORD
./gradlew.bat postgresTest
```

CI запускає `build postgresTest` із PostgreSQL 18. Перевіряються міграція, порядок питань/варіантів,
видалення дочірніх даних та конфлікт застарілого конкурентного оновлення.

Запуск застосунку в контейнері:

```powershell
docker compose --profile app up --build -d
```

Для контейнера JWKS за замовчуванням доступний через `host.docker.internal` (Docker Desktop).
На іншому хості задайте `CONTAINER_KEYCLOAK_JWK_SET_URI`; issuer залишається рівним `iss` токена.
`docker compose down` зупиняє контейнери зі збереженням даних у volume.

## Команда та внесок

| Учасник | Компонент / відповідальність зі специфікації |
| --- | --- |
| Холоденко Д. Р. | Poll Service: опитування, питання, варіанти, доступ і життєвий цикл |
| Буряк В. С. | User Service, Keycloak, API Gateway, координація інтеграції |
| Колісниченко Р. Р. | Result Service: результати й статистика |
| Рейль Є. В. | Vote Service: приймання голосів і правила участі |

Зміни оформлюються окремою гілкою й pull request з код-рев'ю перед merge.
Після злиття цього компонента окремим PR оновлюється commit submodule
`backend/services/poll-service` в інтеграційному репозиторії `koliruslik/Votivo`.
