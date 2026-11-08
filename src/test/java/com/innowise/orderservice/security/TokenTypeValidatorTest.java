package com.innowise.orderservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Checks that a refresh token, or a token of unknown type, is never taken for an access token. */
class TokenTypeValidatorTest {

  private final TokenTypeValidator validator = new TokenTypeValidator();

  @Test
  void acceptsAnAccessToken() {
    assertFalse(validator.validate(jwt("access")).hasErrors());
  }

  @ParameterizedTest
  @ValueSource(strings = {"refresh", "Access", "ACCESS", "access ", ""})
  void rejectsAnyOtherType(String type) {
    OAuth2TokenValidatorResult result = validator.validate(jwt(type));

    assertAll(
        () -> assertTrue(result.hasErrors()),
        () -> assertEquals("invalid_token",
            result.getErrors().iterator().next().getErrorCode()));
  }

  @Test
  void rejectsATokenWithoutAType() {
    assertTrue(validator.validate(Jwt.withTokenValue("t").header("alg", "RS256")
        .claim("sub", "1").build()).hasErrors());
  }

  @Test
  void rejectsATypeThatIsNotText() {
    assertTrue(validator.validate(jwt(List.of("access"))).hasErrors());
  }

  private static Jwt jwt(Object type) {
    return Jwt.withTokenValue("t").header("alg", "RS256").claim("token_type", type).build();
  }
}
