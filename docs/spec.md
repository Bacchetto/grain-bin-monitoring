# Specification

What the system must do: the requirements it is built against. Where the implementation settled something a requirement left open, the result is noted here and the reasoning is in an [ADR](decisions/). Progress is tracked in [GitHub issues](https://github.com/Bacchetto/grain-bin-monitoring/issues?q=label%3Amilestone), one parent issue per milestone.

---

## Tech stack

| Area | Choice |
|---|---|
| Backend | Java 21 (LTS), Spring Boot 4.1.x, Maven (with wrapper) |
| Database | PostgreSQL 16+, Flyway migrations, native table partitioning |
| Testing | JUnit 6, AssertJ, Testcontainers (real Postgres, no H2), MockMvc |
| Front end | React + TypeScript, Vite, Recharts |
| Simulator | Python 3.11+, `requests`, `pytest` |
| Containers | Hand-written multi-stage Dockerfile, Docker Compose for local dev |
| CI/CD | GitHub Actions, OIDC federation to AWS (no long-lived keys) |
| Cloud | AWS `ca-central-1`: ECS Fargate, RDS PostgreSQL, ALB, ECR, S3 + CloudFront, CloudWatch |
| IaC | Terraform, S3 remote state with native lockfile (`use_lockfile = true`) |
| Observability | Spring Actuator, Micrometer (Prometheus format), Grafana locally, CloudWatch in AWS |
| Load testing | k6 |

> **Spring Boot 4 notes.** "Latest stable Spring Boot" resolved to 4.1.1, which
> pulls in **JUnit 6** and **Jackson 3** through its dependency management. The
> JUnit Jupiter API and its `org.junit.jupiter.api` package names are unchanged,
> so tests are written exactly as they would be under JUnit 5. Jackson 3 is a
> real move, and a partial one worth knowing exactly: `jackson-core` and
> `jackson-databind` move to group id `tools.jackson.core` at 3.1.5, while
> `jackson-annotations` stays at `com.fasterxml.jackson.core` 2.21. So mapper
> and module imports change, but `@JsonProperty` and friends still come from
> `com.fasterxml.jackson.annotation`. Spring's own `@JsonComponent` becomes
> `@JacksonComponent`.
>
> Boot 4 also renamed starters. `spring-boot-starter-web` is now
> `spring-boot-starter-webmvc`, Flyway needs an explicit
> `spring-boot-starter-flyway`, and `spring-boot-starter-test` is split into
> per-module `*-test` starters. Most tutorials still describe the Boot 3 names.
> Every difference met so far, including one that fails silently, is listed in
> [ADR 0009](decisions/0009-spring-boot-4.md).

Organize backend packages by feature (`ingest`, `alerts`, `bins`), not by layer.

---

## Domain model

- **Bin:** A physical grain bin. It has a name, a site name (the farm yard), a capacity in bushels, a grain type, and per-bin alert thresholds.
- **Device:** A monitoring controller attached to one bin. It authenticates with its own API key (store only a hash). It has an `expected_interval_seconds` (how often it should report) and a `last_seen_at` timestamp.
- **Sample:** One reporting cycle from a device. It carries a device-assigned, monotonically increasing `seq` and a `recorded_at` timestamp.
- **Reading:** One sensor value within a sample, identified by `cable_index` (which cable) and `depth_index` (position on the cable, 0 = top). It holds `temperature_c` and an optional `moisture_pct`.
- **Alert:** A detected condition on a bin, sensor, or device, with a lifecycle (see [Alert engine](#alert-engine)).

### Schema notes

- `readings` is range-partitioned by `recorded_at`, one partition per month. A scheduled job (or a Flyway-managed function) creates partitions ahead of time. Never let an insert fail because a partition is missing.
- Idempotency: a unique constraint covers `(device_id, seq, cable_index, depth_index, recorded_at)`. **Postgres requires the partition key to be part of any unique constraint on a partitioned table**, which is why `recorded_at` is included. A retried batch carries the same values, so duplicates are still caught.
- Use `INSERT ... ON CONFLICT DO NOTHING` and report duplicate counts back to the caller.
- Store both `recorded_at` (device clock) and `received_at` (server clock).
- Add indexes for the query patterns in the API below. Verify them with `EXPLAIN ANALYZE` and note the results in `docs/decisions/`.
- Flyway migrations are append-only. Never edit a migration once it has been committed.

---

## API

All endpoints are under `/api/v1`. JSON in and out. Errors use RFC 9457 Problem Details (`application/problem+json`).

### Device ingest

`POST /api/v1/readings`, authenticated by the `X-Device-Key` header.

```json
{
  "samples": [
    {
      "seq": 1042,
      "recordedAt": "2026-10-01T14:00:00Z",
      "sensors": [
        { "cable": 0, "depth": 0, "temperatureC": 11.4, "moisturePct": 13.9 },
        { "cable": 0, "depth": 1, "temperatureC": 12.1, "moisturePct": 14.2 }
      ]
    }
  ]
}
```

Response `202 Accepted`:

```json
{ "accepted": 2, "duplicates": 0, "rejected": 0 }
```

Rules:

- The device is identified from its key. It never sends its own ID.
- Maximum 500 samples per batch. Return 413 if exceeded.
- Reject samples with `recordedAt` more than 5 minutes in the future, and count them as `rejected`. The rest of the batch is still processed.
- Likewise reject samples recorded longer ago than `app.ingest.max-sample-age` (default **30 days**, overridable with `APP_INGEST_MAX_SAMPLE_AGE`). This stops a device whose clock has reset from writing into long-past partitions. See [ADR 0006](decisions/0006-reject-samples-older-than-a-configurable-age.md).
- All three counts are in **readings** (one per sensor value), not samples, so `accepted + duplicates + rejected` always equals the number of sensor values sent.
- A structurally invalid reading (a missing field, or a value its column cannot store) fails the whole batch with `400`, and the error names it by path, e.g. `samples[3].sensors[0].temperatureC`.
- Samples may arrive out of order. Order is determined by `recordedAt`, not arrival.
- Update `devices.last_seen_at` only after a successful insert, to the **server's** receive time rather than the device's `recordedAt`, so device clock skew cannot cause or hide a `DEVICE_OFFLINE` alert. See [ADR 0005](decisions/0005-last-seen-uses-server-clock.md).
- Evaluate threshold alerts synchronously after insert (see below).

### Admin and dashboard

Authenticated with `Authorization: Bearer <ADMIN_TOKEN>`, where the token comes from an environment variable. This is deliberately simple and is **not production-grade auth**. The tradeoff is recorded in [ADR 0003](decisions/0003-filter-based-auth.md), and a replacement is enhancement [E10](enhancements.md#e10).

| Method | Path | Purpose |
|---|---|---|
| POST | `/bins` | Create a bin |
| GET | `/bins` | List bins with current status (worst open alert, last reading time) |
| GET | `/bins/{id}` | Bin detail and thresholds |
| PATCH | `/bins/{id}/thresholds` | Update alert thresholds |
| POST | `/bins/{id}/devices` | Register a device. Returns the plaintext API key **once**. |
| GET | `/bins/{id}/latest` | Latest reading per sensor, from the last 7 days; a sensor silent for longer is omitted rather than shown stale |
| GET | `/bins/{id}/readings?from=&to=&bucket=hour\|day` | Time-bucketed averages, min, and max per sensor over `[from, to)`. Buckets align to UTC; at most 1,000 per sensor per request |
| GET | `/alerts?status=open\|acknowledged\|resolved&binId=&limit=` | List alerts, newest detection first. `status` also takes several values, comma-separated (`open,acknowledged`), and defaults to all. `limit` defaults to 100, at most 500. |
| POST | `/alerts/{id}/acknowledge` | Acknowledge an open alert. Repeating it is harmless; a resolved alert is a `409`. |

Device API keys cannot be revoked yet: a lost or leaked key keeps working until its device is removed from the database by hand. This is a known gap, enhancement [E7](enhancements.md#e7).

Health and metrics are served at `/actuator/health` and `/actuator/prometheus`.

---

## Alert engine

| Type | Condition (defaults are configurable per bin) | When evaluated |
|---|---|---|
| `HIGH_TEMPERATURE` | Any sensor above `max_temperature_c` (default 20.0) | On ingest |
| `HIGH_MOISTURE` | Any sensor above `max_moisture_pct` (default 14.5) | On ingest |
| `RATE_OF_RISE` | A sensor's temperature has risen at least `rise_threshold_c` (default 2.0) over the trailing `rise_window_hours` (default 72). Measured on daily averages -- the last 24 h against the 24 h that ended `rise_window_hours` ago -- so the normal day/night swing near the top of a bin is not mistaken for a rise. A sensor needs half a day's expected readings in each window to be judged. See [ADR 0013](decisions/0013-rate-of-rise-on-daily-averages.md). | Scheduled, every 5 min |
| `DEVICE_OFFLINE` | `now - last_seen_at > 3 × expected_interval_seconds`. A device that has never reported is measured from its registration. | Scheduled, every 1 min |

The default thresholds are placeholders for demonstration, not agronomic guidance.

Lifecycle and rules:

- Lifecycle: `OPEN` → `ACKNOWLEDGED` → `RESOLVED`.
- At most one non-resolved alert per condition. Repeat detections update `last_detected_at` rather than creating new rows.
  The key depends on what the alert is about: sensor alerts (`HIGH_TEMPERATURE`, `HIGH_MOISTURE`, `RATE_OF_RISE`) use
  `(bin, type, cable, depth)`; `DEVICE_OFFLINE` has no sensor position and uses `(bin, type, device)`, so two offline
  controllers on one bin raise two alerts and each resolves on its own recovery. See
  [ADR 0002](decisions/0002-device-scoped-offline-alert-dedupe.md).
- Auto-resolve when the condition has been clear for 3 consecutive evaluations. This prevents flapping.
  For the on-ingest alerts, one evaluation is one batch's newest *newly stored* reading per sensor: a resent
  duplicate batch evaluates nothing, a buffered backlog counts once, and a batch that arrives after newer data
  for the same sensor is not evaluated at all. See [ADR 0014](decisions/0014-what-counts-as-an-alert-evaluation.md).
- `DEVICE_OFFLINE` must be based on the last **successfully stored** reading, not on connection attempts. A device that connects but sends only rejected or duplicate data is still offline from a data standpoint.
- Probe fault values are stored and shown, but never evaluated. Firmware commonly reports −127 °C for an unreadable DS18B20, which would make a recovery to 12 °C look like a 139 °C rise, and the probe itself reads 85 °C before its first conversion after power-up. The engine skips exactly 85.0 °C and anything outside −60..100 °C; the range is wide so that genuinely heating grain is never mistaken for a fault. See [ADR 0016](decisions/0016-ignore-probe-fault-values-in-alert-evaluation.md); a fuller treatment is enhancement [E3](enhancements.md#e3).
- With more than one API instance, each scheduled job still runs on only one of them at a time (a PostgreSQL advisory lock), so a clear is never counted twice. See [ADR 0015](decisions/0015-advisory-lock-for-scheduled-alert-jobs.md).
- Every alert state change is logged as structured JSON ([ADR 0019](decisions/0019-structured-json-logging.md)) and counted with a Micrometer counter (`alerts_transitions_total{type,to_state}`).

---

## Simulator

`simulator/sim.py` posts realistic data to the ingest API. Every scenario can run against a local or deployed URL.

```bash
python simulator/sim.py --url http://localhost:8080 --scenario normal --bins 5 --interval 10
```

| Scenario | Behaviour |
|---|---|
| `normal` | Stable temperatures with small diurnal drift near the top of the bin |
| `hotspot` | One sensor mid-bin climbs slowly. It should trigger `RATE_OF_RISE` before `HIGH_TEMPERATURE`. |
| `wet` | Moisture rises on the bottom sensors |
| `flaky` | Resends batches (duplicates), shuffles order, and skips intervals |
| `offline` | Device stops reporting after N samples |

Use `--time-scale` to compress simulated time so that multi-day scenarios run in minutes. The simulator also has a `--seed-bins` mode that creates bins and devices through the admin API and writes the device keys to a local, git-ignored file.

**How `--time-scale` works: it replays the past.** The backend rejects samples more than five minutes in the future and judges rate of rise against its own clock, so a shape that takes days to develop has to be placed in the past. A scenario starts `--history` hours ago -- by default 120 for `hotspot` (three steady days, then two of warming), 24 for `wet`, 0 otherwise -- and is replayed `--time-scale` times faster than real time (default 3600, an hour a second) until it catches up with the present, then carries on live ([ADR 0020](decisions/0020-time-scale-replays-the-past.md)). Replayed samples are spaced at the device's registered interval, because the rate-of-rise rule needs half a day's expected readings in each window. `--history` also works with `normal`, to give the dashboard's charts some past to show. It is capped at 29 days, inside the backend's 30-day sample-age limit.

| Scenario | What it raises, and when |
|---|---|
| `hotspot` | `RATE_OF_RISE` on cable 1, depth 3, within one check of the replay finishing, while that sensor is still near 13 °C. `HIGH_TEMPERATURE` would follow days later if left running. |
| `wet` | `HIGH_MOISTURE` on the bottom sensor of every cable, during the replay: moisture passes 14.5 % twelve hours in. |
| `offline` | Sends 5 samples (`--offline-after N`), then stops. `DEVICE_OFFLINE` opens about 90 seconds later, plus up to a minute for the check. |

`--time-scale` cannot speed up `offline`. `DEVICE_OFFLINE` compares the server's clock with `last_seen_at`, which is also set from the server's clock ([ADR 0005](decisions/0005-last-seen-uses-server-clock.md)), so nothing the simulator does to `recordedAt` moves it. The alert fires after three real registered intervals: at least 90 seconds.

- `--seed-bins N` is safe to repeat: bins it already holds keys for are left alone. Keys are stored per `--url`, so seeding a deployed stack does not overwrite the local keys. Devices are registered as reporting every 30 seconds at least, the backend's minimum; streaming more often is fine.
- `--cycles N` stops after N samples per device; without it the simulator runs until Ctrl-C. `--seed` makes a run repeatable.
- **Checking that each scenario raises its alert:** `pytest` checks the generated shapes against the backend's rules, and the backend's own tests check the rules against the same shapes. `pytest -m e2e` proves the two agree, against a running stack with the alert checks shortened -- see `simulator/tests/test_e2e.py` for the two commands. It takes about two minutes, most of it waiting for `DEVICE_OFFLINE`.
- `seq` is derived from each sample's timestamp in whole seconds, so it increases across restarts with nothing stored on disk, and a resent sample carries its original `seq` -- which is how the backend recognises it as a duplicate.

---

## Front end

Built with Vite + React + TypeScript and strict mode on. Keep it small and functional.

- **Bin list:** name, site, grain type, status badge (worst open alert), last reading age.
- **Bin detail:** a heatmap-style grid of the latest temperature per cable and depth, plus a line chart per sensor over a selectable range (24h / 7d / 30d).
- **Alerts view:** open and acknowledged alerts, with an acknowledge button.
- **Login screen:** prompts for the admin token. Keep the token in memory only and never write it to `localStorage`.
- Typed API client in `frontend/src/api/`, with types that mirror the backend DTOs.

**As built:**

- **Bin list:** every bin with its worst open alert and last reading age, refreshing every 30 seconds.
- **Bin detail:** the bin's limits and open alerts; a grid of the latest temperature at every cable and depth; and, for the sensor picked on the grid (the hottest, until one is picked), a temperature chart and a moisture chart over 24 h, 7 days or 30 days. Hourly buckets for the first two, daily for 30 days.
  - The grid's colour is one blue ramp, the 20 °C below the bin's limit from pale to dark: a fixed span, so a colour means the same temperature in every bin. A cell above the limit is marked with an icon and says so in words; every cell shows its value, so nothing depends on reading a shade. A position with no reading in the last week is a gap, not a stale number.
  - Each chart shows the bucket average as a line, the min-max range as a faint band, and the bin's limit as a reference line, with a crosshair readout and a table of every value underneath. Temperature and moisture are separate charts, never one chart with two y-axes. Colours follow the dataviz skill's reference palette, checked with its validator in both light and dark mode.
- **Alerts view:** every open and acknowledged alert, newest first, with an Acknowledge button. Acknowledging refreshes the list from the server; if the alert resolved first (`409`), the page says so.
- The detail page is loaded lazily, so the chart library is downloaded only when a bin is opened: about 95 kB gzipped for the rest of the app, 110 kB more for the charts.

- **Libraries:** React Router for pages and TanStack Query for fetching and caching ([ADR 0018](decisions/0018-dashboard-stack-and-in-memory-token.md)). The token and the query cache live together, so signing out -- or being signed out by a `401` from any request -- also empties the cache. Tests use Vitest and React Testing Library, with `fetch` replaced by a fake that answers like the API.
- **The token is checked before it is accepted**, by listing bins with it, so a mistyped token fails on the login screen with a clear message. Reloading the page asks for it again: that is the cost of keeping it in memory only.
- **API URL:** `VITE_API_URL`, default `http://localhost:8080`. Vite reads it from the environment or a `frontend/.env.local` file at build time. The API must list the dashboard's origin in `CORS_ALLOWED_ORIGINS`; `.env.example` already has `http://localhost:5173`.

---

## Container image

The backend Dockerfile must:

- Use a multi-stage build: a JDK stage builds the jar, and a JRE stage (Eclipse Temurin) runs it.
- Use Spring Boot layered jars so dependency layers cache between builds.
- Run as a non-root user.
- Expose 8080 and define a `HEALTHCHECK` against `/actuator/health`.
- Pin base images by tag. Add a comment explaining how to pin by digest.
- Come with a `.dockerignore`.

Target an image size under 250 MB. Record the actual size in `docs/results.md`.

**As built:** 239.6 MB uncompressed and 100.7 MB compressed, with Alpine's JRE. See [results.md](results.md#container-image-size).

---

## CI/CD (GitHub Actions)

- **`ci.yml`** runs on pull requests and on pushes to `main`. It covers a gitleaks secret scan of the full history, backend `verify`, frontend lint, test, and build, simulator `pytest`, Docker build, `terraform fmt -check`, and `validate`. It uses Maven and npm caching.
- **`plan.yml`** runs on pull requests that touch `infra/**`. It runs `terraform plan` and posts the output as a PR comment.
- **`deploy.yml`** runs on pushes to `main` after CI passes. It:
  - builds and pushes the image to ECR, tagged with the git SHA
  - runs `terraform apply`
  - forces a new ECS deployment
  - syncs the front-end build to S3 and invalidates CloudFront
- AWS authentication uses GitHub OIDC (`aws-actions/configure-aws-credentials` with a role ARN). The IAM role trust policy is scoped to this repository and the `main` branch. No AWS access keys are stored in GitHub.

**As built:** there are two roles. The plan role is read-only and trusted for pull requests. The deploy role is trusted only for the GitHub environment `production`, which needs the owner's approval before any deploy job runs. See [`infra/terraform/README.md`](../infra/terraform/README.md#deploying-through-github-actions).

---

## AWS infrastructure (Terraform)

Region: `ca-central-1`. Split files by concern: `network.tf`, `ecr.tf`, `ecs.tf`, `rds.tf`, `alb.tf`, `frontend.tf`, `iam_github_oidc.tf`, `observability.tf`, `budgets.tf`, `variables.tf`, `outputs.tf`.

- **Network:** a VPC across 2 AZs, with public subnets for the ALB and ECS tasks and private subnets for RDS. **No NAT gateway** (for cost). ECS tasks get public IPs, and their security group allows inbound traffic only from the ALB. Document this tradeoff in an ADR.
- **RDS:** PostgreSQL on the smallest burstable instance class, single-AZ, in private subnets, reachable only from the ECS security group. Use `manage_master_user_password = true` so the password lives in Secrets Manager.
- **ECS:** a Fargate service with 1 task by default. The task definition reads DB credentials and `ADMIN_TOKEN` from Secrets Manager, and logs go to CloudWatch.
- **ALB:** HTTPS if a domain and ACM certificate are provided through variables. Otherwise it serves HTTP and the README says so.
- **Front end:** a private S3 bucket behind CloudFront with Origin Access Control.
- **Observability:** CloudWatch alarms for 5xx rate on the ALB, ECS CPU, and RDS free storage.
- **Budgets:** an AWS Budgets alert that emails a configurable address at a configurable monthly limit. **Create this first.**
- **State:** remote state in S3 with `use_lockfile = true`. The state bucket is bootstrapped once by hand or by a tiny separate config in `infra/bootstrap/`.

**Cost discipline:** `terraform destroy` when not demoing. The ALB and RDS cost money even when idle.

**As built:** CloudFront is the single HTTPS front door. `/api/*` reaches an **internal** ALB through a CloudFront VPC origin, and everything else is the S3 dashboard. So the ALB has no public address, HTTPS needs no domain or certificate, and `/actuator/prometheus` is never exposed publicly. The resources and their estimated cost are in [`infra/terraform/README.md`](../infra/terraform/README.md).

---

## Load testing

`load-tests/k6/ingest.js` ramps virtual devices that each post a batch every few seconds, using unique `seq` values per device, plus a small percentage of deliberate duplicates.

- Report requests per second, readings per second, p95 and p99 latency, error rate, and duplicate-detection correctness.
- Run it locally and against AWS. Record both runs in `docs/results.md`, with the environment (instance sizes, task count) and the date.
- These numbers are meant to be quoted, so the methodology must be written down and reproducible.

---

## Checks

Every check must pass before a milestone is considered done. CI runs the same set on every pull request.

```bash
cd backend && ./mvnw verify                         # unit + Testcontainers integration tests
cd frontend && npm run lint && npm run build && npm test
cd simulator && pytest                             # with simulator/.venv activated
docker build -t grain-telemetry-api backend/
terraform -chdir=infra/terraform fmt -check && terraform -chdir=infra/terraform validate
```

---

## Milestones

Work in order. Each milestone ends with every check above passing and a short summary of what changed.

### Milestone 1: Core service (complete)
- Maven project with Spring Boot, Actuator, Web, Validation, JDBC or JPA (pick one and justify it in an ADR), Flyway, and Testcontainers -- JDBC, see [ADR 0008](decisions/0008-spring-jdbc-over-jpa.md)
- Flyway schema for bins, devices, partitioned readings, and alerts, with partition creation handled
- Admin endpoints for bins and device registration (API key hashing)
- Ingest endpoint with idempotency, batch limits, and future-timestamp rejection
- `latest` and bucketed `readings` query endpoints
- Testcontainers integration tests covering duplicates, out-of-order samples, and a missing partition
- `docker-compose.yml` for Postgres
- Simulator with `--seed-bins`, `normal`, and `flaky` scenarios

### Milestone 2: Alerts and dashboard (complete)
- Alert engine with all four types, dedupe, auto-resolve, and metrics
- Simulator `hotspot`, `wet`, and `offline` scenarios, and a test that each one produces the expected alert
- React + TypeScript dashboard: bin list, bin detail (grid and charts), alerts view
- Prometheus and Grafana in Compose, with a provisioned dashboard for ingest rate, latency, and alert transitions

### Milestone 3: Containers, CI/CD, AWS (complete, [#2](https://github.com/Bacchetto/grain-bin-monitoring/issues/2))
- Production Dockerfile meeting the [container image](#container-image) requirements
- `ci.yml` green on a pull request
- Terraform bootstrap and main stack. `budgets.tf` is applied first.
- GitHub OIDC role, plus `plan.yml` and `deploy.yml`
- First successful deploy. Seed bins and run the simulator against the deployed URL.

### Milestone 4: Proof and polish ([#8](https://github.com/Bacchetto/grain-bin-monitoring/issues/8))
- k6 load test run locally and in AWS, with results in `docs/results.md`
- CloudWatch alarms
- `docs/architecture.md` with a diagram and a request walkthrough (ingest → alert)
- README updated with screenshots, the live-demo note, and the results summary

### Stretch goals, in priority order ([#13](https://github.com/Bacchetto/grain-bin-monitoring/issues/13))
- Helm chart deployed to a local k3d cluster (documented, not deployed to AWS)
- SQS buffer between the ALB and the database writer for ingest spikes
- MQTT ingest via AWS IoT Core, to bridge to a future embedded CAN-bus sensor project
- Playwright end-to-end smoke test in CI
