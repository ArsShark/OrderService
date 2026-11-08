package com.innowise.orderservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Checks that only the two roles of the Authentication Service open anything. */
class RoleAuthoritiesConverterTest {

  private final RoleAuthoritiesConverter converter = new RoleAuthoritiesConverter();

  @Test
  void mapsAdminAndUserToRoleAuthorities() {
    assertEquals(List.of("ROLE_ADMIN"), authorities(jwtWithRole("ADMIN")));
    assertEquals(List.of("ROLE_USER"), authorities(jwtWithRole("USER")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "User", "ROLE_ADMIN", "ADMIN USER", "ADMIN,USER", "INTERNAL",
      "", " ", " ADMIN", "ADMIN "})
  void givesNothingForAnyOtherText(String role) {
    assertTrue(authorities(jwtWithRole(role)).isEmpty());
  }

  @Test
  void givesNothingForAClaimThatIsNotText() {
    assertTrue(authorities(jwtWithClaim("role", 1)).isEmpty());
    assertTrue(authorities(jwtWithClaim("role", List.of("ADMIN"))).isEmpty());
  }

  @Test
  void givesNothingWithoutTheClaim() {
    assertTrue(authorities(jwtWithClaim("sub", "42")).isEmpty());
  }

  private List<String> authorities(Jwt jwt) {
    return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
  }

  private static Jwt jwtWithRole(String role) {
    return jwtWithClaim("role", role);
  }

  private static Jwt jwtWithClaim(String name, Object value) {
    return Jwt.withTokenValue("token").header("alg", "RS256").claim(name, value).build();
  }
}
