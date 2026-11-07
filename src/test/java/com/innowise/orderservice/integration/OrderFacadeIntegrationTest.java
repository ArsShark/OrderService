package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderDetailsResponse;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.exception.OrderNotFoundException;
import com.innowise.orderservice.service.Caller;
import com.innowise.orderservice.service.OrderFacade;
import com.innowise.orderservice.service.OrderFilter;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The whole way from the facade to the database and to the User Service (a WireMock stub): what an
 * order shows about its owner in every situation of the User Service, what the call looks like on
 * the wire, how the circuit breaker behaves with the real settings, and that no database
 * connection is held while the User Service thinks.
 */
class OrderFacadeIntegrationTest extends AbstractIntegrationTest {

  private static final String OTHER_EMAIL = "other@example.com";
  private static final Caller OWNER = new Caller(7L, OWNER_EMAIL, false);
  private static final Caller STRANGER = new Caller(8L, OTHER_EMAIL, false);
  private static final OrderFilter ANY = new OrderFilter(null, null, null, null);

  @Autowired
  private OrderFacade facade;

  @Autowired
  private DataSource dataSource;

  @Test
  void anOrderComesWithItsOwnerAndTheCallCarriesTheKeyAndTheEmail() {
    long keyboard = insertItem("Keyboard", "49.90");
    UserServiceStub.respondWithUser(OWNER_EMAIL, 7, "Anna", "Petrova");

    OrderDetailsResponse details = facade.create(OWNER, request(keyboard));

    assertAll(
      () -> assertEquals(new UserInfo(7L, "Anna", "Petrova", OWNER_EMAIL), details.user()),
      () -> assertTrue(details.userInfoAvailable()),
      () -> assertEquals(OrderStatus.PENDING, details.status()),
      () -> assertEquals(1, UserServiceStub.requestsReceived()),
      () -> assertEquals(UserServiceStub.KEY,
        UserServiceStub.requests().get(0).getHeader("X-Internal-Key")),
      () -> assertEquals(OWNER_EMAIL,
        UserServiceStub.requests().get(0).queryParameter("email").firstValue()));
  }

  @Test
  void anOrderIsStillSavedAndReturnedWhenTheUserServiceIsDown() {
    long keyboard = insertItem("Keyboard", "49.90");
    UserServiceStub.respondWithStatus(503);

    OrderDetailsResponse details = facade.create(OWNER, request(keyboard));

    assertAll(
      () -> assertNull(details.user()),
      () -> assertFalse(details.userInfoAvailable()),
      () -> assertEquals(OrderStatus.PENDING, details.status()),
      () -> assertEquals(1L, countRows("orders")),
      () -> assertEquals(1L, countRows("order_items")));
  }

  @Test
  void anOwnerWithoutAProfileIsAnAnswerAndNotAFailure() {
    long keyboard = insertItem("Keyboard", "49.90");
    // No stub: the stub server answers 404, which means "nobody has this email".

    OrderDetailsResponse details = facade.create(OWNER, request(keyboard));

    assertAll(
      () -> assertNull(details.user()),
      () -> assertTrue(details.userInfoAvailable()),
      () -> assertEquals(CircuitBreaker.State.CLOSED, userServiceCircuitBreaker.getState()));
  }

  @Test
  void theBreakerOpensAfterFiveFailuresSkipsTheNetworkAndClosesAfterGoodProbes() {
    long keyboard = insertItem("Keyboard", "49.90");
    UserServiceStub.respondWithStatus(503);
    long orderId = facade.create(OWNER, request(keyboard)).id();
    for (int i = 0; i < 4; i++) {
      facade.getById(orderId, OWNER);
    }
    int reachedTheServer = UserServiceStub.requestsReceived();
    CircuitBreaker.State afterFailures = userServiceCircuitBreaker.getState();

    OrderDetailsResponse refused = facade.getById(orderId, OWNER);
    int afterRefusal = UserServiceStub.requestsReceived();

    // The service is back; the breaker lets three probes through and closes.
    UserServiceStub.reset();
    UserServiceStub.respondWithUser(OWNER_EMAIL, 7, "Anna", "Petrova");
    userServiceCircuitBreaker.transitionToHalfOpenState();
    OrderDetailsResponse probe = null;
    for (int i = 0; i < 3; i++) {
      probe = facade.getById(orderId, OWNER);
    }
    OrderDetailsResponse lastProbe = probe;

    assertAll(
      () -> assertEquals(5, reachedTheServer),
      () -> assertEquals(CircuitBreaker.State.OPEN, afterFailures),
      () -> assertFalse(refused.userInfoAvailable()),
      () -> assertEquals(5, afterRefusal, "an open breaker must not reach the network"),
      () -> assertTrue(lastProbe.userInfoAvailable()),
      () -> assertEquals(7L, lastProbe.user().id()),
      () -> assertEquals(CircuitBreaker.State.CLOSED, userServiceCircuitBreaker.getState()));
  }

