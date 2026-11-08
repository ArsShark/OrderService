package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.JwksTestServer;
import com.innowise.orderservice.LogCapture;
import com.innowise.orderservice.TestKeys;
import com.innowise.orderservice.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Authentication Service is not running, so the key set cannot be fetched and no key has
 * ever been cached. A token that is perfectly valid cannot be checked.
 *
 * <p>Spring Security's own behavior is a 500 here: the failure to check is not a rejected token
 * for it, and its filter rethrows it. These tests prove that the chain of this service answers
 * the same 401 as for any other bad token (see {@code FailClosedJwtDecoder}), that the answer
 * says nothing about the cause, and that the log line names no address.
 */
class KeySetNotAvailableTest extends AbstractKeySetSliceTest {

  /** An address on which nothing listens: a server was started there and stopped again. */
  private static final String CLOSED_ADDRESS = closedAddress();

  @DynamicPropertySource
  static void keySetThatIsNotThere(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwks-uri", () -> CLOSED_ADDRESS);
  }

  private static String closedAddress() {
    JwksTestServer server = JwksTestServer.start(TestKeys.jwksJson());
    String address = server.jwksUri();
    server.stop();
    return address;
  }

  @Test
  void aValidTokenIsRefusedWith401InsteadOfA500() throws Exception {
    getItemWith(TestTokens.access().build()).andExpectAll(
        status().isUnauthorized(),
        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
        jsonPath("$.status").value(401),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }

  /** The caller cannot tell an outage from a bad token: the bodies are the same. */
  @Test
  void theAnswerIsTheSameAsForARequestWithoutAToken() throws Exception {
    String withToken = bodyOf(getItemWith(TestTokens.access().build()));
    String withoutToken = bodyOf(getItemWithoutToken());

    assertEquals(withoutToken, withToken);
  }

  @Test
  void theFailureIsLoggedWithoutTheAddressOfTheKeySetAndWithoutTheToken() throws Exception {
    String token = TestTokens.access().build();

    try (LogCapture logs = LogCapture.attach()) {
      getItemWith(token).andExpect(status().isUnauthorized());

      assertAll(
          () -> assertTrue(logs.contains("A token could not be verified"),
              "the outage is visible in the log"),
          () -> assertFalse(logs.contains(CLOSED_ADDRESS), "no address of the key set"),
          () -> assertFalse(logs.contains(".well-known"), "no part of the address"),
          () -> assertFalse(logs.contains(token), "no token"));
    }
  }

  @Test
  void aRequestWithoutATokenStillGets401() throws Exception {
    getItemWithoutToken().andExpectAll(
        status().isUnauthorized(),
        jsonPath("$.detail").value(AUTHENTICATION_REQUIRED));
  }
}
