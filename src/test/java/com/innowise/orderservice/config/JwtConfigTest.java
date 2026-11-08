package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.JwksTestServer;
import com.innowise.orderservice.TestKeys;
import com.innowise.orderservice.TestTokens;
import com.innowise.orderservice.security.FailClosedJwtDecoder;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Checks the decoder the way a request meets it: with a real key set served over HTTP by
 * {@link JwksTestServer}, so the cache, the timeouts and the rate limit of the key source are the
 * real ones.
 *
 * <p>The clock of these tests stands on the day of the tokens, not on the day the tests run: the
 * time of the check comes from the application clock and not from the system clock.
 */
class JwtConfigTest {

  private static final Instant NOW = TestTokens.ISSUED_AT.plusSeconds(60);

  private JwksTestServer server;

  @BeforeEach
  void startServer() {
    server = JwksTestServer.start(TestKeys.jwksJson());
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void acceptsAnAccessTokenAndKeepsItsClaims() {
    Jwt jwt = decoderAt(NOW).decode(TestTokens.access().build());

    assertAll(
        () -> assertEquals("1", jwt.getSubject()),
        () -> assertEquals(7L, ((Number) jwt.getClaims().get("user_id")).longValue()),
        () -> assertEquals("USER", jwt.getClaimAsString("role")),
        () -> assertEquals("user@example.com", jwt.getClaimAsString("email")),
        () -> assertEquals("access", jwt.getClaimAsString("token_type")),
        () -> assertEquals(TestKeys.KEY_ID, jwt.getHeaders().get("kid")));
  }

  @Test
  void acceptsTheTokenOfAnAdminWithoutAProfile() {
    Jwt jwt = decoderAt(NOW).decode(TestTokens.admin().build());

    assertAll(
        () -> assertEquals("ADMIN", jwt.getClaimAsString("role")),
        () -> assertFalse(jwt.hasClaim("user_id")));
  }

  /** There is no clock skew: the token is valid up to its {@code exp} and not a second longer. */
  @Test
  void rejectsAnExpiredTokenWithoutAnyClockSkew() {
    Instant expiresAt = TestTokens.ISSUED_AT.plus(TestTokens.ACCESS_TTL);
    String token = TestTokens.access().build();

    assertDoesNotThrow(() -> decoderAt(expiresAt).decode(token), "valid at the second of exp");
    assertThrows(JwtValidationException.class,
        () -> decoderAt(expiresAt.plusSeconds(1)).decode(token));
  }

  @Test
  void rejectsATokenWithoutAnExpiry() {
    String token = TestTokens.access().expiresAt(null).build();

    assertThrows(JwtValidationException.class, () -> decoderAt(NOW).decode(token));
  }

  @Test
  void rejectsATokenOfAnotherIssuer() {
    String token = TestTokens.access().issuer("someone-else").build();

    assertThrows(JwtValidationException.class, () -> decoderAt(NOW).decode(token));
  }

  @Test
  void rejectsATokenWithoutAnIssuer() {
    String token = TestTokens.access().issuer(null).build();

    assertThrows(JwtValidationException.class, () -> decoderAt(NOW).decode(token));
  }

  @Test
  void rejectsARefreshTokenAndATokenWithoutAType() {
    JwtDecoder decoder = decoderAt(NOW);

    assertAll(
        () -> assertThrows(JwtValidationException.class,
            () -> decoder.decode(TestTokens.access().tokenType("refresh").build())),
        () -> assertThrows(JwtValidationException.class,
            () -> decoder.decode(TestTokens.access().tokenType(null).build())));
  }

  @Test
  void rejectsATokenSignedWithAnotherKeyUnderTheKeyIdOfTheRealOne() {
    String token = TestTokens.access().signedWith(TestKeys.generateRsa(2048)).build();

    assertThrows(BadJwtException.class, () -> decoderAt(NOW).decode(token));
  }

  @Test
  void rejectsATokenWithAKeyIdThatTheKeySetDoesNotHave() {
    String token = TestTokens.access()
        .signedWith(TestKeys.generateRsa(2048)).keyId("unknown").build();

    assertThrows(BadJwtException.class, () -> decoderAt(NOW).decode(token));
  }

  /** A token with {@code alg=none} has no signature at all and must never be accepted. */
  @Test
  void rejectsAnUnsignedToken() {
    String unsigned = String.format("%s.%s.", base64Url("{\"alg\":\"none\"}"),
        base64Url(adminPayload()));

    assertThrows(BadJwtException.class, () -> decoderAt(NOW).decode(unsigned));
  }

  /**
   * The classic algorithm confusion: a token signed with HS256, using the public key as the
   * secret. The decoder accepts RS256 only.
   */
  @Test
  void rejectsATokenSignedWithHmacUsingThePublicKeyAsSecret() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(TestTokens.ISSUER)
        .subject("1")
        .claim("role", "ADMIN")
        .claim("token_type", "access")
        .expirationTime(Date.from(NOW.plusSeconds(600)))
        .build();
    SignedJWT forged = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TestKeys.KEY_ID).build(), claims);
    forged.sign(new MACSigner(TestKeys.KEY_PAIR.getPublic().getEncoded()));
    String token = forged.serialize();

    assertThrows(BadJwtException.class, () -> decoderAt(NOW).decode(token));
  }

  @Test
  void rejectsATokenWhosePayloadWasChanged() {
    String[] parts = TestTokens.access().build().split("\\.");
    String payload = base64Url(adminPayload());
    String forged = String.format("%s.%s.%s", parts[0], payload, parts[2]);

    assertThrows(BadJwtException.class, () -> decoderAt(NOW).decode(forged));
  }

  @Test
  void fetchesTheKeySetOnTheFirstTokenOnlyAndThenUsesTheCache() {
    JwtDecoder decoder = decoderAt(NOW);
    assertEquals(0, server.requestCount(), "nothing is fetched at startup");

    decoder.decode(TestTokens.access().build());
    decoder.decode(TestTokens.admin().build());
    decoder.decode(TestTokens.access().build());

    assertEquals(1, server.requestCount());
  }

  @Test
  void keepsWorkingWithTheCachedKeyWhenTheAuthenticationServiceStops() {
    JwtDecoder decoder = decoderAt(NOW);
    decoder.decode(TestTokens.access().build());

    server.stop();

    assertDoesNotThrow(() -> decoder.decode(TestTokens.access().build()));
  }

  /**
   * Anybody can send a token with a key id that does not exist. It must not make this service
   * call the Authentication Service again every time: the first such token may cause one fetch
   * (the key set could have changed), the following ones are rejected without a call.
   */
  @Test
  void doesNotFetchTheKeySetAgainForEveryTokenWithAnUnknownKeyId() {
    JwtDecoder decoder = decoderAt(NOW);
    decoder.decode(TestTokens.access().build());
    String unknown = TestTokens.access()
        .signedWith(TestKeys.generateRsa(2048)).keyId("unknown").build();

    assertThrows(BadJwtException.class, () -> decoder.decode(unknown));
    int afterTheFirstUnknown = server.requestCount();
    for (int i = 0; i < 5; i++) {
      assertThrows(BadJwtException.class, () -> decoder.decode(unknown));
    }

    assertAll(
        () -> assertTrue(afterTheFirstUnknown <= 2, "at most one extra fetch"),
        () -> assertEquals(afterTheFirstUnknown, server.requestCount()));
  }

  @Test
  void failsClosedWhenTheAuthenticationServiceIsNotRunning() {
    JwtDecoder decoder = decoderAt(NOW);
    server.stop();

    BadJwtException rejected = assertThrows(BadJwtException.class,
        () -> decoder.decode(TestTokens.access().build()));

    assertEquals(FailClosedJwtDecoder.MESSAGE, rejected.getMessage());
  }

  @Test
  void failsClosedWhenTheAuthenticationServiceAnswersWithAnError() {
    server.respondWith(500, "{}");

    assertThrows(BadJwtException.class,
        () -> decoderAt(NOW).decode(TestTokens.access().build()));
  }

  @Test
  void failsClosedWhenTheAnswerIsNotAKeySet() {
    server.respondWith(200, "this is not a key set");

    assertThrows(BadJwtException.class,
        () -> decoderAt(NOW).decode(TestTokens.access().build()));
  }

  @Test
  void failsClosedWhenTheAnswerIsTooLarge() {
    server.respondWith(200, "x".repeat(JwtConfig.JWKS_SIZE_LIMIT_BYTES * 2));

    assertThrows(BadJwtException.class,
        () -> decoderAt(NOW).decode(TestTokens.access().build()));
  }

  @Test
  void doesNotWaitForeverForASlowAuthenticationService() {
    server.hang();
    JwtDecoder decoder = decoderAt(NOW);
    String token = TestTokens.access().build();

    assertTimeoutPreemptively(Duration.ofSeconds(8),
        () -> assertThrows(BadJwtException.class, () -> decoder.decode(token)));
  }

  @Test
  void readsTheRoleOfAnAdminAndOfAUser() {
    JwtAuthenticationConverter converter = new JwtConfig().jwtAuthenticationConverter();

    AbstractAuthenticationToken admin =
        converter.convert(decoderAt(NOW).decode(TestTokens.admin().build()));
    AbstractAuthenticationToken user =
        converter.convert(decoderAt(NOW).decode(TestTokens.access().build()));

    assertAll(
        () -> assertEquals("2", admin.getName()),
        () -> assertTrue(roles(admin.getAuthorities()).contains("ROLE_ADMIN")),
        () -> assertFalse(roles(admin.getAuthorities()).contains("ROLE_USER")),
        () -> assertEquals("1", user.getName()),
        () -> assertTrue(roles(user.getAuthorities()).contains("ROLE_USER")),
        () -> assertFalse(roles(user.getAuthorities()).contains("ROLE_ADMIN")));
  }

  @Test
  void aTokenWithAnUnknownRoleHasNoRole() {
    JwtAuthenticationConverter converter = new JwtConfig().jwtAuthenticationConverter();

    AbstractAuthenticationToken authentication = converter.convert(
        decoderAt(NOW).decode(TestTokens.access().role("SUPERUSER").build()));

    assertTrue(roles(authentication.getAuthorities()).stream()
        .noneMatch(authority -> authority.startsWith("ROLE_")));
  }

  @Test
  void springCreatesTheDecoderAndTheConverterFromTheSettings() {
    new ApplicationContextRunner()
        .withUserConfiguration(JwtConfig.class)
        .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
        .withPropertyValues(
            String.format("app.security.jwks-uri=%s", server.jwksUri()),
            "app.security.jwt-issuer=authentication-service")
        .run(context -> {
          assertNotNull(context.getBean(JwtDecoder.class));
          assertNotNull(context.getBean(JwtAuthenticationConverter.class));
          assertDoesNotThrow(() ->
              context.getBean(JwtDecoder.class).decode(TestTokens.access().build()));
        });
  }

  @Test
  void startupFailsWithoutTheAddressOfTheKeySet() {
    new ApplicationContextRunner()
        .withUserConfiguration(JwtConfig.class)
        .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
        .withPropertyValues("app.security.jwt-issuer=authentication-service")
        .run(context -> {
          assertNotNull(context.getStartupFailure());
          assertTrue(allMessages(context.getStartupFailure()).contains("app.security.jwks-uri"));
        });
  }

  private JwtDecoder decoderAt(Instant now) {
    return new JwtConfig().jwtDecoder(
        new SecurityProperties(server.jwksUri(), TestTokens.ISSUER),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private static List<String> roles(
      Collection<? extends GrantedAuthority> authorities) {
    return authorities.stream().map(GrantedAuthority::getAuthority).toList();
  }

  /** The payload of a token that claims to be an admin, as a forger would write it. */
  private static String adminPayload() {
    String template = """
        {"iss":"%s","sub":"1","role":"ADMIN","token_type":"access","exp":%d}""";
    return String.format(template, TestTokens.ISSUER, NOW.plusSeconds(600).getEpochSecond());
  }

  private static String base64Url(String text) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** The messages of a failure and of all its causes in one string. */
  private static String allMessages(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    return messages.toString();
  }
}
