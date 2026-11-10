package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.ANNA_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.BORIS_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.bodyOf;
import static com.innowise.orderservice.integration.ApiSupport.borisToken;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static com.innowise.orderservice.integration.ApiSupport.userToken;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What the REST answers show about the owner of an order in every situation of the User Service
 * (a WireMock stub), seen from the outside: the JSON of {@code user} and
 * {@code userInfoAvailable}, the status of the answer, what the call to the User Service looks
 * like and how often it is made. {@code OrderFacadeIntegrationTest} proves the same rules one
 * layer below, with Java objects; here the contract is the HTTP one.
 *
 * <p>The important rule: a lookup that fails never fails the request. The order is committed
 * before the lookup starts, so a 5xx would only invite a second POST.
 */
class OrderOwnerDataApiIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA = annaToken();
  private static final String BORIS = borisToken();

  @BeforeEach
  void createTheCatalogue() {
    insertItem("Keyboard", "49.90");
  }

  // ---- the situations of the User Service

  @Test
  void aFoundOwnerIsShownWithTheFieldsOfTheApiOnlyAndTheCallCarriesTheKeyAndTheEmail()
      throws Exception {
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");

    mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(1, 1))).andExpectAll(
      status().isCreated(),
      jsonPath("$.user.id").value(7),
      jsonPath("$.user.name").value("Anna"),
      jsonPath("$.user.surname").value("Petrova"),
      jsonPath("$.user.email").value(ANNA_EMAIL),
      // The User Service sends more; the order shows only what the API promises.
      jsonPath("$.user.birthDate").doesNotExist(),
      jsonPath("$.user.active").doesNotExist(),
      jsonPath("$.userInfoAvailable").value(true));

    assertAll(
      () -> assertEquals(1, UserServiceStub.requestsReceived()),
      () -> assertEquals(UserServiceStub.KEY,
        UserServiceStub.requests().get(0).getHeader("X-Internal-Key")),
      () -> assertEquals(ANNA_EMAIL,
        UserServiceStub.requests().get(0).queryParameter("email").firstValue()));
  }

  @Test
  void anOwnerThatNobodyKnowsIsAnAnswerAndNotAFailure() throws Exception {
    // No stub: the stub server answers 404, which means "nobody has this email".
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));

    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.user").doesNotExist(),
      jsonPath("$.userInfoAvailable").value(true));

    assertEquals(CircuitBreaker.State.CLOSED, userServiceCircuitBreaker.getState());
  }

  @Test
  void anOrderIsStillPlacedWhenTheUserServiceIsDownAndTheOwnerComesBackWithIt()
      throws Exception {
    UserServiceStub.respondWithStatus(503);

    MvcResult created = mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(1, 1)))
      .andExpectAll(
        status().isCreated(),
        jsonPath("$.status").value("PENDING"),
        jsonPath("$.user").doesNotExist(),
        jsonPath("$.userInfoAvailable").value(false))
      .andReturn();
    long orderId = ApiSupport.idOf(created);
    long rows = countRows("orders");

    UserServiceStub.reset();
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.user.name").value("Anna"),
      jsonPath("$.userInfoAvailable").value(true));

    assertEquals(1L, rows, "the order must be saved although the lookup failed");
  }

  @Test
  void aDroppedConnectionIsTheSameAsAnUnavailableService() throws Exception {
    UserServiceStub.respondWithConnectionReset();

    mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(1, 1))).andExpectAll(
      status().isCreated(),
      jsonPath("$.user").doesNotExist(),
      jsonPath("$.userInfoAvailable").value(false));

    assertEquals(1L, countRows("orders"));
  }

  @ParameterizedTest(name = "status {0}")
  @ValueSource(ints = {401, 403, 500, 502, 503})
  void anErrorStatusOfTheUserServiceNeverFailsTheRequest(int userServiceStatus)
      throws Exception {
    UserServiceStub.respondWithStatus(userServiceStatus);
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));

    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.user").doesNotExist(),
      jsonPath("$.userInfoAvailable").value(false));
  }

  // ---- the circuit breaker

  @Test
  void theBreakerOpensAfterFiveFailuresStopsTheCallsAndClosesAfterGoodProbes()
      throws Exception {
    long orderId = insertOrder(7, "PENDING", "0.00");
    UserServiceStub.respondWithStatus(503);
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
        status().isOk(),
        jsonPath("$.userInfoAvailable").value(false));
    }
    int reachedTheServer = UserServiceStub.requestsReceived();
    CircuitBreaker.State afterFailures = userServiceCircuitBreaker.getState();

    // The breaker is open: the answer is the same, and the network is not touched.
    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.user").doesNotExist(),
      jsonPath("$.userInfoAvailable").value(false));
    int afterRefusal = UserServiceStub.requestsReceived();

    // The service is back; the breaker lets three probes through and closes.
    UserServiceStub.reset();
    UserServiceStub.respondWithUser(OWNER_EMAIL, 7, "Anna", "Petrova");
    userServiceCircuitBreaker.transitionToHalfOpenState();
    MvcResult lastProbe = null;
    for (int i = 0; i < 3; i++) {
      lastProbe = mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA))
        .andExpect(status().isOk()).andReturn();
    }
    String probeBody = bodyOf(lastProbe);

    assertAll(
      () -> assertEquals(5, reachedTheServer),
      () -> assertEquals(CircuitBreaker.State.OPEN, afterFailures),
      () -> assertEquals(5, afterRefusal, "an open breaker must not reach the network"),
      () -> assertEquals("Anna", JsonPath.read(probeBody, "$.user.name")),
      () -> assertTrue(JsonPath.<Boolean>read(probeBody, "$.userInfoAvailable")),
      () -> assertEquals(CircuitBreaker.State.CLOSED, userServiceCircuitBreaker.getState()));
  }

  // ---- how often and with which email

  @Test
  void aPageAsksTheUserServiceOnceForEveryDistinctOwner() throws Exception {
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    UserServiceStub.respondWithUser(BORIS_EMAIL, 8, "Boris", "Ivanov");
    placeOrder(mockMvc, ANNA, orderJson(1, 1));
    placeOrder(mockMvc, ANNA, orderJson(1, 2));
    placeOrder(mockMvc, ANNA, orderJson(1, 3));
    placeOrder(mockMvc, BORIS, orderJson(1, 1));
    placeOrder(mockMvc, BORIS, orderJson(1, 2));
    int before = UserServiceStub.requestsReceived();

    MvcResult page = mockMvc.perform(get("/api/orders").param("sort", "id"))
      .andExpect(status().isOk()).andReturn();

    List<String> owners = JsonPath.read(bodyOf(page), "$.content[*].user.name");
    assertAll(
      () -> assertEquals(List.of("Anna", "Anna", "Anna", "Boris", "Boris"), owners),
      () -> assertEquals(2, UserServiceStub.requestsReceived() - before));
  }

  @Test
  void aPageStopsAtTheFirstFailureAndShowsNoOwnerAtAll() throws Exception {
    UserServiceStub.respondWithStatus(503);
    placeOrder(mockMvc, ANNA, orderJson(1, 1));
    placeOrder(mockMvc, BORIS, orderJson(1, 1));
    int before = UserServiceStub.requestsReceived();

    MvcResult page = mockMvc.perform(get("/api/orders"))
      .andExpect(status().isOk()).andReturn();

    List<Boolean> available = JsonPath.read(bodyOf(page), "$.content[*].userInfoAvailable");
    assertAll(
      () -> assertEquals(2, available.size()),
      () -> assertFalse(available.contains(true)),
      () -> assertEquals(1, UserServiceStub.requestsReceived() - before,
        "the second owner must not be asked"));
  }

  @Test
  void aRefusedRequestAndADeletionNeverReachTheUserService() throws Exception {
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    int before = UserServiceStub.requestsReceived();

    mockMvc.perform(as(get("/api/orders/{id}", orderId), BORIS)).andExpect(status().isNotFound());
    mockMvc.perform(as(delete("/api/orders/{id}", orderId), ANNA))
      .andExpect(status().isNoContent());

    assertEquals(before, UserServiceStub.requestsReceived());
  }

  @Test
  void anEmailWithAPlusSignReachesTheUserServiceUnchanged() throws Exception {
    String email = "anna+shop@example.com";
    UserServiceStub.respondWithUser(email, 7, "Anna", "Petrova");

    mockMvc.perform(json(as(post("/api/orders"), userToken(7, email)), orderJson(1, 1)))
      .andExpectAll(
        status().isCreated(),
        jsonPath("$.user.name").value("Anna"),
        jsonPath("$.userInfoAvailable").value(true));
  }

  @Test
  void theOwnerIsLookedUpByTheEmailThatTheOrderStoredNotByTheEmailOfTheCaller()
      throws Exception {
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    UserServiceStub.reset();
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");

    // The same profile with another email in the token: the order still names the old one.
    mockMvc.perform(as(get("/api/orders/{id}", orderId), userToken(7, "new-address@example.com")))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.user.name").value("Anna"));

    assertEquals(ANNA_EMAIL,
      UserServiceStub.requests().get(0).queryParameter("email").firstValue());
  }
}
