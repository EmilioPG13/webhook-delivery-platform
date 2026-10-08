# webhook-delivery-platform

A service that accepts events from other applications and delivers them to the HTTP endpoints its
customers register. The point of the project is what happens when things go wrong: receivers that
are down, slow, or hostile.

**Status: under construction.** Event intake, tenants, API keys and endpoint management work.
Delivery (queue, signing, retries) is next, so nothing is sent to a receiver yet.

## What works today

- Multi-tenant API authenticated with hashed API keys (`Authorization: Bearer whk_...`).
- Endpoint management. Each endpoint gets a signing secret, shown once and stored encrypted
  (AES-256-GCM).
- `POST /v1/events` stores the event and creates one delivery per subscribed endpoint in a single
  transaction. With an `Idempotency-Key`, concurrent or repeated requests produce exactly one event.
- Cursor-paginated queries for events, deliveries and attempts. Tenants cannot see each other's data.
- Errors as RFC 9457 problem documents. OpenAPI docs at `/swagger-ui/index.html`.

## Stack

Java 25, Spring Boot 4.1, PostgreSQL 18, Flyway, Maven. Tests run against a real PostgreSQL 18
with Testcontainers.

## Run it locally

Requires a JDK 25 and Docker.

```powershell
docker compose up -d --wait
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The `dev` profile serves on port 8081 and uses public throwaway secrets. Create a tenant with the
operator token, then use the API key it returns:

```powershell
curl.exe -X POST http://localhost:8081/admin/tenants -H "Authorization: Bearer dev-admin-token" `
  -H "Content-Type: application/json" -d '{\"name\":\"Acme\"}'
```

Configuration for anything other than local development is in `.env.example`.

## Tests

```powershell
.\mvnw.cmd verify
```

## Performance

To be measured.
