# Local development

The full setup, beyond the quick start in the [README](../README.md#development).

## One-time setup

- **A Docker-compatible container runtime.** This project is developed with
  [Rancher Desktop](https://rancherdesktop.io/); Docker Desktop also works.
  With Rancher Desktop, set *Preferences → Container Engine* to **dockerd
  (moby)** -- containerd has no Docker-compatible API, so Testcontainers cannot
  use it. On Windows, also set the environment variable
  `DOCKER_HOST=npipe:////./pipe/docker_engine` so Testcontainers can find it.
  See [ADR 0012](decisions/0012-rancher-desktop-for-local-containers.md).
- **Configuration:** `cp .env.example .env`, then set `ADMIN_TOKEN` (the file
  shows how to generate one). This one file configures both Docker Compose and
  the API.
- **Simulator:** `python -m venv simulator/.venv`, activate it, then
  `pip install -r simulator/requirements-dev.txt`.
- **Secret scanning on commit:** install [gitleaks](https://github.com/gitleaks/gitleaks)
  (`winget install Gitleaks.Gitleaks`), then `git config core.hooksPath .githooks`
  once per clone. The pre-commit hook scans what you stage and refuses a commit
  that adds a key, token or password. CI scans the full history again on every
  push, since a local hook can be skipped.
- **Front end:** Node.js 24 LTS, then `cd frontend && npm install`. The dev
  server watches files by polling, because native file watching fails on the
  network drive this repository is developed on. On a local disk, set
  `VITE_NATIVE_WATCH=true` for the faster native watcher.

## Running it

```bash
docker compose up -d                                  # PostgreSQL :5432, Prometheus :9090, Grafana :3000
cd backend && ./mvnw spring-boot:run                  # API on :8080, configured from ../.env
python simulator/sim.py --seed-bins 3                 # bins + devices; keys saved to simulator/.devices.json
python simulator/sim.py --scenario normal --interval 10
python simulator/sim.py --scenario flaky --cycles 20  # duplicates, reordering, skipped intervals
python simulator/sim.py --scenario hotspot --bins 1   # replays 5 days (~2 min), then live: RATE_OF_RISE
python simulator/sim.py --scenario wet --bins 1       # replays 1 day: HIGH_MOISTURE
python simulator/sim.py --scenario offline --bins 1   # 5 samples, then silence: DEVICE_OFFLINE
```

To see scheduled alerts sooner, start the API with shorter checks:
`APP_ALERTS_RATE_OF_RISE_CHECK_INTERVAL=5s APP_ALERTS_OFFLINE_CHECK_INTERVAL=5s ./mvnw spring-boot:run`.

**The production image, locally:** instead of `./mvnw spring-boot:run`, run
`docker compose --profile api up -d --build`. It builds `backend/Dockerfile` and
runs it on the same port, 8080, against the Compose database, so the
dashboard, the simulator and Prometheus need no change. Run one or the other,
not both. Its logs are JSON, as in production: `docker compose logs -f api`.

The dashboard: `cd frontend && npm run dev`, then open http://localhost:5173 and
sign in with the `ADMIN_TOKEN` from `.env`.

**Monitoring:** open http://localhost:3000 for the *Grain Bin Telemetry* Grafana dashboard -- readings per second by outcome, ingest requests and latency percentiles, and alert transitions. Viewing needs no login; the `admin` account, for editing, uses `GRAFANA_ADMIN_PASSWORD` from `.env`. Prometheus scrapes the API on the host every 15 seconds as `host.docker.internal:8080`; if the dashboard's *API scrape* panel says DOWN, check http://localhost:9090 under *Status > Targets*.

The Prometheus and Grafana configuration under `ops/` is **built into their images**, not mounted: the Docker engine inside Rancher Desktop cannot mount files from the network drive this repository is developed on. After editing anything under `ops/`, run `docker compose up -d --build`. See [ADR 0021](decisions/0021-local-tooling-on-a-network-drive.md). The dashboard is provisioned from `ops/grafana/dashboards/grain-telemetry.json`; to change it, edit in Grafana as admin, export the JSON, and replace that file.

## Configuration

Configuration comes from environment variables (see `.env.example`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_TOKEN`, `CORS_ALLOWED_ORIGINS`, `GRAFANA_ADMIN_PASSWORD`, and optionally `APP_INGEST_MAX_SAMPLE_AGE`, `APP_ALERTS_OFFLINE_CHECK_INTERVAL` and `APP_ALERTS_RATE_OF_RISE_CHECK_INTERVAL` (defaults `1m` and `5m`; shorten them for a demo). Never commit `.env`. `CORS_ALLOWED_ORIGINS` lists the exact origins a browser may call the API from, such as the dashboard's `http://localhost:5173`; empty allows none, and wildcards are refused. Why CORS runs before authentication: [ADR 0017](decisions/0017-cors-filter-before-authentication.md).

Spring Boot does not read `.env` files by itself. `./mvnw spring-boot:run` switches on a `local` profile that imports the root `.env`, so the API and Compose always agree -- change the database password there and both sides see it. A variable exported in the shell still wins. The tests never activate that profile, so a developer's `.env` cannot change what they see.
