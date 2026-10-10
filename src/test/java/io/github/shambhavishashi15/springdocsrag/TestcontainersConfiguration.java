package io.github.shambhavishashi15.springdocsrag;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Starts a real Postgres + pgvector for tests; @ServiceConnection points the datasource at it.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Same image as compose.yaml. It's a postgres image with pgvector added, so declare it compatible.
	private static final DockerImageName PGVECTOR_IMAGE =
			DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE);
	}

}
