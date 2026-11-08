package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.TestKeys;
import com.innowise.orderservice.TestTokens;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the security chain of the whole application: real tokens, verified with the key set that
 * a small HTTP server serves, real beans and a real database.
 *
 * <p>These tests prove that the chain is applied to every request, that every kind of bad token
 * gets the very same answer, that time comes from the application clock, and that an unknown
 * path is never a 404 for a caller that the chain refuses.
 *
 * <p>{@link #mockMvc} sends an admin token by default. A request that needs another caller sets
 * its own {@code Authorization} header, and a request with no token at all goes through
 * {@code anonymous}, a MockMvc without the default.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  private static final String ACCESS_DENIED = "Access is denied";

  @Autowired
  private ApplicationContext context;

  @Autowired
  private WebApplicationContext webContext;

  private MockMvc anonymous;

  @BeforeEach
  void buildAnonymousMockMvc() {
    anonymous = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
  }

  /** Every kind of token that must be refused, with a name for the report. */
  static Stream<Arguments> badTokens() {
    String userToken = TestTokens.access().build();
    String adminToken = TestTokens.admin().build();
    String[] user = userToken.split("\\.");
    String[] admin = adminToken.split("\\.");
    String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
        .issuer(TestTokens.ISSUER)
        .subject("2")
        .claim("role", "ADMIN")
        .claim("token_type", "access")
        .expirationTime(Date.from(TestTokens.ISSUED_AT.plus(Duration.ofMinutes(15))))
        .build()).serialize();
    return Stream.of(
        // The token must also have been issued before it expired: the encoder refuses otherwise.
        Arguments.of("expired", TestTokens.admin()
            .issuedAt(START.minus(TestTokens.ACCESS_TTL))
            .expiresAt(START.minusSeconds(1)).build()),
        Arguments.of("a refresh token", TestTokens.admin().tokenType("refresh").build()),
        Arguments.of("a token without a type", TestTokens.admin().tokenType(null).build()),
        Arguments.of("another issuer", TestTokens.admin().issuer("somebody-else").build()),
        Arguments.of("no issuer", TestTokens.admin().issuer(null).build()),
        Arguments.of("no expiry", TestTokens.admin().expiresAt(null).build()),
        Arguments.of("signed with another key",
            TestTokens.admin().signedWith(TestKeys.generateRsa(2048)).build()),
        Arguments.of("a key id that the key set does not have",
            TestTokens.admin().keyId("unknown-key").build()),
        Arguments.of("unsigned", unsigned),
        Arguments.of("the payload of an admin under the signature of a user",
            String.format("%s.%s.%s", user[0], admin[1], user[2])),
        Arguments.of("garbage", "not.a.token"));
  }

  private static String bodyOf(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /**
   * Spring Boot would create an in-memory user with a random password and print the password to
   * the log; the exclusion in the application class must prevent that.
   */
  @Test
  void noDefaultUserDetailsServiceExists() {
    assertEquals(0, context.getBeanNamesForType(UserDetailsService.class).length);
  }

  @Test
  void anAnonymousCallToAnUnknownPathIs401() throws Exception {
    anonymous.perform(get("/nowhere"))
      .andExpectAll(
        status().isUnauthorized(),
        problemJson(),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  @Test
  void anAuthenticatedCallToAnUnknownPathIs403ForEveryRole() throws Exception {
    String userToken = TestTokens.access().build();

    assertAll(
      () -> mockMvc.perform(get("/nowhere"))
        .andExpectAll(
          status().isForbidden(),
          problemJson(),
          jsonPath("$.detail").value(ACCESS_DENIED)),
      () -> mockMvc.perform(get("/nowhere")
          .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpectAll(
          status().isForbidden(),
          problemJson(),
          jsonPath("$.detail").value(ACCESS_DENIED)));
  }

  /** The detail of the 401 does not say why: every bad token looks like no token at all. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("badTokens")
  void everyBadTokenGetsTheSameAnswerAsNoToken(String name, String token) throws Exception {
    String withoutToken = bodyOf(anonymous.perform(get("/nowhere"))
      .andExpect(status().isUnauthorized()).andReturn());

    String withBadToken = bodyOf(anonymous.perform(get("/nowhere")
        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
      .andExpect(status().isUnauthorized())
      .andReturn());

    assertEquals(withoutToken, withBadToken);
  }

  /** The time of a token is judged by the application clock, with no tolerance. */
  @Test
  void aTokenEndsWhenTheApplicationClockPassesItsExpiry() throws Exception {
    String userToken = TestTokens.access().build();
    anonymous.perform(get("/nowhere").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
      .andExpect(status().isForbidden());

    clock.set(START.plus(TestTokens.ACCESS_TTL).plusSeconds(1));

    anonymous.perform(get("/nowhere").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
      .andExpect(status().isUnauthorized());
  }

  /** A direct request to the error page is a normal request and stays closed. */
  @Test
  void aDirectRequestToTheErrorPathIsClosed() throws Exception {
    anonymous.perform(get("/error")).andExpect(status().isUnauthorized());
  }

  /**
   * The ERROR dispatch of a servlet container is permitted, so that a failure keeps its real
   * status. MockMvc cannot forward, but it can send a request of that dispatch type; Spring MVC
   * answers it (a 404 for an unknown path) instead of Security.
   */
  @Test
  void theErrorDispatchIsLetThroughSoTheRealStatusSurvives() throws Exception {
    anonymous.perform(get("/nowhere").with(request -> {
      request.setDispatcherType(DispatcherType.ERROR);
      return request;
    })).andExpect(status().isNotFound());
  }
}
