# Order Service

Order management microservice of the course e-commerce application
(Innowise Java Advanced). Part of a system with Authentication Service,
API Gateway, User Service and Payment Service.

> Work in progress: the database schema and the entities are done (JACAG-12).

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data JPA)
- PostgreSQL 18 with Liquibase migrations
- Log4j2 (SLF4J API in the code)
- JUnit 5, Testcontainers

## Requirements

- JDK 21
- Docker (the tests and the local PostgreSQL)
- Maven Wrapper is included in the project

## Configuration

The profile `local` is the default. Environment variables override every default.

| Variable | Default | Meaning |
|---|---|---|
| `DB_USERNAME` | none, required | database user |
| `DB_PASSWORD` | none, required | database password |
| `DB_HOST` | `localhost` | database host |
| `DB_PORT` | `5435` | database port |
| `DB_NAME` | `order_service_db` | database name |
| `SERVER_PORT` | `8083` | HTTP port |
| `LOG_DIR` | `logs` | folder of the log files |

Credentials are never stored in the repository.

## Run locally

Start PostgreSQL (port 5435, because 5432 may be taken by a native installation):

```powershell
docker run -d --name order-service-db -e POSTGRES_DB=order_service_db `
  -e POSTGRES_USER=$env:DB_USERNAME -e POSTGRES_PASSWORD=$env:DB_PASSWORD `
  -p 5435:5432 postgres:18
```

Then run the application. The variables live only in the current PowerShell tab:

```powershell
$env:DB_USERNAME = "order_user"
$env:DB_PASSWORD = "<your password>"
.\mvnw spring-boot:run
```

## Database schema

Liquibase creates the schema on startup (`src/main/resources/db/changelog`).

- `items`: `id`, `name`, `price`, `deleted` (soft delete). Managed by an admin.
- `orders`: `id`, `user_id`, `user_email`, `status`, `total_price`, `deleted` (soft delete).
  `user_id` is a plain value: the user lives in the User Service.
- `order_items`: `id`, `order_id`, `item_id`, `quantity`, `unit_price`. `unit_price` is a copy of
  the price at the moment of the order, so a price change does not change old orders.

Every table also has `created_at` and `updated_at` (JPA Auditing, time from the application clock).
Order statuses: `PENDING`, `PAID`, `PAYMENT_FAILED`, `CANCELLED`.

## Tests

```powershell
.\mvnw verify
```

The integration tests (tag `integration`) start PostgreSQL with Testcontainers and need Docker.
To skip them: `.\mvnw verify "-DexcludedGroups=integration"`. The JaCoCo report is written to
`target/site/jacoco/`.
