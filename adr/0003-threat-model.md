# 3. Threat model

Status: accepted. Revisit when the cloud deployment (phase 6) exists.

## What is protected

- **The platform's own network.** The worker makes HTTP requests to URLs that customers choose. Anything
  it can reach that a customer cannot (the database, the queue, the cloud metadata service) is a target.
- **Customers' data and isolation.** Event payloads, endpoint secrets, delivery history.
- **The signing secret of each endpoint.** Whoever holds it can forge webhooks that the receiver will
  trust.
- **Availability for everyone.** One tenant or one receiver must not be able to slow the others down.

## Who attacks, and how

| Attacker | Attempt | What stops it | Proven by |
|---|---|---|---|
| A customer | Registers an endpoint URL that points inside the platform (`169.254.169.254`, `10.x`, `localhost`, `[::1]`, `2130706433`, `0177.0.0.1`) so the worker fetches internal data | The registration check refuses literal private addresses, the name `localhost`, and every unusual IP notation. It is a convenience, not the defence | `EndpointUrlValidatorTest`, `EndpointApiTest` |
| A customer | Registers a name that resolves to a private address | The guard judges what the name resolves to at the moment of connecting, and refuses the whole name if any returned address is private | `GuardedDnsResolverTest`, `BlockedDestinationTest` |
| A customer | DNS rebinding: a name that is public when registered and `127.0.0.1` at delivery | There is no gap between checking and using: the resolver that checks is the one the HTTP client connects through | `GuardedDnsResolverTest`, `WebhookSenderTest.dnsRebindingIsStoppedAtDeliveryTime` |
| A customer | A receiver that answers `301` to `http://169.254.169.254/` | Redirects are never followed; the `3xx` is recorded as a failed attempt | `WebhookSenderTest.aRedirectIsNotFollowed` |
| A customer | IPv4 hidden inside IPv6 (`::ffff:127.0.0.1`, NAT64, 6to4) | The embedded IPv4 address is extracted and judged as IPv4 | `AddressPolicyTest` |
| A customer | A receiver that is slow, drips bytes, or never answers, to tie up workers | A hard deadline on the whole exchange, a 1 KiB cap on the answer read, a per-endpoint concurrency cap, and the circuit breaker that switches the endpoint off | `WebhookSenderTest`, `EndpointConcurrencyTest`, `EndpointBreakerTest` |
| A customer | Floods `POST /v1/events` | Per-tenant token bucket with `429` and `Retry-After`, shared across API instances | `RateLimitApiTest`, `PostgresPublishRateLimiterTest`, ADR 2 |
| A customer | Reads or changes another tenant's data | Every query takes the tenant from the authenticated key, never from the request; another tenant's row is a `404` | `TenantIsolationTest` |
| Someone with a captured webhook | Replays it to the receiver | The signature covers the id, the timestamp and the body; the receiver rejects stale timestamps and deduplicates by `webhook-id` | `SignerTest`, README (receiver duties) |
| Someone with read access to the database | Reads endpoint secrets | Secrets are stored encrypted with AES-256-GCM, bound to their endpoint, under a versioned key held outside the database | `EndpointSecretCipherTest` |
| Someone with write access to the database | Edits a stored secret, or copies one onto another endpoint | Decryption fails authentication; the delivery is not sent and no signature is produced | `EndpointSecretCipherTest`, `DeliveryEndToEndTest.aTamperedSecretIsNeverUsedAndNothingIsSent` |
| Someone with access to the logs | Looks for keys and secrets | API keys are stored as SHA-256 hashes and shown once; secrets and keys are never logged, checked with the most verbose logging on | `LogSecrecyTest` |
| A customer | Guesses an API key | 256 random bits behind a lookup by hash; revoked keys stop working at once | `AuthenticationTest` |

## What does not stop an attack, said plainly

- **The registration check can be passed by a name.** It never does a DNS lookup, so a hostname that
  points somewhere private is accepted at registration and refused at the first delivery. The customer
  finds out when the endpoint is switched off with reason `blocked_destination`, not when it is
  created. This is on purpose: a DNS answer at registration proves nothing about delivery time, and
  registration should not depend on DNS being up.
- **Blocking is by IP range, not by intent.** A receiver on a public address that proxies to an internal
  service is outside what the platform can see.
- **Nothing outside the application yet.** A Kubernetes `NetworkPolicy` that denies the worker's egress
  to internal ranges, and IMDSv2 required on the nodes, are planned for phase 6. Until they exist the
  guard in the code is the only layer, and a bug in it is a hole. Both were not verified in an AWS
  environment, because there is none yet.
- **Rotating the master key does not re-encrypt old rows.** A retired key must stay configured until
  none is left. There is no job for that yet.
- **A leaked endpoint secret** lets the holder forge webhooks to that one receiver until the secret is
  replaced. There is no secret rotation for endpoints yet (it needs two valid secrets at once, which the
  signature header supports and the schema does not).
- **The development switch** `webhooks.endpoints.allow-private-destinations` turns the guard off. It
  exists because a local receiver is exactly what the guard refuses, it logs a warning at startup, and
  it defaults to off. A deployment that sets it is unprotected.
- **Rate limiting costs a database round trip per publish and, for a flood of rejected requests, can
  crowd the connection pool.** See ADR 2.
- **No protection against a malicious but valid receiver reading the payload.** The payload is the
  customer's own data sent to the customer's own endpoint.

## Not in scope

Denial of service at the network layer, compromise of the cloud account or the build pipeline, and the
security of the receiver's own code.
