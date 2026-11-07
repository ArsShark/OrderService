package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserServicePropertiesTest {

  private static final Duration TWO = Duration.ofSeconds(2);

  @Test
  void validSettingsAreKept() {
    UserServiceProperties properties = new UserServiceProperties("http://user-service:8082",
      TWO, Duration.ofSeconds(5));

    assertAll(
      () -> assertEquals("http://user-service:8082", properties.baseUri()),
      () -> assertEquals(TWO, properties.connectTimeout()),
      () -> assertEquals(Duration.ofSeconds(5), properties.readTimeout()));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void aMissingAddressStopsTheStartup(String baseUri) {
    assertThrows(IllegalArgumentException.class,
      () -> new UserServiceProperties(baseUri, TWO, TWO));
  }

  @Test
  void timeoutsMustBePositive() {
    assertAll(
      () -> assertThrows(IllegalArgumentException.class,
        () -> new UserServiceProperties("http://x", Duration.ZERO, TWO)),
      () -> assertThrows(IllegalArgumentException.class,
        () -> new UserServiceProperties("http://x", TWO, Duration.ofSeconds(-1))));
  }
}
