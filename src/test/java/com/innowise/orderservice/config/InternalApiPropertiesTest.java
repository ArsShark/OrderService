package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class InternalApiPropertiesTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void aMissingKeyStopsTheStartup(String key) {
    assertThrows(IllegalArgumentException.class, () -> new InternalApiProperties(key));
  }

  @Test
  void theKeyIsKeptButNeverPrinted() {
    InternalApiProperties properties = new InternalApiProperties("very-secret-key");

    assertAll(
      () -> assertEquals("very-secret-key", properties.apiKey()),
      () -> assertFalse(properties.toString().contains("very-secret-key")));
  }
}
