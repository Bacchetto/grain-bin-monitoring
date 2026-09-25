# Grain Bin Telemetry

A backend service and dashboard for monitoring stored grain. Sensor cables hang inside grain bins and report temperature and moisture at several depths. The system ingests those readings, stores them as time series, and raises alerts when a bin shows signs of spoilage or when a device stops reporting.

This project demonstrates production-style backend engineering end to end: API design, data modeling, testing, containerization, CI/CD, infrastructure as code on AWS, observability, and load testing.

> **Note for Claude Code:** This README is the project spec. Read the whole file before writing code, then work through the [Milestones](#milestones) in order. See [Working agreement](#working-agreement) at the bottom.

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
> [ADR 0009](docs/decisions/0009-spring-boot-4.md).

---

## Architecture

```
 [Simulator / real devices]
            |
            |  POST /api/v1/readings  (X-Device-Key header)
            v
   +-------------------+        +---------------------+
   |  ALB (HTTPS)      | -----> |  Spring Boot API    |
   +-------------------+        |  (ECS Fargate)      |
                                |  - ingest           |
   [React dashboard]            |  - query            |
   S3 + CloudFront  ----------> |  - alert engine     |
                                |  - scheduled jobs   |
                                +----------+----------+
                                           |
                                           v
                                +---------------------+
                                |  RDS PostgreSQL     |
                                |  readings partitioned|
                                |  by month           |
                                +---------------------+
```

Locally, `docker compose up` runs Postgres, the API, Prometheus, and Grafana. The front end runs with `npm run dev`.

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
- Likewise reject samples recorded longer ago than `app.ingest.max-sample-age` (default **30 days**, overridable with `APP_INGEST_MAX_SAMPLE_AGE`). This stops a device whose clock has reset from writing into long-past partitions. See [ADR 0006](docs/decisions/0006-reject-samples-older-than-a-configurable-age.md).
- All three counts are in **readings** (one per sensor value), not samples, so `accepted + duplicates + rejected` always equals the number of sensor values sent.
- A structurally invalid reading (a missing field, or a value its column cannot store) fails the whole batch with `400`, and the error names it by path, e.g. `samples[3].sensors[0].temperatureC`.
- Samples may arrive out of order. Order is determined by `recordedAt`, not arrival.
- Update `devices.last_seen_at` only after a successful insert, to the **server's** receive time rather than the device's `recordedAt`, so device clock skew cannot cause or hide a `DEVICE_OFFLINE` alert. See [ADR 0005](docs/decisions/0005-last-seen-uses-server-clock.md).
- Evaluate threshold alerts synchronously after insert (see below).

### Admin and dashboard

Authenticated with `Authorization: Bearer <ADMIN_TOKEN>`, where the token comes from an environment variable. This is deliberately simple and is **not production-grade auth**. The tradeoff is recorded in [ADR 0003](docs/decisions/0003-filter-based-auth.md), and a replacement is enhancement [E10](docs/enhancements.md#e10).

| Method | Path | Purpose |
|---|---|---|
| POST | `/bins` | Create a bin |
| GET | `/bins` | List bins with current status (worst open alert, last reading time) |
| GET | `/bins/{id}` | Bin detail and thresholds |
| PATCH | `/bins/{id}/thresholds` | Update alert thresholds |
| POST | `/bins/{id}/devices` | Register a device. Returns the plaintext API key **once**. |
| GET | `/bins/{id}/latest` | Latest reading per sensor, from the last 7 days; a sensor silent for longer is omitted rather than shown stale |
| GET | `/bins/{id}/readings?from=&to=&bucket=hour\|day` | Time-bucketed averages, min, and max per sensor over `[from, to)`. Buckets align to UTC; at most 1,000 per sensor per request |
| GET | `/alerts?status=open\|acknowledged\|resolved&binId=` | List alerts *(Milestone 2)* |
| POST | `/alerts/{id}/acknowledge` | Acknowledge an alert *(Milestone 2)* |

Device API keys cannot be revoked yet: a lost or leaked key keeps working until its device is removed from the database by hand. This is a known gap, enhancement [E7](docs/enhancements.md#e7).

Health and metrics are served at `/actuator/health` and `/actuator/prometheus`.

---

## Alert engine

| Type | Condition (defaults are configurable per bin) | When evaluated |
|---|---|---|
| `HIGH_TEMPERATURE` | Any sensor above `max_temperature_c` (default 20.0) | On ingest |
| `HIGH_MOISTURE` | Any sensor above `max_moisture_pct` (default 14.5) | On ingest |
| `RATE_OF_RISE` | A sensor's temperature has risen at least `rise_threshold_c` (default 2.0) over the trailing `rise_window_hours` (default 72) | Scheduled, every 5 min |
| `DEVICE_OFFLINE` | `now - last_seen_at > 3 × expected_interval_seconds` | Scheduled, every 1 min |

The default thresholds are placeholders for demonstration, not agronomic guidance.

Lifecycle and rules:

- Lifecycle: `OPEN` → `ACKNOWLEDGED` → `RESOLVED`.
- At most one non-resolved alert per condition. Repeat detections update `last_detected_at` rather than creating new rows.
  The key depends on what the alert is about: sensor alerts (`HIGH_TEMPERATURE`, `HIGH_MOISTURE`, `RATE_OF_RISE`) use
  `(bin, type, cable, depth)`; `DEVICE_OFFLINE` has no sensor position and uses `(bin, type, device)`, so two offline
  controllers on one bin raise two alerts and each resolves on its own recovery. See
  [ADR 0002](docs/decisions/0002-device-scoped-offline-alert-dedupe.md).
- Auto-resolve when the condition has been clear for 3 consecutive evaluations. This prevents flapping.
- `DEVICE_OFFLINE` must be based on the last **successfully stored** reading, not on connection attempts. A device that connects but sends only rejected or duplicate data is still offline from a data standpoint.
- Probe fault values are stored as ordinary readings today. A disconnected DS18B20 reads −127 °C, so a recovery to 12 °C looks like a 139 °C rise, and a probe that has just powered on reads 85 °C. How the engine keeps these out of evaluation is to be decided with it: enhancement [E3](docs/enhancements.md#e3).
- Every alert state change is logged as structured JSON and counted with a Micrometer counter (`alerts_transitions_total{type,to_state}`).

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

**Implemented so far:** `--seed-bins`, `normal` and `flaky`. `hotspot`, `wet`, `offline` and `--time-scale` arrive in Milestone 2.

`--time-scale` cannot speed up `offline`. `DEVICE_OFFLINE` compares the server's clock with `last_seen_at`, which is also set from the server's clock ([ADR 0005](docs/decisions/0005-last-seen-uses-server-clock.md)), so nothing the simulator does to `recordedAt` moves it. The alert fires after three real registered intervals: at least 90 seconds.

- `--seed-bins N` is safe to repeat: bins it already holds keys for are left alone. Keys are stored per `--url`, so seeding a deployed stack does not overwrite the local keys. Devices are registered as reporting every 30 seconds at least, the backend's minimum; streaming more often is fine.
- `--cycles N` stops after N samples per device; without it the simulator runs until Ctrl-C. `--seed` makes a run repeatable.
- `seq` is derived from each sample's timestamp in whole seconds, so it increases across restarts with nothing stored on disk, and a resent sample carries its original `seq` -- which is how the backend recognises it as a duplicate.

---

## Front end

Built with Vite + React + TypeScript and strict mode on. Keep it small and functional.

- **Bin list:** name, site, grain type, status badge (worst open alert), last reading age.
- **Bin detail:** a heatmap-style grid of the latest temperature per cable and depth, plus a line chart per sensor over a selectable range (24h / 7d / 30d).
- **Alerts view:** open and acknowledged alerts, with an acknowledge button.
- **Login screen:** prompts for the admin token. Keep the token in memory only and never write it to `localStorage`.
- Typed API client in `frontend/src/api/`, with types that mirror the backend DTOs.

---

## Repository layout

```
.
├── backend/                  Spring Boot service (Maven)
│   ├── src/main/java/...     api/, ingest/, alerts/, bins/, devices/, config/
│   ├── src/main/resources/db/migration/   Flyway scripts
│   ├── src/test/java/...
│   └── Dockerfile
├── frontend/                 Vite + React + TypeScript
├── simulator/                Python simulator and its tests
├── infra/terraform/          AWS infrastructure
├── load-tests/k6/            k6 scripts
├── ops/                      Prometheus config, Grafana dashboards (provisioned)
├── docs/
│   ├── architecture.md
│   ├── results.md            Load test results
│   ├── enhancements.md       Possible future enhancements, not yet scheduled
│   └── decisions/            ADRs (NNNN-title.md)
├── .github/workflows/
├── docker-compose.yml
└── README.md
```

Organize backend packages by feature (`ingest`, `alerts`, `bins`), not by layer.

---

## Local development

### One-time setup

- **A Docker-compatible container runtime.** This project is developed with
  [Rancher Desktop](https://rancherdesktop.io/); Docker Desktop also works.
  With Rancher Desktop, set *Preferences → Container Engine* to **dockerd
  (moby)** -- containerd has no Docker-compatible API, so Testcontainers cannot
  use it. On Windows, also set the environment variable
  `DOCKER_HOST=npipe:////./pipe/docker_engine` so Testcontainers can find it.
  See [ADR 0012](docs/decisions/0012-rancher-desktop-for-local-containers.md).
- **Configuration:** `cp .env.example .env`, then set `ADMIN_TOKEN` (the file
  shows how to generate one). This one file configures both Docker Compose and
  the API.
- **Simulator:** `python -m venv simulator/.venv`, activate it, then
  `pip install -r simulator/requirements-dev.txt`.

### Running it

```bash
docker compose up -d db                               # PostgreSQL on localhost:5432
cd backend && ./mvnw spring-boot:run                  # API on :8080, configured from ../.env
python simulator/sim.py --seed-bins 3                 # bins + devices; keys saved to simulator/.devices.json
python simulator/sim.py --scenario normal --interval 10
python simulator/sim.py --scenario flaky --cycles 20  # duplicates, reordering, skipped intervals
```

Still to come: Prometheus and Grafana in Compose, the front end
(`cd frontend && npm install && npm run dev`, UI on :5173), and the `hotspot`,
`wet` and `offline` scenarios with `--time-scale` arrive in Milestone 2.
`docker compose up` running everything, including the API container, arrives in
Milestone 3 with the Dockerfile.

Configuration comes from environment variables (see `.env.example`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_TOKEN`, `CORS_ALLOWED_ORIGINS`, and optionally `APP_INGEST_MAX_SAMPLE_AGE`. Never commit `.env`. `CORS_ALLOWED_ORIGINS` is not read by the API yet; it is wired up in Milestone 2 with the front end that needs it.

Spring Boot does not read `.env` files by itself. `./mvnw spring-boot:run` switches on a `local` profile that imports the root `.env`, so the API and Compose always agree -- change the database password there and both sides see it. A variable exported in the shell still wins. The tests never activate that profile, so a developer's `.env` cannot change what they see.

### Checks that must pass before any milestone is considered done

```bash
cd backend && ./mvnw verify                         # unit + Testcontainers integration tests
cd frontend && npm run lint && npm run build && npm test
cd simulator && pytest                             # with simulator/.venv activated
docker build -t grain-telemetry-api backend/
terraform -chdir=infra/terraform fmt -check && terraform -chdir=infra/terraform validate
```

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

---

## CI/CD (GitHub Actions)

- **`ci.yml`** runs on pull requests and on pushes to `main`. It covers backend `verify`, frontend lint, test, and build, simulator `pytest`, Docker build, `terraform fmt -check`, and `validate`. It uses Maven and npm caching.
- **`plan.yml`** runs on pull requests that touch `infra/**`. It runs `terraform plan` and posts the output as a PR comment.
- **`deploy.yml`** runs on pushes to `main` after CI passes. It:
  - builds and pushes the image to ECR, tagged with the git SHA
  - runs `terraform apply`
  - forces a new ECS deployment
  - syncs the front-end build to S3 and invalidates CloudFront
- AWS authentication uses GitHub OIDC (`aws-actions/configure-aws-credentials` with a role ARN). The IAM role trust policy is scoped to this repository and the `main` branch. No AWS access keys are stored in GitHub.

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

---

## Load testing

`load-tests/k6/ingest.js` ramps virtual devices that each post a batch every few seconds, using unique `seq` values per device, plus a small percentage of deliberate duplicates.

- Report requests per second, readings per second, p95 and p99 latency, error rate, and duplicate-detection correctness.
- Run it locally and against AWS. Record both runs in `docs/results.md`, with the environment (instance sizes, task count) and the date.
- These numbers are meant to be quoted, so the methodology must be written down and reproducible.

---

## Milestones

Work in order. Each milestone ends with every check above passing and a short summary of what changed.

### Milestone 1: Core service (week 1)
- [x] Maven project with Spring Boot, Actuator, Web, Validation, JDBC or JPA (pick one and justify it in an ADR), Flyway, and Testcontainers -- JDBC, see [ADR 0008](docs/decisions/0008-spring-jdbc-over-jpa.md)
- [x] Flyway schema for bins, devices, partitioned readings, and alerts, with partition creation handled
- [x] Admin endpoints for bins and device registration (API key hashing)
- [x] Ingest endpoint with idempotency, batch limits, and future-timestamp rejection
- [x] `latest` and bucketed `readings` query endpoints
- [x] Testcontainers integration tests covering duplicates, out-of-order samples, and a missing partition
- [x] `docker-compose.yml` for Postgres
- [x] Simulator with `--seed-bins`, `normal`, and `flaky` scenarios

### Milestone 2: Alerts and dashboard (week 2)
- [ ] Alert engine with all four types, dedupe, auto-resolve, and metrics
- [ ] Simulator `hotspot`, `wet`, and `offline` scenarios, and a test that each one produces the expected alert
- [ ] React + TypeScript dashboard: bin list, bin detail (grid and charts), alerts view
- [ ] Prometheus and Grafana in Compose, with a provisioned dashboard for ingest rate, latency, and alert transitions

### Milestone 3: Containers, CI/CD, AWS (week 3)
- [ ] Production Dockerfile meeting the requirements above
- [ ] `ci.yml` green on a pull request
- [ ] Terraform bootstrap and main stack. `budgets.tf` is applied first.
- [ ] GitHub OIDC role, plus `plan.yml` and `deploy.yml`
- [ ] First successful deploy. Seed bins and run the simulator against the ALB URL.

### Milestone 4: Proof and polish (week 4)
- [ ] k6 load test run locally and in AWS, with results in `docs/results.md`
- [ ] CloudWatch alarms
- [ ] `docs/architecture.md` with a diagram and a request walkthrough (ingest → alert)
- [ ] README updated with screenshots, the live-demo note, and the results summary

### Stretch goals (in priority order)
- [ ] Helm chart deployed to a local k3d cluster (documented, not deployed to AWS)
- [ ] SQS buffer between the ALB and the database writer for ingest spikes
- [ ] MQTT ingest via AWS IoT Core, to bridge to a future embedded CAN-bus sensor project
- [ ] Playwright end-to-end smoke test in CI

---

## Working agreement

Every part of this project should be explainable by the people working on it. Optimize for clarity and learnability over cleverness.

1. **Go one milestone at a time.** At the end of each milestone, stop and summarize what was built, what is left, and any decisions made. Do not start the next milestone until asked.
2. **Record decisions.** Any non-obvious choice gets a short ADR in `docs/decisions/` covering context, decision, alternatives, and consequences. Examples: JDBC vs JPA, partitioning strategy, no NAT gateway, admin-token auth.
3. **Explain the unfamiliar parts.** The owner is newer to AWS, Terraform, GitHub Actions OIDC, and TypeScript. In those areas, add concise comments explaining *why* each resource or block exists, not just what it is.
4. **Tests come with features.** No feature is done without tests. Integration tests use Testcontainers against real Postgres. Never use H2 or mock the database for repository or ingest tests.
5. **Keep dependencies minimal.** Do not add a library when the standard library or Spring already covers it. Justify every new dependency in the commit message.
6. **No secrets in the repo.** Use `.env.example` for local configuration and Secrets Manager in AWS. Never print secrets in logs or CI output.
7. **Commits.** Small, focused commits using Conventional Commits (`feat:`, `fix:`, `test:`, `infra:`, `docs:`).
8. **Do not run `terraform apply` or `terraform destroy`,** and do not create any AWS resources. Write the code and run `fmt`, `validate`, and `plan` only. The owner applies changes manually until the pipeline is trusted.
9. **Keep this README current.** Tick milestone checkboxes as items land, and update any section that the implementation changes.
10. **Ask when a spec detail is ambiguous** rather than guessing. For small details, pick the simplest reasonable option and note it in the milestone summary.
