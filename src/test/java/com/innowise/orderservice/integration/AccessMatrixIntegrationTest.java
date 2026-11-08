package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.TestTokens;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who may call which endpoint, for every endpoint and every kind of caller, through the real
 * chain, the real decoder and a real database.
 *
 * <p>A caller that the chain lets through gets the answer of the application for a request that
 * names nothing that exists (404, or 422 for an item that does not exist): the interesting fact
 * here is that the answer is not 401 or 403. A caller that is refused gets 401 (no token) or
 * 403. The four callers are an anonymous one, a user (profile 7), an admin with a profile and
 * an admin without one. The ownership of orders and the content of the answers are tested with
 * real data in the API tests.
 */
class AccessMatrixIntegrationTest extends AbstractIntegrationTest {

  private static final String ORDER_BODY = """
    {"items": [{"itemId": 999, "quantity": 1}]}
    """;

  private static final String ITEM_BODY = """
    {"name": "Keyboard", "price": 49.90}
    """;

  @Autowired
  private WebApplicationContext webContext;

  private MockMvc anonymous;

  @BeforeEach
  void buildAnonymousMockMvc() {
    anonymous = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
  }

  /**
   * One endpoint and the status each caller gets.
   *
   * @param method the HTTP method
   * @param path the path, with an id that does not exist
   * @param body the JSON body, or {@code null}
   * @param user the status for a user with a profile
   * @param admin the status for an admin with a profile
   * @param adminWithoutProfile the status for an admin without a profile
   */
  private record Row(HttpMethod method, String path, String body, int user, int admin,
      int adminWithoutProfile) {

    @Override
    public String toString() {
      return String.format("%s %s", method, path);
    }
  }

  static Stream<Arguments> endpoints() {
    return Stream.of(
      // Orders. An admin without a profile cannot own an order, so he cannot place one or list
      // "his own"; he may read and change the orders of everybody.
      new Row(HttpMethod.POST, "/api/orders", ORDER_BODY, 422, 422, 403),
      new Row(HttpMethod.GET, "/api/orders/999", null, 404, 404, 404),
      new Row(HttpMethod.GET, "/api/orders", null, 403, 200, 200),
      new Row(HttpMethod.GET, "/api/orders/my", null, 200, 200, 403),
      new Row(HttpMethod.PUT, "/api/orders/999", ORDER_BODY, 404, 404, 404),
      new Row(HttpMethod.PATCH, "/api/orders/999/cancel", null, 404, 404, 404),
      new Row(HttpMethod.DELETE, "/api/orders/999", null, 404, 404, 404),
      // Items: everybody reads, only an admin changes.
      new Row(HttpMethod.GET, "/api/items", null, 200, 200, 200),
      new Row(HttpMethod.GET, "/api/items/999", null, 404, 404, 404),
      new Row(HttpMethod.POST, "/api/items", ITEM_BODY, 403, 201, 201),
      new Row(HttpMethod.PUT, "/api/items/999", ITEM_BODY, 403, 404, 404),
      new Row(HttpMethod.DELETE, "/api/items/999", null, 403, 404, 404),
      // Not in the API: closed, also for an admin.
      new Row(HttpMethod.PATCH, "/api/orders/999", null, 403, 403, 403),
      new Row(HttpMethod.POST, "/api/orders/999/cancel", null, 403, 403, 403),
      new Row(HttpMethod.PATCH, "/api/items/999", null, 403, 403, 403),
      new Row(HttpMethod.GET, "/api/orders/999/items", null, 403, 403, 403),
      new Row(HttpMethod.GET, "/internal/orders", null, 403, 403, 403))
      .map(Arguments::of);
  }

  private static MockHttpServletRequestBuilder builderOf(Row row, String token) {
    MockHttpServletRequestBuilder builder = request(row.method(), row.path());
    if (token != null) {
      builder.header(HttpHeaders.AUTHORIZATION, bearer(token));
    }
    if (row.body() != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(row.body());
    }
    return builder;
  }

  private ResultActions send(MockMvc client, Row row, String token) throws Exception {
    return client.perform(builderOf(row, token));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void everyCallerGetsTheExpectedStatus(Row row) {
    String userToken = TestTokens.access().build();
    String adminToken = TestTokens.admin().userId(1L).email("boss@example.com").build();
    String adminWithoutProfileToken = TestTokens.admin().build();

    assertAll(
      () -> send(anonymous, row, null).andExpect(status().isUnauthorized()),
      () -> send(anonymous, row, userToken).andExpect(status().is(row.user())),
      () -> send(anonymous, row, adminToken).andExpect(status().is(row.admin())),
      () -> send(anonymous, row, adminWithoutProfileToken)
        .andExpect(status().is(row.adminWithoutProfile())));
  }

  /** A token that cannot be verified is a 401 on every endpoint, also on the closed ones. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void aBadTokenIsRefusedBeforeAnythingElse(Row row) throws Exception {
    send(anonymous, row, "not.a.token").andExpect(status().isUnauthorized());
  }
}
