package com.innowise.orderservice.config;

import com.innowise.orderservice.exception.UserServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The circuit breaker of the calls to the User Service, built in code from the core module of
 * Resilience4j (the Spring Boot starter is not used).
 */
@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class ResilienceConfig {

  /** The name of the breaker in log lines. */
  public static final String USER_SERVICE_BREAKER = "user-service";

  private static final Logger LOGGER = LoggerFactory.getLogger(ResilienceConfig.class);

  /**
   * Creates the breaker. Only a {@link UserServiceUnavailableException} counts as a failure: an
   * unknown email is a normal answer, and a bug in our own code must not open the breaker.
   * Every change of state is logged, because "the user data is missing" starts with it.
   *
   * @param properties the thresholds and the durations
   * @return the breaker, shared by every caller
   */
  @Bean
  public CircuitBreaker userServiceCircuitBreaker(CircuitBreakerProperties properties) {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
      .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(properties.slidingWindowSize())
      .minimumNumberOfCalls(properties.minimumNumberOfCalls())
      .failureRateThreshold(properties.failureRateThreshold())
      .waitDurationInOpenState(properties.openStateDuration())
      .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
      .recordExceptions(UserServiceUnavailableException.class)
      .build();
    CircuitBreaker breaker = CircuitBreaker.of(USER_SERVICE_BREAKER, config);
    breaker.getEventPublisher().onStateTransition(event -> LOGGER.warn(
      "Circuit breaker '{}' changed its state from {} to {}", event.getCircuitBreakerName(),
      event.getStateTransition().getFromState(), event.getStateTransition().getToState()));
    return breaker;
  }
}
