# 0025 - The production Dockerfile: Alpine JRE, layered jar, non-root

**Status:** Accepted, 2026-09-29 (recorded 2026-10-23)
**Applies to:** `backend/Dockerfile`, `backend/.dockerignore`

## Context

The spec asks for a hand-written multi-stage Dockerfile: a JDK stage that builds
and an Eclipse Temurin JRE stage that runs. It must use Spring Boot's layered
jars, run as a non-root user, expose 8080 with a `HEALTHCHECK`, pin base images
by tag, and stay under 250 MB. Within that, a few choices remained open.

## Decision

- **Build stage:** `eclipse-temurin:21.0.12_8-jdk-noble` (Ubuntu). The Maven
  wrapper and `pom.xml` are copied first and dependencies downloaded in their
  own layer, with a BuildKit cache mount for `~/.m2`, so a source change does
  not download dependencies again. Tests are skipped in the build
  (`-DskipTests`): they need Docker for Testcontainers, and CI runs them before
  any image is built.
- **Runtime stage:** `eclipse-temurin:21.0.12_8-jre-alpine`. A JRE, because
  nothing compiles at runtime. Alpine, because it is what gets under the size
  target.
- **Layers:** the jar is split with `java -Djarmode=tools -jar app.jar extract
  --layers --launcher`, and the layers are copied least-changing first:
  dependencies, the Boot loader, snapshot dependencies, then the application.
- **A dedicated unprivileged user,** `app`.
- **`HEALTHCHECK`** with busybox `wget` on `/actuator/health`, with a 60-second
  start period that covers Flyway at startup.
- **`JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`**:
  the heap is sized from the container's memory limit, and an out-of-memory
  JVM exits so ECS replaces it instead of leaving it half-working.
- **Pinned by exact Temurin tag,** with a comment showing how to pin by digest
  instead.

## Alternatives

**Ubuntu JRE (`-jre-noble`) at runtime.** glibc instead of musl, which is a
little more familiar and has fewer surprises with native libraries. But it is
larger, and the target leaves only a small margin.

**Distroless Java.** Smaller attack surface, with no shell and no package
manager. But there is no `wget` for the `HEALTHCHECK` the spec requires, and
nothing to debug with inside the container.

**A `jlink` runtime trimmed to the modules the application uses.** It would
roughly halve the image, but it is a custom JRE to maintain. Recorded as
enhancement E16.

**Jib or Cloud Native Buildpacks.** Both build good layered images with no
Dockerfile at all. The spec asks for a hand-written one, so the layering stays
visible and explainable.

## Consequences

- **239.6 MB uncompressed and 100.7 MB compressed** (`docs/results.md`). The
  JRE is 165 MB of that, and the application's own code is a 0.6 MB layer, so a
  normal code change ships under a megabyte of new image.
- **Alpine's packages are this image's main source of vulnerability findings.**
  ECR's first scan reported four HIGH CVEs, in `expat` and `zlib` from the base
  image (issue #20). Pinning by tag means fixes arrive only when the tag is
  bumped.
- **musl, not glibc,** at runtime. Temurin publishes and tests Alpine builds,
  and the application uses no native libraries, so this has not mattered.
- `docker build` takes no flags and works the same locally and in CI, and the
  same image runs in Compose (`--profile api`) and on ECS.
