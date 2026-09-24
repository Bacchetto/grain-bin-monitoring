# 0009 - Spring Boot 4.1, not 3.5

**Status:** Accepted, 2026-09-23 (recorded 2026-09-24)
**Applies to:** `backend/pom.xml`

## Context

The README specifies "latest stable Spring Boot". In September 2026 that is
**4.1.1**. The 3.5 line is still supported, and far more of the documentation,
tutorials and other material in circulation describes it.

## Decision

**Spring Boot 4.1.x**, chosen by the project owner. It is the literal reading of
the specification, and building on the current major line keeps the project
current for longer.

## Alternatives

**Spring Boot 3.5.x.** Still supported, and almost every search result would
match it exactly. Rejected because it contradicts the specification and has the
shorter remaining support window. Its advantage -- familiarity -- is real, and
the consequences below are largely the price of giving it up.

## Consequences

Each of these was hit during Milestone 1, and each is recorded where the code
that needs it lives:

| Change in Boot 4 | Where it surfaced |
|---|---|
| `spring-boot-starter-web` is now `spring-boot-starter-webmvc`; Flyway needs an explicit starter | `pom.xml`, Phase 2 |
| `spring-boot-starter-test` is split into per-module `*-test` starters | `pom.xml`, Phase 2 |
| Jackson 3 -- but only `jackson-core` and `jackson-databind` move to `tools.jackson`; `jackson-annotations` stays on `com.fasterxml` | README tech stack notes |
| JUnit 6, with the Jupiter API unchanged | README tech stack |
| `TestRestTemplate` moved to a module not on the classpath by default | Tests use `RestTestClient` instead |
| `@LocalServerPort` moved package | `WebIntegrationTest` |
| **Every metrics exporter is disabled by default** | `/actuator/prometheus` returned 404 until `management.prometheus.metrics.export.enabled=true` |

The last is the only one that failed silently. Exposing the endpoint was not
enough, and nothing reported the problem; it was found because a test asserted
the endpoint was reachable.

The general lesson: when something does not compile or does not appear, check
the 4.x documentation and the resolved classpath before trusting remembered 3.x
patterns. Most of the fixes above came from reading class files in the actual
jars, not from search results.
