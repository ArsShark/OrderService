package com.innowise.orderservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Checks which failures of the wrapped decoder become a rejected token. */
class FailClosedJwtDecoderTest {

  @Test
  void passesAVerifiedTokenThrough() {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "1").build();

    assertSame(jwt, new FailClosedJwtDecoder(token -> jwt).decode("t"));
  }

  @Test
  void rethrowsARejectedTokenUnchanged() {
    BadJwtException rejected = new BadJwtException("expired");
    JwtDecoder decoder = new FailClosedJwtDecoder(token -> {
      throw rejected;
    });

    assertSame(rejected, assertThrows(BadJwtException.class, () -> decoder.decode("t")));
  }

  @Test
  void turnsAFailureToCheckIntoARejectedToken() {
    JwtException unavailable = new JwtException(
        "Couldn't retrieve remote JWK set: http://secret-host/jwks",
        new IOException("connection refused"));
    JwtDecoder decoder = new FailClosedJwtDecoder(token -> {
      throw unavailable;
    });

    BadJwtException rejected = assertThrows(BadJwtException.class, () -> decoder.decode("t"));

    assertAll(
        () -> assertEquals(FailClosedJwtDecoder.MESSAGE, rejected.getMessage()),
        () -> assertFalse(rejected.getMessage().contains("secret-host")),
        () -> assertSame(unavailable, rejected.getCause()));
  }

  @Test
  void doesNotHideABug() {
    JwtDecoder decoder = new FailClosedJwtDecoder(token -> {
      throw new IllegalStateException("bug");
    });

    assertThrows(IllegalStateException.class, () -> decoder.decode("t"));
  }
}
