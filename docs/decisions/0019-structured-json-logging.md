# 0019 - Structured JSON logging, built into Spring Boot

**Status:** Accepted, 2026-09-25 (recorded 2026-09-28)
**Applies to:** `application.properties`, `application-local.properties`, `alerts.AlertTransitions`

## Context

The README requires every alert state change to be "logged as structured
JSON". Production logs will go to CloudWatch in Milestone 3, where JSON fields
can be queried directly. A plain-text line needs a parsing pattern, which
breaks whenever the message wording changes.

## Decision

**Spring Boot's built-in structured logging**:
`logging.structured.format.console=logstash` writes one JSON object per line.
Log calls that carry data use SLF4J's fluent `addKeyValue`, and each pair
becomes its own JSON field -- `alertId`, `binId`, `alertType`, `toState`.

The **`local` profile turns it off** (an empty value), so a developer at a
terminal reads plain text. The message text therefore repeats the essentials --
`Alert 12 HIGH_TEMPERATURE on bin 3 -> OPEN` -- because the plain format
prints only the message, not the key-value pairs.

## Alternatives

**logstash-logback-encoder.** The long-standing library for this. Boot has
covered it natively since 3.4, so it would be a dependency for nothing.

**JSON everywhere, including local development.** Accurate, but unreadable at a
terminal.

**Plain text everywhere.** Fails the README's requirement.

## Consequences

- Tests run with the shipped configuration, so they log JSON, and one test
  asserts that a transition's line is JSON with its fields as separate keys.
- **The local-profile gap was found by running it, not by a test.** Every
  transition first read only "Alert transition" at a terminal, because the
  key-value pairs appear only in JSON.
- Only the logs changed format. Metrics are separate: the
  `alerts_transitions_total` counter is incremented at the same moment, after
  commit.
