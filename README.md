# Order Service

Order management microservice of the course e-commerce application
(Innowise Java Advanced). Part of a system with Authentication Service,
API Gateway, User Service and Payment Service.

> Work in progress: the database schema, the entities, the DAO layer and the service layer are
> done (JACAG-12, JACAG-13, JACAG-14). The controllers and the security come next (JACAG-15).

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data JPA)
- PostgreSQL 18 with Liquibase migrations
- Log4j2 (SLF4J API in the code)
- Bean Validation, MapStruct, Resilience4j (core module, circuit breaker)
- JUnit 5, Mockito, Testcontainers, WireMock

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
| `INTERNAL_API_KEY` | none, required | shared key of the internal endpoints of the other services; an empty value stops the startup |
| `USER_SERVICE_URI` | `http://localhost:8082` | internal address of the User Service (never the API Gateway) |
| `AUTH_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | internal address of the key set of the Authentication Service (never the API Gateway) |
| `JWT_ISSUER` | `authentication-service` | the issuer that a token must have; the same value as in the Authentication Service |

The circuit breaker of the calls to the User Service is configured with
`app.user-service.circuit-breaker.*`; the defaults are a window of 10 calls, at least 5 calls
before the rate is judged, 50 percent failures to open, 10 seconds open, 3 probes. The timeouts
are `app.user-service.connect-timeout` (2 s) and `app.user-service.read-timeout` (5 s).

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
$env:INTERNAL_API_KEY = "<the same key as in the User Service>"
.\mvnw spring-boot:run
```

Every endpoint needs an access token, so the Authentication Service (port 8081) must be
running: the service reads its key set on the first request with a token. The orders also need
the User Service (port 8082) to show the owner; without it an order is still returned, with
`userInfoAvailable = false`.

## Database schema

Liquibase creates the schema on startup (`src/main/resources/db/changelog`).

- `items`: `id`, `name`, `price`, `deleted` (soft delete). Managed by an admin.
- `orders`: `id`, `user_id`, `user_email`, `status`, `total_price`, `deleted` (soft delete),
  `version` (optimistic locking).
  `user_id` is a plain value: the user lives in the User Service.
- `order_items`: `id`, `order_id`, `item_id`, `quantity`, `unit_price`. `unit_price` is a copy of
  the price at the moment of the order, so a price change does not change old orders.

Every table also has `created_at` and `updated_at` (JPA Auditing, time from the application clock).
Order statuses: `PENDING`, `PAID`, `PAYMENT_FAILED`, `CANCELLED`.

## Data access (DAO layer)

- `OrderRepository` extends the marker `Repository`, not `JpaRepository`, and exposes only
  `saveAndFlush`, `findById`, `findAll(Specification, Pageable)` and `findAllByUserId`. There is
  no `delete`: an order is deleted softly with `Order.markDeleted()`.
- Deleted orders are hidden by `@SQLRestriction("deleted = false")` on the entity, so every query
  (and the count query of a page) skips them. Items are not hidden this way: the lines of old
  orders must still see them.
- `OrderSpecifications` builds the filter of the order list: creation date range
  (`created_at >= from AND created_at < before`, half-open), statuses, owner. A filter that is not
  set does not appear in the query.
- `@Version` on `Order`: saving an order that somebody else changed meanwhile fails with an
  optimistic locking exception instead of overwriting the other change.
- `hibernate.default_batch_fetch_size: 50` loads the lines and items of a whole page of orders in
  a few queries instead of one query per order (N+1). `QueryCountIntegrationTest` guards it.

## Service layer

Three beans, so that an HTTP call never holds a database connection:

```
OrderFacade            no transaction; the entry point of the controllers
  |- OrderService      @Transactional (read-only by default); the database only
  `- UserInfoResolver  circuit breaker + UserServiceClient; asks the User Service
