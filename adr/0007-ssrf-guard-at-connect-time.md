# 7. The SSRF guard judges the address the connection uses

Status: accepted

## Context

Customers choose the URLs the worker calls, so a customer can try to make the platform call something inside its
own network: the metadata service, the database, another pod. The URL can point there directly with an IP, or through
a name whose DNS answer changes between registration and delivery (DNS rebinding). The broader picture is in
[ADR 3](0003-threat-model.md); this records the decision about where the check lives.

## Decision

A custom `DnsResolver` is the guard, and the HTTP client connects through it. The addresses that were checked are
therefore exactly the ones the socket opens. If any address a name resolves to is private, loopback, link-local or
otherwise reserved, the whole name is refused, the attempt is recorded as `blocked_destination`, and the endpoint is
switched off at once. Redirects are never followed, because a redirect is another URL the receiver picks.

Registration also checks the URL, but only as a convenience for the customer: it never does a DNS lookup, and it
rejects literal private IPs, `localhost`, and any host whose last label is a number (which catches the decimal,
octal, hex and short IP notations without parsing each of them).

## Consequences

- A name that resolves to a public address at registration and a private one at delivery is caught, which is the
  rebinding attack.
- Registration does not depend on DNS being up, and tests do not depend on the network.
- A development switch, `allow-private-destinations`, turns the guard off, because a local receiver is exactly what it
  refuses. It defaults to off and logs a warning when on. `compose.yaml` sets it; production must never.
- The guard is one layer. The Kubernetes `NetworkPolicy` on the worker, which denies the private ranges at the network
  level, backs it up from outside, and it was checked on a real cluster.

## Alternatives considered

- **Resolve and check at registration only.** An answer at registration says nothing about delivery time.
- **Check the address, then connect to the name.** The gap between the two calls is the attack.
- **Parse every IP notation and judge it.** Easy to get wrong. Judging the resolved address makes the notation
  irrelevant.
- **An egress proxy that does the filtering.** A sound design for production, and one more component to run and
  secure. Not built.
