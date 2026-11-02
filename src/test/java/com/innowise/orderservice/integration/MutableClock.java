package com.innowise.orderservice.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A UTC clock for tests that stands still until a test moves it.
 *
 * <p>A fixed clock would give every entity the same {@code createdAt} and {@code updatedAt},
 * so the tests could not tell whether an update really refreshed {@code updatedAt}. With this
 * clock a test creates an entity, advances the time and then checks the new value.
 */
final class MutableClock extends Clock {

  private final AtomicReference<Instant> current;

  MutableClock(Instant start) {
    this.current = new AtomicReference<>(start);
  }

  /** Sets the current instant. */
  void set(Instant instant) {
    current.set(instant);
  }

  /** Moves the clock forward by the given duration. */
  void advance(Duration duration) {
    current.updateAndGet(now -> now.plus(duration));
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    if (!ZoneOffset.UTC.equals(zone)) {
      throw new UnsupportedOperationException("MutableClock works in UTC only");
    }
    return this;
  }

  @Override
  public Instant instant() {
    return current.get();
  }
}