```

`OrderFacade` first lets `OrderService` do its work and commit, then asks the User Service for
the owner of the order. Every answer except the deletion has the owner: `user` and
`userInfoAvailable`.

| Situation | `user` | `userInfoAvailable` |
|---|---|---|
| the profile is found | the profile (`id`, `name`, `surname`, `email`) | `true` |
| nobody has this email (404) | `null` | `true` |
| the User Service cannot answer (down, slow, refused key, open breaker) | `null` | `false` |

An order that is saved stays saved and is returned complete; a failed lookup does not become an
error, because an error would invite the client to send the same `POST` again.

- **Prices.** The client sends item ids and quantities only. The unit price is copied from the
  catalog into the line (a snapshot), the total is calculated by the server. A later price change
  does not change an existing order until its lines are replaced; replacing the lines copies the
  current prices again, also for lines the client did not touch.
- **Replacing lines.** The line of an item that stays is updated in place; only lines of other
  items are removed and added. Hibernate runs inserts before deletes, so deleting and adding the
  line of the same item in one flush would break `UNIQUE (order_id, item_id)`.
- **Ownership.** `OrderService` works with a `Caller` (user id, email, admin flag). An order of
  somebody else is the same "not found" as a missing one. A `PAID` order cannot be edited or
  cancelled; only an admin deletes it.
- **Status transitions** are methods of `Order` (`cancel`, `markPaid`, `markPaymentFailed`); the
  status has no setter.
- **Row lock.** Every write loads the order with `SELECT ... FOR UPDATE`. A change of the lines
  alone does not touch the row of the order (the collection is the inverse side), so `@Version`
  would not notice it; the lock makes a payment and an update of the lines wait for each other.
- **Circuit breaker** (Resilience4j, core module, built in code). Only "the User Service cannot
  answer" counts as a failure; an unknown email is a normal answer. Every change of state is
  logged. A page of orders asks once for every distinct owner and stops at the first failure.
- **Limits of the request:** at most 50 lines, 1 to 1000 pieces per line, no item twice, item
  price up to 99999.99, so a total always fits into `NUMERIC(12,2)`.

## REST API

All paths are under `/api`. Every request needs `Authorization: Bearer <access token>`. Errors
are `application/problem+json` (RFC 9457); a validation failure is a 400 with an `errors` list of
`{field, message}` and never the rejected values.

| Method and path | Who | Success | Errors |
|---|---|---|---|
| `POST /api/orders` | user or admin, with a profile | 201, `Location` | 400, 403 (no profile in the token), 422 (item not available, total too large) |
| `GET /api/orders/{id}` | user (own order) or admin | 200 | 404 (also for the order of somebody else) |
| `GET /api/orders` | admin | 200, a page | 400 |
| `GET /api/orders/my` | user or admin, with a profile | 200, a page | 400, 403 (no profile in the token) |
| `PUT /api/orders/{id}` | user (own order) or admin | 200 | 400, 404, 409 (status does not allow it), 422 |
| `PATCH /api/orders/{id}/cancel` | user (own order) or admin | 200, also when already cancelled | 404, 409 (a paid order) |
| `DELETE /api/orders/{id}` | user (own order, not paid) or admin | 204 | 404, 409 |
| `GET /api/items`, `GET /api/items/{id}` | any role | 200 | 404 |
| `POST /api/items`, `PUT /api/items/{id}`, `DELETE /api/items/{id}` | admin | 201 (`Location`), 200, 204 | 400, 403, 404 |

- **Owner.** A new order belongs to the profile of the token (`user_id`); the body has no owner.
  Its `userEmail` is the `email` claim. A token without `user_id` or `email` cannot place an
  order or list "my" orders (403), also when it is an admin's: an order needs an owner.
- **Lists.** `page` (from 0), `size` (default 20, at most 100) and `sort=<property>,<asc|desc>`.
  The orders are sorted by `createdAt,desc` unless asked otherwise, the items by `id`. The
  sortable properties are `status`, `totalPrice`, `userId`, `createdAt`, `updatedAt` for the
  orders and `name`, `price`, `createdAt`, `updatedAt` for the items; `id` is always the last key.
  The answer is `{content, page, size, totalElements, totalPages}`.
- **Filters of the orders.** `createdFrom` and `createdBefore` are ISO-8601 instants
  (`2026-11-01T00:00:00Z`) and make the half-open range `[createdFrom, createdBefore)`; a range
  that is empty (`createdFrom` not before `createdBefore`) is a 400. `statuses` is a list
  (`statuses=PENDING,PAID` or the parameter repeated). `userId` is for the admin's list only;
  `/my` ignores it, the owner is always the caller.
- **Location.** The `Location` of a created resource is built from the request URL, so behind
  the API Gateway it points to the Gateway (`server.forward-headers-strategy: framework`).

## Security

The service is a resource server. A token is verified with the public key of the Authentication
Service (`AUTH_JWKS_URI`): RS256 only, the issuer, the time (with no tolerance, by the clock of
the application) and `token_type = access`. A key set that cannot be read is a 401, never a 500.
The role comes from the claim `role` (only the exact `ADMIN` and `USER` count).

- **Two layers.** `SecurityConfig` lists every path and method with the roles that may call it
  and denies everything else (401 without a token, 403 with one, also for unknown paths). Every
  controller method repeats its role with `@PreAuthorize`, and a test fails when a method has
  none.
- **Whose order.** The controller builds a `Caller` (profile, email, admin or not) from the
  token; the service decides: a user reaches only his own orders, and an order of somebody else
  is the same 404 as a missing one. An admin reaches every order.
- 401 and 403 have constant texts (`Authentication is required`, `Access is denied`); every kind
  of bad token gets the same answer. Tokens, emails and keys are never logged.

## Known limits

- A `POST` whose answer is lost and is sent again creates a second order: there is no
  idempotency key.
- The owner is looked up by the email that the order stored when it was created. If an admin
  changes the email of a profile in the User Service (the login in the Authentication Service
  does not change), the lookup finds nobody and the order shows `user = null` with
  `userInfoAvailable = true`.
- The state of the circuit breaker lives in the memory of one instance; every replica judges the
  User Service on its own.
- When the first lookup of a page fails, the rest of the page has no user data either, although
  the User Service might have answered for the other owners. This is on purpose: a hanging
  service would otherwise cost one timeout per owner.
- A token lives 15 minutes and cannot be revoked: a user that is deactivated or deleted in the
  User Service can still use a token that he already has until it expires.
- The email of a token is the login of the Authentication Service. If an admin changes the email
  of a profile, a new order stores the old one and the owner lookup finds nobody.
- A 401 has no `WWW-Authenticate` header (RFC 9110 asks for one), as in the other services.
- The payment of an order is started by the client in the Payment Service, which does not check
  the amount against the order (JACAG-24).

## Tests

```powershell
.\mvnw verify
```

The integration tests (tag `integration`) start PostgreSQL with Testcontainers and need Docker.
To skip them: `.\mvnw verify "-DexcludedGroups=integration"`. The JaCoCo report is written to
`target/site/jacoco/`.

The tokens are real: a small HTTP server plays the key set of the Authentication Service, and
the application verifies them the way it does in production. The web layer is tested with
`@WebMvcTest` and the real security chain, the access rules for every endpoint and caller with
`AccessMatrixIntegrationTest`, and a key set that is down or stops with `KeySetNotAvailableTest`
and `KeySetOutageTest`.

The User Service is played by WireMock. Tests of the client use a generous read timeout (10 s)
and a short one only in the test that proves the timeout; the breaker tests switch its state with
`transitionTo...State()` and never wait for a clock.
