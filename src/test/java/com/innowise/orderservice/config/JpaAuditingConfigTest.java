package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

class JpaAuditingConfigTest {

  @Test
  void theAuditingTimeComesFromTheApplicationClock() {
    Instant now = Instant.parse("2026-11-02T10:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    DateTimeProvider provider = new JpaAuditingConfig().auditingDateTimeProvider(clock);

    assertEquals(Optional.of(now), provider.getNow());
  }
}