  @Test
  void aPageAsksTheUserServiceOnceForEveryDistinctOwner() {
    long keyboard = insertItem("Keyboard", "49.90");
    UserServiceStub.respondWithUser(OWNER_EMAIL, 7, "Anna", "Petrova");
    UserServiceStub.respondWithUser(OTHER_EMAIL, 8, "Boris", "Ivanov");
    facade.create(OWNER, request(keyboard));
    facade.create(OWNER, request(keyboard));
    facade.create(STRANGER, request(keyboard));
    int before = UserServiceStub.requestsReceived();

    Page<OrderDetailsResponse> page = facade.findAll(ANY, PageRequest.of(0, 10, Sort.by("id")));

    assertAll(
      () -> assertEquals(3, page.getContent().size()),
      () -> assertEquals(2, UserServiceStub.requestsReceived() - before),
      () -> assertEquals(List.of("Anna", "Anna", "Boris"),
        page.getContent().stream().map(order -> order.user().name()).toList()));
  }

  @Test
  void aPageStopsAtTheFirstFailure() {
    long keyboard = insertItem("Keyboard", "49.90");
    facade.create(OWNER, request(keyboard));
    facade.create(STRANGER, request(keyboard));
    UserServiceStub.reset();
    UserServiceStub.respondWithStatus(503);

    Page<OrderDetailsResponse> page = facade.findAll(ANY, PageRequest.of(0, 10, Sort.by("id")));

    assertAll(
      () -> assertEquals(1, UserServiceStub.requestsReceived(), "the second owner is not asked"),
      () -> assertTrue(page.getContent().stream().noneMatch(
        OrderDetailsResponse::userInfoAvailable)));
  }

  @Test
  void aRefusedRequestNeverReachesTheUserService() {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = facade.create(OWNER, request(keyboard)).id();
    int before = UserServiceStub.requestsReceived();

    assertThrows(OrderNotFoundException.class, () -> facade.getById(orderId, STRANGER));
    facade.delete(orderId, OWNER);

    assertEquals(before, UserServiceStub.requestsReceived());
  }

  /**
   * The facade has no transaction, so the connection of {@code OrderService} is returned to the
   * pool before the call to the User Service starts. The stub answers after four seconds; from
   * 1.5 to 2.5 seconds after the start the pool must show no active connection while the call is
   * still running. If the call were made inside the transaction, the connection would be held
   * for the whole four seconds.
   */
  @Test
  void noDatabaseConnectionIsHeldWhileTheUserServiceThinks() throws Exception {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = facade.create(OWNER, request(keyboard)).id();
    UserServiceStub.respondSlowlyWithUser(OWNER_EMAIL, 7, "Anna", "Petrova", 4000);
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<OrderDetailsResponse> call = executor.submit(() -> facade.getById(orderId, OWNER));
      Thread.sleep(1500);
      int worstActive = 0;
      for (int i = 0; i < 10; i++) {
        worstActive = Math.max(worstActive, pool.getHikariPoolMXBean().getActiveConnections());
        Thread.sleep(100);
      }
      boolean stillWaiting = !call.isDone();
      OrderDetailsResponse details = call.get();

      int held = worstActive;
      assertAll(
        () -> assertTrue(stillWaiting, "the call to the user service was still running"),
        () -> assertEquals(0, held, "no connection may be held during the call"),
        () -> assertTrue(details.userInfoAvailable()));
    } finally {
      executor.shutdownNow();
    }
  }

  private static OrderRequest request(long itemId) {
    return new OrderRequest(List.of(new OrderItemRequest(itemId, 1)));
  }
}
