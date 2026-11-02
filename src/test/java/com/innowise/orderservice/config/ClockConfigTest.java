package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

  @Test
  void theApplicationClockIsTheUtcSystemClock() {
    Clock clock = new ClockConfig().clock();

    assertAll(
      () -> assertNotNull(clock),
      () -> assertEquals(ZoneOffset.UTC, clock.getZone()));
  }
}
