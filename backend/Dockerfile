# The API's production image. Two stages: a JDK builds the jar, and a much
# smaller JRE-only image runs it -- the compiler, Maven and the source never
# reach production.
#
#   docker build -t grain-telemetry-api backend/
#
# PINNING: base images are pinned by tag to an exact Temurin release
# (21.0.12_8), so a rebuild months from now uses the same JDK the tests ran on.
# A tag can still be re-pointed by its publisher; to rule that out, pin by
# digest instead, which names one exact image:
#   docker buildx imagetools inspect eclipse-temurin:21.0.12_8-jre-alpine
#   FROM eclipse-temurin:21.0.12_8-jre-alpine@sha256:<digest from above>
# The trade-off is that a digest never picks up security fixes by itself, so
# it needs a tool such as Dependabot or Renovate to bump it.

# ---------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /workspace

# The Maven wrapper and pom first, then the dependency download, then the
# source. Docker caches each step and reruns a step only if its inputs
# changed, so an edit to a .java file reuses the downloaded dependencies
# instead of fetching them again.
#
# --mount=type=cache keeps Maven's local repository between builds as well,
# outside the image, so even a changed pom only downloads what is new.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q dependency:go-offline

COPY src src
# -DskipTests: the tests need Docker (Testcontainers), which is not available
# inside a build, and CI runs them before any image is built.
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q -DskipTests package \
 && cp target/telemetry-*.jar app.jar

# Split the jar into Spring Boot's layers -- dependencies, the loader,
# snapshot dependencies, and this application's own classes -- so the runtime
# stage can copy each into its own image layer (see below).
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2: run
# ---------------------------------------------------------------------------
# JRE, not JDK: no compiler is needed to run. Alpine keeps the image small
# (the README's target is under 250 MB).
FROM eclipse-temurin:21.0.12_8-jre-alpine

# A dedicated, unprivileged user. Running as root in a container is not the
# same as root on the host, but if the process were ever compromised, root
# inside the container is a far better starting point for an attacker.
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

# Least-changing first. A new build of the application changes only the last
# layer, so a deploy pushes and pulls a few hundred kilobytes rather than the
# whole dependency tree, which almost never changes.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# Size the heap from the container's memory limit rather than the host's:
# 75% of whatever ECS or Docker allows, leaving room for the JVM's own
# non-heap memory. Exit on OutOfMemoryError so the orchestrator restarts the
# task, instead of leaving a half-working JVM running.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Docker's own view of whether the app is up, from inside the container.
# (ECS uses the load balancer's health check on the same endpoint.) The
# start period covers startup, including Flyway migrations, before failures
# count. busybox wget is part of Alpine; no curl needed.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

# The extracted layout is launched by Spring Boot's loader rather than
# `java -jar`.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
