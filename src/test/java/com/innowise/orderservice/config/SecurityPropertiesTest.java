package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that a wrong address or issuer is refused when the settings are created. */
class SecurityPropertiesTest {

  private static final String URI = "http://authentication-service:8081/.well-known/jwks.json";

  @Test
  void acceptsAnHttpAndAnHttpsAddress() {
    assertAll(
        () -> assertDoesNotThrow(() -> new SecurityProperties(URI, "authentication-service")),
        () -> assertDoesNotThrow(
            () -> new SecurityProperties("HTTPS://auth.internal/jwks", "authentication-service")));
  }

  @Test
  void keepsTheValues() {
    SecurityProperties properties = new SecurityProperties(URI, "authentication-service");

    assertAll(
        () -> assertEquals(URI, properties.jwksUri()),
        () -> assertEquals("authentication-service", properties.jwtIssuer()));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "/.well-known/jwks.json", "localhost:8081/jwks",
      "ftp://auth/jwks", "file:///etc/jwks.json", "http:///jwks", "http://exa mple/jwks"})
  void refusesAddressesThatAreNotAbsoluteHttpUris(String address) {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> new SecurityProperties(address, "authentication-service"));

    assertTrue(failure.getMessage().contains("app.security.jwks-uri"), failure.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void refusesABlankIssuer(String issuer) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> new SecurityProperties(URI, issuer));

    assertTrue(failure.getMessage().contains("app.security.jwt-issuer"), failure.getMessage());
  }
}
