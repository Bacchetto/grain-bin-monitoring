# 0021 - Local tooling that works from a network drive: poll, don't watch; build, don't mount

**Status:** Accepted, 2026-09-28
**Applies to:** `frontend/vite.config.ts`, `ops/prometheus/Dockerfile`, `ops/grafana/Dockerfile`, `docker-compose.yml`

## Context

On the development machine the repository lives on `Z:`, a mapped network
drive. Two ordinary local-development techniques fail there, and neither
failure shows up in any test or build:

- **Native file watching.** Vite's dev server asks the operating system to
  report file changes, and on a network drive that call fails. The dev server
  crashed on start with `UNKNOWN: unknown error, watch`.
- **Bind mounts.** The Docker engine runs inside Rancher Desktop's Linux VM,
  which can see local disks but not a mapped network drive. Mounting
  Prometheus's config into its container was refused with
  `invalid volume specification`. The same mount from `C:` worked.

## Decision

- **The Vite dev server polls** for file changes every 300 ms. Setting
  `VITE_NATIVE_WATCH=true` on a local disk restores the faster native watcher.
- **Prometheus's and Grafana's configuration is built into their images** with
  two short Dockerfiles, instead of being bind-mounted. A build sends its files
  to the engine over the Docker API, so it works from any drive.

## Alternatives

**Move the repository to a local disk.** Fixes both at a stroke, and is the
owner's choice to make at any time -- nothing here depends on the drive. The
project should still work where it is.

**Compose `configs:` or inline config.** For single files only, and awkward for
a dashboard JSON; the built image is simpler.

## Consequences

- **After editing anything under `ops/`, run `docker compose up -d --build`.**
  Plain `up` keeps the old image. The README and `docker-compose.yml` both say
  so.
- Polling costs a little CPU while the dev server runs.
- Both work unchanged on a local disk and in CI, so neither is a workaround
  that has to be undone later.
- **A related trap: the usual advice for reaching the host breaks under
  Rancher.** Linux guides add `extra_hosts: host.docker.internal:host-gateway`.
  Under Rancher Desktop it replaced the working name, which points at the
  Windows host, with the VM's own gateway, and Prometheus's scrape failed.
  Rancher and Docker Desktop both define the name already, so the line is
  omitted, and the Compose file says why.
