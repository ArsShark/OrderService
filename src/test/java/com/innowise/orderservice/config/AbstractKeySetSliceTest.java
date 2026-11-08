package com.innowise.orderservice.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.innowise.orderservice.TestTokens;
import com.innowise.orderservice.controller.ItemController;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.service.ItemService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the tests that put the key set of the Authentication Service in a state of the test's
 * choice (not running, running and then stopped) and send real tokens through the whole security
 * chain: the real decoder, the real chain and the real error handler, with the controller and the
 * service behind them as mocks.
 *
 * <p>Each subclass sets {@code app.security.jwks-uri} with a {@code @DynamicPropertySource}
 * method, so each gets its own context and its own cache of the key. The clock stands one minute
 * after the {@code iat} of the tokens of {@link TestTokens}, so a default token is valid.
 *
 * <p>The request is {@code GET /api/items/3}: any caller with a role may read an item, and the
 * service is a mock, so no database is needed.
 */
@WebMvcTest(controllers = ItemController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, AbstractKeySetSliceTest.FixedClock.class})
abstract class AbstractKeySetSliceTest {

  protected static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  @Autowired
  protected MockMvc mockMvc;

  @MockitoBean
  private ItemService itemService;

  @BeforeEach
  void theItemExists() {
    Instant now = TestTokens.ISSUED_AT;
    when(itemService.getById(3L))
        .thenReturn(new ItemResponse(3L, "Keyboard", new BigDecimal("49.90"), now, now));
  }

  /**
   * Sends the request with a token.
   *
   * @param token a compact serialized token
   * @return the result of the request
   */
  protected ResultActions getItemWith(String token) throws Exception {
    return mockMvc.perform(get("/api/items/3")
        .header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token)));
  }

  /**
   * Sends the request without any token.
   *
   * @return the result of the request
   */
  protected ResultActions getItemWithoutToken() throws Exception {
    return mockMvc.perform(get("/api/items/3"));
  }

  /**
   * Reads the body of an answer as text.
   *
   * @param actions the result of a request
   * @return the body
   */
  protected static String bodyOf(ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /** The clock of the slice: one minute after the {@code iat} of the test tokens. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {

    @Bean
    Clock clock() {
      return Clock.fixed(TestTokens.ISSUED_AT.plusSeconds(60), ZoneOffset.UTC);
    }
  }
}
