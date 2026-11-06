package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CircuitBreakerPropertiesTest {

  private static final Duration TEN = Duration.ofSeconds(10);

  @Test
  void validSettingsAreKept() {
    CircuitBreakerProperties properties = new CircuitBreakerProperties(10, 5, 50, TEN, 3);

    assertAll(
      () -> assertEquals(10, properties.slidingWindowSize()),
      () -> assertEquals(5, properties.minimumNumberOfCalls()),
      () -> assertEquals(50f, properties.failureRateThreshold()),
      () -> assertEquals(TEN, properties.openStateDuration()),
      () -> assertEquals(3, properties.halfOpenCalls()));
  }

  @Test
  void countsMustBePositive() {
    assertAll(
      () -> assertThrows(IllegalArgumentException.class,
        () -> new CircuitBreakerProperties(0, 5, 50, TEN, 3)),
      () -> assertThrows(IllegalArgumentException.class,
        () -> new CircuitBreakerProperties(10, 0, 50, TEN, 3)),
      () -> assertThrows(IllegalArgumentException.class,
        () -> new CircuitBreakerProperties(10, 5, 50, TEN, 0)));
  }

  @ParameterizedTest
  @ValueSource(floats = {0f, 0.5f, 100.5f, 150f, -1f})
  void theFailureRateIsAPercentageFromOneToHundred(float threshold) {
    assertThrows(IllegalArgumentException.class,
      () -> new CircuitBreakerProperties(10, 5, threshold, TEN, 3));
  }

  @Test
  void theOpenDurationMustBePositive() {
    assertAll(
      () -> assertThrows(IllegalArgumentException.class,
        () -> new CircuitBreakerProperties(10, 5, 50, Duration.ZERO, 3)),
      () -> assertThrows(IllegalArgumentException.class,
        () -> new CircuitBreakerProperties(10, 5, 50, Duration.ofSeconds(-1), 3)));
  }
}
