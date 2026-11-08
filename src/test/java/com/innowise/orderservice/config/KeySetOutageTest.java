package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.JwksTestServer;
import com.innowise.orderservice.TestKeys;
import com.innowise.orderservice.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Authentication Service stops while this service is running.
 *
 * <p>The key set is fetched once and cached, so a restart or a crash of the Authentication
 * Service must not stop the callers who already have a token. The server of the test is really
 * stopped (its port is closed), and the requests go through the whole chain.
 */
class KeySetOutageTest extends AbstractKeySetSliceTest {

  private static final JwksTestServer KEY_SET_SERVER = JwksTestServer.start(TestKeys.jwksJson());

  @DynamicPropertySource
  static void keySetOfTheServer(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwks-uri", KEY_SET_SERVER::jwksUri);
  }

  @AfterAll
  static void stopTheServer() {
    KEY_SET_SERVER.stop();
  }

  @Test
  void theCachedKeyKeepsWorkingAfterTheKeySetServerStops() throws Exception {
    getItemWith(TestTokens.access().build()).andExpect(status().isOk());
    assertEquals(1, KEY_SET_SERVER.requestCount(), "the key is fetched by the first token");

    KEY_SET_SERVER.stop();

    String forgery = TestTokens.access().signedWith(TestKeys.generateRsa(2048)).build();
    String unknownKey = TestTokens.access()
        .signedWith(TestKeys.generateRsa(2048)).keyId("unknown-key").build();
    assertAll(
        // A real token is still accepted: the key comes from the cache.
        () -> getItemWith(TestTokens.admin().build()).andExpect(status().isOk()),
        () -> getItemWith(TestTokens.access().build()).andExpect(status().isOk()),
        // A token that the cached key does not verify is refused, as without any outage.
        () -> getItemWith(forgery).andExpectAll(
            status().isUnauthorized(),
            jsonPath("$.detail").value(AUTHENTICATION_REQUIRED)),
        // A key the cache does not know needs the key set, which is down: refused, not a 500.
        () -> getItemWith(unknownKey).andExpectAll(
            status().isUnauthorized(),
            jsonPath("$.detail").value(AUTHENTICATION_REQUIRED)),
        () -> assertEquals(1, KEY_SET_SERVER.requestCount(), "nothing was fetched again"));
  }
}
