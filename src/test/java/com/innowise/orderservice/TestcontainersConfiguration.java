package com.innowise.orderservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a PostgreSQL container and a Kafka container for tests and wires them into the Spring
 * context. Spring manages the container lifecycle. {@link ServiceConnection} replaces the
 * datasource properties, and the address of the broker is given through the property that
 * production uses, so the tests need no environment variables.
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

  /**
   * A single Kafka broker in KRaft mode, the image of the Compose file of the Payment Service.
   * Unlike that Compose file the container creates a topic when something writes to it; the
   * tests declare the topic of the Payment Service as a bean, so it has its real three partitions.
   *
   * @return the container
   */
  @Bean
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.0"));
  }

  /**
   * Gives the application the address of the broker, through the property of the profiles.
   *
   * @param kafkaContainer the started container
   * @return the registrar that Spring calls when it builds the environment
   */
  @Bean
  DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafkaContainer) {
    return registry -> registry.add("spring.kafka.bootstrap-servers",
      kafkaContainer::getBootstrapServers);
  }
}
