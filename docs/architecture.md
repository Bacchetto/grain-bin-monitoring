# Architecture

> **Status: not yet written.** This document is filled in during Milestone 4.
> The outline below is the agreed shape so the gap is visible rather than
> silently missing. For now, the [README](../README.md) is the authoritative
> description of the system.

## Planned contents

- **System diagram.** Devices and simulator, ALB, the Spring Boot service on
  ECS Fargate, RDS PostgreSQL, and the S3 + CloudFront front end.
- **Request walkthrough: ingest to alert.** Following a single batch from
  `POST /api/v1/readings` through device authentication, partition resolution,
  the idempotent insert, the `last_seen_at` update, and synchronous threshold
  evaluation to an alert row and a Micrometer counter.
- **Data model.** Bins, devices, samples, readings, alerts, and why `readings`
  is partitioned by month.
- **Failure behaviour.** Duplicate batches, out-of-order samples, clock skew
  on the device, a missing partition, and a device that goes quiet.
- **What is deliberately not here.** Scope boundaries and the tradeoffs taken
  for cost or simplicity, each linking to its ADR.
