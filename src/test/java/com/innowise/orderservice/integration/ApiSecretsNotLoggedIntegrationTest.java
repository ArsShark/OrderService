package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.adminToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.itemJson;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static com.innowise.orderservice.integration.ApiSupport.userToken;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.LogCapture;
import com.innowise.orderservice.TestKeys;
import com.innowise.orderservice.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Log hygiene through the REST API: one run sends real requests with real tokens, successes and
 * every kind of refusal (401, 403, 404, 409, 400, 415), and walks through the troubles of the
 * User Service (down, a refused key, an open breaker). Then every secret of the run is searched
 * for in everything that was logged: the tokens (whole, and their payload and signature parts),
 * the claim {@code jti}, the emails, the names, the internal key and the values that the refused
 * requests carried. {@link SecretsNotLoggedIntegrationTest} proves the same one layer below, with
 * no tokens and no HTTP.
 *
 * <p>The run must also have written the lines that are expected, so a capture that sees nothing
 * cannot pass.
 */
class ApiSecretsNotLoggedIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA_EMAIL = "zinaida.quarterman@example.com";
  private static final String BORIS_EMAIL = "boris.secret@example.com";
  private static final String BOSS_EMAIL = "boss.secret@example.com";
  private static final String FIRST_NAME = "Zinaida";
  private static final String SURNAME = "Quarterman";

  @Autowired
  private WebApplicationContext webContext;

  @Test
  void noTokenNoEmailNoNameNoKeyAndNoValueOfARefusedRequestReachesAnyLogLine()
      throws Exception {
    insertItem("Keyboard", "49.90");
    String anna = userToken(7, ANNA_EMAIL);
    String boris = userToken(8, BORIS_EMAIL);
    String boss = adminToken(1, BOSS_EMAIL);
    String expired = TestTokens.admin()
      .issuedAt(START.minus(TestTokens.ACCESS_TTL))
      .expiresAt(START.minusSeconds(1))
      .build();
    String anotherKey = TestTokens.admin().signedWith(TestKeys.generateRsa(2048)).build();
    String garbage = "secret-garbage-token-value";

    Map<String, String> secrets = new LinkedHashMap<>();
    addToken(secrets, "the token of Anna", anna);
    addToken(secrets, "the token of Boris", boris);
    addToken(secrets, "the token of the admin", boss);
    addToken(secrets, "the expired token", expired);
    addToken(secrets, "the token of another key", anotherKey);
    secrets.put("the garbage token", garbage);
    secrets.put("the email of Anna", ANNA_EMAIL);
    secrets.put("the email of Boris", BORIS_EMAIL);
    secrets.put("the email of the admin", BOSS_EMAIL);
    secrets.put("a first name", FIRST_NAME);
    secrets.put("a surname", SURNAME);
    secrets.put("the internal key", UserServiceStub.KEY);
    secrets.put("a status of a request", "secret-status-value");
    secrets.put("a date of a request", "secret-date-value");
    secrets.put("a sort property of a request", "secret-sort-property");
    secrets.put("a path value", "secretpathid");
    secrets.put("a body text", "secret-body-text");
    secrets.put("a quantity of a request", "7654321");
    secrets.put("a name of an item of a request", "TopSecretItemName");

    MockMvc anonymous = MockMvcBuilders.webAppContextSetup(webContext)
      .apply(springSecurity()).build();
    String longName = String.format("TopSecretItemName%s", "x".repeat(260));

    try (LogCapture capture = LogCapture.attach()) {
      // Successes, with the owner found for Anna and unknown for Boris.
      UserServiceStub.respondWithUser(ANNA_EMAIL, 7, FIRST_NAME, SURNAME);
      long orderId = placeOrder(mockMvc, anna, orderJson(1, 2));
      long borisOrder = placeOrder(mockMvc, boris, orderJson(1, 1));
      mockMvc.perform(as(get("/api/orders/{id}", orderId), anna)).andExpect(status().isOk());
      mockMvc.perform(as(get("/api/orders/my"), anna)).andExpect(status().isOk());
      mockMvc.perform(get("/api/orders")).andExpect(status().isOk());
      mockMvc.perform(json(as(put("/api/orders/{id}", orderId), anna), orderJson(1, 3)))
        .andExpect(status().isOk());
      mockMvc.perform(json(as(post("/api/items"), boss), itemJson("Mouse", "19.90")))
        .andExpect(status().isCreated());
      mockMvc.perform(as(get("/api/items"), anna)).andExpect(status().isOk());

      // Refusals: 404, 409, 403 and the bad requests (400, 415).
      mockMvc.perform(as(get("/api/orders/{id}", borisOrder), anna))
        .andExpect(status().isNotFound());
      mockMvc.perform(as(delete("/api/orders/999"), anna)).andExpect(status().isNotFound());
      jdbcTemplate.update("UPDATE orders SET status = 'PAID' WHERE id = ?", orderId);
      mockMvc.perform(as(patch("/api/orders/{id}/cancel", orderId), anna))
        .andExpect(status().isConflict());
      mockMvc.perform(as(get("/api/orders"), anna)).andExpect(status().isForbidden());
      mockMvc.perform(get("/api/orders").param("statuses", "secret-status-value"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(get("/api/orders").param("createdFrom", "secret-date-value"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(get("/api/orders").param("sort", "secret-sort-property"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(as(get("/api/orders/secretpathid"), anna))
        .andExpect(status().isBadRequest());
      mockMvc.perform(json(as(post("/api/orders"), anna), "{\"items\": [\"secret-body-text\""))
        .andExpect(status().isBadRequest());
      mockMvc.perform(json(as(post("/api/orders"), anna), "{\"items\": \"secret-body-text\"}"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(json(as(post("/api/orders"), anna), orderJson(1, 7654321)))
        .andExpect(status().isBadRequest());
      mockMvc.perform(json(post("/api/items"), itemJson(longName, "1.00")))
        .andExpect(status().isBadRequest());
      mockMvc.perform(as(post("/api/orders"), anna)
          .contentType(MediaType.TEXT_PLAIN).content("secret-body-text"))
        .andExpect(status().isUnsupportedMediaType());

      // 401: no token and every kind of bad token.
      anonymous.perform(get("/api/orders/my")).andExpect(status().isUnauthorized());
      for (String bad : new String[] {expired, anotherKey, garbage}) {
        anonymous.perform(as(get("/api/orders/my"), bad)).andExpect(status().isUnauthorized());
      }

      // The User Service is down: five failures open the breaker, the sixth call is refused by it.
      UserServiceStub.reset();
      UserServiceStub.respondWithStatus(503);
      for (int i = 0; i < 6; i++) {
        mockMvc.perform(as(get("/api/orders/{id}", orderId), anna)).andExpect(status().isOk());
      }
      // A refused key, seen through a probe of the half-open breaker.
      UserServiceStub.reset();
      UserServiceStub.respondWithStatus(401);
      userServiceCircuitBreaker.transitionToHalfOpenState();
      mockMvc.perform(as(get("/api/orders/{id}", orderId), anna)).andExpect(status().isOk());
      // Back to normal.
      UserServiceStub.reset();
      UserServiceStub.respondWithUser(ANNA_EMAIL, 7, FIRST_NAME, SURNAME);
      userServiceCircuitBreaker.reset();
      mockMvc.perform(as(get("/api/orders/{id}", orderId), anna)).andExpect(status().isOk());

      assertAll(
        () -> assertFalse(capture.lines().isEmpty(), "the run must have logged something"),
        () -> assertTrue(capture.contains("Created order with id")),
        () -> assertTrue(capture.contains("Request rejected with status 404")),
        () -> assertTrue(capture.contains("Request rejected with status 409")),
        () -> assertTrue(capture.contains("Request rejected with status 403")),
        () -> assertTrue(capture.contains("Request rejected with status 401")),
        () -> assertTrue(capture.contains("The user service answered with status 503")),
        () -> assertTrue(
          capture.contains("The user service refused the internal key (status 401)")),
        () -> assertTrue(capture.contains("Circuit breaker 'user-service' changed its state")));
      secrets.forEach((name, secret) -> assertFalse(capture.contains(secret),
        String.format("%s must not be logged", name)));
    }
  }

  /**
   * Adds a token as a whole, its payload and its signature (the parts that make it unique), and
   * its {@code jti} when it has one.
   */
  private static void addToken(Map<String, String> secrets, String name, String token) {
    String[] parts = token.split("\\.");
    secrets.put(name, token);
    secrets.put(String.format("the signature of %s", name), parts[2]);
    String payload = parts[1];
    secrets.put(String.format("the payload of %s", name), payload);
    String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    Map<String, Object> claims = JsonPath.read(json, "$");
    Object jti = claims.get("jti");
    if (jti != null) {
      secrets.put(String.format("the jti of %s", name), jti.toString());
    }
  }
}
