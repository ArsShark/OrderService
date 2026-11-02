package com.innowise.orderservice.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with a {@link MutableClock} in the integration tests.
 *
 * <p>The application has one {@code Clock} bean, and both JPA Auditing and the services read the
 * time from it, so replacing it moves all of them at once.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

  /**
   * The clock of the integration tests. It is primary, so it wins over the clock of
   * {@code ClockConfig} wherever a plain {@code Clock} is injected.
   *
   * @return a clock that starts at {@link AbstractIntegrationTest#START}
   */
  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(AbstractIntegrationTest.START);
  }
}
