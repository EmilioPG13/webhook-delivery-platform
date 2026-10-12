# 8. Endpoint secrets are encrypted in PostgreSQL

Status: accepted

## Context

Signing a delivery needs the endpoint's secret in plaintext, so it cannot be stored as a hash the way API keys are (an
API key is only ever compared; a signing secret is used). The secret has to be stored somewhere the worker can read
it for every attempt.

## Decision

Each secret is encrypted with AES-256-GCM in the `endpoints` row. The nonce is random per secret and the endpoint id is
the associated data, so a ciphertext copied onto another endpoint's row fails to decrypt. One master key encrypts them
all: it comes from Secrets Manager on AWS (read once at startup, with the pod's own role) and from an environment
variable locally. The app refuses to start without one. `secret_key_version` records which key encrypted a row, so
the master key can be rotated: new secrets use the new key and old ones still decrypt through the retired one.

The secret is shown once, when the endpoint is created. Keys and secrets are never logged, and a test fails if one
appears in the logs.

## Consequences

- Someone who gets both the database and the master key reads every endpoint secret. That is a stated limit, not an
  oversight.
- The master key is read once, so rotating it means restarting the pods.
- No network call sits in the delivery path: decrypting is local.

## Alternatives considered

- **One Secrets Manager secret per endpoint.** Costs 0.40 USD per secret per month, which grows with every customer,
  and puts a network call in front of every attempt.
- **KMS envelope encryption**, with a data key per secret wrapped by a KMS key. This is the production step and would
  remove the single master key from the process. Not built here; it is the first thing I would change.
- **Plaintext in the database.** A database backup or a read-only SQL injection would then hand over every secret.
- **Hashing the secret.** Impossible, since signing needs the value.
