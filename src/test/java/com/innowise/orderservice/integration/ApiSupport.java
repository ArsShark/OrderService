package com.innowise.orderservice.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.StringJoiner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Small helpers of the tests that call the REST API: tokens of the people that appear in the
 * tests, request bodies and a few shortcuts. Everything is static and nothing here touches the
 * Spring context, so the helpers cannot change the shared context of the integration tests.
 *
 * <p>The tokens live ten years, like the default admin token of {@code AbstractIntegrationTest}:
 * a test that moves the clock a few days ahead must not lose its caller.
 */
final class ApiSupport {

  static final long ANNA_ID = 7L;
  static final String ANNA_EMAIL = "anna@example.com";
  static final long BORIS_ID = 8L;
  static final String BORIS_EMAIL = "boris@example.com";
  static final long BOSS_ID = 1L;
  static final String BOSS_EMAIL = "boss@example.com";

  private static final Instant LONG_LIVED = TestTokens.ISSUED_AT.plus(Duration.ofDays(3650));

  private ApiSupport() {
  }

  /**
   * A token of a user with a profile.
   *
   * @param userId the id of the profile
   * @param email the email, which is also the login
   * @return the compact serialized token
   */
  static String userToken(long userId, String email) {
    return TestTokens.access()
      .subject(String.valueOf(100 + userId))
      .userId(userId)
      .email(email)
      .expiresAt(LONG_LIVED)
      .build();
  }

  /** The token of Anna, a user with the profile 7. */
  static String annaToken() {
    return userToken(ANNA_ID, ANNA_EMAIL);
  }

  /** The token of Boris, a user with the profile 8. */
  static String borisToken() {
    return userToken(BORIS_ID, BORIS_EMAIL);
  }

  /**
   * A token of an admin who has a profile, so that he may place orders himself.
   *
   * @param userId the id of the profile
   * @param email the email of the admin
   * @return the compact serialized token
   */
  static String adminToken(long userId, String email) {
    return TestTokens.admin()
      .userId(userId)
      .email(email)
      .expiresAt(LONG_LIVED)
      .build();
  }

  /** The token of an admin with the profile 1. */
  static String bossToken() {
    return adminToken(BOSS_ID, BOSS_EMAIL);
  }

  /** A token of a user whose token has no {@code user_id}: an account without a profile. */
  static String userTokenWithoutProfile(String email) {
    return TestTokens.access()
      .subject("200")
      .userId(null)
      .email(email)
      .expiresAt(LONG_LIVED)
      .build();
  }

  /** Adds the {@code Authorization} header, which wins over the admin token of the default. */
  static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
    return request.header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token));
  }

  /** Gives the request a JSON body. */
  static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /**
   * The body of an order.
   *
   * @param itemIdAndQuantity pairs: the id of an item, then the quantity
   * @return the JSON text
   */
  static String orderJson(long... itemIdAndQuantity) {
    StringJoiner lines = new StringJoiner(", ");
    for (int i = 0; i + 1 < itemIdAndQuantity.length; i += 2) {
      lines.add(String.format("{\"itemId\": %d, \"quantity\": %d}",
        itemIdAndQuantity[i], itemIdAndQuantity[i + 1]));
    }
    return String.format("{\"items\": [%s]}", lines);
  }

  /** The body of an item; the price is written as given. */
  static String itemJson(String name, String price) {
    return String.format("{\"name\": \"%s\", \"price\": %s}", name, price);
  }

  /** The text of the answer. */
  static String bodyOf(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /** The {@code id} of the created resource that the answer carries. */
  static long idOf(MvcResult result) throws Exception {
    Number id = JsonPath.read(bodyOf(result), "$.id");
    return id.longValue();
  }

  /**
   * Places an order and expects 201.
   *
   * @return the id of the new order
   */
  static long placeOrder(MockMvc mockMvc, String token, String body) throws Exception {
    return idOf(mockMvc.perform(json(as(post("/api/orders"), token), body))
      .andExpect(status().isCreated())
      .andReturn());
  }
}
