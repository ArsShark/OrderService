package com.innowise.orderservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.service.Caller;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Tests of {@link Callers}: the caller comes from the verified token and nowhere else. */
class CallersTest {

  private static JwtAuthenticationToken token(String role, Consumer<Jwt.Builder> claims) {
    Jwt.Builder builder = Jwt.withTokenValue("token")
      .header("alg", "RS256")
      .issuedAt(Instant.parse("2026-11-02T10:00:00Z"))
      .expiresAt(Instant.parse("2026-11-02T10:15:00Z"))
      .subject("2");
    claims.accept(builder);
    return new JwtAuthenticationToken(builder.build(),
      List.of(new SimpleGrantedAuthority(String.format("ROLE_%s", role))));
  }

  private static JwtAuthenticationToken user() {
    return token("USER", jwt -> jwt.claim("user_id", 7L).claim("email", "buyer@example.com"));
  }

  @Test
  void aUserTokenGivesTheProfileTheEmailAndNoAdminRight() {
    Caller caller = Callers.from(user());

    assertAll(
      () -> assertEquals(7L, caller.userId()),
      () -> assertEquals("buyer@example.com", caller.email()),
      () -> assertFalse(caller.admin()));
  }

  @Test
  void anAdminTokenIsAnAdminWithOrWithoutAProfile() {
    Caller withoutProfile = Callers.from(
      token("ADMIN", jwt -> jwt.claim("email", "admin@example.com")));
    Caller withProfile = Callers.from(token("ADMIN",
      jwt -> jwt.claim("user_id", 1L).claim("email", "admin@example.com")));

    assertAll(
      () -> assertTrue(withoutProfile.admin()),
      () -> assertNull(withoutProfile.userId()),
      () -> assertTrue(withProfile.admin()),
      () -> assertEquals(1L, withProfile.userId()));
  }

  /** The id comes as a JSON number; the token of an int-sized id is an Integer after parsing. */
  @Test
  void aNumberOfAnyKindIsAUserId() {
    Caller caller = Callers.from(
      token("USER", jwt -> jwt.claim("user_id", 7).claim("email", "buyer@example.com")));

    assertEquals(7L, caller.userId());
  }

  @Test
  void aUserIdThatIsNotANumberCountsAsNoProfile() {
    JwtAuthenticationToken text = token("USER",
      jwt -> jwt.claim("user_id", "7").claim("email", "buyer@example.com"));

    assertAll(
      () -> assertNull(Callers.from(text).userId()),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(text)));
  }

  @Test
  void aTokenWithoutAnEmailOrWithABlankOneHasNoEmail() {
    JwtAuthenticationToken missing = token("USER", jwt -> jwt.claim("user_id", 7L));
    JwtAuthenticationToken blank = token("USER",
      jwt -> jwt.claim("user_id", 7L).claim("email", "  "));

    assertAll(
      () -> assertNull(Callers.from(missing).email()),
      () -> assertNull(Callers.from(blank).email()));
  }

  @Test
  void ownerReturnsTheCallerOfATokenWithAProfileAndAnEmail() {
    assertEquals(new Caller(7L, "buyer@example.com", false), Callers.owner(user()));
  }

  @Test
  void ownerRefusesATokenWithoutAProfileWhateverTheRole() {
    JwtAuthenticationToken admin = token("ADMIN", jwt -> jwt.claim("email", "admin@example.com"));
    JwtAuthenticationToken regularUser = token("USER",
      jwt -> jwt.claim("email", "buyer@example.com"));

    assertAll(
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(admin)),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(regularUser)));
  }

  @Test
  void ownerRefusesATokenWithoutAnEmail() {
    JwtAuthenticationToken noEmail = token("USER", jwt -> jwt.claim("user_id", 7L));

    assertThrows(AccessDeniedException.class, () -> Callers.owner(noEmail));
  }

  /** The role of a token is its authority, and only the exact ROLE_ADMIN makes an admin. */
  @Test
  void onlyTheAuthorityRoleAdminMakesAnAdmin() {
    JwtAuthenticationToken claimOnly = new JwtAuthenticationToken(
      Jwt.withTokenValue("token").header("alg", "RS256").claim("role", "ADMIN")
        .claim("user_id", 7L).claim("email", "buyer@example.com").build(),
      List.of(new SimpleGrantedAuthority("ROLE_USER")));

    assertFalse(Callers.from(claimOnly).admin());
  }

  /** Anything but a verified token is refused, so a configuration mistake closes the door. */
  @Test
  void anAuthenticationThatIsNotAVerifiedTokenIsRefused() {
    UsernamePasswordAuthenticationToken mock = UsernamePasswordAuthenticationToken.authenticated(
      "user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertAll(
      () -> assertThrows(AccessDeniedException.class, () -> Callers.from(mock)),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.owner(mock)),
      () -> assertThrows(AccessDeniedException.class, () -> Callers.from(null)));
  }
}
