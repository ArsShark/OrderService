package com.innowise.orderservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts a PostgreSQL container for tests and wires it into the Spring context. Spring manages
 * the container lifecycle, and {@link ServiceConnection} replaces the datasource properties,
 * so the tests need no environment variables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /**
   * PostgreSQL container with the same major version as the local database.
   *
   * @return the container
   */
  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:18");
  }
}
