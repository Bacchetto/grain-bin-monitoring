# 0012 - Rancher Desktop as the local container runtime

**Status:** Accepted, 2026-09-23 (recorded 2026-09-24)
**Applies to:** local development only

## Context

The development machine started with no container runtime. One is required
twice over: Testcontainers starts a real PostgreSQL for the integration tests,
and Docker Compose runs the local database. Both need a Docker-compatible API.
On Windows, every option also needs WSL2.

## Decision

**Rancher Desktop**, with its container engine set to **dockerd (moby)**, chosen
by the project owner.

## Alternatives

**Docker Desktop.** The most common choice and the best-trodden path. Its licence
is free for personal use and small organisations but paid for larger ones.
Rejected to avoid the licensing question entirely, not because it would not work
-- nothing in the project depends on Rancher specifically, and Docker Desktop is
a drop-in alternative.

**Podman Desktop.** Free and open source, but Testcontainers needs additional
configuration to work with it -- one more thing to explain for no benefit here.

**Rancher Desktop with its default containerd engine.** Rejected because
containerd exposes no Docker-compatible API, so Testcontainers cannot use it.

## Consequences

- **The engine must be dockerd.** This is the most likely thing to go wrong on a
  new machine, and the failure -- Testcontainers cannot find Docker -- looks like
  a code problem. The README's setup section says so.
- **On Windows, `DOCKER_HOST=npipe:////./pipe/docker_engine` is required** for
  processes running natively, including the Maven test run.
- Rancher Desktop starts a Kubernetes cluster by default. Nothing here uses it,
  it costs memory, and it can be disabled in Preferences.
- The first build after a reboot once hung for twelve minutes while the WSL
  virtual machine settled -- a thread dump showed `docker-java` blocked reading
  the named pipe. Worth knowing when a build stalls with no output.
