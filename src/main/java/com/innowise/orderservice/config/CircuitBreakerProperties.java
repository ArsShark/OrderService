package com.innowise.orderservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the circuit breaker that guards the calls to the User Service.
 *
 * <p>The breaker looks at the last {@code slidingWindowSize} calls. Once at least
 * {@code minimumNumberOfCalls} are known and at least {@code failureRateThreshold} percent of the
 * window failed, it opens: calls are refused at once, without touching the network. After
 * {@code openStateDuration} the next call may pass as a probe ("half-open"); after
 * {@code halfOpenCalls} probes it closes again if they went well and opens again if they did not.
 *
 * @param slidingWindowSize how many of the latest calls are judged
 * @param minimumNumberOfCalls how many calls are needed before the rate is judged at all
 * @param failureRateThreshold percentage of failed calls (1 to 100) that opens the breaker
 * @param openStateDuration how long the breaker stays open before it lets a probe through
 * @param halfOpenCalls how many probes are judged in the half-open state
 */
@ConfigurationProperties(prefix = "app.user-service.circuit-breaker")
public record CircuitBreakerProperties(
  @DefaultValue("10") int slidingWindowSize,
  @DefaultValue("5") int minimumNumberOfCalls,
  @DefaultValue("50") float failureRateThreshold,
  @DefaultValue("10s") Duration openStateDuration,
  @DefaultValue("3") int halfOpenCalls) {

  /** Refuses values that make no sense, so the startup fails. */
  public CircuitBreakerProperties {
    requirePositive(slidingWindowSize, "sliding-window-size");
    requirePositive(minimumNumberOfCalls, "minimum-number-of-calls");
    requirePositive(halfOpenCalls, "half-open-calls");
    if (failureRateThreshold < 1 || failureRateThreshold > 100) {
      throw new IllegalArgumentException(
        "app.user-service.circuit-breaker.failure-rate-threshold must be from 1 to 100");
    }
    if (openStateDuration.isZero() || openStateDuration.isNegative()) {
      throw new IllegalArgumentException(
        "app.user-service.circuit-breaker.open-state-duration must be positive");
    }
  }

  private static void requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(
        String.format("app.user-service.circuit-breaker.%s must be positive", name));
    }
  }
}
