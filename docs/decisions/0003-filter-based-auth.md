# 0003 - Servlet filters for authentication, not Spring Security

**Status:** Accepted, 2026-09-23
**Applies to:** `config.ApiSecurityConfig`, `config.AdminAuthFilter`, `config.DeviceAuthFilter`

## Context

The API has two kinds of caller with nothing in common:

| Paths | Credential |
|---|---|
| `POST /api/v1/readings` | `X-Device-Key` header, one key per device |
| everything else under `/api/v1` | `Authorization: Bearer <ADMIN_TOKEN>` |

There are no users, no roles, no sessions, no login flow and no token issuance.
The admin token is a single value from an environment variable, and a device
key is looked up by digest. The whole of the behaviour is "read a header,
check it, either continue or return 401".

The README already states that the admin token is not production-grade auth
and asks for the tradeoff to be recorded.

## Decision

Two `OncePerRequestFilter` implementations, registered over explicit URL
patterns by `FilterRegistrationBean`. Spring Security is not used.

`/actuator/**` is outside both filters: the load balancer health check and the
Prometheus scrape have no credential to present, and only `health` and
`prometheus` are exposed.

## Alternatives

**Spring Security.** The right answer for sessions, OAuth2, method security, or
anything with users and roles. Here it would mean adding a framework whose
filter chain, `AuthenticationManager`, `SecurityContext` propagation and
configuration DSL all have to be understood and explained -- in order to
justify about sixty lines of behaviour. The project's working agreement is that
the owner must be able to explain every part of this system, and a dependency
whose concepts outnumber the requirements works against that.

This would change the moment any of the following appear: more than one
principal, role-based access, token expiry or rotation, or a browser login. At
that point hand-rolling stops being simpler and starts being a liability, and
this ADR should be superseded rather than stretched.

**A single filter with a branch on the path.** Fewer moving parts, but it
mixes two unrelated credential schemes in one class and makes "which paths are
covered by what" a matter of reading an `if` rather than reading the
registration. Two filters keep that answerable from
`ApiSecurityConfig` alone.

## Consequences

### What this costs

Everything Spring Security would have provided correctly must now be got right
by hand. Specifically:

- **Constant-time comparison.** `AdminAuthFilter` hashes both the configured
  and the presented token before comparing with `MessageDigest.isEqual`.
  Hashing first is what makes it genuinely constant-time: `isEqual` returns
  early on a length mismatch, so comparing raw tokens would leak the secret's
  length. Two SHA-256 digests are always 32 bytes.
- **No path left uncovered.** Servlet URL patterns cannot express exclusions,
  so the admin filter is registered on all of `/api/v1/*` -- which necessarily
  includes the ingest path -- and stands aside for it in `shouldNotFilter`.
  Getting this wrong in the other direction would leave the ingest path
  unauthenticated.
- **Correct 401 shape.** Filters run before the `DispatcherServlet`, so Spring
  MVC's Problem Details handling does not apply. `common.ProblemResponses`
  serializes the same `ProblemDetail` type the controllers use, with a
  `WWW-Authenticate` challenge.
- **Filters must not become beans.** Spring Boot auto-registers any `Filter`
  bean against every request path. The filters are therefore constructed
  inside the registration `@Bean` methods and are neither `@Component` nor
  beans themselves, so the registrations are the only way they are applied.

Each of those is what the integration tests target, precisely because nothing
else is checking them.

### The admin token itself

Not production-grade, deliberately:

- one token for everyone, so there is no audit trail of who did what
- no expiry, and rotating it means a redeploy
- no scopes; the token grants everything the admin API can do
- compromise is total and silent until the token is changed

It is mandatory and has no default. The application refuses to start without
it, because starting with a blank token would serve every admin endpoint to
anyone who asks, and would do so silently.

Replacing this is the first thing to do before this API holds real data.

## See also

- [0004 - SHA-256 for device API keys](0004-sha-256-for-device-api-keys.md)
