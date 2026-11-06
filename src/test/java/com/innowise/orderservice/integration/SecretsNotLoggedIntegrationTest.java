package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.LogCapture;
import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.service.Caller;
import com.innowise.orderservice.service.OrderFacade;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Log hygiene, end to end: one run goes through every outcome of the call to the User Service
 * (found, nobody has the email, error status, refused key, open breaker, probes), then every
 * secret of the run is searched for in everything that was logged. The run must also have written
 * the lines that are expected, so a capture that sees nothing cannot pass.
 *
 * <p>The messages of the HTTP client hold the address of the User Service with the email in the
 * query string, so a log line that printed an exception message would be caught here.
 */
class SecretsNotLoggedIntegrationTest extends AbstractIntegrationTest {

  private static final String OTHER_EMAIL = "boris.secret@example.com";
  private static final String SURNAME = "Quarterman";
  private static final String FIRST_NAME = "Zinaida";

  @Autowired
  private OrderFacade facade;

  @Test
  void neitherAnEmailNorAPersonalNameNorTheKeyReachesAnyLogLine() {
    long keyboard = insertItem("Keyboard", "49.90");
    Caller owner = new Caller(7L, OWNER_EMAIL, false);
    Caller other = new Caller(8L, OTHER_EMAIL, false);
    OrderRequest request = new OrderRequest(List.of(new OrderItemRequest(keyboard, 1)));
    Map<String, String> secrets = new LinkedHashMap<>();
    secrets.put("owner email", OWNER_EMAIL);
    secrets.put("other email", OTHER_EMAIL);
    secrets.put("first name", FIRST_NAME);
    secrets.put("surname", SURNAME);
    secrets.put("internal key", UserServiceStub.KEY);

    try (LogCapture capture = LogCapture.attach()) {
      // Found.
      UserServiceStub.respondWithUser(OWNER_EMAIL, 7, FIRST_NAME, SURNAME);
      long orderId = facade.create(owner, request).id();
      // Nobody has this email (no stub: the stub answers 404).
      facade.create(other, request);
      // The service is down: five failures open the breaker, the sixth call is refused by it.
      UserServiceStub.reset();
      UserServiceStub.respondWithStatus(503);
      for (int i = 0; i < 6; i++) {
        facade.getById(orderId, owner);
      }
      // A refused key, seen through a probe of the half-open breaker.
      UserServiceStub.reset();
      UserServiceStub.respondWithStatus(401);
      userServiceCircuitBreaker.transitionToHalfOpenState();
      facade.getById(orderId, owner);
      // Back to normal.
      UserServiceStub.reset();
      UserServiceStub.respondWithUser(OWNER_EMAIL, 7, FIRST_NAME, SURNAME);
      userServiceCircuitBreaker.reset();
      facade.getById(orderId, owner);

      assertAll(
        () -> assertFalse(capture.lines().isEmpty(), "the run must have logged something"),
        () -> assertTrue(capture.contains("The user service answered with status 503")),
        () -> assertTrue(
          capture.contains("The user service refused the internal key (status 401)")),
        () -> assertTrue(capture.contains("Circuit breaker 'user-service' changed its state")),
        () -> assertTrue(capture.contains("Created order with id")));
      secrets.forEach((name, secret) -> assertFalse(capture.contains(secret),
        String.format("the %s must not be logged", name)));
      assertEquals(2, countRows("orders"));
    }
  }
}
