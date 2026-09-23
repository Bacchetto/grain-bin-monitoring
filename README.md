# Grain Bin Telemetry

A backend service and dashboard for monitoring stored grain. Sensor cables hang inside grain bins and report temperature and moisture at several depths. The system ingests those readings, stores them as time series, and raises alerts when a bin shows signs of spoilage or when a device stops reporting.

This project demonstrates production-style backend engineering end to end: API design, data modeling, testing, containerization, CI/CD, infrastructure as code on AWS, observability, and load testing.

> **Note for Claude Code:** This README is the project spec. Read the whole file before writing code, then work through the [Milestones](#milestones) in order. See [Working agreement](#working-agreement) at the bottom.

---

## Tech stack

| Area | Choice |
|---|---|
| Backend | Java 21 (LTS), latest stable Spring Boot, Maven (with wrapper) |
| Database | PostgreSQL 16+, Flyway migrations, native table partitioning |
| Testing | JUnit 5, AssertJ, Testcontainers (real Postgres, no H2), MockMvc |
| Front end | React + TypeScript, Vite, Recharts |
| Simulator | Python 3.11+, `requests`, `pytest` |
| Containers | Hand-written multi-stage Dockerfile, Docker Compose for local dev |
| CI/CD | GitHub Actions, OIDC federation to AWS (no long-lived keys) |
| Cloud | AWS `ca-central-1`: ECS Fargate, RDS PostgreSQL, ALB, ECR, S3 + CloudFront, CloudWatch |
| IaC | Terraform, S3 remote state with native lockfile (`use_lockfile = true`) |
| Observability | Spring Actuator, Micrometer (Prometheus format), Grafana locally, CloudWatch in AWS |
| Load testing | k6 |

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
- Reject samples with `recordedAt` more than 5 minutes in the future, and count them as `rejected`.
- Samples may arrive out of order. Order is determined by `recordedAt`, not arrival.
- Update `devices.last_seen_at` only after a successful insert.
- Evaluate threshold alerts synchronously after insert (see below).

### Admin and dashboard

Authenticated with `Authorization: Bearer <ADMIN_TOKEN>`, where the token comes from an environment variable. This is deliberately simple and is **not production-grade auth**. Record this tradeoff in an ADR.

| Method | Path | Purpose |
|---|---|---|
| POST | `/bins` | Create a bin |
| GET | `/bins` | List bins with current status (worst open alert, last reading time) |
| GET | `/bins/{id}` | Bin detail and thresholds |
| PATCH | `/bins/{id}/thresholds` | Update alert thresholds |
| POST | `/bins/{id}/devices` | Register a device. Returns the plaintext API key **once**. |
| GET | `/bins/{id}/latest` | Latest reading per sensor |
| GET | `/bins/{id}/readings?from=&to=&bucket=hour\|day` | Time-bucketed averages, min, and max per sensor |
| GET | `/alerts?status=open\|acknowledged\|resolved&binId=` | List alerts |
| POST | `/alerts/{id}/acknowledge` | Acknowledge an alert |

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
- At most one non-resolved alert per `(bin, type, cable, depth)`. Repeat detections update `last_detected_at` rather than creating new rows.
- Auto-resolve when the condition has been clear for 3 consecutive evaluations. This prevents flapping.
- `DEVICE_OFFLINE` must be based on the last **successfully stored** reading, not on connection attempts. A device that connects but sends only rejected or duplicate data is still offline from a data standpoint.
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
│   └── decisions/            ADRs (NNNN-title.md)
├── .github/workflows/
├── docker-compose.yml
└── README.md
```

Organize backend packages by feature (`ingest`, `alerts`, `bins`), not by layer.

---

## Local development

```bash
docker compose up -d db prometheus grafana   # infrastructure only
cd backend && ./mvnw spring-boot:run         # API on :8080
cd frontend && npm install && npm run dev    # UI on :5173
python simulator/sim.py --seed-bins 3
python simulator/sim.py --scenario hotspot --time-scale 720
```

Or `docker compose up` to run everything, including the API container.

Configuration comes from environment variables (see `.env.example`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_TOKEN`, `CORS_ALLOWED_ORIGINS`. Never commit `.env`.

### Checks that must pass before any milestone is considered done

```bash
cd backend && ./mvnw verify                         # unit + Testcontainers integration tests
cd frontend && npm run lint && npm run build && npm test
cd simulator && pytest
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
- [ ] Maven project with Spring Boot, Actuator, Web, Validation, JDBC or JPA (pick one and justify it in an ADR), Flyway, and Testcontainers
- [ ] Flyway schema for bins, devices, partitioned readings, and alerts, with partition creation handled
- [ ] Admin endpoints for bins and device registration (API key hashing)
- [ ] Ingest endpoint with idempotency, batch limits, and future-timestamp rejection
- [ ] `latest` and bucketed `readings` query endpoints
- [ ] Testcontainers integration tests covering duplicates, out-of-order samples, and a missing partition
- [ ] `docker-compose.yml` for Postgres
- [ ] Simulator with `--seed-bins`, `normal`, and `flaky` scenarios

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
