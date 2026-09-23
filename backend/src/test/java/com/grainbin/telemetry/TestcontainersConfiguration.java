package com.grainbin.telemetry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real PostgreSQL in Docker for integration tests.
 *
 * <p>The project rule is no H2 and no mocked database. The schema relies on
 * range partitioning, {@code INSERT ... ON CONFLICT DO NOTHING} and
 * {@code DISTINCT ON}, none of which an in-memory database reproduces
 * faithfully. A test that passed against H2 would prove nothing about
 * production.
 *
 * <p>{@code @ServiceConnection} replaces the usual dance of copying the
 * container's random host and port into Spring properties: Boot discovers the
 * container and points the application's {@code DataSource} at it.
 *
 * <p>Spring caches application contexts between test classes, so the container
 * is started once and reused across the suite rather than per test class.
 *
 * <p>Public because integration tests live in the feature packages they
 * exercise, not alongside this class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * Pinned to the same major version as production (RDS PostgreSQL 16).
	 * Testing against {@code postgres:latest} would mean the database under
	 * test silently changes version over time, and partitioning behaviour is
	 * exactly the sort of thing that differs between majors.
	 */
	private static final DockerImageName POSTGRES_IMAGE =
			DockerImageName.parse("postgres:16-alpine");

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

}
